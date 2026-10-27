#!/bin/sh
# Builds dist/Advanced_Counter.jar with the shared toolchain in Tools/FijiPluginDev and runs headless tests.
set -eu
cd "$(dirname "$0")"
DEV=${FIJI_PLUGIN_DEV:-$(cd .. && pwd)/Tools/FijiPluginDev}
. "$DEV/env.sh"
rm -rf build/plugin build/test
mkdir -p build/plugin build/test dist
"$JAVAC" --release 8 -encoding UTF-8 -Xlint:-options -cp "$COMPILE_CP" -d build/plugin $(find src -name '*.java')
cp plugins.config LICENSE build/plugin/ 2>/dev/null || cp plugins.config build/plugin/
"$JAR" cf dist/Advanced_Counter.jar -C build/plugin .
if [ -d test ] && [ "${SKIP_TESTS:-0}" != 1 ]; then
  "$JAVAC" -proc:none -encoding UTF-8 -cp "$RUN_CP:build/plugin" -d build/test $(find test -name '*.java')
  "$JAVA" -Djava.awt.headless=true -cp "$RUN_CP:build/plugin:build/test" CoreTest
fi
echo "Built dist/Advanced_Counter.jar"
