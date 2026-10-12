"""Home: who plays, Play, live server status, memory, news (only when the accounts service provides it)."""
from __future__ import annotations

import time

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtWidgets import (QHBoxLayout, QLabel, QLineEdit, QProgressBar, QSizePolicy, QStackedWidget,
                               QVBoxLayout, QWidget)

from ... import settings as settings_mod, system
from ...api import ApiError
from .. import icons, theme
from ..skin_view import head_pixmap, load_skin_bytes, placeholder_skin
from ..widgets import Backdrop, Card, ScaledImage, StatusDot, button, label, set_invalid


class PlayerCard(Card):
    """Identity for this launch: Luminary account, or nickname (offline mode) when accounts allow it."""

    go_profile = Signal(str)  # "login" | "register" | "profile"

    def __init__(self, state):
        super().__init__("Игрок")
        self.state = state
        self.stack = QStackedWidget()
        self.body.addWidget(self.stack)

        # page 0: nickname (offline mode, as in 1.x)
        nick_page = QWidget()
        nl = QVBoxLayout(nick_page)
        nl.setContentsMargins(0, 0, 0, 0)
        nl.setSpacing(6)
        self.nick_intro = label("", "muted", wrap=True)
        nl.addWidget(self.nick_intro)
        self.nick = QLineEdit()
        self.nick.setPlaceholderText("Ник: латиница, цифры и _ (3-16)")
        self.nick.setAccessibleName("Никнейм")
        self.nick.setMaxLength(16)
        self.nick.setText(state.settings.nickname)
        self.nick.textChanged.connect(self._nick_changed)
        nl.addWidget(self.nick)
        self.nick_hint = label("", "error")
        self.nick_hint.hide()
        nl.addWidget(self.nick_hint)
        self.account_links = QHBoxLayout()
        self.login_link = button("Войти в аккаунт", "link")
        self.login_link.clicked.connect(lambda: self.go_profile.emit("login"))
        self.register_link = button("Создать аккаунт", "link")
        self.register_link.clicked.connect(lambda: self.go_profile.emit("register"))
        self.account_links.addWidget(self.login_link)
        self.links_sep = label("·", "muted")
        self.account_links.addWidget(self.links_sep)
        self.account_links.addWidget(self.register_link)
        self.account_links.addStretch()
        nl.addLayout(self.account_links)
        self.stack.addWidget(nick_page)

        # page 1: account required, signed out
        req_page = QWidget()
        rl = QVBoxLayout(req_page)
        rl.setContentsMargins(0, 0, 0, 0)
        rl.addWidget(label("Для игры на сервере нужен аккаунт Luminary.", wrap=True))
        rr = QHBoxLayout()
        b1 = button("Войти", "primary", "profile")
        b1.clicked.connect(lambda: self.go_profile.emit("login"))
        b2 = button("Создать аккаунт")
        b2.clicked.connect(lambda: self.go_profile.emit("register"))
        rr.addWidget(b1)
        rr.addWidget(b2)
        rr.addStretch()
        rl.addLayout(rr)
        self.stack.addWidget(req_page)

        # page 2: signed in
        acc_page = QWidget()
        al = QHBoxLayout(acc_page)
        al.setContentsMargins(0, 0, 0, 0)
        al.setSpacing(12)
        self.avatar = QLabel()
        self.avatar.setFixedSize(48, 48)
        al.addWidget(self.avatar)
        col = QVBoxLayout()
        col.setSpacing(2)
        self.acc_name = label("", "title")
        self.acc_sub = label("", "muted")
        col.addWidget(self.acc_name)
        col.addWidget(self.acc_sub)
        al.addLayout(col, 1)
        prof = button("Профиль и скин", None, "skin")
        prof.clicked.connect(lambda: self.go_profile.emit("profile"))
        al.addWidget(prof)
        self.stack.addWidget(acc_page)

        # page 3: checking / unreachable
        chk = QWidget()
        cl = QVBoxLayout(chk)
        cl.setContentsMargins(0, 0, 0, 0)
        self.check_text = label("Проверка аккаунта…", "muted", wrap=True)
        cl.addWidget(self.check_text)
        self.retry = button("Повторить", None, "refresh")
        self.retry.clicked.connect(state.restore_session)
        cl.addWidget(self.retry, 0, Qt.AlignLeft)
        self.stack.addWidget(chk)

        self._avatar_hash = None
        state.account_changed.connect(self.refresh)
        state.account_status.connect(lambda _s: self.refresh())
        self.refresh()

    def _nick_changed(self, text: str) -> None:
        err = settings_mod.validate_nickname(text) if text else None
        self._hint(err)

    def nickname(self) -> str | None:
        """Valid nickname for an offline launch, or None (and an inline error)."""
        text = self.nick.text().strip()
        err = settings_mod.validate_nickname(text)
        if err:
            self._hint(err)
            self.nick.setFocus()
            return None
        if text != self.state.settings.nickname:
            self.state.update("nickname", text)
            self.state.sounds.play("saved")
        return text

    def _hint(self, err: str | None) -> None:
        self.nick_hint.setText(err or "")
        self.nick_hint.setVisible(bool(err))
        set_invalid(self.nick, bool(err))

    def uses_nickname(self) -> bool:
        return self.stack.currentIndex() == 0

    def refresh(self) -> None:
        st = self.state
        accounts = st.accounts_enabled
        for w in (self.login_link, self.register_link, self.links_sep):
            w.setVisible(accounts)
        if st.signed_in:
            me = st.me or {}
            self.acc_name.setText(me.get("profile", {}).get("name") or me.get("username", ""))
            self.acc_sub.setText("Аккаунт Luminary" + ("" if st.status == "ok" else " · нет связи с сервером"))
            self._update_avatar()
            self.stack.setCurrentIndex(2)
        elif st.status == "checking":
            self.check_text.setText("Проверка аккаунта…")
            self.retry.hide()
            self.stack.setCurrentIndex(3)
        elif st.status == "offline" and st.session:
            if st.catalog.accounts_required:
                self.check_text.setText("Сервер аккаунтов недоступен. Без него войти в игру нельзя.")
                self.retry.show()
                self.stack.setCurrentIndex(3)
            else:
                self.nick_intro.setText("Сервер аккаунтов недоступен — можно играть по нику.")
                self.stack.setCurrentIndex(0)
        elif accounts and st.catalog.accounts_required:
            self.stack.setCurrentIndex(1)
        else:
            self.nick_intro.setText("Войди в аккаунт, чтобы играть со своим скином, или введи ник."
                                    if accounts else "Введи ник, под которым будешь играть.")
            self.stack.setCurrentIndex(0)

    def _update_avatar(self) -> None:
        skin = ((self.state.me or {}).get("profile") or {}).get("skin") or {}
        url, h = skin.get("url"), skin.get("hash")
        if not url:
            self.avatar.setPixmap(head_pixmap(placeholder_skin(), 48))
            self._avatar_hash = None
            return
        if h == self._avatar_hash:
            return
        self._avatar_hash = h
        api = self.state.api()

        def done(png):
            try:
                img, _ = load_skin_bytes(png)
                self.avatar.setPixmap(head_pixmap(img, 48))
            except ValueError:
                pass
        self.avatar.setPixmap(head_pixmap(placeholder_skin(), 48))
        self.state.runner.run(lambda ctx: api.fetch_texture(url), done, lambda e: None, owner=self, name="avatar")


