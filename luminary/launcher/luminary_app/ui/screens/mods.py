"""Mods: what is installed, whether it matches the server's pack, and compatibility problems.

The Luminary pack is server-authoritative: the launcher syncs .minecraft/<sync folders> with the server
manifest and moves unknown files to .backup. So this screen inspects and repairs; it does not offer a
catalog or per-mod install, which the sync would undo.
"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (QAbstractItemView, QComboBox, QHBoxLayout, QHeaderView, QLineEdit, QSplitter,
                               QStackedWidget, QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget)

from ... import modpack, mods
from .. import icons, theme
from ..dialogs import ErrorDialog, UpdateDialog, open_path
from ..widgets import Card, StateView, button, label

SYNC_TEXT = {"in_pack": ("В сборке", theme.GREEN), "modified": ("Изменён", theme.YELLOW),
             "extra": ("Не из сборки", theme.YELLOW), "unknown": ("—", theme.MUTED)}


class ModsScreen(QWidget):
    def __init__(self, state, controller):
        super().__init__()
        self.state = state
        self.controller = controller
        self.mods: list[mods.ModInfo] = []
        self.pack_cfg = None
        lay = QVBoxLayout(self)
        lay.setContentsMargins(32, 28, 32, 24)
        lay.setSpacing(14)
        top = QHBoxLayout()
        top.addWidget(label("Моды", "display"))
        top.addStretch()
        self.summary = label("", "muted")
        top.addWidget(self.summary)
        lay.addLayout(top)

        info = Card(flat=True, padding=12)
        info.setObjectName("Banner")
        info.body.addWidget(label(
            "Сборку определяет сервер: при запуске лаунчер сверяет моды и настройки с сервером, докачивает "
            "недостающее и убирает лишнее (копии — в .minecraft/.backup). Поэтому свои моды здесь не "
            "устанавливаются: администрация добавляет их в сборку на сервере.", "muted", wrap=True))
        lay.addWidget(info)

        bar = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText("Поиск по названию, id или файлу")
        self.search.setClearButtonEnabled(True)
        self.search.addAction(icons.icon("search", theme.MUTED), QLineEdit.LeadingPosition)
        self.search.setAccessibleName("Поиск модов")
        self.search.setMinimumWidth(240)
        self.search.textChanged.connect(self.apply_filter)
        bar.addWidget(self.search, 1)
        self.filter = QComboBox()
        for text, key in (("Все моды", "all"), ("С проблемами", "issues"), ("Не из сборки", "extra"),
                          ("Изменённые", "modified")):
            self.filter.addItem(text, key)
        self.filter.currentIndexChanged.connect(self.apply_filter)
        self.filter.setAccessibleName("Фильтр")
        bar.addWidget(self.filter)
        self.btn_reload = button("Обновить", None, "refresh")
        self.btn_reload.clicked.connect(self.reload)
        self.btn_verify = button("Проверить сборку", "primary", "check",
                                 "Сверить файлы с сервером и восстановить сборку")
        self.btn_verify.clicked.connect(self.verify_pack)
        self.btn_folder = button("Папка модов", "ghost", "folder")
        self.btn_folder.clicked.connect(lambda: open_path(state.paths.mc_dir / "mods"))
        for b in (self.btn_reload, self.btn_verify, self.btn_folder):
            bar.addWidget(b)
        lay.addLayout(bar)

        self.stack = QStackedWidget()
        self.state_view = StateView()
        self.state_view.action.connect(self.reload)
        self.stack.addWidget(self.state_view)
        split = QSplitter(Qt.Horizontal)
        self.table = QTableWidget(0, 4)
        self.table.setHorizontalHeaderLabels(["Мод", "Версия", "Сборка", "Совместимость"])
        self.table.verticalHeader().hide()
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setShowGrid(False)
        self.table.setAlternatingRowColors(False)
        self.table.setWordWrap(False)
        h = self.table.horizontalHeader()
        h.setSectionResizeMode(0, QHeaderView.Stretch)
        for c in (1, 2, 3):
            h.setSectionResizeMode(c, QHeaderView.ResizeToContents)
        self.table.itemSelectionChanged.connect(self.show_details)
        split.addWidget(self.table)
        self.details = Card(padding=14)
        self.details.setMinimumWidth(260)
        self.d_title = label("Выбери мод", "title", wrap=True)
        self.d_meta = label("", "caption", wrap=True, selectable=True)
        self.d_desc = label("", "muted", wrap=True)
        self.d_issues = label("", "error", wrap=True)
        self.d_deps = label("", "caption", wrap=True)
        for w in (self.d_title, self.d_meta, self.d_desc, self.d_issues, self.d_deps):
            self.details.body.addWidget(w)
        self.details.body.addStretch()
        split.addWidget(self.details)
        split.setStretchFactor(0, 3)
        split.setStretchFactor(1, 1)
        self.stack.addWidget(split)
        lay.addWidget(self.stack, 1)
        self._loaded = False
        controller.phase.connect(lambda ph: self.btn_verify.setEnabled(ph == "idle"))
        controller.game_finished.connect(lambda _r: self._loaded and self.reload())

    def showEvent(self, e) -> None:
        super().showEvent(e)
        if not self._loaded:
            self.reload()

    # ------------------------------------------------------------------ data
    def reload(self) -> None:
        self._loaded = True
        self.state_view.loading("Чтение модов…")
        self.stack.setCurrentIndex(0)
        self.btn_reload.setEnabled(False)
        st = self.state
        profile = st.profile
        mc = st.paths.mc_dir

        def work(ctx):
            cfg = None
            if profile.modpack_config:
                try:
                    cfg = modpack.load_config(st.paths.base / profile.modpack_config)
                except (modpack.PackError, ValueError, OSError):
                    cfg = None
            hashes = None
            if cfg is not None:
                man = modpack.local_manifest(mc, cfg.folders)
                if man is not None:
                    hashes = {f.path: f.sha256 for f in man.files}
            cache = modpack.HashCache(mc / modpack.HASH_CACHE)
            result = mods.scan(mc / "mods", profile.minecraft, hashes,
                               lambda rel, p: cache.get(rel, p, ctx.cancel))
            cache.save()
            return cfg, hashes is not None, result

        def done(res):
            self.btn_reload.setEnabled(True)
            self.pack_cfg, have_manifest, self.mods = res
            if not self.mods:
                self.state_view.empty("Модов пока нет",
                                      "Они появятся после синхронизации сборки при первом запуске игры."
                                      if self.pack_cfg else "Папка .minecraft/mods пуста.",
                                      "Обновить", "mods")
                self.summary.setText("")
                return
            issues = sum(1 for m in self.mods if m.issues or m.error)
            self.summary.setText(f"{len(self.mods)} модов" + (f" · проблем: {issues}" if issues else "") +
                                 ("" if have_manifest else " · сборка ещё не синхронизирована"))
            self.stack.setCurrentIndex(1)
            self.apply_filter()

        def failed(exc):
            self.btn_reload.setEnabled(True)
            self.state_view.error("Не удалось прочитать моды", str(exc))

        st.runner.run(work, done, failed, owner=self, name="scan-mods")

    def apply_filter(self) -> None:
        q = self.search.text().strip().lower()
        key = self.filter.currentData()
        rows = []
        for m in self.mods:
            hay = " ".join(filter(None, [m.name, m.id, m.file])).lower()
            if q and q not in hay:
                continue
            if key == "issues" and not (m.issues or m.error):
                continue
            if key in ("extra", "modified") and m.sync_state != key:
                continue
            rows.append(m)
        self.table.setRowCount(len(rows))
        for r, m in enumerate(rows):
            name = QTableWidgetItem(m.title)
            name.setData(Qt.UserRole, m)
            name.setToolTip(m.file)
            ver = QTableWidgetItem(m.version or "—")
            text, color = SYNC_TEXT.get(m.sync_state, SYNC_TEXT["unknown"])
            sync = QTableWidgetItem(text)
            sync.setForeground(QColor(color))
            if m.error:
                comp = QTableWidgetItem("Ошибка")
                comp.setForeground(QColor(theme.RED))
            elif m.issues:
                comp = QTableWidgetItem(f"Проблем: {len(m.issues)}")
                comp.setForeground(QColor(theme.YELLOW))
            else:
                comp = QTableWidgetItem("OK")
                comp.setForeground(QColor(theme.GREEN))
            for c, it in enumerate((name, ver, sync, comp)):
                self.table.setItem(r, c, it)
        if not rows and self.mods:
            self.d_title.setText("Ничего не найдено")

    def show_details(self) -> None:
        items = self.table.selectedItems()
        if not items:
            return
        m: mods.ModInfo = self.table.item(items[0].row(), 0).data(Qt.UserRole)
        self.d_title.setText(m.title)
        size = f"{m.size / 1024 / 1024:.1f} МБ" if m.size > 1024 * 1024 else f"{m.size // 1024} КБ"
        meta = [f"Файл: {m.file} ({size})"]
        if m.id:
            meta.append(f"id: {m.id}")
        if m.version:
            meta.append(f"Версия: {m.version}")
        if m.authors:
            meta.append("Авторы: " + ", ".join(m.authors[:5]))
        if m.environment and m.environment != "*":
            meta.append("Сторона: " + {"client": "только клиент", "server": "только сервер"}.get(m.environment, m.environment))
        self.d_meta.setText("\n".join(meta))
        self.d_desc.setText(m.description[:600])
        self.d_issues.setText("\n".join([m.error] if m.error else m.issues))
        deps = [f"{k} {v if isinstance(v, str) else ' | '.join(map(str, v))}" for k, v in m.depends.items()]
        self.d_deps.setText(("Зависимости: " + ", ".join(deps)) if deps else "")

    # ------------------------------------------------------------------ verify / repair
    def verify_pack(self) -> None:
        if self.controller.busy or self.state.game_state == "running":
            self.state.toast.emit("Подожди, пока закончится запуск или игра", "info")
            return
        st = self.state
        profile = st.profile
        if not profile.modpack_config:
            st.toast.emit("У этой сборки нет сервера обновлений", "info")
            return
        self.btn_verify.setEnabled(False)
        self.summary.setText("Сверка с сервером…")
        mc = st.paths.mc_dir

        def work(ctx):
            cfg = modpack.load_config(st.paths.base / profile.modpack_config)
            if cfg is None:
                raise modpack.PackError("нет config_updater.json")
            manifest = modpack.fetch_manifest(cfg)
            cache = modpack.HashCache(mc / modpack.HASH_CACHE)
            mc.mkdir(parents=True, exist_ok=True)
            plan = modpack.make_plan(manifest, mc, cfg.folders, cache, cancel=ctx.cancel)
            cache.save()
            return cfg, cache, plan

        def done(res):
            cfg, cache, plan = res
            self.btn_verify.setEnabled(True)
            if plan.empty:
                self.summary.setText(f"Сборка {plan.manifest.version} в порядке")
                st.toast.emit("Сборка совпадает с сервером", "ok")
                return
            if UpdateDialog(self, plan).exec():
                self._apply(cfg, cache, plan)
            else:
                self.summary.setText("Сборка отличается от серверной")

        def failed(exc):
            self.btn_verify.setEnabled(True)
            self.summary.setText("")
            ErrorDialog(self, "Проверка сборки не удалась", str(exc), logs_dir=st.paths.log_dir).exec()

        st.runner.run(work, done, failed, owner=self, name="verify-pack")

    def _apply(self, cfg, cache, plan) -> None:
        st = self.state
        self.btn_verify.setEnabled(False)

        def work(ctx):
            return modpack.apply_plan(plan, cfg, st.paths.mc_dir, cache,
                                      lambda d, t, msg: ctx.progress(f"{int(d * 100 / t) if t else 100}% · {msg}"),
                                      ctx.cancel)

        def done(res):
            self.btn_verify.setEnabled(True)
            down, deleted = res
            st.sounds.play("loaded")
            st.toast.emit(f"Сборка восстановлена: скачано {down}, убрано {deleted}", "ok")
            self.reload()

        def failed(exc):
            self.btn_verify.setEnabled(True)
            ErrorDialog(self, "Обновление сборки не удалось", f"{exc}\n\nФайлы сборки не изменены.",
                        logs_dir=st.paths.log_dir).exec()

        st.runner.run(work, done, failed, lambda m: self.summary.setText(m), owner=self, name="apply-pack")
