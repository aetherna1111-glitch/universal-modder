"""Profile: sign in / register, and the skin manager with the interactive 3D preview."""
from __future__ import annotations

import time
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QButtonGroup, QFileDialog, QHBoxLayout, QLineEdit, QPushButton, QStackedWidget,
                               QTabWidget, QVBoxLayout, QWidget)

from ... import settings as settings_mod
from ...api import ApiError
from .. import theme
from ..skin_view import SkinFileError, SkinView, load_skin_bytes, placeholder_skin
from ..widgets import Card, PasswordEdit, StateView, button, label, set_invalid


class AuthForms(QWidget):
    def __init__(self, state):
        super().__init__()
        self.state = state
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        card = Card(padding=24)
        card.setMaximumWidth(460)
        card.body.addWidget(label("Аккаунт Luminary", "display"))
        card.body.addWidget(label("Один аккаунт для лаунчера, скина и друзей. Ник аккаунта — твой ник в игре.",
                                  "muted", wrap=True))
        self.tabs = QTabWidget()
        self.tabs.addTab(self._login_tab(), "Вход")
        self.tabs.addTab(self._register_tab(), "Регистрация")
        card.body.addWidget(self.tabs)
        lay.addWidget(card, 0, Qt.AlignHCenter | Qt.AlignTop)
        lay.addStretch()

    def _field(self, placeholder: str) -> QLineEdit:
        e = QLineEdit()
        e.setPlaceholderText(placeholder)
        e.setAccessibleName(placeholder)
        e.setMaxLength(16)
        return e

    def _login_tab(self) -> QWidget:
        w = QWidget()
        l = QVBoxLayout(w)
        l.setContentsMargins(0, 12, 0, 0)
        l.setSpacing(10)
        self.l_name = self._field("Ник")
        self.l_pass = PasswordEdit("Пароль")
        self.l_err = label("", "error", wrap=True)
        self.l_btn = button("Войти", "primary", "profile")
        self.l_btn.clicked.connect(self.login)
        self.l_pass.returnPressed.connect(self.login)
        self.l_name.returnPressed.connect(self.l_pass.setFocus)
        for x in (self.l_name, self.l_pass, self.l_err, self.l_btn):
            l.addWidget(x)
        l.addWidget(label("Забыли пароль? Обратись к администрации сервера — восстановление по почте не "
                          "подключено.", "caption", wrap=True))
        return w

    def _register_tab(self) -> QWidget:
        w = QWidget()
        l = QVBoxLayout(w)
        l.setContentsMargins(0, 12, 0, 0)
        l.setSpacing(10)
        self.r_name = self._field("Ник в игре (3-16: латиница, цифры, _)")
        self.r_name.textChanged.connect(self._name_check)
        self.r_pass = PasswordEdit("Пароль (от 8 символов)")
        self.r_rep = PasswordEdit("Повтори пароль")
        self.r_err = label("", "error", wrap=True)
        self.r_btn = button("Создать аккаунт", "primary", "user_plus")
        self.r_btn.clicked.connect(self.register)
        self.r_rep.returnPressed.connect(self.register)
        for x in (self.r_name, self.r_pass, self.r_rep, self.r_err, self.r_btn):
            l.addWidget(x)
        l.addWidget(label("Если ты уже играл на сервере, укажи тот же ник с тем же регистром букв — "
                          "так сохранится твой инвентарь и прогресс.", "caption", wrap=True))
        if not self.state.accounts_enabled:
            self.r_btn.setEnabled(False)
        return w

    def _name_check(self, text: str) -> None:
        err = settings_mod.validate_nickname(text) if text else None
        set_invalid(self.r_name, bool(err))
        self.r_err.setText(err or "")

    def show_tab(self, which: str) -> None:
        self.tabs.setCurrentIndex(1 if which == "register" else 0)
        (self.r_name if which == "register" else self.l_name).setFocus()

    def _busy(self, btn: QPushButton, busy: bool, text: str) -> None:
        btn.setEnabled(not busy)
        btn.setText("Подождите…" if busy else text)

    def _api_failed(self, err_label, btn, text):
        def failed(exc):
            self._busy(btn, False, text)
            if isinstance(exc, ApiError):
                msg = exc.message
                if exc.status == 429 and exc.retry_after:
                    msg = f"Слишком много попыток. Повтори через {exc.retry_after} с."
            else:
                msg = "Неожиданная ошибка, подробности в журнале"
            err_label.setText(msg)
            self.state.sounds.play("error")
        return failed

    def login(self) -> None:
        name, pw = self.l_name.text().strip(), self.l_pass.text()
        if not name or not pw:
            self.l_err.setText("Введи ник и пароль")
            return
        self.l_err.setText("")
        self._busy(self.l_btn, True, "Войти")
        api = self.state.api()

        def done(body):
            self._busy(self.l_btn, False, "Войти")
            self.l_pass.clear()
            self.state.apply_login(body)
            self.state.sounds.play("saved")
            self.state.toast.emit(f"Добро пожаловать, {body.get('username')}!", "ok")

        self.state.runner.run(lambda ctx: api.login(name, pw), done,
                              self._api_failed(self.l_err, self.l_btn, "Войти"), owner=self, name="login")

    def register(self) -> None:
        name, pw, rep = self.r_name.text().strip(), self.r_pass.text(), self.r_rep.text()
        err = settings_mod.validate_nickname(name)
        if err:
            self.r_err.setText(err)
            return
        if len(pw) < 8:
            self.r_err.setText("Пароль должен быть не короче 8 символов")
            return
        if pw != rep:
            self.r_err.setText("Пароли не совпадают")
            return
        self.r_err.setText("")
        self._busy(self.r_btn, True, "Создать аккаунт")
        api = self.state.api()

        def done(body):
            self._busy(self.r_btn, False, "Создать аккаунт")
            self.r_pass.clear()
            self.r_rep.clear()
            self.state.apply_login(body)
            self.state.sounds.play("saved")
            self.state.toast.emit("Аккаунт создан", "ok")

        self.state.runner.run(lambda ctx: api.register(name, pw), done,
                              self._api_failed(self.r_err, self.r_btn, "Создать аккаунт"), owner=self, name="register")


