# OTA coverage (TgAuto 1.9.6)

## What ships over the air and what needs an APK

| Part | Over the air | How |
|---|---|---|
| Python layer (`python/`, host, helpers) | yes | `.tgadyn` overlay, official key, auto-apply, boot guard |
| Config schemas | yes | `.tgadyn` |
| UI strings (RU/EN) | yes | `.tgadyn` string overlay (`res/strings.json`) |
| Docs | yes | `.tgadyn` |
| Compose screens for plugins | yes | `.tgadex` dex plugin (own boot guard) |
| Scripts | yes | script store, `.tgascript`, per-script rollback |
| Remote switches (pause, kill, revoke) | yes | signed `flags.json` (below) |
| Kotlin app UI, manifest, permissions, services | no | new APK (in-app install screen) |
| Native libs, Python runtime, Chaquopy, userland | no | new APK |

Rule of thumb: put everything that can be Python, strings or a dex plugin there, and keep the APK for manifest,
permissions and native code. A normal fix then reaches users within one 6-hour check cycle without an APK.

## Remote hotfix flags and kill-switch

Two assets on the release marked "latest": `flags.json` and `flags.json.sig` (base64 Ed25519 over
`TGAFLAGS1\n` + exact bytes of `flags.json`, made with an official key).
Rules: bad signature, unknown format or `seq` not greater than the cached one is ignored (no replay of old flags).
Verified flags are cached, so they work offline at the next start. Fetched at app start, at most hourly, independent
of the other auto-checks.

| Field | Effect |
|---|---|
| `seq` | required integer, must grow with every new file |
| `ota_pause`, `dex_pause`, `apk_pause`, `store_pause` | stop automatic download and apply for that part (manual install still works) |
| `ota_kill`, `dex_kill` | pause plus remove the installed overlay / plugin at the next start (back to built-in code) |
| `revoked_ota`, `revoked_dex` | version lists; an installed revoked version is rolled back at the next start, a revoked release is skipped |
| `revoked_apk` | version list; never auto-downloaded |
| `message_en`, `message_ru` | text shown in Settings > Updates |

Flags stay in force until a file with a higher `seq` replaces them. To lift a kill-switch publish `seq+1` with the
fields set to false. Make a file:

    python3 tools/make_flags.py flags.json --key ~/Claude/telescript-backup/ota-signing/ed25519_private.pem -o out/
    python3 tools/make_flags.py --verify out/flags.json out/flags.json.sig --pub <public key hex>
    gh release upload vX.Y.Z out/flags.json out/flags.json.sig --clobber

(the release must be the latest one; the key file is never printed).

## Rollback

- Boot guard: an overlay or plugin that fails to start twice is removed and the previous one (or built-in code) returns.
  Same for `.tgadex` plugins, with their own guard.
- Manual: Settings > Updates > Rollback / Reset to built-in.
- Remote: `revoked_*` rolls back a bad version everywhere at the next start, `*_kill` returns to built-in code.
- Every rollback is written to the update log and shown in Settings.
- Scripts: per-script rollback in the store, config and data are kept.

## APK updates

Permission is checked before the download (nothing is downloaded that could not be installed). A verified APK stays
"ready to install" across restarts (re-verified on every read). The tap on the normal-importance notification opens an
in-app install screen with the Install button, the permission shortcut and the log of the last attempts. The
installer is never started from the background; only a tap on the notification or on the button opens it.
