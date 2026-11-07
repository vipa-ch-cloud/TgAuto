# TgAutoS: script catalog repository (template)

Layout: `index.json` + `index.json.sig` (signed by the owner), `packages/<id>-<version>.tgascript`,
`store-meta.json` (per-script catalog data: verified/official flags, tags, descriptions).

Review flow:
1. A user opens an issue with the `submit-script` form, or a PR adding the script folder.
2. The owner reads the code (network hosts, Telegram actions, risky modules).
3. If it is acceptable: `python3 tools/make_script_pkg.py <dir> -o packages/<id>-<ver>.tgascript`,
   set `"verified": true` for it in `store-meta.json`, then
   `python3 tools/make_store_index.py packages/*.tgascript --base-url <raw URL prefix> --meta store-meta.json --key <owner key> -o .`
4. Commit `index.json` and `index.json.sig`. The app trusts only the signed index; `verified` comes from there.

There is no upload server. Not-yet-reviewed scripts stay out of the catalog; users can still install them
from a file, with a clear warning.

## Optional card metadata (1.9.1)

Add to `tgascript.json` (all optional): `title_ru`, `title_en`, `icon` (shield, chat_reply, bot, script, tool,
star, bell, lock), `color` (`#RRGGBB`), `repo`, `published` (ISO date), `homepage`. `make_store_index.py` copies
them into `index.json`.

## Community sources

Anyone can host their own catalog with the same layout. Users add its `index.json` URL (https) in the app under
Store -> Sources; an optional Ed25519 public key (64 hex) makes the app verify `index.json.sig`. Without a key the
source is unsigned. Foreign sources are never marked verified/official, and their updates are manual.