class SkinManager(QWidget):
    """Current skin, a pending (not yet saved) skin, model switch, save/delete."""

    def __init__(self, state, local_only: bool = False):
        super().__init__()
        self.state = state
        self.local_only = local_only
        self.pending: bytes | None = None
        self.current_png: bytes | None = None
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(18)

        view_card = Card("Скин в 3D")
        self.view = SkinView()
        self.view.reduce_motion = state.settings.reduce_motion
        view_card.body.addWidget(self.view, 1)
        model_row = QHBoxLayout()
        model_row.addWidget(label("Модель:", "muted"))
        self.model_group = QButtonGroup(self)
        self.model_btns = {}
        for key, text in (("classic", "Классическая (Steve)"), ("slim", "Тонкая (Alex)")):
            b = QPushButton(text)
            b.setProperty("variant", "segment")
            b.setCheckable(True)
            b.setCursor(Qt.PointingHandCursor)
            self.model_group.addButton(b)
            self.model_btns[key] = b
            model_row.addWidget(b)
        self.model_btns["classic"].setChecked(True)
        self.model_group.buttonClicked.connect(self._model_clicked)
        model_row.addStretch()
        reset = button("", "ghost", "refresh", "Сбросить вид (R)")
        reset.clicked.connect(self.view.reset_view)
        model_row.addWidget(reset)
        view_card.body.addLayout(model_row)
        view_card.body.addWidget(label("Перетащи модель мышью или стрелками, колесо — масштаб.", "caption"))
        lay.addWidget(view_card, 3)

        side = QVBoxLayout()
        side.setSpacing(14)
        self.acc_card = Card("Профиль")
        self.name = label("", "title")
        self.meta = label("", "caption", wrap=True, selectable=True)
        self.acc_card.body.addWidget(self.name)
        self.acc_card.body.addWidget(self.meta)
        side.addWidget(self.acc_card)

        sk = Card("Скин")
        self.skin_state = label("", wrap=True)
        sk.body.addWidget(self.skin_state)
        self.choose = button("Выбрать PNG…", None, "upload")
        self.choose.clicked.connect(self.pick_file)
        self.save = button("Сохранить скин", "primary", "check")
        self.save.clicked.connect(self.upload)
        self.discard = button("Отменить", "ghost")
        self.discard.clicked.connect(self.discard_pending)
        self.delete = button("Удалить скин", "danger", "trash")
        self.delete.clicked.connect(self.remove)
        for b in (self.choose, self.save, self.discard, self.delete):
            sk.body.addWidget(b)
        self.msg = label("", "caption", wrap=True)
        sk.body.addWidget(self.msg)
        sk.body.addWidget(label("Формат: PNG 64×64 или 64×32, до 128 КБ. Другие игроки на сервере видят скин, "
                                "когда сервер работает с авторизацией Luminary (см. документацию).",
                                "caption", wrap=True))
        side.addWidget(sk)
        side.addStretch()
        sw = QWidget()
        sw.setLayout(side)
        sw.setMinimumWidth(300)
        sw.setMaximumWidth(380)
        lay.addWidget(sw, 2)
        state.account_changed.connect(self.refresh)
        state.settings_changed.connect(lambda k: k == "reduce_motion" and self._motion())
        self.refresh()

    def _motion(self) -> None:
        self.view.reduce_motion = self.state.settings.reduce_motion
        self.view.set_auto_rotate(not self.view.reduce_motion)

    # ------------------------------------------------------------------ state
    def _skin(self) -> dict:
        return ((self.state.me or {}).get("profile") or {}).get("skin") or {}

    def _model(self) -> str:
        return "slim" if self.model_btns["slim"].isChecked() else "classic"

    def _set_model(self, model: str) -> None:
        self.model_btns[model].setChecked(True)
        self.view.set_slim(model == "slim")

    def refresh(self) -> None:
        me = self.state.me or {}
        prof = me.get("profile") or {}
        if self.local_only:
            self.name.setText("Без аккаунта")
            self.meta.setText("Предпросмотр работает без входа. Сохранить скин можно после входа в аккаунт.")
        else:
            self.name.setText(prof.get("name", ""))
            created = me.get("created_at")
            exp = me.get("session_expires_at")
            lines = [f"UUID: {prof.get('uuid', '')}"]
            if created:
                lines.append("Аккаунт создан: " + time.strftime("%d.%m.%Y", time.localtime(created)))
            if exp:
                lines.append("Сессия до: " + time.strftime("%d.%m.%Y", time.localtime(exp)))
            self.meta.setText("\n".join(lines))
        if self.pending is None:
            self._load_current()
        self._update_buttons()

    def _load_current(self) -> None:
        skin = self._skin()
        url = skin.get("url")
        if self.local_only or not url:
            self.current_png = None
            self.view.set_skin(placeholder_skin(), skin.get("model") == "slim")
            self._set_model(skin.get("model", "classic"))
            self.skin_state.setText("Скин не установлен — в игре будет стандартный." if not self.local_only
                                    else "Выбери PNG, чтобы посмотреть, как он выглядит.")
            return
        self.skin_state.setText("Загрузка текущего скина…")
        api = self.state.api()

        def done(png):
            try:
                img, _ = load_skin_bytes(png)
            except SkinFileError as exc:
                self.skin_state.setText(str(exc))
                return
            if self.pending is not None:
                return
            self.current_png = png
            self.view.set_skin(img, skin.get("model") == "slim")
            self._set_model(skin.get("model", "classic"))
            updated = skin.get("updated_at")
            self.skin_state.setText("Текущий скин" + (time.strftime(" · обновлён %d.%m.%Y", time.localtime(updated))
                                                      if updated else ""))

        def failed(exc):
            self.skin_state.setText("Не удалось загрузить скин: " + str(exc))

        self.state.runner.run(lambda ctx: api.fetch_texture(url), done, failed, owner=self, name="my-skin")

    def _update_buttons(self) -> None:
        online = not self.local_only and self.state.status == "ok"
        has_pending = self.pending is not None
        self.save.setVisible(has_pending)
        self.save.setEnabled(online)
        self.discard.setVisible(has_pending)
        self.delete.setVisible(not has_pending and not self.local_only and bool(self._skin().get("url")))
        self.delete.setEnabled(online)
        if has_pending and self.local_only:
            self.msg.setText("Войди в аккаунт, чтобы сохранить этот скин.")

    # ------------------------------------------------------------------ actions
    def pick_file(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Выбери скин", str(Path.home()), "PNG (*.png)")
        if path:
            self.load_file(Path(path))

    def load_file(self, path: Path) -> None:
        try:
            if path.stat().st_size > 1024 * 1024:
                raise SkinFileError("Файл слишком большой")
            data = path.read_bytes()
            img, model = load_skin_bytes(data)
        except (OSError, SkinFileError) as exc:
            self.msg.setProperty("role", "error")
            self.msg.setText(str(exc))
            self.msg.style().polish(self.msg)
            self.state.sounds.play("error")
            return
        self.pending = data
        self.view.set_skin(img, model == "slim")
        self._set_model(model)
        self.msg.setProperty("role", "caption")
        self.msg.style().polish(self.msg)
        self.msg.setText(f"Модель определена автоматически: {'тонкая' if model == 'slim' else 'классическая'}. "
                         "Можно переключить.")
        self.skin_state.setText(f"Не сохранён: {path.name}")
        self._update_buttons()

    def discard_pending(self) -> None:
        self.pending = None
        self.msg.setText("")
        self._load_current()
        self._update_buttons()

    def _model_clicked(self, _b) -> None:
        model = self._model()
        self.view.set_slim(model == "slim")
        if self.pending is None and not self.local_only and self._skin().get("url") and model != self._skin().get("model"):
            api = self.state.api()

            def done(me):
                self.state.set_me(me)
                self.state.toast.emit("Модель скина изменена", "ok")

            def failed(exc):
                self.msg.setText(self.state.handle_api_error(exc))
                self._set_model(self._skin().get("model", "classic"))

            self.state.runner.run(lambda ctx: api.set_skin_model(model), done, failed, owner=self, name="skin-model")

    def upload(self) -> None:
        if self.pending is None:
            return
        data, model = self.pending, self._model()
        self.save.setEnabled(False)
        self.msg.setText("Загрузка…")
        api = self.state.api()

        def done(me):
            self.pending = None
            self.msg.setText("")
            self.state.set_me(me)
            self.state.sounds.play("saved")
            self.state.toast.emit("Скин сохранён", "ok")

        def failed(exc):
            self.save.setEnabled(True)
            self.msg.setProperty("role", "error")
            self.msg.setText(self.state.handle_api_error(exc))
            self.msg.style().polish(self.msg)

        self.state.runner.run(lambda ctx: api.upload_skin(data, model), done, failed, owner=self, name="upload-skin")

    def remove(self) -> None:
        api = self.state.api()
        self.delete.setEnabled(False)

        def done(me):
            self.state.set_me(me)
            self.state.toast.emit("Скин удалён", "ok")

        def failed(exc):
            self.delete.setEnabled(True)
            self.msg.setText(self.state.handle_api_error(exc))

        self.state.runner.run(lambda ctx: api.delete_skin(), done, failed, owner=self, name="delete-skin")


class ProfileScreen(QWidget):
    def __init__(self, state):
        super().__init__()
        self.state = state
        lay = QVBoxLayout(self)
        lay.setContentsMargins(32, 28, 32, 24)
        lay.setSpacing(14)
        self.title = label("Профиль", "display")
        lay.addWidget(self.title)
        self.stack = QStackedWidget()
        self.info = StateView()
        self.info.action.connect(state.restore_session)
        self.stack.addWidget(self.info)                    # 0
        self.auth = AuthForms(state)
        self.stack.addWidget(self.auth)                    # 1
        self.manager = SkinManager(state)
        self.stack.addWidget(self.manager)                 # 2
        self.local = SkinManager(state, local_only=True)
        self.stack.addWidget(self.local)                   # 3
        lay.addWidget(self.stack, 1)
        state.account_changed.connect(self.refresh)
        state.account_status.connect(lambda _s: self.refresh())
        self.refresh()

    def open(self, which: str) -> None:
        self.refresh()
        if self.stack.currentIndex() == 1:
            self.auth.show_tab(which)

    def refresh(self) -> None:
        st = self.state
        if not st.accounts_enabled:
            self.title.setText("Скин")
            self.stack.setCurrentIndex(3)
        elif st.signed_in:
            self.title.setText("Профиль")
            self.stack.setCurrentIndex(2)
        elif st.status == "checking":
            self.info.loading("Проверка аккаунта…")
            self.stack.setCurrentIndex(0)
        elif st.status == "offline":
            self.info.error("Сервер аккаунтов недоступен", "Проверь подключение к интернету.")
            self.stack.setCurrentIndex(0)
        else:
            self.title.setText("Профиль")
            self.stack.setCurrentIndex(1)
