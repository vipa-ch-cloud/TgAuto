[Русский](README.md) | English

# TgAuto

Scripts: https://github.com/vipa-ch-cloud/TgAutoS

TgAuto is an Android app that runs Python Telegram (Telethon) userbot scripts on the phone, no root needed.

## What it is
The app runs your Python Telegram scripts right on the phone and can connect LLMs to them. The UI is available in Russian and English (it follows the system language), Material You design.

## Features
- Runs Telethon scripts on embedded Python 3.12 (Chaquopy), background work through a foreground service.
- Telegram login by phone number (QR as a fallback); the session is shared by all scripts.
- The scripts folder is picked via SAF; scripts are mirrored into the app's internal storage.
- Runtime pip: install Python libraries on the phone for each script.
- LLMs for scripts: Claude and Gemini accounts through Termux (the real CLIs, without tools), Codex (Nightly, via Termux, experimental), and any OpenAI-compatible providers (DeepSeek, OpenAI, your own API). The providers section is currently Nightly-only. Keys are stored only on the phone.
- "Config" form: a script ships a `config.schema.json` next to it and the app shows its settings as a form.
- Script logs in the "Logs" tab.

The scripts (automod, autoresponder) live in a separate repository: https://github.com/vipa-ch-cloud/TgAutoS

## Screenshots
Screenshots will be added later.

## Installation
Detailed user guide: [GUIDE.en.md](GUIDE.en.md) ([Русский](GUIDE.md)).
Ready-made APKs: `TgAuto-<version>.apk` (stable) and `TgAuto-nightly-<version>.apk` (Nightly, installs side by side).

## Building
You need JDK 17+, the Android SDK (compileSdk 37) and Python 3.12 for Chaquopy.
```
cd android
export TELESCRIPT_BUILD_PYTHON=/path/to/python3.12   # if the default path does not fit
./gradlew assembleStableDebug assembleNightlyDebug
```
APKs: `android/app/build/outputs/apk/{stable,nightly}/debug/`.

## A note on identifiers
The app is called TgAuto, but the internal applicationId is still `com.v1pach.telescript` (Nightly adds `.nightly`), and the environment variables for scripts are named `TELESCRIPT_*`. This is intentional so that updates keep user data.

## License
Licensed under the GNU GPL v3 or later (GPL-3.0-or-later). The full text is in [LICENSE](LICENSE).
