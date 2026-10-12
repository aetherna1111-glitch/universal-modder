# updater.py — модуль синхронизации сборки с сервером
import os
import json
import hashlib
import shutil
import urllib.request
import urllib.error
from datetime import datetime

from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QLabel, QPushButton,
    QProgressBar, QMessageBox, QScrollArea, QWidget, QFrame
)

# =========================================================
#  ПУТИ И КОНСТАНТЫ
# =========================================================
BASE_DIR        = os.path.dirname(os.path.abspath(__file__))
MC_DIR          = os.path.join(BASE_DIR, '.minecraft')
CONFIG_FILE     = os.path.join(BASE_DIR, 'config_updater.json')
LOCAL_MANIFEST  = os.path.join(MC_DIR, '.luminary_manifest.json')
BACKUP_DIR      = os.path.join(MC_DIR, '.backup')

# цвета (как в лаунчере)
BG           = "#0a1418"
PANEL        = "#0f1e26"
CARD         = "#142a33"
BORDER       = "#1e3d47"
ACCENT       = "#3dd6c4"
ACCENT_HOVER = "#2bb5a4"
TEXT         = "#d4eae6"
MUTED        = "#5f8a8b"
GREEN        = "#4ade80"
RED          = "#ff6b6b"
YELLOW       = "#facc15"


# =========================================================
#  ЗАГРУЗКА КОНФИГА
# =========================================================
def load_config():
    if not os.path.exists(CONFIG_FILE):
        return None
    with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
        return json.load(f)


# =========================================================
#  SHA-256
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
#  СКАЧИВАНИЕ ФАЙЛА
# =========================================================
def download_file(url, dest_path, timeout=30):
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    tmp_path = dest_path + ".tmp"

    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        with open(tmp_path, 'wb') as f:
            shutil.copyfileobj(response, f)

    # атомарная замена
    if os.path.exists(dest_path):
        os.remove(dest_path)
    os.rename(tmp_path, dest_path)


# =========================================================
#  СРАВНЕНИЕ ЛОКАЛЬНОЙ СБОРКИ С МАНИФЕСТОМ
# =========================================================
def compare_with_manifest(manifest, sync_folders):
    """
    Возвращает словарь:
    {
      'to_download': [{path, sha256, size, url}, ...],
      'to_delete':   [path, ...],
      'up_to_date':  [path, ...]
    }
    """
    to_download = []
    to_delete = []
    up_to_date = []

    # какие файлы ЕСТЬ в манифесте
    manifest_paths = set()
    for item in manifest.get('files', []):
        manifest_paths.add(item['path'])

    # 1. Проверяем все файлы из манифеста — есть ли локально и совпадает ли хеш
    for item in manifest.get('files', []):
        rel_path = item['path']
        local_path = os.path.join(MC_DIR, rel_path.replace('/', os.sep))

        if not os.path.exists(local_path):
            to_download.append(item)
            continue

        try:
            local_hash = sha256_of_file(local_path)
        except Exception:
            to_download.append(item)
            continue

        if local_hash != item['sha256']:
            to_download.append(item)
        else:
            up_to_date.append(rel_path)

    # 2. Проверяем локальные файлы — нет ли лишних (которых нет в манифесте)
    for folder in sync_folders:
        full = os.path.join(MC_DIR, folder)
        if not os.path.isdir(full):
            continue

        for root, dirs, files in os.walk(full):
            for fname in files:
                fpath = os.path.join(root, fname)
                rel = os.path.relpath(fpath, MC_DIR).replace("\\", "/")
                if rel not in manifest_paths:
                    to_delete.append(rel)

    return {
        'to_download': to_download,
        'to_delete': to_delete,
        'up_to_date': up_to_date,
    }


