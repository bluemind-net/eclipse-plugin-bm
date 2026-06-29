#!/usr/bin/env bash
# Quick local install: compile the bundle with javac against the running
# Eclipse's plugins, package it, and drop it into ~/eclipse/dropins.
# This bypasses the Tycho build (handy when mvn can't run locally).
set -e

ECLIPSE=/home/alexandre/eclipse
ECLIPSE_PLUGINS="$ECLIPSE/plugins"
PLUGIN_DIR=/home/alexandre/dev/eclipse-plugin-bm/net.bluemind.devtools
SRC="$PLUGIN_DIR/src"
OUT=/tmp/bm-devtools-build/bin
OUT_JAR="$ECLIPSE/dropins/net.bluemind.devtools_1.4.0.jar"

rm -rf "$OUT" && mkdir -p "$OUT"

# Classpath = every (non-source) jar shipped with this Eclipse.
CP=$(find "$ECLIPSE_PLUGINS" -name "*.jar" ! -name "*.source_*" | tr '\n' ':')

echo "Compiling (JavaSE-21)..."
find "$SRC" -name "*.java" > /tmp/bm-devtools-build/srcs.txt
javac --release 21 -cp "$CP" -d "$OUT" "@/tmp/bm-devtools-build/srcs.txt"

cp -r "$PLUGIN_DIR/icons" "$OUT/"
cp "$PLUGIN_DIR/plugin.xml" "$OUT/"

echo "Packaging -> $OUT_JAR"
jar cfm "$OUT_JAR" "$PLUGIN_DIR/META-INF/MANIFEST.MF" -C "$OUT" .

echo "Clearing OSGi cache..."
rm -rf "$ECLIPSE/configuration/org.eclipse.osgi"

echo "Done — restart Eclipse to apply."
