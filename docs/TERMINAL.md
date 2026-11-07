# Terminal and AI CLI (1.9.3-term)

## Status

- Available in **both flavors**. Entry point: one button "Открыть консоль" (Settings). Stable: plain console in theme colours. Nightly: CRT console (ConsoleOverlay). Tabs: Журнал | Shell | ИИ-CLI.
- The old Settings card, TerminalActivity, the gate and the red warnings are removed.
- **Not verified on a real device or emulator** (none was available). Verified: both flavors build, APK contents (aapt/dexdump), alignment, pure-logic tests (`tools/kotlin_test/run_terminal.sh`).

## 1.9.2 crash cause

`libtermux.so` from the Termux AAR is 4 KB aligned. On 16 KB page-size devices `JNI.<clinit>` threw UnsatisfiedLinkError. Fix: own `libtermux.so` (`tools/pty/termux.c`, `tools/pty/build.sh`, NDK, LOAD align 0x4000) in `src/terminal/jniLibs/{arm64-v8a,x86_64}` and `jniLibs.pickFirsts += "**/libtermux.so"`. Checked in the built APK.

## Embedded userland (arm64 only)

- `tools/termux_userland/build_userland.py` (Gradle task `fetchUserland`, runs before preBuild; skip with `-PnoUserland`; cache `~/.cache/tgauto-userland`) downloads official Termux `.deb` packages (bash, busybox, coreutils, git, curl, openssl, python, nodejs, npm deps and their libs; list in `build/userland/packages.txt`), patches soname/NEEDED with patchelf and writes executables as `jniLibs/arm64-v8a/lib<name>.so`.
- `useLegacyPackaging=true`, so the libraries are extracted to `nativeLibraryDir`, the only place W^X (targetSdk 37) permits exec.
- Data files (certificates, python stdlib, git templates, npm) come from `assets/userland-arm64.zip`, unpacked to `filesDir/usr`; executables there are symlinks to `nativeLibraryDir`. `LD_LIBRARY_PATH=nativeLibraryDir`.
- Termux binaries hardcode the `/data/data/com.termux/files/usr` prefix; this is worked around by env vars (PYTHONHOME, SSL_CERT_FILE, CURL_CA_BUNDLE, GIT_EXEC_PATH, GIT_TEMPLATE_DIR, GIT_SSL_CAINFO, NPM_CONFIG_PREFIX, NODE_EXTRA_CA_CERTS, ...).
- Scripts in the data dir cannot be exec'd (SELinux), so `claude`, `codex`, `gemini`, `npm`, `npx`, `pip` are bash functions in `usr/etc/tga-env.sh` that run `node file` or `/system/bin/linker64 file`.
- Other ABIs (x86_64) fall back to `/system/bin/sh` without userland.
- APK grows by roughly 55-60 MB (about 95 MB total).

## AI CLI

Install/update/remove/login/launch of Claude Code, Codex and Gemini CLI go through `CliManager` -> `Userland.run` using npm inside the userland (no apt/pkg, no Termux app needed). Logic in `CliLogic.kt`.

## Limits

- Claude Code's native binary and Codex (musl build) may not run on Android/bionic; npm installation can succeed while launch fails. Not tested.
- No apt/pkg; the package set is fixed at build time. Unused python test modules and bash loadables still add size (not trimmed).
- DNS/network in the app sandbox depend on the device.
- Optional HTTP proxy is validated and quoted; prefer one without a password.
- TgAuto does not read or store CLI tokens; logs pass `CliLogic.redact`.

## Licences

- Termux `terminal-emulator` / `terminal-view` 0.118.1: Apache-2.0 (Copyright Termux developers and jackpal Android-Terminal-Emulator, https://github.com/termux/termux-app).
- The userland consists of unmodified official Termux packages (except patched soname/NEEDED) under their own licences: bash GPLv3, busybox GPLv2, coreutils GPLv3, git GPLv2, curl (curl licence), openssl/Apache-2.0, python (PSF), nodejs (MIT) etc. Exact list with versions and homepages:

```
bash 5.3.20  https://www.gnu.org/software/bash/
libandroid-support 29-1  https://github.com/termux/libandroid-support
libiconv 1.19.1  https://www.gnu.org/software/libiconv/
readline 8.3.6  https://tiswww.case.edu/php/chet/readline/rltop.html
ncurses 6.6.20260307+really6.5.20250830  https://invisible-island.net/ncurses/
busybox 1.38.0-1  https://busybox.net/
libandroid-selinux 14.0.0.11-1  https://selinuxproject.org
pcre2 10.49  https://pcre2project.github.io/pcre2/
curl 8.23.0  https://curl.se/
libcurl 8.23.0  https://curl.se/
libnghttp2 1.71.0  https://nghttp2.org/
libnghttp3 1.19.0  https://nghttp2.org/nghttp3/
libngtcp2 1.26.0  https://github.com/ngtcp2/ngtcp2
openssl 1:3.6.5  https://www.openssl.org/
ca-certificates 1:2026.11.05  https://curl.se/docs/caextract.html
zlib 1.4  https://www.zlib.net/
libssh2 1.11.1-2  https://www.libssh2.org
git 2.56.0  https://git-scm.com/
libexpat 2.10.0  https://libexpat.github.io/
less 710  https://www.greenwoodsoftware.com/less/
python 3.14.10  https://python.org/
gdbm 1.26-1  https://www.gnu.org.ua/software/gdbm/
libandroid-posix-semaphore 0.1-4  https://man7.org/linux/man-pages/man7/sem_overview.7.html
libbz2 1.0.8-8  http://www.bzip.org/
libcrypt 0.2-6  http://michael.dipperstein.com/crypt/
libffi 3.8.0  https://sourceware.org/libffi/
liblzma 5.8.4  https://tukaani.org/xz/
libsqlite 3.54.1-1  https://www.sqlite.org
ncurses-ui-libs 6.6.20260307+really6.5.20250830  https://invisible-island.net/ncurses/
zstd 1.5.7-1  https://github.com/facebook/zstd
python-pip 26.3  https://pip.pypa.io/
nodejs-lts 24.22.2  https://nodejs.org/
libc++ 30  https://libcxx.llvm.org/
c-ares 1.34.9  https://c-ares.org/
resolv-conf 1.3  https://man7.org/linux/man-pages/man5/resolv.conf.5.html
libicu 78.3  http://site.icu-project.org/home
```

Sources are available from the homepages above and from packages.termux.dev. Claude Code, Codex and Gemini CLI are installed by the user from npm under their own terms; they are not bundled.

## Build and tests

`./gradlew --offline assembleStableRelease assembleNightlyRelease` (needs network once for the userland, or `-PnoUserland`). Tests: `tools/kotlin_test/run_terminal.sh`.
