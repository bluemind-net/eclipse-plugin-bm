#!/usr/bin/env bash
# Quick local install: compile the bundle with javac against the running
# Eclipse's plugins, package it, and drop it into <eclipse>/dropins.
# This bypasses the Tycho build (handy when mvn can't run locally).
#
# Eclipse location is resolved from (first match wins):
#   1. $ECLIPSE_HOME
#   2. ./eclipse  (next to this script)
#   3. $HOME/eclipse
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$SCRIPT_DIR/net.bluemind.devtools"

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

ECLIPSE_PLUGINS="$ECLIPSE/plugins"
SRC="$PLUGIN_DIR/src"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bm-devtools-build.XXXXXX")"
OUT="$BUILD_DIR/bin"
trap 'rm -rf "$BUILD_DIR"' EXIT

mkdir -p "$OUT" "$ECLIPSE/dropins"

# The bundle is a singleton: OSGi picks whichever installed copy has the
# highest Bundle-Version, silently ignoring the rest. A stale copy from a
# real Tycho/p2 install can therefore outrank this script's own dropins jar
# if its qualifier happens to sort higher — so any existing copy, wherever it
# lives (plugins/ from a Tycho install, dropins/ from a previous run of this
# script), is removed first to guarantee only this build is ever picked up.
BUNDLE_VERSION="$(sed -n 's/^Bundle-Version: *\(.*\)$/\1/p' "$PLUGIN_DIR/META-INF/MANIFEST.MF")"
QUALIFIER="$(date +%Y%m%d%H%M%S)"
RESOLVED_VERSION="${BUNDLE_VERSION%.qualifier}.$QUALIFIER"
OUT_JAR="$ECLIPSE/dropins/net.bluemind.devtools_${RESOLVED_VERSION}.jar"

echo "Removing existing net.bluemind.devtools bundles..."
find "$ECLIPSE/plugins" "$ECLIPSE/dropins" -maxdepth 1 -name 'net.bluemind.devtools_*.jar' -print -delete

# A jar removed from plugins/ (as opposed to dropins/, which is scanned fresh
# every launch) can still be referenced by simpleconfigurator's bundles.info —
# the p2-managed "installed bundles" list — if this was originally installed
# via a real Tycho/p2 install (e.g. `ape -u`/`bm-cli setup upgrade`). Left
# dangling, simpleconfigurator tries to load a file that no longer exists and
# the whole bundle fails to activate — no error dialog, just a silently
# missing "BlueMind" menu and MCP server. Strip it so only the dropins copy
# this script just wrote provides the bundle.
BUNDLES_INFO="$ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
if [ -f "$BUNDLES_INFO" ] && grep -q '^net\.bluemind\.devtools,' "$BUNDLES_INFO"; then
	echo "Removing net.bluemind.devtools from simpleconfigurator's bundles.info..."
	sed -i '/^net\.bluemind\.devtools,/d' "$BUNDLES_INFO"
fi

# Classpath = every (non-source) jar shipped with this Eclipse.
CP="$(find "$ECLIPSE_PLUGINS" -name '*.jar' ! -name '*.source_*' | tr '\n' ':')"

echo "Eclipse:   $ECLIPSE"
echo "Compiling (JavaSE-21)..."
find "$SRC" -name '*.java' > "$BUILD_DIR/srcs.txt"
javac --release 21 -encoding UTF-8 -cp "$CP" -d "$OUT" "@$BUILD_DIR/srcs.txt"

cp -r "$PLUGIN_DIR/icons" "$OUT/"
cp "$PLUGIN_DIR/plugin.xml" "$OUT/"

MANIFEST="$BUILD_DIR/MANIFEST.MF"
sed "s/^Bundle-Version: .*/Bundle-Version: $RESOLVED_VERSION/" "$PLUGIN_DIR/META-INF/MANIFEST.MF" > "$MANIFEST"

echo "Packaging -> $OUT_JAR (version $RESOLVED_VERSION)"
jar cfm "$OUT_JAR" "$MANIFEST" -C "$OUT" .

echo "Clearing OSGi cache..."
rm -rf "$ECLIPSE/configuration/org.eclipse.osgi"

echo "Done — restart Eclipse to apply."
