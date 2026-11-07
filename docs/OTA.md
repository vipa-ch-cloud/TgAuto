# OTA updates and script store (TgAuto 1.9)

## What OTA can change
Python layer (`python/`), config schemas (`schemas/<id>.config.schema.json`), string resources
(`res/strings.json`), docs. It cannot change Kotlin/Compose UI, the manifest, native libs or the
Python runtime; those need a new APK. Dex plugins are stage 2.

## Package `.tgadyn`
Zip: `manifest.json`, `manifest.sig` (Ed25519 over `TGADYN1\n` + manifest bytes), payload files.
Manifest: format, name, version, build, requires_base (version_name / version_code ranges),
channel (stable|nightly|any), created, files [{path, sha256}], changelog_en/ru.
Rejected: unsigned, bad signature, wrong base or channel, hash mismatch, unlisted files, path
traversal, zip bombs, oversize.

## Build and sign (private key stays outside the repo, never committed)
    python3 tools/make_ota.py PAYLOAD --template manifest.template.json --key ~/Claude/telescript-backup/ota-signing/ed25519_private.pem -o X.tgadyn
    python3 tools/verify_ota.py X.tgadyn

## Delivery
- GitHub release of vipa-ch-cloud/TgAuto with a `*.tgadyn` asset: the app checks `releases/latest`.
- Or post the file in Telegram/anywhere: opening it launches the installer (VIEW/SEND).
Example release asset: `TgAuto-ota-1.9.1.tgadyn`; post text: version, changelog, "requires 1.9.x".

## On device
Overlay in `files/ota/<version>/`, atomic `current`/`previous` pointers. Two failed starts roll back
automatically. Settings > Updates: install from file, check releases, auto-check (default on,
notification only), auto-apply (default off), rollback, reset to built-in.

## Script store (repo vipa-ch-cloud/TgAutoS)
Signed `index.json` (+ `.sig`) lists scripts (id, name, description en/ru, author, version, tags,
sha256, package URL, verified/official). Screen: search, tags, details, install/update/rollback/delete.
Scripts live in `files/store/<id>/<version>/`; config and data are kept apart, so updates, rollback
and delete-keep-data never overwrite them. Auto-update (default off) applies only to verified scripts
whose update adds no new network hosts.
Unsigned/unverified/local `.tgascript`: a warning with permissions, hosts found in code, sha256,
author and verified label; install needs an explicit checkbox. Never silent.
Build: `tools/make_script_pkg.py`, `tools/make_store_index.py`; repo template: `tools/store_repo_template/`.

## Limits
No upload backend: user scripts go through a GitHub issue/PR and owner review. The static scan is a
hint, not a guarantee. Store updates cover store-installed scripts only, not SAF-folder scripts.

## 1.9.1: store cards, metadata, community sources

Optional manifest/catalog fields (all ignored if absent, old catalogs keep working): `title_ru`, `title_en`,
`icon` (shield, chat_reply, bot, script, tool, star, bell, lock; anything else = script), `color` (hex `#RRGGBB`),
`repo` (https URL), `published` (ISO date), `homepage` (https URL). `tools/make_store_index.py` copies them from
`tgascript.json` into the index; the app also reads them from the installed package.

Community sources (Store, "Sources"): the official catalog is built in and verified with the key from OtaKeys.
A user may add an extra `index.json` URL (https only) and an optional Ed25519 public key (64 hex). Rules:
- Foreign sources are never `verified` or `official`, whatever their index claims.
- With a key the index signature is checked; without a key the source is unsigned and shown with a warning.
- Same checks as always: sha256, permission list, static scan, explicit install confirmation.
- Adding a source needs a confirmation with a warning. Sources are stored in Settings (`store_sources`).
- Updates from third-party sources are manual only; an id installed from one source cannot be replaced from another.

What the app fetches automatically: `OtaManager` queries `releases/latest` of `vipa-ch-cloud/TgAuto` (https only),
takes the first asset ending in `.tgadyn`, parses the version from its name and offers it if it is newer than the
installed one. Base compatibility (`requires_base`, channel) is checked at verify time. Auto-check runs at start
(default on, at most once per 6 h); auto-apply is off by default (takes effect after restart). Pre-releases and
older releases are not examined. The app does NOT download or install APKs (no REQUEST_INSTALL_PACKAGES).
1.9.1 UI (Compose) changes ship only in the APK; `.tgadyn` carries Python, strings and schemas.

## 1.9.2: APK updates, auto OTA, community keys

- **Auto OTA** (default on): the app fetches `.tgadyn` from releases/latest, applies it only if signed by the official key, keeps the previous version for rollback (boot guard: 2 failed starts). Packages from community keys are never applied automatically. Results are logged in Settings > Updates.
- **Community keys**: Settings > Updates > Trusted keys (hex or base64 Ed25519 public key + name, sha256 fingerprint shown, deletable). A package whose signature verifies only against such a key shows a warning (author, fingerprint) and needs an explicit confirmation; it is never "official/verified". The official key is built in, is tried first, and cannot be removed or replaced. Domain separation is unchanged: the signed message is `TGADYN1\n` + manifest bytes.
- **Make your own key and package** (the private key stays outside the repo and is never printed unless `--print-private` is given):

      python3 tools/gen_ota_key.py --out ~/keys/my_ota.pem      # prints public key hex + fingerprint
      python3 tools/make_ota.py payload --template manifest.json --key ~/keys/my_ota.pem -o my.tgadyn
      python3 tools/verify_ota.py my.tgadyn --pub official.hex --trust <your public key hex>

  Give users the public key hex and fingerprint over a channel they trust.
- **APK updates**: assets `TgAuto-X.Y.Z.apk` (stable) and `TgAuto-nightly-X.Y.Z.apk` (nightly) in releases/latest of vipa-ch-cloud/TgAuto; optional sha256 via the asset `digest` or a `SHA256SUMS` / `<file>.sha256` asset. The new APK must have the same package name, a higher versionCode and exactly the same signing certificate set as the installed app, otherwise it is refused. Modes: off / notify (default) / download and offer / auto (silent only where Android allows; otherwise the system prompt is shown). Checked at start, at most every 6 hours.