# =========================================================
#  ПОТОК: СКАЧИВАНИЕ / УДАЛЕНИЕ
# =========================================================
class UpdateWorker(QThread):
    progress = Signal(int, int, str)     # current, total, filename
    finished_ok = Signal(int, int)       # downloaded, deleted
    failed = Signal(str)

    def __init__(self, server_url, manifest, to_download, to_delete, cfg):
        super().__init__()
        self.server_url = server_url
        self.manifest = manifest
        self.to_download = to_download
        self.to_delete = to_delete
        self.cfg = cfg

    def run(self):
        try:
            downloaded = 0
            deleted = 0
            total = len(self.to_download) + len(self.to_delete)
            current = 0

            # скачивание
            for item in self.to_download:
                rel = item['path']
                url = self.server_url.rstrip('/') + '/' + rel
                dest = os.path.join(MC_DIR, rel.replace('/', os.sep))

                self.progress.emit(current, total, f"Скачиваю: {rel}")
                download_file(url, dest, timeout=self.cfg.get('timeout', 30))
                current += 1
                downloaded += 1

            # удаление
            backup_enabled = self.cfg.get('backup_before_delete', True)
            backup_dir_name = self.cfg.get('backup_folder', '.backup')

            for rel in self.to_delete:
                local_path = os.path.join(MC_DIR, rel.replace('/', os.sep))
                if not os.path.exists(local_path):
                    continue

                self.progress.emit(current, total, f"Удаляю: {rel}")

                if backup_enabled:
                    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
                    backup_path = os.path.join(
                        MC_DIR, backup_dir_name, stamp,
                        rel.replace('/', os.sep)
                    )
                    os.makedirs(os.path.dirname(backup_path), exist_ok=True)
                    try:
                        shutil.move(local_path, backup_path)
                    except Exception:
                        os.remove(local_path)
                else:
                    os.remove(local_path)

                current += 1
                deleted += 1

            # сохраняем локальную копию манифеста
            with open(LOCAL_MANIFEST, 'w', encoding='utf-8') as f:
                json.dump(self.manifest, f, indent=2, ensure_ascii=False)

            self.finished_ok.emit(downloaded, deleted)

        except Exception as e:
            self.failed.emit(str(e))


