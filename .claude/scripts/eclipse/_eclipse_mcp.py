"""Shared Eclipse MCP client for the bm-eclipse-* scripts: config discovery + JSON-RPC calls."""
from __future__ import annotations

import glob
import json
import os
import sys
import urllib.error
import urllib.request

CONFIG_DIR = os.path.expanduser("~/.config/bluemind/mcp")
REQUEST_TIMEOUT_S = 1800


def die(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def find_configs():
    return sorted(glob.glob(os.path.join(CONFIG_DIR, "eclipse-*.json")))


def load_config(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        die(f"cannot read Eclipse MCP config {path}: {e}")


def pick_config(config_path=None, bundle_hint=None):
    """Resolution order: --config/env override, bundle-hint match, most-recently-written."""
    if config_path:
        return config_path
    env = os.environ.get("ECLIPSE_MCP_CONFIG")
    if env:
        return env
    configs = find_configs()
    if not configs:
        die(f"no Eclipse MCP config found in {CONFIG_DIR}\n"
            f"       is Eclipse running with the BlueMind Dev Tools plugin?")
    if bundle_hint:
        for path in configs:
            cfg = load_config(path)
            if bundle_hint in (cfg.get("projects") or []):
                return path
        die(f"no Eclipse MCP config has {bundle_hint!r} in its projects[]")
    return max(configs, key=os.path.getmtime)


def call(config_path, tool, arguments, timeout=REQUEST_TIMEOUT_S):
    """POST a tools/call request, return (text, is_error)."""
    cfg = load_config(config_path)
    url = cfg.get("url")
    token = cfg.get("token")
    if not url or not token:
        die(f"{config_path} is missing url/token")
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": tool, "arguments": arguments},
    }).encode()
    req = urllib.request.Request(
        url, data=payload, method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        die(f"HTTP {e.code} on {url}: {e.read().decode('utf-8', errors='replace')[:500]}")
    except urllib.error.URLError as e:
        die(f"cannot reach Eclipse MCP at {url}: {e.reason}")
    err = body.get("error")
    if err:
        die(f"JSON-RPC error {err.get('code')}: {err.get('message')}")
    result = body.get("result") or {}
    content = result.get("content") or []
    text = content[0]["text"] if content and "text" in content[0] else json.dumps(result, indent=2)
    return text, bool(result.get("isError"))


def list_tools(config_path, timeout=5):
    """tools/list against one instance. Returns the list of tool descriptors,
    or None if the instance is unreachable (used for connectivity checks, so it
    stays graceful instead of calling die())."""
    cfg = load_config(config_path)
    url = cfg.get("url")
    token = cfg.get("token")
    if not url or not token:
        return None
    payload = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}).encode()
    req = urllib.request.Request(
        url, data=payload, method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read())
    except (urllib.error.URLError, OSError, json.JSONDecodeError):
        return None
    return (body.get("result") or {}).get("tools") or []


def extract_json_block(markdown):
    """Pull the trailing ```json ...``` block a tool report ends with, if any."""
    marker = "```json"
    start = markdown.rfind(marker)
    if start == -1:
        return None
    start += len(marker)
    end = markdown.find("```", start)
    if end == -1:
        return None
    try:
        return json.loads(markdown[start:end])
    except json.JSONDecodeError:
        return None