class ServerCard(Card):
    def __init__(self, state, service):
        super().__init__(f"Сервер {state.profile.server.name if state.profile.server else ''}".strip())
        self.state = state
        self.service = service
        self.refresh_btn = button("", "ghost", "refresh", "Обновить статус")
        self.refresh_btn.setFixedSize(30, 30)
        self.refresh_btn.clicked.connect(service.refresh)
        self.add_header_widget(self.refresh_btn)
        row = QHBoxLayout()
        row.setSpacing(8)
        self.dot = StatusDot("pending", 12)
        self.headline = label("Проверка…", "title")
        row.addWidget(self.dot)
        row.addWidget(self.headline)
        row.addStretch()
        self.body.addLayout(row)
        self.details = label("", "muted", wrap=True)
        self.body.addWidget(self.details)
        self.sample = label("", "caption", wrap=True)
        self.body.addWidget(self.sample)
        self.updated = label("", "caption")
        self.body.addWidget(self.updated)
        service.updated.connect(self.show_status)
        service.checking.connect(lambda: self.refresh_btn.setEnabled(False))
        self.tick = QTimer(self)
        self.tick.setInterval(15_000)
        self.tick.timeout.connect(self._update_age)
        self.tick.start()
        if state.profile.server is None:
            self.headline.setText("Сервер не задан в профиле")
            self.dot.set_state("offline")

    def show_status(self, st) -> None:
        self.refresh_btn.setEnabled(True)
        if st.online:
            self.dot.set_state("online")
            self.headline.setText("Онлайн")
            ping = f" · пинг {st.latency_ms:.0f} мс" if st.latency_ms is not None else ""
            self.details.setText(f"Игроков: {st.players} из {st.max_players}{ping}" +
                                 (f"\nВерсия: {st.version}" if st.version else ""))
            self.sample.setText(("Сейчас играют: " + ", ".join(st.sample)) if st.sample else "")
        else:
            self.dot.set_state("error")
            self.headline.setText("Недоступен")
            self.details.setText("Сервер не ответил. Он может быть выключен или на обслуживании.")
            self.sample.setText("")
        self._update_age()

    def _update_age(self) -> None:
        st = self.service.last
        if st is None:
            return
        age = int(time.time() - st.checked_at)
        self.updated.setText("Обновлено только что" if age < 20 else f"Обновлено {age // 60 or 1} мин назад"
                             if age >= 60 else f"Обновлено {age} с назад")


