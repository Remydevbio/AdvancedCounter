#!/bin/sh
# Copies the plugin into Fiji's plugins folder. Restart Fiji afterwards.
set -eu
cd "$(dirname "$0")"
FIJI_DIR=${FIJI_DIR:-$(cd .. && pwd)/Tools/Fiji}
test -f dist/Advanced_Counter.jar || { echo "Run ./build.sh first" >&2; exit 1; }
cp dist/Advanced_Counter.jar "$FIJI_DIR/plugins/Advanced_Counter.jar"
echo "Installed to $FIJI_DIR/plugins. Restart Fiji: Plugins > Advanced Counter."
