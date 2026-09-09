"""Shared open/close/group/focus planning for bm-eclipse-sync and bm-eclipse-doctor.

Group membership and the open/close/focus arithmetic live here once, so the two
scripts can never drift on what "close the gwt group" or "focus on X" means.
Data in, data out — no printing beyond print_capped. `plan_focus` also reads
MANIFEST.MF straight off disk (via `bundle_requires`): a closed project has no
PDE model, so Require-Bundle for it exists nowhere else. Each script wraps this
in its own reporting idiom: bm-eclipse-sync prints for a human; bm-eclipse-doctor
routes it through its own [doctor:*] traceability and Report, sequenced before
the diagnostic snapshot.
"""
import os
import sys
import xml.etree.ElementTree as ET
from collections import deque

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _eclipse_mcp as mcp

# Path prefixes (relative to repo root, as returned by list_projects) that
# identify each named group. Verified against a live workspace on 2026-08-13
# (see the eclipse-sync skill for the exact counts / excluded edge cases).
GROUPS = {
    "gwt": ["open/ui/gwt-libs/", "open/ui/gwt-ui-libs/"],
    "closure": ["open/ui/closures/"],
}

# Every MCP-triggered test launch adds this feature to its OSGi JUnit launch
# unconditionally (BmTestLaunchShortcut.buildSelectedFeatures, net.bluemind.devtools) and
# resolves it from the WORKSPACE if open, target platform otherwise (featureDefaultLocation).
# A PDE feature is not a bundle dependency — nothing ever has Require-Bundle on it — so
# bundle_closure's mechanical walk can never see it as needed and plan_focus's "close every
# other open project" would close it right along with genuinely unrelated ones. Confirmed
# 2026-09-01: a --focus on 5 mapi test projects closed it; every test launched afterwards
# failed with "No connection factory found for dbtype PGSQL" (BMPoolActivator) regardless of
# which providers were reopened — only reopening net.bluemind.tests.feature itself fixed it.
TEST_LAUNCH_ALWAYS_OPEN = {"net.bluemind.tests.feature"}


def split_names(raw):
    return [n.strip() for n in raw.split(",") if n.strip()]


def fetch_projects(config_path):
    text, is_error = mcp.call(config_path, "list_projects", {"scope": "all"})
    if is_error:
        mcp.die(f"list_projects failed:\n{text}")
    data = mcp.extract_json_block(text)
    if data is None:
        mcp.die("no json block found in list_projects report")
    return data.get("projects", [])


def fetch_repo_root(config_path):
    """Repo root on disk, for `plan_focus`'s manifest reads. A cheap doctor_snapshot
    (waitForBuild=False) — the same workspace metadata bm-eclipse-doctor's own
    diagnostic read carries, just without waiting for a build to get it. None on
    failure: callers fall back to a focus with no closure (the pre-14/08 behaviour)
    rather than dying over a metadata lookup."""
    text, is_error = mcp.call(config_path, "doctor_snapshot", {"severity": "error", "waitForBuild": False})
    if is_error:
        return None
    data = mcp.extract_json_block(text)
    if data is None:
        return None
    return (data.get("workspace") or {}).get("repoRoot")


def split_bundle_names(value):
    """Comma-split a Require-Bundle value, ignoring commas inside [..] ranges and
    "..." quotes, then keep the symbolic name before the first ';'."""
    names, buf, depth, quoted = [], [], 0, False
    for ch in value:
        if ch == '"':
            quoted = not quoted
        elif ch == "[" and not quoted:
            depth += 1
        elif ch == "]" and not quoted:
            depth = max(0, depth - 1)
        if ch == "," and depth == 0 and not quoted:
            names.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    names.append("".join(buf))
    return [n.split(";")[0].strip() for n in names if n.strip()]


def bundle_requires(project_dir):
    """Require-Bundle of a project, read from its MANIFEST.MF on disk — works
    whether the project is open or closed, since PDE builds no model for a closed
    one (see bm-eclipse-doctor's transitive_closed, the other caller of this)."""
    manifest = os.path.join(project_dir, "META-INF", "MANIFEST.MF") if project_dir else None
    if not manifest or not os.path.isfile(manifest):
        return []
    try:
        with open(manifest, encoding="utf-8", errors="replace") as f:
            raw = f.read()
    except OSError:
        return []
    lines = []
    for line in raw.splitlines():
        if line.startswith(" ") and lines:
            lines[-1] += line[1:]
        else:
            lines.append(line)
    for line in lines:
        if not line.startswith("Require-Bundle:"):
            continue
        return split_bundle_names(line.split(":", 1)[1])
    return []


def pom_feature_requirements(project_dir):
    """<requirement><type>eclipse-feature</type><id>…</id></requirement> entries from a
    project's pom.xml — the same parsing BmTestLaunchShortcut.readExtraFeatureRequirements
    (net.bluemind.devtools) does to pick the PDE features an MCP test launch adds beyond the
    universal TEST_LAUNCH_ALWAYS_OPEN one (e.g. net.bluemind.exchange.mapi.feature for the mapi
    *.tests projects). Per-project, unlike TEST_LAUNCH_ALWAYS_OPEN — a feature is still not a
    bundle, so bundle_closure can't find these either way."""
    pom = os.path.join(project_dir, "pom.xml") if project_dir else None
    if not pom or not os.path.isfile(pom):
        return []
    try:
        root_el = ET.parse(pom).getroot()
    except (OSError, ET.ParseError):
        return []
    ids = []
    for req in root_el.iter():
        if req.tag.rsplit("}", 1)[-1] != "requirement":
            continue
        fields = {child.tag.rsplit("}", 1)[-1]: (child.text or "").strip() for child in req}
        if fields.get("type") == "eclipse-feature" and fields.get("id"):
            ids.append(fields["id"])
    return ids