class RamCard(Card):
    """Memory for Minecraft (-Xmx), limited to what this PC can spare."""

    def __init__(self, state):
        super().__init__("Оперативная память")
        self.state = state
        from PySide6.QtWidgets import QButtonGroup, QGridLayout, QPushButton
        choices = system.ram_choices(state.total_ram)
        grid = QGridLayout()
        grid.setSpacing(6)
        self.group = QButtonGroup(self)
        self.group.setExclusive(True)
        for i, mb in enumerate(choices):
            b = QPushButton(system.format_mb(mb))
            b.setProperty("variant", "segment")
            b.setProperty("ram", mb)
            b.setCheckable(True)
            b.setChecked(mb == state.settings.ram_mb)
            b.setCursor(Qt.PointingHandCursor)
            b.setAccessibleName(f"Выделить {system.format_mb(mb)}")
            self.group.addButton(b)
            grid.addWidget(b, i // 4, i % 4)
        self.group.buttonClicked.connect(self._picked)
        self.body.addLayout(grid)
        total = state.total_ram
        self.caption = label(f"В системе {system.format_mb(total)}. Больше выбрать нельзя, чтобы системе "
                             f"хватило памяти." if total else "Не удалось определить объём памяти системы.",
                             "caption", wrap=True)
        self.body.addWidget(self.caption)
        state.settings_changed.connect(self._sync)

    def _picked(self, b) -> None:
        v = int(b.property("ram"))
        if v != self.state.settings.ram_mb:
            self.state.update("ram_mb", v)
            self.state.sounds.play("ram")

    def _sync(self, key: str) -> None:
        if key == "ram_mb":
            for b in self.group.buttons():
                b.setChecked(int(b.property("ram")) == self.state.settings.ram_mb)


class NewsCard(Card):
    def __init__(self, state):
        super().__init__("Новости")
        self.state = state
        self.items = QVBoxLayout()
        self.items.setSpacing(10)
        self.body.addLayout(self.items)
        self.msg = label("Загрузка…", "muted", wrap=True)
        self.body.addWidget(self.msg)
        self.retry = button("Повторить", None, "refresh")
        self.retry.clicked.connect(self.load)
        self.retry.hide()
        self.body.addWidget(self.retry, 0, Qt.AlignLeft)
        self.timer = QTimer(self)
        self.timer.setInterval(10 * 60_000)
        self.timer.timeout.connect(self.load)
        self.timer.start()
        self.load()

    def load(self) -> None:
        api = self.state.api()
        self.retry.hide()

        def done(body):
            from ..widgets import clear_layout
            clear_layout(self.items)
            news = (body or {}).get("news", [])
            self.msg.setVisible(not news)
            self.msg.setText("Новостей пока нет.")
            for n in news[:3]:
                box = QVBoxLayout()
                box.setSpacing(2)
                head = label(n.get("title", ""), wrap=True)
                head.setStyleSheet("font-weight: 600;")
                date = time.strftime("%d.%m.%Y", time.localtime(n.get("published_at", 0)))
                box.addWidget(head)
                box.addWidget(label(date, "caption"))
                body_lbl = label(n.get("body", ""), "muted", wrap=True)
                body_lbl.setTextFormat(Qt.PlainText)
                box.addWidget(body_lbl)
                self.items.addLayout(box)

        def failed(exc):
            self.msg.setVisible(True)
            self.msg.setText("Новости недоступны: " + (exc.message if isinstance(exc, ApiError) else "ошибка"))
            self.retry.show()

        self.state.runner.run(lambda ctx: api.news(3), done, failed, owner=self, name="news")


class HomeScreen(QWidget):
    go_profile = Signal(str)

    def __init__(self, state, controller, service):
        super().__init__()
        self.state = state
        self.controller = controller
        root = QHBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        center = Backdrop(state.paths.assets_dir / "hero_bg.png", 40, 215)
        cl = QVBoxLayout(center)
        cl.setContentsMargins(28, 24, 28, 24)
        cl.setSpacing(16)
        banner = ScaledImage(state.paths.assets_dir / "hero_banner.png", max_height=140)
        banner.setAccessibleName("Welcome to Luminary")
        cl.addWidget(banner)
        cl.addStretch(1)
        self.player = PlayerCard(state)
        self.player.go_profile.connect(self.go_profile)
        self.player.setMaximumWidth(720)
        cl.addWidget(self.player)

        play_box = QVBoxLayout()
        play_box.setSpacing(8)
        self.play = button("ИГРАТЬ", "play", "play")
        self.play.setMinimumHeight(60)
        self.play.setIconSize(self.play.iconSize() * 1.4)
        self.play.setAccessibleName("Играть")
        self.play.setToolTip("Запустить Minecraft и подключиться к серверу (Ctrl+Enter)")
        self.play.clicked.connect(self.on_play)
        play_box.addWidget(self.play)
        prow = QHBoxLayout()
        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.progress.hide()
        prow.addWidget(self.progress, 1)
        self.cancel = button("Отмена", "ghost")
        self.cancel.clicked.connect(controller.cancel)
        self.cancel.hide()
        prow.addWidget(self.cancel)
        play_box.addLayout(prow)
        self.status = label("", "muted")
        self.status.setWordWrap(True)
        play_box.addWidget(self.status)
        wrap = QWidget()
        wrap.setLayout(play_box)
        wrap.setMaximumWidth(720)
        cl.addWidget(wrap)
        root.addWidget(center, 1)

        right = Backdrop(state.paths.assets_dir / "right_bg.png", 120, 150, anchor="bottom")
        right.setFixedWidth(340)
        rl = QVBoxLayout(right)
        rl.setContentsMargins(16, 16, 16, 16)
        rl.setSpacing(12)
        rl.addWidget(ServerCard(state, service))
        rl.addWidget(RamCard(state))
        if state.accounts_enabled:
            rl.addWidget(NewsCard(state))
        rl.addStretch()
        root.addWidget(right)

        controller.phase.connect(self.on_phase)
        controller.progress.connect(self.on_progress)
        controller.notice.connect(lambda m: self.status.setText(m))
        state.account_changed.connect(self._update_play_enabled)
        state.account_status.connect(lambda _s: self._update_play_enabled())
        self._update_play_enabled()

    def on_play(self) -> None:
        if not self.play.isEnabled():
            return
        nick = None
        if self.player.uses_nickname():
            nick = self.player.nickname()
            if nick is None:
                return
        self.controller.start(nick)

    def _update_play_enabled(self) -> None:
        if self.controller.busy or self.state.game_state == "running":
            return
        st = self.state
        blocked = st.catalog.accounts_required and not st.signed_in
        self.play.setEnabled(not blocked and st.status != "checking")

    def on_phase(self, phase: str) -> None:
        busy = phase in ("preparing", "syncing", "installing", "starting")
        texts = {"idle": "ИГРАТЬ", "preparing": "ПОДГОТОВКА…", "syncing": "ОБНОВЛЕНИЕ СБОРКИ…",
                 "installing": "УСТАНОВКА…", "starting": "ЗАПУСК…", "running": "ИГРА ЗАПУЩЕНА"}
        self.play.setText(texts.get(phase, "ИГРАТЬ"))
        self.play.setEnabled(phase == "idle")
        self.progress.setVisible(busy)
        self.cancel.setVisible(phase in ("preparing", "syncing", "installing"))
        if phase == "idle":
            self.progress.setValue(0)
            self._update_play_enabled()
        if phase == "running":
            self.status.setText("Minecraft запущен. Лаунчер можно свернуть.")

    def on_progress(self, text: str, fraction) -> None:
        self.status.setText(text)
        if fraction is None:
            self.progress.setRange(0, 0)  # indeterminate
        else:
            self.progress.setRange(0, 1000)
            self.progress.setValue(int(max(0.0, min(1.0, fraction)) * 1000))
