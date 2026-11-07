[Русский](GUIDE.md) | English

# TgAuto: installing on your phone (no root needed)

## 1. Termux
- Download Termux from F-Droid (f-droid.org) or GitHub (github.com/termux/termux-app/releases).
- The Google Play version will not work, it is outdated.

## 2. Claude Code in Termux
Open Termux and enter the commands one by one:
```
pkg upgrade -y
pkg install -y libc++ nodejs
node -v
DISABLE_AUTOUPDATER=1 npm i -g @anthropic-ai/claude-code@2.1.112
mkdir -p ~/.claude && echo '{"env":{"DISABLE_AUTOUPDATER":"1"}}' > ~/.claude/settings.json
```
- If Termux asks about replacing configs, press Enter.
- `node -v` must print a version. If you get `CANNOT LINK EXECUTABLE` instead, run the first two lines again.
- You need exactly Claude Code 2.1.112; newer versions do not run in Termux.

## 3. Log in to Claude
- Type `claude` and choose to log in with a Claude account.
- A link opens: sign in in the browser, copy the code and paste it back into Termux.
- Exit with `/exit`.
- Check: `claude -p "say ok"`. If it answers, everything works.

## 3b. Gemini (optional, fallback)
If Claude is unavailable, scripts will use Gemini. You need a regular Google account, no API key.
```
pkg install -y python make clang
npm install -g @google/gemini-cli --ignore-scripts
gemini --version
```
- `--ignore-scripts` is required: without it the install fails building `tree-sitter` (an `android_ndk_path` error). It is not needed for operation.
- If the new version still does not start, install an older one: `npm install -g @google/gemini-cli@0.2.2 --ignore-scripts`. There is also a Termux build: github.com/DioNanos/gemini-cli-termux.
- Type `gemini` and choose **Login with Google** (Sign in with Google).
- Gemini shows a link. Open it in the phone's browser and sign in to Google. The page then redirects to `localhost`: that is the same phone, this is expected, go back to Termux.
- If Gemini asks for a code, copy it from the page and paste it into Termux. If the browser does not open by itself, run: `NO_BROWSER=true gemini`.
- Exit with `/quit`.
- Check: `gemini -p "say ok"`. If it answers, everything works.

## 4. Allow TgAuto to control Termux
```
mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
termux-reload-settings
```

## 5. TgAuto
- Install `TgAuto-1.7.apk`.
- On first launch allow notifications and "Run commands in Termux".
- In the phone settings disable battery optimization for **Termux** and **TgAuto**: Apps → Battery → Unrestricted.

## 6. Telegram keys
- Go to my.telegram.org → API development tools → create an application (any name).
- Copy the **api_id** and **api_hash**.

## 7. Log in to Telegram
- In TgAuto open the "Account" tab and paste the api_id and api_hash.
- Enter your phone number, then the code from Telegram, then the 2FA password if you have one.

## 8. Checking Claude and Gemini
- Open "Settings" → LLM and choose the order: "Claude, then Gemini", "Claude only", "Gemini only" or "API key only".
- Press "Check" for the account you need. You should get "ok" back.
- The Anthropic key at the bottom is only a last resort and can be left empty.

## 9. Scripts
- Open the "Scripts" tab and press "Choose folder". Create, for example, `Documents/Scripts`.
- Put the scripts into this folder (they live in a separate repository, TgAutoS (github.com/vipa-ch-cloud/TgAutoS): the `automod` and `autoresponder` folders).
- On the script card press "Dependencies", then "Run".
- Script folders live in the app's private storage: `/data/data/com.v1pach.telescript/files/config/<name>` and `.../files/data/<name>`. Only root can access them. For the auto-responder: `profile.json` in `config/autoresponder`, `state.json` and `style.json` in `data/autoresponder`. Without root you do not need this: the script creates its own files.

## 10. Automod in a chat
Your account must be an admin in the chat where Iris is present. Type the commands right in the chat:
- `.automod`: enable automod in this chat.
- `.automod -r=link`: enable and take the rules from the link.
- `.automod -d`: remove automod from this chat.
- `.automod off`: turn off (the chat stays in the list).
- `.automod status`: show whether automod is on.
- `.context N`: how many messages to look at in one check (N is a number).
- `.ams`: check the chat right now.

## 11. Auto-responder
- In the "Scripts" tab choose the folder with the `autoresponder` script, then "Dependencies".
- No separate login is needed: the script uses the TgAuto session ("Account" tab, step 7). No QR scanning.
- Commands in any chat:
  - `.start`: enable replies in this chat.
  - `.start imit`: enable with imitation of your style.
  - `.stop`: disable.
  - `.mem`: show what the bot remembers.
  - `.ign`: ignore this chat.
  - `.noign`: stop ignoring.
  - `.reload`: reread settings and profile.
- To voice messages the bot replies "voice again" (in Russian: «опять гс»).

