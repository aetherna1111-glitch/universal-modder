"""Friends (Luminary accounts): search, requests, list with presence, player profiles."""
from __future__ import annotations

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtWidgets import QHBoxLayout, QLineEdit, QScrollArea, QStackedWidget, QVBoxLayout, QWidget

from ...api import ApiError
from .. import icons, theme
from ..dialogs import PlayerDialog
from ..widgets import Card, StateView, StatusDot, button, clear_layout, label

PRESENCE_TEXT = {"playing": "Играет на сервере", "online": "В лаунчере", "offline": "Не в сети"}


class FriendsScreen(QWidget):
    go_profile = Signal(str)

    def __init__(self, state):
        super().__init__()
        self.state = state
        lay = QVBoxLayout(self)
        lay.setContentsMargins(32, 28, 32, 24)
        lay.setSpacing(14)
        top = QHBoxLayout()
        top.addWidget(label("Друзья", "display"))
        top.addStretch()
        self.refresh_btn = button("Обновить", None, "refresh")
        self.refresh_btn.clicked.connect(self.load)
        top.addWidget(self.refresh_btn)
        lay.addLayout(top)

        self.stack = QStackedWidget()
        self.state_view = StateView()
        self.state_view.action.connect(self._state_action)
        self.stack.addWidget(self.state_view)

        content = QWidget()
        cl = QHBoxLayout(content)
        cl.setContentsMargins(0, 0, 0, 0)
        cl.setSpacing(18)
        left = QVBoxLayout()
        left.setSpacing(14)
        find = Card("Найти игрока")
        srow = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText("Ник (от 2 символов)")
        self.search.setMaxLength(16)
        self.search.setAccessibleName("Поиск игроков")
        self.search.returnPressed.connect(self.do_search)
        self.search_btn = button("", None, "search", "Искать")
        self.search_btn.clicked.connect(self.do_search)
        srow.addWidget(self.search, 1)
        srow.addWidget(self.search_btn)
        find.body.addLayout(srow)
        self.search_msg = label("", "caption", wrap=True)
        find.body.addWidget(self.search_msg)
        self.results = QVBoxLayout()
        self.results.setSpacing(6)
        find.body.addLayout(self.results)
        left.addWidget(find)
        self.req_card = Card("Заявки")
        self.requests = QVBoxLayout()
        self.requests.setSpacing(6)
        self.req_card.body.addLayout(self.requests)
        left.addWidget(self.req_card)
        left.addStretch()
        lw = QWidget()
        lw.setLayout(left)
        lw.setFixedWidth(360)
        cl.addWidget(lw)

        self.list_card = Card("Список друзей")
        self.friends_box = QVBoxLayout()
        self.friends_box.setSpacing(4)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        inner = QWidget()
        inner.setLayout(self.friends_box)
        scroll.setWidget(inner)
        self.list_card.body.addWidget(scroll, 1)
        cl.addWidget(self.list_card, 1)
        self.stack.addWidget(content)
        lay.addWidget(self.stack, 1)

        self.timer = QTimer(self)
        self.timer.setInterval(60_000)
        self.timer.timeout.connect(self.load)
        state.account_changed.connect(self._account_changed)
        state.account_status.connect(lambda _s: self._account_changed())
        self._account_changed()

    # ------------------------------------------------------------------ states
    def _account_changed(self) -> None:
        st = self.state
        if not st.accounts_enabled:
            self._mode = "none"
            self.state_view.empty("Друзья появятся с аккаунтами Luminary",
                                  "Этому лаунчеру пока не подключён сервер аккаунтов. После подключения здесь можно "
                                  "будет находить игроков, добавлять друзей и видеть, кто сейчас играет.",
                                  None, "friends")
            self.stack.setCurrentIndex(0)
            self.refresh_btn.hide()
        elif not st.signed_in:
            self._mode = "login"
            self.state_view.empty("Войди, чтобы видеть друзей", "Друзья привязаны к аккаунту Luminary.",
                                  "Войти", "friends")
            self.stack.setCurrentIndex(0)
            self.refresh_btn.hide()
        else:
            self._mode = "ok"
            self.refresh_btn.show()
            if self.isVisible():
                self.load()

    def _state_action(self) -> None:
        if self._mode == "login":
            self.go_profile.emit("login")
        else:
            self.load()

    def showEvent(self, e) -> None:
        super().showEvent(e)
        if self._mode == "ok":
            self.load()
            self.timer.start()

    def hideEvent(self, e) -> None:
        super().hideEvent(e)
        self.timer.stop()

    # ------------------------------------------------------------------ data
    def load(self) -> None:
        if self._mode != "ok":
            return
        if self.stack.currentIndex() == 0:
            self.state_view.loading("Загрузка друзей…")
        api = self.state.api()

        def failed(exc):
            msg = self.state.handle_api_error(exc)
            if self._mode == "ok":
                self.state_view.error("Не удалось загрузить друзей", msg)
                self.stack.setCurrentIndex(0)

        self.state.runner.run(lambda ctx: api.friends(), self.show_data, failed, owner=self, name="friends")

    def show_data(self, data: dict) -> None:
        self.stack.setCurrentIndex(1)
        clear_layout(self.friends_box)
        friends = data.get("friends", [])
        if not friends:
            self.friends_box.addWidget(label("Пока никого. Найди игрока слева и отправь заявку.", "muted", wrap=True))
        for f in friends:
            self.friends_box.addWidget(self._friend_row(f))
        self.friends_box.addStretch()
        playing = sum(1 for f in friends if f.get("presence") == "playing")
        self.list_card.title_label.setText(f"СПИСОК ДРУЗЕЙ · {len(friends)}" + (f" · ИГРАЮТ: {playing}" if playing else ""))

        clear_layout(self.requests)
        incoming, outgoing = data.get("incoming", []), data.get("outgoing", [])
        if not incoming and not outgoing:
            self.requests.addWidget(label("Нет заявок.", "muted"))
        for r in incoming:
            row = QHBoxLayout()
            row.addWidget(label(r["name"]), 1)
            acc = button("Принять", "primary", "check")
            acc.clicked.connect(lambda _=False, rid=r["id"]: self._act(lambda a: a.accept_request(rid), "Заявка принята"))
            dec = button("Отклонить", "ghost")
            dec.clicked.connect(lambda _=False, rid=r["id"]: self._act(lambda a: a.decline_request(rid), "Заявка отклонена"))
            row.addWidget(acc)
            row.addWidget(dec)
            self.requests.addLayout(row)
        for r in outgoing:
            row = QHBoxLayout()
            row.addWidget(label(f"{r['name']} — ждём ответа", "muted"), 1)
            c = button("Отменить", "ghost")
            c.clicked.connect(lambda _=False, rid=r["id"]: self._act(lambda a: a.cancel_request(rid), "Заявка отменена"))
            row.addWidget(c)
            self.requests.addLayout(row)

    def _friend_row(self, f: dict) -> QWidget:
        w = QWidget()
        row = QHBoxLayout(w)
        row.setContentsMargins(6, 6, 6, 6)
        row.setSpacing(10)
        presence = f.get("presence", "offline")
        row.addWidget(StatusDot(presence, 10))
        col = QVBoxLayout()
        col.setSpacing(0)
        col.addWidget(label(f["name"]))
        col.addWidget(label(PRESENCE_TEXT.get(presence, ""), "caption"))
        row.addLayout(col, 1)
        prof = button("Профиль", "ghost", "profile")
        prof.clicked.connect(lambda _=False, d=f: PlayerDialog(self, self.state, d).exec())
        rem = button("", "ghost", "trash", f"Удалить {f['name']} из друзей")
        rem.clicked.connect(lambda _=False, n=f["name"]: self._act(lambda a: a.remove_friend(n), f"{n} удалён из друзей"))
        row.addWidget(prof)
        row.addWidget(rem)
        return w

    def _act(self, fn, ok_text: str) -> None:
        api = self.state.api()

        def done(_):
            self.state.toast.emit(ok_text, "ok")
            self.load()

        def failed(exc):
            self.state.toast.emit(self.state.handle_api_error(exc), "error")

        self.state.runner.run(lambda ctx: fn(api), done, failed, owner=self, name="friend-action")

    def do_search(self) -> None:
        q = self.search.text().strip()
        if len(q) < 2:
            self.search_msg.setText("Введи хотя бы 2 символа ника")
            return
        self.search_msg.setText("Поиск…")
        api = self.state.api()

        def done(body):
            clear_layout(self.results)
            users = body.get("users", [])
            self.search_msg.setText("" if users else "Никого не нашли")
            for u in users[:8]:
                row = QHBoxLayout()
                row.addWidget(label(u["name"]), 1)
                add = button("Добавить", None, "user_plus")
                add.clicked.connect(lambda _=False, n=u["name"]: self._act(lambda a: a.send_friend_request(n),
                                                                           f"Заявка отправлена: {n}"))
                row.addWidget(add)
                self.results.addLayout(row)

        def failed(exc):
            self.search_msg.setText(self.state.handle_api_error(exc))

        self.state.runner.run(lambda ctx: api.search_users(q), done, failed, owner=self, name="search")
