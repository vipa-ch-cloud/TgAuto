#!/bin/sh
# Runs the Kotlin core tests on a plain JVM (needs kotlinc and org.json on the classpath).
# KOTLINC and JSON_JAR can be overridden.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
KOTLINC=${KOTLINC:-kotlinc}
JSON_JAR=${JSON_JAR:-$(find "$HOME/.gradle" -name 'json-*.jar' | head -1)}
OUT=$(mktemp -d)
python3 -I "$HERE/gen_fixtures.py" "$OUT/fx"
"$KOTLINC" -nowarn -cp "$JSON_JAR" -d "$OUT/classes" "$HERE/../../android/app/src/main/java/com/v1pach/telescript/ota/Ed25519.kt" \
  "$HERE/../../android/app/src/main/java/com/v1pach/telescript/ota/OtaCore.kt" "$HERE/../../android/app/src/main/java/com/v1pach/telescript/ota/TrustedKeys.kt" "$HERE/../../android/app/src/main/java/com/v1pach/telescript/ota/ApkCore.kt" \
  "$HERE/../../android/app/src/main/java/com/v1pach/telescript/store/StoreMeta.kt" "$HERE/../../android/app/src/nightly/java/com/v1pach/telescript/ui/CrtPalette.kt" "$HERE/OtaCoreTest.kt"
java -cp "$OUT/classes:$JSON_JAR:$("$KOTLINC" -version >/dev/null 2>&1; dirname "$(command -v "$KOTLINC")")/../lib/kotlin-stdlib.jar" OtaCoreTestKt "$OUT/fx" "${REAL_INDEX:-}"
