# Terminal and AI CLI (1.9.2)

Русская краткая версия: раздел 16 в GUIDE.md.

## Architecture (hybrid)

1. **Built-in terminal.** `terminal/TerminalHost.kt` creates `TerminalSession` objects from `termux-terminal-emulator` (native `libtermux.so`, `forkpty`) and shows them with `termux-terminal-view` inside a Compose `AndroidView`. The process is `/system/bin/sh` with the app's environment (`CliLogic.shellEnv`). Sessions live as long as the app process.
2. **AI CLI manager.** `terminal/CliManager.kt` sends install, update, remove and status scripts (built in `CliLogic.kt`) to Termux through the existing `LlmBridge.runRaw` (RUN_COMMAND, background mode). Login opens a foreground Termux session via RUN_COMMAND, so credentials are typed in Termux.
3. **Backend wiring.** After install/remove `LlmBridge.setup` relinks the CLIs; in Nightly the button sets the existing `ai_codex_on` preference.

## Why not a full Linux userland inside the app

targetSdk 37 enforces W^X: an ELF file in `filesDir` cannot be executed. Only `nativeLibraryDir` (libs packaged in the APK) and system binaries run. A Termux bootstrap or proot unpacked into app data would not start, so it is not offered. Real node/npm therefore live in Termux (which has its own targetSdk), and TgAuto drives them over RUN_COMMAND.

## Limits (not hidden in the UI)

- The local shell is Android `sh` + toybox; no apt, node, pip, python.
- Chaquopy Python is in-process only.
- Termux script output is delivered when the script ends (background RUN_COMMAND has no streaming).
- Install needs Termux from F-Droid, the RUN_COMMAND permission and `allow-external-apps=true`. Starting Termux sessions from the background may need "Display over other apps".
- Not verified on a device in this build: PTY behaviour on real hardware, IME keys, Termux round trip, the credential gate. Pure logic is covered by `tools/kotlin_test/run_terminal.sh`.

## Security

- `TerminalActivity` is `exported=false`, has no intent filter and ignores intent extras except a UI page number.
- Starting needs a one-time, 15 second grant created by a button handler (`TerminalPolicy`); `.tgadyn`/`.tgascript`, store packages, scripts and Telegram messages have no path to it.
- Terminal is off by default; enabling shows a warning and a confirmation.
- Optional device credential on entry and after 60 s in the background; `FLAG_SECURE` on the screen.
- Logs and the install preview go through `CliLogic.redact`; TgAuto does not read or store CLI tokens.
- The optional HTTP proxy is validated (no shell metacharacters) and quoted; credentials in it are written to Termux npm config by npm itself, so prefer a proxy without a password.

## Licences

- `com.github.termux.termux-app:terminal-emulator` and `terminal-view` v0.118.1 (JitPack): Apache License 2.0 (the termux-app repository states that these library modules are Apache-2.0 because they derive from jackpal Android-Terminal-Emulator; the app itself is GPLv3). Apache-2.0 is one-way compatible with this project's GPLv3. No code was copied; the libraries are used as binaries. Keep the attribution below.
- Attribution: "Termux terminal-emulator and terminal-view, Copyright Termux developers and jackpal (Android-Terminal-Emulator), Apache License 2.0, https://github.com/termux/termux-app".
- Claude Code, Codex and Gemini CLI are installed from their own registries under their own licences and terms; TgAuto does not bundle them.

## Tests

`tools/kotlin_test/run_terminal.sh` compiles `CliLogic.kt` with `TerminalLogicTest.kt` (version parsing, install/login script building, proxy validation, error classification, redaction, shell environment, grant and relock policy).