## 12. The "Config" button
- A script with settings has a "Config" button on its card (automod and the auto-responder have it).
- Change switches, numbers, text, lists and choices, then press "Save". "Reset to defaults" sets default values but does not save yet.
- Only the shown settings are saved, the rest of the file is untouched. Keys and passwords are not shown here.
- Changes apply after the script is restarted.
- Your own script folder gets the button if a `config.schema.json` lies next to it (see automod's `config.schema.json` for the format).

## 13. Providers: DeepSeek, OpenAI, your own API (TgAuto Nightly build only)
This section appeared in Nightly (`TgAuto-nightly-*.apk`, installs next to the stable one). In the stable build the LLM settings stay as before.
If you do not need Claude and Gemini or they are unavailable, scripts can use any OpenAI-compatible service.
- "Settings" → "Providers" → press "DeepSeek", "OpenAI" or "Custom API".
- DeepSeek: create a key at platform.deepseek.com → API keys. The address `https://api.deepseek.com` and model `deepseek-chat` are already filled in. A positive balance is required.
- Custom API (for example tokenlavka): take the address from the service dashboard, it usually ends with `/v1`, for example `https://tokenlavka.example/v1`. Paste the key and the model name. The "Load model list" button shows the available ones.
- Press "Check": you get a short answer or an error text (401 means a wrong key, 404 a wrong address or model).
- Order in the list with the up and down buttons: scripts try providers top to bottom.
- Privacy: keys are stored only in the app's private folder on the phone (a file with 600 permissions). They are not written to logs, not shown after saving and do not end up in the scripts folder. The script receives only the file path in `TELESCRIPT_LLM_PROVIDERS_FILE`; the file is read by the script you started.
- Codex (ChatGPT account) is not supported yet: the official login in Termux fails for many (github.com/openai/codex/issues/6632), there is no proven method.

## 14. Console and AI backend selection (TgAuto Nightly build only)
The stable build does not have these sections.
- "Settings" -> "CLI" (top) -> "Open console": a full-screen terminal. Commands go to Termux over the same bridge the scripts use, so Termux and the RUN_COMMAND permission are required (section 4).
- Quick buttons: "check claude", "check gemini", "node versions". There is history (up and down arrows), Ctrl+C and copying: tapping a line selects it, the copy button takes the selection or everything.
- ANSI (SGR) colors and newlines are supported. Limitations: output appears only after the command finishes (background RUN_COMMAND does not stream), and interactive programs (vim, password prompts) do not work. Ctrl+C stops waiting and signals the process.
- "Settings" -> "AI backend": who answers for scripts. "Auto" (Claude, then Gemini, then providers, then API; each item has a switch and order arrows), "Claude only", "Gemini only" or one specific provider.
- What the choice really does: unselected claude and gemini are hidden from scripts, scripts get a providers file with only the selected ones, and the Anthropic API key is passed only if API is selected. The order inside "Auto" is passed in `TELESCRIPT_LLM_BACKEND` and applies where a script honours it. A script's own `backend` config still takes priority. The `TELESCRIPT_LLM_PROVIDERS_FILE` contract is unchanged.
- After upgrading from an older version the choice is taken from the old order setting until you change something.
- Console effect: the "CRT effect" button in the console title bar (on by default, remembered) adds a rolling refresh band, scanlines, a vignette and a soft glow.
- Codex (Nightly): the "Codex" switch in "AI backend" adds it to Auto, or pick "Only Codex". It runs through Termux like Claude; log in with `codex login` there. Not verified on a real device.
- Proxy secret: for MTProxy, hex (optionally dd/ee) or base64 is accepted; spaces are stripped and a wrong secret shows an error instead of a Python message.
- Scripts (automod, auto-responder): Telegram reconnect spam in the log collapses into "no connection / restored" lines.

## 15. OTA updates and the script store (1.9)

Settings > Updates (OTA): install a signed `.tgadyn` from a file, check GitHub releases, auto-check (on by default, notification only), auto-apply (off by default), rollback, reset to built-in. OTA changes the Python layer, config schemas, strings and docs; the Kotlin UI, manifest and native libs need a new APK. Two failed starts roll back automatically.

Scripts tab > Store: catalog from vipa-ch-cloud/TgAutoS with search, tags, details, install, update, rollback and delete. Script config and data are never overwritten. Script auto-update is off by default and covers only verified scripts that add no new network hosts. A `.tgascript` can be installed from a file; an unverified or unsigned one shows a warning (permissions, hosts, sha256, author) and needs a checkbox. There is no upload server: submit your script through a GitHub issue, the owner reviews it and adds the Verified label. Details: docs/OTA.md.

### Community sources and cards (1.9.1)
The store shows cards with colour, icon, version, author, date and a repository link. "Sources" lets you add a
third-party catalog (index.json URL, https) and an optional Ed25519 key (64 hex). Foreign sources never get
verified/official; without a key they are unsigned, with a warning. Same sha256, permission and install-confirm
checks. Updates from foreign sources are manual only.
OTA fetches only .tgadyn from the releases/latest of the TgAuto repo.

### APK updates, auto OTA and community keys (1.9.2)
- Settings > Updates > "App update (APK)": modes off, notify (default), download and offer, auto. Source: releases/latest of vipa-ch-cloud/TgAuto (TgAuto-X.Y.Z.apk for stable, TgAuto-nightly-X.Y.Z.apk for nightly). Checked at start, at most every 6 hours. The file is downloaded over https to a temp file with progress; size and sha256 (when published) are checked, then the package name and signing certificate MUST match the installed app, otherwise the install is refused. Installation uses PackageInstaller and needs "Install unknown apps" for TgAuto (the settings screen opens for you). Silent "auto" works only where Android allows it (12+ when updating via the same installer); otherwise Android shows its usual prompt.
- Auto OTA: "download and apply automatically" is on by default, for packages signed by the official key only, with automatic rollback. Check results go to the log in the OTA section.
- Community keys: Settings > Updates > "Trusted keys". Add an Ed25519 public key (hex or base64) with a name; the sha256 fingerprint is shown and the key can be deleted. A package signed by such a key is installed only manually after an explicit warning (author, fingerprint) and is never treated as official. The official key cannot be removed or replaced. For your own packages see tools/gen_ota_key.py and tools/make_ota.py --key in docs/OTA.md.
- CRT effect (nightly): a static RGB-subpixel grid with scanlines, soft glow on light text, light chromatic fringe and a vignette; no rolling band and no outlines. Toggle in the console header.

## 16. Terminal and AI CLI (1.9.2)

Settings has a "Terminal and AI CLI" card. The terminal is off by default; enabling it needs a confirmation and shows a warning.

- **Terminal.** A real PTY (the Termux emulator), several tabs, ANSI colours, scrollback, an Esc/Tab/Ctrl/Alt/arrows/Home/End/PgUp/PgDn row, paste and copy of the screen. Shell: `/system/bin/sh` + toybox, working directory is the app's `home/`, the app's `bin/` is in `PATH`, variables `TGA_ROOT`, `TGA_SCRIPTS`, `TGA_CONFIG`.
- **Honest limits.** The app targets Android 17 (targetSdk 37): executing downloaded programs from app data is forbidden (W^X). So there is no apt, node, pip or Python interpreter inside; only the system commands. Chaquopy Python runs inside the app process and is not exposed to the shell.
- **AI CLI.** The "AI CLI" tab installs, updates and removes claude (`@anthropic-ai/claude-code`), codex (`@openai/codex`) and gemini (`@google/gemini-cli`) in **Termux** (nodejs via `pkg`, then `npm install -g`). You need Termux from F-Droid, the "Run commands in Termux" permission and `allow-external-apps=true`. Script output arrives when it finishes, not line by line. Path and version are verified afterwards. "Login" opens a real Termux session: sign in there; TgAuto never sees or stores tokens. An optional HTTP proxy applies to install scripts only (the Telegram proxy does not work for npm).
- **AI backend.** Installed claude and codex are picked up by the existing bridge (Termux RUN_COMMAND); Nightly has an "Enable Codex in AI backend" button.
- **Security.** The terminal opens only from your tap in Settings: the activity is not exported and reads no command from an intent; scripts, store packages, Telegram and OTA files cannot start it. Optionally the device credential (PIN, pattern, fingerprint) is required on entry and after a minute in the background. The screen is excluded from screenshots. Install logs are scrubbed of URL passwords and tokens.

Details: `docs/TERMINAL.md`.

## Troubleshooting
- `CANNOT LINK EXECUTABLE "node"`: run `pkg upgrade -y`, then `pkg install -y libc++ nodejs`.
- "Check" reports `bad interpreter: No such file or directory`: in Termux run `pkg install -y libc++ nodejs` and restart Termux. TgAuto already starts the CLI through `node`, but nothing works without node.
- "Check" reports "not installed" or `not found`: the needed CLI is missing in Termux, install it per step 2 (Claude) or 3b (Gemini).
- `claude` prints "Bad system call" or "native binary not installed": the wrong version got installed, repeat the install with `@2.1.112`.
- "Check" is silent: open Termux so it stays in the background and try again.
- `gemini` fails to install with `android_ndk_path` or `tree-sitter`: install with `--ignore-scripts`, see step 3b.
- "Check" for Gemini mentions login: run `gemini` in Termux and log in again via Login with Google.
- Gemini answers about quota: a free account has a daily limit, wait or choose "Claude only".
- Script errors are in the "Logs" tab.

Everyone needs their own Claude account and api_id/api_hash: you do steps 3 (and 3b if you need Gemini) and 6 yourself.
