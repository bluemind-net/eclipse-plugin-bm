#!/usr/bin/env bash
# Install the locally built p2 repository into the local Eclipse, headless.
# Run `mvn clean verify` first: this script consumes
# net.bluemind.devtools.site/target/repository via the p2 director, which
# re-reads the repo metadata every time (unlike Help > Check for Updates,
# which caches it).
#
# Eclipse must not be running while the director touches the p2 profile, so
# the script stops it, installs, then relaunches it.
#
# Eclipse location is resolved from (first match wins):
#   1. $ECLIPSE_HOME
#   2. ./eclipse  (next to this script)
#   3. $HOME/eclipse
# The p2 profile is auto-detected, or forced with $ECLIPSE_PROFILE.
#
# Usage: ./install-local.sh [--restart|--no-restart]
#   default    relaunch Eclipse only if it was running when we started
#   --restart  always relaunch
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$SCRIPT_DIR/net.bluemind.devtools.site/target/repository"
FEATURE_XML="$SCRIPT_DIR/net.bluemind.devtools.feature/feature.xml"

RESTART=auto
for arg in "$@"; do
	case "$arg" in
		--restart) RESTART=always ;;
		--no-restart) RESTART=never ;;
		-h|--help) sed -n '2,20p' "$0"; exit 0 ;;
		*) echo "error: unknown argument: $arg" >&2; exit 2 ;;
	esac
done

# Resolve the Eclipse installation.
for cand in "${ECLIPSE_HOME:-}" "$SCRIPT_DIR/eclipse" "$HOME/eclipse"; do
	if [ -n "$cand" ] && [ -d "$cand/plugins" ]; then
		ECLIPSE="$cand"
		break
	fi
done
if [ -z "${ECLIPSE:-}" ]; then
	echo "error: could not find an Eclipse install (set ECLIPSE_HOME=/path/to/eclipse)" >&2
	exit 1
fi

if [ ! -f "$REPO/content.jar" ] && [ ! -f "$REPO/content.xml" ]; then
	echo "error: no p2 repository in $REPO — run 'mvn clean verify' first" >&2
	exit 1
fi

# The feature IU is the feature id suffixed with .feature.group.
FEATURE_ID="$(sed -n 's/^[[:space:]]*id="\([^"]*\)".*/\1/p' "$FEATURE_XML" | head -1)"
if [ -z "$FEATURE_ID" ]; then
	echo "error: could not read the feature id from $FEATURE_XML" >&2
	exit 1
fi
IU="$FEATURE_ID.feature.group"

# Resolve the p2 profile of this install (usually a single *.profile directory).
PROFILE="${ECLIPSE_PROFILE:-}"
if [ -z "$PROFILE" ]; then
	REGISTRY="$ECLIPSE/p2/org.eclipse.equinox.p2.engine/profileRegistry"
	mapfile -t profiles < <(find "$REGISTRY" -maxdepth 1 -name '*.profile' -printf '%f\n' 2>/dev/null | sed 's/\.profile$//')
	if [ "${#profiles[@]}" -ne 1 ]; then
		echo "error: could not pick a p2 profile in $REGISTRY (found: ${profiles[*]:-none})." >&2
		echo "       Set ECLIPSE_PROFILE=<name> explicitly." >&2
		exit 1
	fi
	PROFILE="${profiles[0]}"
fi

# A leftover deploy.sh jar in dropins shadows the p2-installed bundle.
if compgen -G "$ECLIPSE/dropins/net.bluemind.devtools*" >/dev/null; then
	echo "warning: $ECLIPSE/dropins still holds a net.bluemind.devtools jar (from deploy.sh)."
	echo "         It may win over the version installed here — remove it if the plugin looks stale."
fi

echo "Eclipse:   $ECLIPSE"
echo "Profile:   $PROFILE"
echo "Feature:   $IU"
echo "Repo:      $REPO"

# List the PIDs of this Eclipse install (exact match on the launcher binary,
# so neither this script nor a grep of it can match).
eclipse_pids() {
	local dir pid exe
	for dir in /proc/[0-9]*; do
		pid="${dir#/proc/}"
		exe="$(readlink "/proc/$pid/exe" 2>/dev/null)" || continue
		[ "$exe" = "$ECLIPSE/eclipse" ] && echo "$pid"
	done
	return 0
}

WAS_RUNNING=0
pids="$(eclipse_pids)"
if [ -n "$pids" ]; then
	WAS_RUNNING=1
	echo "Stopping Eclipse ($(echo "$pids" | tr '\n' ' '))..."
	# shellcheck disable=SC2086
	kill $pids
	for _ in $(seq 60); do
		[ -z "$(eclipse_pids)" ] && break
		sleep 1
	done
	pids="$(eclipse_pids)"
	if [ -n "$pids" ]; then
		echo "Eclipse did not exit after 60s — killing."
		# shellcheck disable=SC2086
		kill -9 $pids
		sleep 2
	fi
fi

director() {
	"$ECLIPSE/eclipse" -nosplash -consolelog \
		-application org.eclipse.equinox.p2.director \
		-repository "file://$REPO" \
		-destination "$ECLIPSE" \
		-profile "$PROFILE" \
		"$@"
}

# Uninstall first: the director does not reliably replace an installed IU with
# a newer build, and installing over it leaves both bundles in plugins/.
echo "Uninstalling any previous $FEATURE_ID..."
if ! director -uninstallIU "$IU"; then
	echo "(nothing to uninstall, or uninstall failed — continuing)"
fi

echo "Installing..."
director -installIU "$IU"

installed="$(find "$ECLIPSE/features" -maxdepth 1 -name "${FEATURE_ID}_*" -printf '%f\n' | sort | tr '\n' ' ')"
echo "Installed feature(s): ${installed:-none}"

if [ "$RESTART" = always ] || { [ "$RESTART" = auto ] && [ "$WAS_RUNNING" = 1 ]; }; then
	# -clean drops the OSGi/config cache so the new bundle is re-resolved.
	echo "Restarting Eclipse (-clean, startup takes ~10s+)..."
	cd "$HOME"
	setsid "$ECLIPSE/eclipse" -clean >/dev/null 2>&1 </dev/null &
	echo "Done."
else
	echo "Done — start Eclipse to apply (it was not running)."
fi
