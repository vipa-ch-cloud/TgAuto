#!/bin/sh
# Pure-logic tests of the terminal / AI-CLI feature. Uses kotlinc if present, otherwise the compiler jars
# from the Gradle cache (kotlin-compiler-embeddable + stdlib).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../android/app/src/main/java/com/v1pach/telescript/terminal/CliLogic.kt"
OUT=$(mktemp -d)
if command -v kotlinc >/dev/null 2>&1; then
  kotlinc -nowarn -d "$OUT" "$SRC" "$HERE/TerminalLogicTest.kt"
  STD=$(dirname "$(command -v kotlinc)")/../lib/kotlin-stdlib.jar
else
  G="$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin"
  f() { find "$G/$1" -name "$2" | head -1; }
  STD=$(f kotlin-stdlib 'kotlin-stdlib-2.3.20.jar')
  CP="$(f kotlin-compiler-embeddable 'kotlin-compiler-embeddable-2.2.10.jar'):$STD:$(f kotlin-script-runtime 'kotlin-script-runtime-2.2.10.jar'):$(f kotlin-reflect 'kotlin-reflect-1.6.10.jar'):$(f kotlin-daemon-embeddable 'kotlin-daemon-embeddable-2.2.10.jar'):$(find "$HOME/.gradle" -name "kotlinx-coroutines-core-jvm-1.9.0.jar" | head -1):$(find "$HOME/.gradle" -name 'annotations-13*.jar' | head -1)"
  java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -cp "$STD" -d "$OUT" "$SRC" "$HERE/TerminalLogicTest.kt"
fi
java -cp "$OUT:$STD" TerminalLogicTestKt