# =========================================================
#  ОКНО "ДОСТУПНО ОБНОВЛЕНИЕ"
# =========================================================
class UpdateDialog(QDialog):
    def __init__(self, parent, manifest, comparison):
        super().__init__(parent)
        self.setWindowTitle("Обновление Luminary")
        self.setFixedSize(500, 420)
        self.setStyleSheet(f"""
            QDialog {{
                background: {BG};
                color: {TEXT};
            }}
            QLabel {{
                color: {TEXT};
                background: transparent;
            }}
            QPushButton {{
                background: {ACCENT};
                color: #0a1418;
                border: none;
                border-radius: 8px;
                padding: 10px 20px;
                font-size: 14px;
                font-weight: bold;
            }}
            QPushButton:hover {{
                background: {ACCENT_HOVER};
            }}
            QPushButton#secondary {{
                background: transparent;
                color: {MUTED};
                border: 1px solid {BORDER};
            }}
            QPushButton#secondary:hover {{
                background: {CARD};
                color: {TEXT};
            }}
        """)

        self.manifest = manifest
        self.comparison = comparison

        self.build_ui()

    def build_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(25, 25, 25, 25)
        layout.setSpacing(15)

        title = QLabel("Доступно обновление сборки")
        title.setStyleSheet(
            f"color: {ACCENT}; font-size: 18px; font-weight: bold; "
            f"background: transparent;"
        )
        layout.addWidget(title)

        # версия
        version = QLabel(f"Версия: {self.manifest.get('version', '?')}")
        version.setStyleSheet(
            f"color: {MUTED}; font-size: 12px; background: transparent;"
        )
        layout.addWidget(version)

        layout.addSpacing(10)

        # сводка
        n_download = len(self.comparison['to_download'])
        n_delete = len(self.comparison['to_delete'])

        total_size = sum(
            f['size'] for f in self.comparison['to_download']
        )
        size_mb = total_size / 1024 / 1024

        summary = QLabel(
            f"• Скачать файлов: <b>{n_download}</b><br>"
            f"• Удалить файлов: <b>{n_delete}</b><br>"
            f"• Объём загрузки: <b>{size_mb:.2f} МБ</b>"
        )
        summary.setTextFormat(Qt.RichText)
        summary.setStyleSheet(
            f"color: {TEXT}; font-size: 13px; background: transparent;"
        )
        layout.addWidget(summary)

        layout.addSpacing(10)

        # список файлов (скролл)
        files_label = QLabel("Изменения:")
        files_label.setStyleSheet(
            f"color: {MUTED}; font-size: 12px; background: transparent;"
        )
        layout.addWidget(files_label)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        scroll.setStyleSheet(f"""
            QScrollArea {{
                background: {CARD};
                border: 1px solid {BORDER};
                border-radius: 8px;
            }}
        """)

        content = QWidget()
        content.setStyleSheet(f"background: {CARD};")
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(10, 10, 10, 10)
        content_layout.setSpacing(4)

        for item in self.comparison['to_download'][:50]:
            lbl = QLabel(f"➕ {item['path']}")
            lbl.setStyleSheet(
                f"color: {GREEN}; font-size: 11px; "
                f"background: transparent;"
            )
            content_layout.addWidget(lbl)

        for rel in self.comparison['to_delete'][:50]:
            lbl = QLabel(f"➖ {rel}")
            lbl.setStyleSheet(
                f"color: {RED}; font-size: 11px; "
                f"background: transparent;"
            )
            content_layout.addWidget(lbl)

        if (len(self.comparison['to_download']) > 50
                or len(self.comparison['to_delete']) > 50):
            more = QLabel("... (ещё много файлов)")
            more.setStyleSheet(
                f"color: {MUTED}; font-size: 11px; "
                f"background: transparent;"
            )
            content_layout.addWidget(more)

        content_layout.addStretch()
        scroll.setWidget(content)
        layout.addWidget(scroll, 1)

        # кнопки
        btns = QHBoxLayout()
        btns.addStretch()

        btn_skip = QPushButton("Играть без обновления")
        btn_skip.setObjectName("secondary")
        btn_skip.setCursor(Qt.PointingHandCursor)
        btn_skip.clicked.connect(self.reject)
        btns.addWidget(btn_skip)

        btn_update = QPushButton("Обновить")
        btn_update.setCursor(Qt.PointingHandCursor)
        btn_update.clicked.connect(self.accept)
        btns.addWidget(btn_update)

        layout.addLayout(btns)


# =========================================================
#  ОКНО ПРОГРЕССА
# =========================================================
class ProgressDialog(QDialog):
    def __init__(self, parent, worker):
        super().__init__(parent)
        self.setWindowTitle("Обновление Luminary")
        self.setFixedSize(500, 200)
        self.setModal(True)
        self.setStyleSheet(f"""
            QDialog {{
                background: {BG};
                color: {TEXT};
            }}
            QLabel {{
                color: {TEXT};
                background: transparent;
            }}
            QProgressBar {{
                background: {CARD};
                border: 1px solid {BORDER};
                border-radius: 6px;
                height: 24px;
                text-align: center;
                color: {TEXT};
            }}
            QProgressBar::chunk {{
                background: {ACCENT};
                border-radius: 5px;
            }}
        """)

        self.worker = worker

        layout = QVBoxLayout(self)
        layout.setContentsMargins(25, 25, 25, 25)
        layout.setSpacing(15)

        self.title = QLabel("Скачиваю файлы...")
        self.title.setStyleSheet(
            f"color: {ACCENT}; font-size: 16px; font-weight: bold; "
            f"background: transparent;"
        )
        layout.addWidget(self.title)

        self.file_label = QLabel("Подготовка...")
        self.file_label.setStyleSheet(
            f"color: {MUTED}; font-size: 12px; background: transparent;"
        )
        layout.addWidget(self.file_label)

        self.bar = QProgressBar()
        self.bar.setRange(0, 100)
        self.bar.setValue(0)
        layout.addWidget(self.bar)

        layout.addStretch()

        # сигналы
        self.worker.progress.connect(self.on_progress)
        self.worker.finished_ok.connect(self.on_finished)
        self.worker.failed.connect(self.on_failed)

    def on_progress(self, current, total, message):
        if total > 0:
            percent = int(current / total * 100)
            self.bar.setValue(percent)
        self.file_label.setText(message)

    def on_finished(self, downloaded, deleted):
        self.bar.setValue(100)
        self.title.setText("Обновление завершено!")
        self.file_label.setText(
            f"Скачано: {downloaded}, удалено: {deleted}"
        )
        self.accept()

    def on_failed(self, error):
        self.title.setText("Ошибка обновления")
        self.file_label.setText(error)
        QMessageBox.critical(self, "Ошибка", f"Ошибка обновления:\n{error}")
        self.reject()


