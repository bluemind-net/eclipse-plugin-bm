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
OUT_JAR="$ECLIPSE/dropins/net.bluemind.devtools_1.6.0.jar"
trap 'rm -rf "$BUILD_DIR"' EXIT

mkdir -p "$OUT" "$ECLIPSE/dropins"

# Classpath = every (non-source) jar shipped with this Eclipse.
CP="$(find "$ECLIPSE_PLUGINS" -name '*.jar' ! -name '*.source_*' | tr '\n' ':')"

echo "Eclipse:   $ECLIPSE"
echo "Compiling (JavaSE-21)..."
find "$SRC" -name '*.java' > "$BUILD_DIR/srcs.txt"
javac --release 21 -encoding UTF-8 -cp "$CP" -d "$OUT" "@$BUILD_DIR/srcs.txt"

cp -r "$PLUGIN_DIR/icons" "$OUT/"
cp "$PLUGIN_DIR/plugin.xml" "$OUT/"

echo "Packaging -> $OUT_JAR"
jar cfm "$OUT_JAR" "$PLUGIN_DIR/META-INF/MANIFEST.MF" -C "$OUT" .

echo "Clearing OSGi cache..."
rm -rf "$ECLIPSE/configuration/org.eclipse.osgi"

echo "Done — restart Eclipse to apply."
