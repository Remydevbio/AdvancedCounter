#!/bin/sh
# Runs the GUI test inside the real Fiji (Tools/Fiji). Windows appear on the desktop;
# input is synthesized inside Java, so the mouse and keyboard are not taken over.
set -eu
cd "$(dirname "$0")"
DEV=${FIJI_PLUGIN_DEV:-$(cd .. && pwd)/Tools/FijiPluginDev}
. "$DEV/env.sh"
SKIP_TESTS=${SKIP_TESTS:-1} ./build.sh
./install.sh  # test the installed jar, as a user would run it
mkdir -p build/gui
rm -f build/gui/gui-results.txt
ROOT=$(pwd)
cd "$FIJI_DIR"
timeout ${GUI_TIMEOUT:-300} ./fiji --allow-multiple -Dadvcounter.root="$ROOT" --class-path "$ROOT/build/test" --run "$ROOT/test/gui.ijm" > "$ROOT/build/gui/fiji.log" 2>&1 || true
cat "$ROOT/build/gui/gui-results.txt"
grep -q ' 0 failed' "$ROOT/build/gui/gui-results.txt"
