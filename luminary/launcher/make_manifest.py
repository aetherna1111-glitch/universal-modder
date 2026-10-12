# make_manifest.py — генератор manifest.json для сервера
# Запускается ТОЛЬКО разработчиком перед загрузкой на сервер.
#
# Использование:
#   python\python.exe make_manifest.py

import os
import sys
import json
import hashlib
from datetime import datetime

# =========================================================
#  НАСТРОЙКИ
# =========================================================
BASE_DIR     = os.path.dirname(os.path.abspath(__file__))
MC_DIR       = os.path.join(BASE_DIR, '.minecraft')
CONFIG_FILE  = os.path.join(BASE_DIR, 'config_updater.json')
OUTPUT_FILE  = os.path.join(BASE_DIR, 'manifest.json')

# =========================================================
#  ЧТЕНИЕ КОНФИГА
# =========================================================
def load_config():
    if not os.path.exists(CONFIG_FILE):
        print(f"!!! Не найден {CONFIG_FILE}")
        print("Создай config_updater.json рядом с launcher.py")
        sys.exit(1)

    with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
        return json.load(f)


# =========================================================
#  SHA-256 ХЕШ ФАЙЛА
# =========================================================
def sha256_of_file(path, chunk_size=65536):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


# =========================================================
#  СБОРКА МАНИФЕСТА
# =========================================================
def build_manifest(sync_folders):
    manifest = {
        "version": datetime.now().strftime("%Y.%m.%d-%H%M"),
        "updated": datetime.now().isoformat(timespec='seconds'),
        "files": []
    }

    total_files = 0
    total_size = 0

    for folder in sync_folders:
        full_path = os.path.join(MC_DIR, folder)
        if not os.path.isdir(full_path):
            print(f"  [пропуск] папка не найдена: {folder}/")
            continue

        print(f"  [обработка] {folder}/")
        folder_count = 0
        folder_size = 0

        for root, dirs, files in os.walk(full_path):
            for fname in files:
                fpath = os.path.join(root, fname)

                # путь относительно .minecraft/
                rel = os.path.relpath(fpath, MC_DIR).replace("\\", "/")

                try:
                    h = sha256_of_file(fpath)
                    size = os.path.getsize(fpath)
                except Exception as e:
                    print(f"    [ошибка] {rel}: {e}")
                    continue

                manifest["files"].append({
                    "path": rel,
                    "sha256": h,
                    "size": size
                })

                folder_count += 1
                folder_size += size
                total_files += 1
                total_size += size

        print(f"             {folder_count} файлов, "
              f"{folder_size / 1024 / 1024:.2f} МБ")

    return manifest, total_files, total_size


# =========================================================
#  MAIN
# =========================================================
def main():
    print("=" * 60)
    print("  Генератор manifest.json")
    print("=" * 60)
    print()

    cfg = load_config()
    sync_folders = cfg.get("sync_folders", [])

    if not sync_folders:
        print("!!! В config_updater.json пустой sync_folders")
        sys.exit(1)

    print(f"Папка .minecraft: {MC_DIR}")
    print(f"Синхронизируются: {', '.join(sync_folders)}")
    print()

    manifest, total_files, total_size = build_manifest(sync_folders)

    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    print()
    print("=" * 60)
    print(f"  Готово!")
    print(f"  Версия:  {manifest['version']}")
    print(f"  Файлов:  {total_files}")
    print(f"  Размер:  {total_size / 1024 / 1024:.2f} МБ")
    print(f"  Файл:    {OUTPUT_FILE}")
    print("=" * 60)
    print()
    print("Что делать дальше:")
    print(f"  1. Загрузи {os.path.basename(OUTPUT_FILE)} на сервер")
    print(f"  2. Загрузи изменённые файлы из .minecraft/ на сервер")
    print()


if __name__ == "__main__":
    main()