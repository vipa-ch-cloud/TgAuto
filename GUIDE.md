# TeleScript: установка на телефон (root не нужен)

## 1. Termux
- Скачай Termux с F-Droid (f-droid.org) или с GitHub (github.com/termux/termux-app/releases).
- Версия из Play Маркета не подойдёт, она устаревшая.

## 2. Claude Code в Termux
Открой Termux и вводи команды по одной:
```
pkg upgrade -y
pkg install -y libc++ nodejs
node -v
DISABLE_AUTOUPDATER=1 npm i -g @anthropic-ai/claude-code@2.1.112
mkdir -p ~/.claude && echo '{"env":{"DISABLE_AUTOUPDATER":"1"}}' > ~/.claude/settings.json
```
- Если Termux спросит про замену конфигов, жми Enter.
- `node -v` должен показать версию. Если вместо неё ошибка `CANNOT LINK EXECUTABLE`, ещё раз выполни первые две строки.
- Версия Claude Code нужна именно 2.1.112, новые в Termux не запускаются.

## 3. Вход в Claude
- Введи `claude` и выбери вход через аккаунт Claude.
- Откроется ссылка: войди в браузере, скопируй код и вставь его обратно в Termux.
- Выйди командой `/exit`.
- Проверь: `claude -p "скажи ок"`. Если ответил, всё работает.

## 3б. Gemini (по желанию, запасной вариант)
Если Claude недоступен, скрипты возьмут Gemini. Нужен обычный аккаунт Google, ключ API не нужен.
```
pkg install -y python make clang
npm install -g @google/gemini-cli --ignore-scripts
gemini --version
```
- `--ignore-scripts` обязателен: без него установка падает на сборке `tree-sitter` (ошибка про `android_ndk_path`). Для работы это не нужно.
- Если новая версия всё равно не запускается, поставь старую: `npm install -g @google/gemini-cli@0.2.2 --ignore-scripts`. Есть и сборка для Termux: github.com/DioNanos/gemini-cli-termux.
- Введи `gemini` и выбери **Login with Google** (Sign in with Google).
- Gemini покажет ссылку. Открой её в браузере телефона и войди в Google. Страница потом перейдёт на `localhost`: это тот же телефон, так и должно быть, вернись в Termux.
- Если Gemini просит вставить код, скопируй его со страницы и вставь в Termux. Если браузер сам не открывается, запусти так: `NO_BROWSER=true gemini`.
- Выйди командой `/quit`.
- Проверь: `gemini -p "скажи ок"`. Если ответил, всё работает.

## 4. Разрешить TeleScript управлять Termux
```
mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
termux-reload-settings
```

## 5. TeleScript
- Установи `TeleScript-1.0.apk`.
- При запуске разреши уведомления и «Выполнять команды в Termux».
- В настройках телефона отключи оптимизацию батареи для **Termux** и **TeleScript**: Приложения → Батарея → Без ограничений.

## 6. Ключи Telegram
- Зайди на my.telegram.org → API development tools → создай приложение (название любое).
- Скопируй **api_id** и **api_hash**.

## 7. Вход в Telegram
- В TeleScript открой вкладку «Аккаунт» и вставь api_id и api_hash.
- Введи номер, потом код из Telegram, потом пароль 2FA, если он есть.

## 8. Проверка Claude и Gemini
- Открой «Настройки» → LLM и выбери порядок: «Claude, потом Gemini», «Только Claude», «Только Gemini» или «Только API-ключ».
- Нажми «Проверить» у нужного аккаунта. Должно прийти «ок».
- Ключ Anthropic внизу нужен только как последний запас, можно оставить пустым.

## 9. Скрипты
- Открой вкладку «Скрипты» и нажми «Выбрать папку». Создай, например, `Documents/Scripts`.
- Нажми «Пример: automod», чтобы автомод появился в папке.
- На карточке скрипта нажми «Зависимости», потом «Запустить».

## 10. Автомод в чате
Твой аккаунт должен быть админом в чате, в котором стоит Ирис. Команды пиши прямо в чате:
- `.automod`: включить автомод в этом чате.
- `.automod -r=ссылка`: включить и взять правила по ссылке.
- `.automod -d`: убрать автомод из этого чата.
- `.automod off`: выключить (чат остаётся в списке).
- `.automod status`: показать, включён ли автомод.
- `.context N`: сколько сообщений смотреть в одной проверке (N это число).
- `.ams`: проверить чат прямо сейчас.

## Если не работает
- `CANNOT LINK EXECUTABLE "node"`: выполни `pkg upgrade -y`, затем `pkg install -y libc++ nodejs`.
- «Проверить» пишет `bad interpreter: No such file or directory`: выполни в Termux `pkg install -y libc++ nodejs` и перезапусти Termux. TeleScript уже запускает CLI через `node`, но без node ничего не заработает.
- «Проверить» пишет «не установлен» или `not found`: нужного CLI нет в Termux, поставь его по шагу 2 (Claude) или 3б (Gemini).
- `claude` выдаёт «Bad system call» или «native binary not installed»: встала не та версия, повтори установку с `@2.1.112`.
- «Проверить» молчит: открой Termux, чтобы он висел в фоне, и попробуй ещё раз.
- `gemini` падает при установке с `android_ndk_path` или `tree-sitter`: ставь с `--ignore-scripts`, см. шаг 3б.
- «Проверить» у Gemini пишет про вход: запусти `gemini` в Termux и войди заново через Login with Google.
- Gemini отвечает про квоту: у бесплатного аккаунта есть дневной лимит, подожди или выбери «Только Claude».
- Ошибки скриптов смотри во вкладке «Логи».

Каждому нужны свои аккаунт Claude и api_id/api_hash: шаги 3 (и 3б, если нужен Gemini) и 6 делаешь сам.