# =========================================================
#  ГЛАВНАЯ ФУНКЦИЯ — ВЫЗЫВАЕТСЯ ИЗ launcher.py
# =========================================================
def check_and_update(parent):
    """
    Проверяет обновления. Возвращает:
      True  — можно запускать Minecraft
      False — игрок отказался или критическая ошибка
    """
    cfg = load_config()
    if not cfg:
        print("[updater] config_updater.json не найден, пропускаю проверку")
        return True

    server_url = cfg.get('server_url', '').rstrip('/') + '/'
    manifest_url = server_url + cfg.get('manifest_name', 'manifest.json')

    print(f"[updater] Проверка обновлений: {manifest_url}")

    # 1. Скачиваем манифест
    try:
        req = urllib.request.Request(manifest_url)
        with urllib.request.urlopen(req, timeout=cfg.get('timeout', 30)) as r:
            remote_manifest = json.loads(r.read().decode('utf-8'))
        print(f"[updater] Манифест получен, версия {remote_manifest.get('version')}")
    except Exception as e:
        print(f"[updater] Не удалось получить манифест: {e}")
        if cfg.get('block_launch_on_error', False):
            QMessageBox.critical(
                parent, "Ошибка",
                f"Не удалось проверить обновления.\n\n{e}"
            )
            return False
        return True  # играть со старой сборкой

    # 2. Сравниваем
    sync_folders = cfg.get('sync_folders', [])
    comparison = compare_with_manifest(remote_manifest, sync_folders)

    n_download = len(comparison['to_download'])
    n_delete = len(comparison['to_delete'])
    total = n_download + n_delete

    print(f"[updater] К скачиванию: {n_download}, к удалению: {n_delete}")

    # 3. Если нечего делать — запускаем сразу
    if total == 0:
        print("[updater] Сборка актуальна, обновление не нужно")
        return True

    # 4. Если auto_update — обновляем без вопроса
    if cfg.get('auto_update', False):
        worker = UpdateWorker(
            server_url, remote_manifest,
            comparison['to_download'], comparison['to_delete'], cfg
        )
        dlg = ProgressDialog(parent, worker)
        worker.start()
        dlg.exec()
        worker.wait()
        return True

    # 5. Иначе — показываем окошко с вопросом
    dlg = UpdateDialog(parent, remote_manifest, comparison)
    if dlg.exec() != QDialog.Accepted:
        print("[updater] Игрок отказался от обновления")
        return True  # играть со старой сборкой

    # 6. Игрок согласился — показываем прогресс
    worker = UpdateWorker(
        server_url, remote_manifest,
        comparison['to_download'], comparison['to_delete'], cfg
    )
    progress_dlg = ProgressDialog(parent, worker)
    worker.start()
    progress_dlg.exec()
    worker.wait()

    return True