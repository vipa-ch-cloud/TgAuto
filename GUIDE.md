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

## 8. Проверка Claude
- Открой «Настройки» → LLM, режим «Claude-аккаунт (Termux)».
- Нажми «Проверить». Должно прийти «ок».

## 9. Скрипты
- Открой вкладку «Скрипты» и нажми «Выбрать папку». Создай, например, `Documents/Scripts`.
- Нажми «Пример: automod», чтобы автомод появился в папке.
- На карточке скрипта нажми «Зависимости», потом «Запустить».

## 10. Автомод в чате
- Твой аккаунт должен быть админом в чате, в котором стоит Ирис.
- Включить: `.automod`, или `.automod -r=ссылка_на_правила`.
- Выключить: `.automod off`. Проверить: `.automod status`.

## Если не работает
- `CANNOT LINK EXECUTABLE "node"`: выполни `pkg upgrade -y`, затем `pkg install -y libc++ nodejs`.
- `claude` выдаёт «Bad system call» или «native binary not installed»: встала не та версия, повтори установку с `@2.1.112`.
- «Проверить» молчит: открой Termux, чтобы он висел в фоне, и попробуй ещё раз.
- Ошибки скриптов смотри во вкладке «Логи».

Каждому нужны свои аккаунт Claude и api_id/api_hash: шаги 3 и 6 делаешь сам.
