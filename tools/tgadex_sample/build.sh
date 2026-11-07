#!/bin/sh
# Builds hello.dex from HelloPlugin.java with javac + d8 (Android SDK build-tools) against the plugin API.
# The API classes are NOT put into the dex: the app provides them (parent class loader).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
AJAR=$(ls -d "$SDK"/platforms/android-*/android.jar | tail -1)
D8=$(ls -d "$SDK"/build-tools/*/d8 | tail -1)
OUT=$(mktemp -d)
API="$HERE/../../android/app/src/main/java/com/v1pach/telescript/plugin"
javac --release 11 -cp "$AJAR" -d "$OUT/api" "$API"/*.java
javac --release 11 -cp "$AJAR:$OUT/api" -d "$OUT/cls" "$HERE/HelloPlugin.java"
"$D8" --min-api 26 --lib "$AJAR" --classpath "$OUT/api" --output "$OUT" $(find "$OUT/cls" -name '*.class')
cp "$OUT/classes.dex" "${1:-$HERE/hello.dex}"
echo "wrote ${1:-$HERE/hello.dex}"
