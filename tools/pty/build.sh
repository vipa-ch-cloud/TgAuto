#!/bin/sh
# Rebuilds libtermux.so (PTY JNI of termux-app terminal-emulator v0.118.1, Apache-2.0) with 16 KB ELF alignment.
# The prebuilt library in the JitPack AAR is 4 KB aligned and cannot be loaded on 16 KB page-size devices.
set -e
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.2.12479018}
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/../../android/app/src/terminal/jniLibs
for t in aarch64:arm64-v8a x86_64:x86_64; do
  arch=${t%%:*}; abi=${t##*:}
  mkdir -p "$OUT/$abi"
  "$BIN/${arch}-linux-android26-clang" -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -Wl,--build-id=none -s -o "$OUT/$abi/libtermux.so" "$HERE/termux.c"
done
