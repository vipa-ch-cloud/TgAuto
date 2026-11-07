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