def bundle_closure(seeds, projects, root):
    """Everything `seeds` need transitively through Require-Bundle, read from disk
    manifests so a closed provider counts too — restricted to bundles that are
    actual workspace projects, since nothing else can be opened anyway. `seeds`
    itself is included. `root` is the repo root `bundle_requires` resolves each
    project's MANIFEST.MF against; without one (metadata lookup failed) this is a
    no-op, seeds come back alone."""
    by_name = {p["name"]: p for p in projects}
    out = {s for s in seeds if s in by_name}
    if not root:
        return out
    queue = deque(out)
    while queue:
        current = queue.popleft()
        project_dir = os.path.join(root, by_name[current]["path"])
        for dep in bundle_requires(project_dir):
            if dep in out or dep not in by_name:
                continue
            out.add(dep)
            queue.append(dep)
    return out


def group_projects(projects, group):
    prefixes = GROUPS[group]
    return [p for p in projects if any(p["path"].startswith(pfx) for pfx in prefixes)]


def plan_close(names, projects):
    """(unknown, already_closed, to_close) for an explicit close of exact names."""
    by_name = {p["name"]: p for p in projects}
    unknown = [n for n in names if n not in by_name]
    already = [n for n in names if n in by_name and not by_name[n]["open"]]
    to_close = [n for n in names if n in by_name and by_name[n]["open"]]
    return unknown, already, to_close


def plan_open(names, projects):
    """(unknown, already_open, to_open) for an explicit open of exact names."""
    by_name = {p["name"]: p for p in projects}
    unknown = [n for n in names if n not in by_name]
    already = [n for n in names if n in by_name and by_name[n]["open"]]
    to_open = [n for n in names if n in by_name and not by_name[n]["open"]]
    return unknown, already, to_open


def plan_close_group(group, projects):
    """(matched, to_close) — every currently open project in the named group."""
    matched = group_projects(projects, group)
    return matched, [p["name"] for p in matched if p["open"]]


def plan_open_group(group, projects):
    """(matched, to_open) — every currently closed project in the named group."""
    matched = group_projects(projects, group)
    return matched, [p["name"] for p in matched if not p["open"]]


def plan_focus(names, projects, root):
    """(unknown, to_open, to_close) — open `names` and everything they transitively
    require (Require-Bundle, via bundle_closure), close every OTHER currently open
    project except TEST_LAUNCH_ALWAYS_OPEN. `root` is the repo root for reading
    closed projects' manifests; pass None to fall back to the old "close everything
    else" behaviour with no closure (TEST_LAUNCH_ALWAYS_OPEN still applies — it
    doesn't depend on root).

    A provider `names` genuinely needs is never closed, even though it wasn't named
    explicitly — closing it used to be exactly what broke FEATBL-3661's run on
    14/08: `--focus` on 2 projects closed 362 others including everything they
    depended on, bm-eclipse-doctor protected that whole close set from reopening
    (see its module docstring), and 30292 errors came back as an unresolvable
    protected-conflict instead of the one-call fix this was supposed to be.

    TEST_LAUNCH_ALWAYS_OPEN is a second, narrower exception for what Require-Bundle
    structurally cannot see at all (a PDE feature, not a bundle) rather than what a
    root-less closure missed — see its own docstring. A third source of the same blind
    spot is per-project: a seed's own pom.xml can declare extra `eclipse-feature`
    requirements beyond the universal one (pom_feature_requirements — mirrors
    BmTestLaunchShortcut.readExtraFeatureRequirements, e.g. net.bluemind.exchange.mapi.feature
    for the mapi *.tests projects, itself found closed right alongside net.bluemind.tests.feature
    in the same 01/09 incident). Needs `root` the same way bundle_closure does; a no-root run
    skips it, same degrade as the Require-Bundle closure."""
    by_name = {p["name"]: p for p in projects}
    unknown = [n for n in names if n not in by_name]
    extra_features = set()
    if root:
        for n in names:
            if n not in by_name:
                continue
            project_dir = os.path.join(root, by_name[n]["path"])
            extra_features.update(pom_feature_requirements(project_dir))
    keep = bundle_closure(names, projects, root) | (TEST_LAUNCH_ALWAYS_OPEN & by_name.keys()) \
        | (extra_features & by_name.keys())
    to_open = sorted(n for n in keep if not by_name[n]["open"])
    to_close = [p["name"] for p in projects if p["open"] and p["name"] not in keep]
    return unknown, to_open, to_close


def print_capped(label, names, cap=30):
    print(f"{label} ({len(names)}):")
    for n in names[:cap]:
        print(f"  - {n}")
    if len(names) > cap:
        print(f"  ... {len(names) - cap} more")
