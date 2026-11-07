# .tgadex — signed code/UI plugin package

## RU
`.tgadex` — подписанный zip с кодом (classes.dex) и интерфейсом плагина. Отдельный формат от `.tgadyn`: подпись Ed25519 с доменом `TGADEX1\n`, поэтому пакет не валиден как `.tgadyn`/`.tgascript`/каталог и наоборот.

Содержимое: `manifest.json` (id, version, build, minAppVersion, apiVersion, entry, channel, files с sha256/size), `classes.dex` (до `classes9.dex`), `signature.ed25519`.

Безопасность:
- Плагин выполняется с правами приложения. Ставьте только от доверенных авторов.
- Официальный ключ: автоприменение по настройке «Применять официальные обновления плагина» (по умолчанию ВЫКЛ). Ключи сообщества — только вручную с предупреждением, помечаются как не официальные.
- Пакет перепроверяется при каждом запуске; dex read-only (Android 14+).
- После 2 неудачных стартов — откат на предыдущий плагин или встроенный код.

API v1 (Java, пакет `com.v1pach.telescript.plugin`): `TgadexPlugin.onLoad(PluginHost)`, `screens()`, `createView(Context, id)`, `onUnload()`.
Инструменты: `tools/make_tgadex.py`, `tools/verify_tgadex.py`, `tools/test_tgadex.py`; пример — `tools/tgadex_sample/`.
UI: Настройки → Обновления (OTA) → Плагин (.tgadex). Из GitHub-релиза берётся `.tgadex` asset.

## EN
`.tgadex` is a signed zip carrying code (classes.dex) and plugin UI, separate from `.tgadyn`: Ed25519 signature with domain `TGADEX1\n`, so it never validates as `.tgadyn`/`.tgascript`/catalog and vice versa.

Contents: `manifest.json` (id, version, build, minAppVersion, apiVersion, entry, channel, files with sha256/size), `classes.dex` (up to `classes9.dex`), `signature.ed25519`.

Security:
- A plugin runs with the app's privileges. Install only from authors you trust.
- Official key: auto-apply via the "Apply official plugin updates automatically" setting (OFF by default). Community keys: manual only, with a warning, marked unofficial.
- The package is re-verified on every start; dex files are read-only (Android 14+).
- After 2 failed starts the app rolls back to the previous plugin or the built-in code.

Plugin API v1 (Java, package `com.v1pach.telescript.plugin`): `TgadexPlugin.onLoad(PluginHost)`, `screens()`, `createView(Context, id)`, `onUnload()`.
Tools: `tools/make_tgadex.py`, `tools/verify_tgadex.py`, `tools/test_tgadex.py`; sample in `tools/tgadex_sample/`.
UI: Settings -> Updates (OTA) -> Plugin (.tgadex). The `.tgadex` asset of a GitHub release is used.
