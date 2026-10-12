"""Settings: account, game (memory, Java, JVM args, after-launch, modpack updates), interface, diagnostics."""
from __future__ import annotations

import logging

from PySide6.QtCore import Qt, QTimer
from PySide6.QtWidgets import (QButtonGroup, QCheckBox, QComboBox, QFileDialog, QFormLayout, QHBoxLayout, QLineEdit,
                               QRadioButton, QScrollArea, QVBoxLayout, QWidget)

from ... import __version__, settings as settings_mod, system
from ..dialogs import ChangePasswordDialog, copy_text, open_path
from ..widgets import Card, button, label, set_invalid


def _row_label(text: str):
    lb = label(text)
    lb.setMinimumHeight(38)
    lb.setAlignment(Qt.AlignLeft | Qt.AlignVCenter)
    return lb


def _caption(form, widget) -> None:
    """A hint under a field that takes no space while empty."""
    form.addRow("", widget)
    widget.setVisible(bool(widget.text()))


class SettingsScreen(QWidget):
    def __init__(self, state, controller, go_profile, updates=None):
        super().__init__()
        self.updates = updates
        self.state = state
        self.controller = controller
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        outer.addWidget(scroll)
        page = QWidget()
        scroll.setWidget(page)
        lay = QVBoxLayout(page)
        lay.setContentsMargins(32, 28, 32, 32)
        lay.setSpacing(18)
        lay.addWidget(label("Настройки", "display"))
        lay.addWidget(label("Изменения сохраняются сразу.", "muted"))
        s = state.settings

        # ------------------------------------------------------------ account
        acc = Card("Аккаунт")
        self.acc_text = label("", wrap=True)
        acc.body.addWidget(self.acc_text)
        arow = QHBoxLayout()
        self.btn_profile = button("Профиль и скин", None, "skin")
        self.btn_profile.clicked.connect(lambda: go_profile("profile"))
        self.btn_login = button("Войти", "primary", "profile")
        self.btn_login.clicked.connect(lambda: go_profile("login"))
        self.btn_pw = button("Сменить пароль")
        self.btn_pw.clicked.connect(lambda: ChangePasswordDialog(self, state).exec())
        self.btn_logout = button("Выйти", "ghost", "exit")
        self.btn_logout.clicked.connect(lambda: state.logout(False))
        self.btn_logout_all = button("Выйти на всех устройствах", "danger")
        self.btn_logout_all.clicked.connect(lambda: state.logout(True))
        for b in (self.btn_login, self.btn_profile, self.btn_pw, self.btn_logout, self.btn_logout_all):
            arow.addWidget(b)
        arow.addStretch()
        acc.body.addLayout(arow)
        lay.addWidget(acc)
        state.account_changed.connect(self._update_account)
        state.account_status.connect(lambda _s: self._update_account())

        # ------------------------------------------------------------ game
        game = Card("Игра")
        form = QFormLayout()
        form.setHorizontalSpacing(18)
        form.setVerticalSpacing(12)
        form.setLabelAlignment(Qt.AlignLeft | Qt.AlignVCenter)

        self.profile_box = QComboBox()
        for p in state.catalog.profiles:
            self.profile_box.addItem(f"{p.name} — Minecraft {p.minecraft}, {p.loader_label()}", p.id)
        self.profile_box.setCurrentIndex(max(0, self.profile_box.findData(state.profile.id)))
        self.profile_box.setEnabled(len(state.catalog.profiles) > 1)
        self.profile_box.currentIndexChanged.connect(
            lambda i: state.update("profile_id", self.profile_box.itemData(i)))
        self.profile_box.setAccessibleName("Профиль сборки")
        form.addRow(_row_label("Сборка"), self.profile_box)

        self.ram = QComboBox()
        for mb in system.ram_choices(state.total_ram):
            self.ram.addItem(system.format_mb(mb), mb)
        self.ram.setCurrentIndex(max(0, self.ram.findData(s.ram_mb)))
        self.ram.currentIndexChanged.connect(lambda i: state.update("ram_mb", int(self.ram.itemData(i))))
        self.ram.setAccessibleName("Оперативная память")
        state.settings_changed.connect(self._sync_ram)
        form.addRow(_row_label("Память"), self.ram)

        java_row = QHBoxLayout()
        self.java = QLineEdit(s.java_path)
        self.java.setPlaceholderText(f"Встроенная: {state.profile.java_relpath()}")
        self.java.setAccessibleName("Путь к Java")
        self.java.editingFinished.connect(self._save_java)
        browse = button("Обзор…")
        browse.clicked.connect(self._browse_java)
        self.java_check = button("Проверить")
        self.java_check.clicked.connect(self._check_java)
        java_row.addWidget(self.java, 1)
        java_row.addWidget(browse)
        java_row.addWidget(self.java_check)
        jw = QWidget()
        jw.setLayout(java_row)
        java_row.setContentsMargins(0, 0, 0, 0)
        form.addRow(_row_label("Java"), jw)
        self.java_msg = label("", "caption", wrap=True)
        _caption(form, self.java_msg)

        self.jvm = QLineEdit(s.jvm_args)
        self.jvm.setPlaceholderText("Например: -XX:+UseG1GC")
        self.jvm.setAccessibleName("Дополнительные аргументы JVM")
        self.jvm.textChanged.connect(self._jvm_changed)
        self.jvm.editingFinished.connect(self._save_jvm)
        form.addRow(_row_label("Аргументы JVM"), self.jvm)
        self.jvm_msg = label("Память и аккаунт лаунчер задаёт сам.", "caption", wrap=True)
        _caption(form, self.jvm_msg)

        after = QHBoxLayout()
        self.after_group = QButtonGroup(self)
        for value, text in (("minimize", "Свернуть лаунчер"), ("keep", "Оставить открытым"),
                            ("close", "Закрыть лаунчер")):
            rb = QRadioButton(text)
            rb.setProperty("value", value)
            rb.setChecked(s.after_launch == value)
            self.after_group.addButton(rb)
            after.addWidget(rb)
        after.addStretch()
        self.after_group.buttonClicked.connect(lambda b: state.update("after_launch", b.property("value")))
        aw = QWidget()
        aw.setLayout(after)
        after.setContentsMargins(0, 0, 0, 0)
        form.addRow(_row_label("После запуска игры"), aw)
        form.addRow("", label("«Закрыть» — лаунчер не сообщит о вылете игры и не покажет друзьям, что ты играешь.",
                              "caption", wrap=True))

        self.pack = QComboBox()
        self.pack.addItem("Спрашивать перед обновлением", "ask")
        self.pack.addItem("Обновлять автоматически", "auto")
        self.pack.setCurrentIndex(max(0, self.pack.findData(s.pack_updates)))
        self.pack.currentIndexChanged.connect(lambda i: state.update("pack_updates", self.pack.itemData(i)))
        form.addRow(_row_label("Обновления сборки"), self.pack)
        game.body.addLayout(form)
        lay.addWidget(game)

        # ------------------------------------------------------------ interface
        ui = Card("Интерфейс")
        self.sounds = QCheckBox("Звуки интерфейса")
        self.sounds.setChecked(s.sounds)
        self.sounds.toggled.connect(self._toggle_sounds)
        self.motion = QCheckBox("Уменьшить анимацию (без вращения скина и плавных эффектов)")
        self.motion.setChecked(s.reduce_motion)
        self.motion.toggled.connect(lambda v: state.update("reduce_motion", v))
        ui.body.addWidget(self.sounds)
        ui.body.addWidget(self.motion)
        lay.addWidget(ui)

        # ------------------------------------------------------------ diagnostics
        diag = Card("Диагностика")
        self.debug = QCheckBox("Подробный журнал (для поиска проблем)")
        self.debug.setChecked(s.debug_logging)
        self.debug.toggled.connect(self._toggle_debug)
        diag.body.addWidget(self.debug)
        drow = QHBoxLayout()
        b_logs = button("Папка журналов", None, "folder")
        b_logs.clicked.connect(lambda: open_path(state.paths.log_dir))
        b_game = button("Папка игры", None, "folder")
        b_game.clicked.connect(lambda: open_path(state.paths.mc_dir))
        b_copy = button("Скопировать сведения", None, "copy",
                        "Версии, ОС и пути — без паролей и токенов, для обращения к администрации")
        b_copy.clicked.connect(lambda: (copy_text(state.diagnostics()), state.toast.emit("Сведения скопированы", "ok")))
        for b in (b_logs, b_game, b_copy):
            drow.addWidget(b)
        drow.addStretch()
        diag.body.addLayout(drow)
        api = state.catalog.api_url or "не настроен (profiles.json → accounts.api_url)"
        diag.body.addWidget(label(f"Сервер аккаунтов: {api}", "caption", wrap=True, selectable=True))
        diag.body.addWidget(label(f"Luminary Launcher {__version__} · папка {state.paths.base}", "caption",
                                  wrap=True, selectable=True))
        urow = QHBoxLayout()
        self.update_btn = button("Проверить обновления", None, "refresh")
        self.update_status = label("", "caption", wrap=True)
        urow.addWidget(self.update_btn)
        urow.addWidget(self.update_status, 1)
        diag.body.addLayout(urow)
        if updates is not None and updates.enabled:
            self.update_btn.clicked.connect(lambda: updates.check(manual=True))
            updates.status.connect(self.update_status.setText)
            self.update_status.setText(updates.last_status)
        else:
            self.update_btn.setEnabled(False)
            self.update_status.setText("Автообновление не настроено (profiles.json → launcher_update)")
        lay.addWidget(diag)
        lay.addStretch()
        self._update_account()

    # ---------------------------------------------------------------- handlers
    def _update_account(self) -> None:
        st = self.state
        signed = st.signed_in
        if not st.accounts_enabled:
            self.acc_text.setText("Аккаунты Luminary пока не подключены к этому лаунчеру — игра по нику.")
        elif signed:
            name = (st.me or {}).get("username", "")
            self.acc_text.setText(f"Вы вошли как <b>{name}</b>." +
                                  ("" if st.status == "ok" else " Сервер аккаунтов сейчас недоступен."))
        elif st.status == "checking":
            self.acc_text.setText("Проверка аккаунта…")
        else:
            self.acc_text.setText("Вы не вошли в аккаунт.")
        self.btn_login.setVisible(st.accounts_enabled and not signed)
        for b in (self.btn_profile, self.btn_pw, self.btn_logout, self.btn_logout_all):
            b.setVisible(signed)
        self.btn_pw.setEnabled(st.status == "ok")
        self.btn_logout_all.setEnabled(st.status == "ok")

    def _sync_ram(self, key: str) -> None:
        if key == "ram_mb":
            self.ram.blockSignals(True)
            self.ram.setCurrentIndex(max(0, self.ram.findData(self.state.settings.ram_mb)))
            self.ram.blockSignals(False)

    def _browse_java(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Выбери java.exe или javaw.exe", "",
                                              "Java (java.exe javaw.exe java)")
        if path:
            self.java.setText(path)
            self._save_java()

    def _java_message(self, text: str, error: bool = False) -> None:
        self.java_msg.setProperty("role", "error" if error else "caption")
        self.java_msg.style().polish(self.java_msg)
        self.java_msg.setText(text)
        self.java_msg.setVisible(bool(text))

    def _save_java(self) -> None:
        text = self.java.text().strip()
        err = settings_mod.validate_java_path(text)
        set_invalid(self.java, bool(err))
        if err:
            self._java_message(err + ". Сохранён прежний путь.", True)
        else:
            self._java_message("Используется встроенная Java." if not text else "Сохранено.")
            self.state.update("java_path", text)

    def _check_java(self) -> None:
        path = str(self.controller.java_path())
        need = self.state.profile.java_min_version
        self.java_check.setEnabled(False)
        self._java_message("Проверка…")

        def done(major):
            self.java_check.setEnabled(True)
            if major is None:
                self._java_message(f"Не удалось запустить Java: {path}", True)
            elif major < need:
                self._java_message(f"Java {major} — для Minecraft {self.state.profile.minecraft} нужна {need}+", True)
            else:
                self._java_message(f"Java {major} — подходит")

        self.state.runner.run(lambda ctx: system.java_major_version(path), done,
                              lambda e: done(None), owner=self, name="java-check")

    def _jvm_changed(self, text: str) -> None:
        err = settings_mod.validate_jvm_args(text)
        set_invalid(self.jvm, bool(err))
        self.jvm_msg.setText(err or "Память и аккаунт лаунчер задаёт сам.")

    def _save_jvm(self) -> None:
        text = self.jvm.text().strip()
        if settings_mod.validate_jvm_args(text) is None:
            self.state.update("jvm_args", text)

    def _toggle_sounds(self, on: bool) -> None:
        self.state.sounds.enabled = on
        self.state.update("sounds", on)

    def _toggle_debug(self, on: bool) -> None:
        logging.getLogger().setLevel(logging.DEBUG if on else logging.INFO)
        self.state.update("debug_logging", on)
