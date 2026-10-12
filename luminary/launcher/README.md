# Luminary Launcher

Лаунчер Minecraft-сервера Luminary (1.20.1, Fabric).

## Запуск
- **Luminary.exe** — обычный запуск без консольных окон. Собирается один раз командой `build_starter.bat`
  (нужен только Windows, ничего устанавливать не надо).
- `run.bat` — запасной вариант (на секунду мелькнёт окно cmd).
- `run-debug.bat` — для разработчика: консоль с журналом.

## Файлы
- `launcher.py` — точка входа; код лаунчера в `luminary_app/`.
- `profiles.json` — версия Minecraft, загрузчик, сервер, сборка, адрес сервера аккаунтов.
- `config_updater.json` — откуда синхронизируется сборка (`make_manifest.py` создаёт manifest.json).
- `LuminaryLauncher.exe` — установщик игры PortableMC 5.0.5; `python\`, `jre\` — портативные Python и Java.
- Создаются автоматически: `launcher_data\` (настройки, зашифрованная сессия), `logs\` (журналы
  лаунчера, установки и игры), `runtime\` (authlib-injector), `.minecraft\`.

## Если что-то не работает
Настройки → Диагностика → «Папка журналов» и «Скопировать сведения». Ошибки запуска и вылеты игры
показываются в окне со ссылками на журнал и отчёт о сбое.

Подробнее: `../docs/DEPLOYMENT.md`, `../docs/SECURITY.md`, `../docs/TESTING.md`.
