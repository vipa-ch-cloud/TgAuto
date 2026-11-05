Русский | [English](README.en.md)

# TgAuto

Скрипты: https://github.com/vipa-ch-cloud/TgAutoS

TgAuto is an Android app that runs Python Telegram (Telethon) userbot scripts on the phone, no root needed.

## Что это
Приложение запускает ваши Python-скрипты для Telegram прямо на телефоне и умеет подключать к ним LLM. Интерфейс на русском и английском (по языку системы), Material You.

## Возможности
- Запуск скриптов Telethon на встроенном Python 3.12 (Chaquopy), фоновая работа через foreground-сервис.
- Вход в Telegram по номеру телефона (QR как запасной вариант), сессия общая для скриптов.
- Папка со скриптами выбирается через SAF, скрипты зеркалируются во внутреннюю память приложения.
- Установка Python-библиотек на телефоне (runtime pip) для каждого скрипта.
- LLM для скриптов: аккаунты Claude и Gemini через Termux (настоящие CLI, без инструментов), Codex (Nightly, через Termux, экспериментально), любые OpenAI-совместимые провайдеры (DeepSeek, OpenAI, свой API). Раздел провайдеров пока только в сборке Nightly. Ключи хранятся только на телефоне.
- Форма «Конфиг»: скрипт кладёт рядом `config.schema.json`, приложение показывает настройки формой.
- Логи скриптов во вкладке «Логи».

Скрипты (automod, autoresponder) лежат в отдельном репозитории: https://github.com/vipa-ch-cloud/TgAutoS

## Скриншоты
Скриншоты будут добавлены позже.

## Установка
Подробная инструкция для пользователя: [GUIDE.md](GUIDE.md) ([English](GUIDE.en.md)).
Готовые APK: `TgAuto-<версия>.apk` (стабильная) и `TgAuto-nightly-<версия>.apk` (Nightly, ставится рядом).

## Сборка
Нужны JDK 17+ и Android SDK (compileSdk 37), Python 3.12 для Chaquopy.
```
cd android
export TELESCRIPT_BUILD_PYTHON=/path/to/python3.12   # если путь по умолчанию не подходит
./gradlew assembleStableDebug assembleNightlyDebug
```
APK: `android/app/build/outputs/apk/{stable,nightly}/debug/`.

## Примечание об идентификаторах
Имя приложения TgAuto, но внутренний applicationId остался `com.v1pach.telescript` (Nightly добавляет `.nightly`), а переменные окружения для скриптов называются `TELESCRIPT_*`. Это сделано намеренно, чтобы обновления сохраняли данные пользователей.

## Лицензия
Проект распространяется по лицензии GNU GPL v3 или новее (GPL-3.0-or-later). Полный текст в файле [LICENSE](LICENSE).
