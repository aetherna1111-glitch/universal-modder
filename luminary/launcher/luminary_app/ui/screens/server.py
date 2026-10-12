"""Server: description, modes, how to start, rules (owner's text), address and live status."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QGridLayout, QHBoxLayout, QLabel, QScrollArea, QVBoxLayout, QWidget

from ... import content
from .. import icons, theme
from ..dialogs import copy_text
from ..widgets import Card, ScaledImage, StatusDot, button, label


class ServerScreen(QWidget):
    def __init__(self, state, service):
        super().__init__()
        self.state = state
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

        server = state.profile.server
        # ---- header: kelp artwork + name, address, status
        head = QHBoxLayout()
        head.setSpacing(24)
        art = ScaledImage(state.paths.assets_dir / "server_image.png")
        art.setFixedWidth(320)
        art.setAccessibleName("Ламинария")
        art.setStyleSheet(f"border-radius: 12px; border: 1px solid {theme.BORDER};")
        head.addWidget(art, 0, Qt.AlignTop)
        info = QVBoxLayout()
        info.setSpacing(10)
        info.addWidget(label(server.name if server else "Luminary", "display"))
        intro = label(content.INTRO, "body", wrap=True)
        info.addWidget(intro)
        if server:
            addr_row = QHBoxLayout()
            addr = f"{server.host}:{server.port}"
            addr_lbl = label(addr, selectable=True)
            addr_lbl.setStyleSheet(f"font-family: Consolas, 'DejaVu Sans Mono', monospace; color: {theme.ACCENT};")
            addr_row.addWidget(label("Адрес:", "muted"))
            addr_row.addWidget(addr_lbl)
            copy = button("Копировать", "ghost", "copy", "Скопировать адрес сервера")
            copy.clicked.connect(lambda: (copy_text(addr), state.toast.emit("Адрес скопирован", "ok")))
            addr_row.addWidget(copy)
            addr_row.addStretch()
            info.addLayout(addr_row)
            st_row = QHBoxLayout()
            self.dot = StatusDot("pending", 12)
            self.status = label("Проверка статуса…", "muted")
            st_row.addWidget(self.dot)
            st_row.addWidget(self.status)
            st_row.addStretch()
            info.addLayout(st_row)
            service.updated.connect(self.show_status)
        info.addStretch()
        head.addLayout(info, 1)
        lay.addLayout(head)

        # ---- modes
        modes = Card("Режимы и активности")
        modes.body.addWidget(label(content.MODES_TITLE, "muted", wrap=True))
        grid = QGridLayout()
        grid.setHorizontalSpacing(16)
        grid.setVerticalSpacing(12)
        for i, (title, text) in enumerate(content.MODES):
            cell = QVBoxLayout()
            cell.setSpacing(2)
            t = label(title)
            t.setStyleSheet(f"font-weight: 700; color: {theme.TEXT};")
            cell.addWidget(t)
            cell.addWidget(label(text[:1].upper() + text[1:], "muted", wrap=True))
            grid.addLayout(cell, i // 2, i % 2, Qt.AlignTop)
        grid.setColumnStretch(0, 1)
        grid.setColumnStretch(1, 1)
        modes.body.addLayout(grid)
        lay.addWidget(modes)

        row = QHBoxLayout()
        row.setSpacing(18)
        start = Card("Как начать")
        self.start_text = label("", "body", wrap=True)
        start.body.addWidget(self.start_text)
        start.body.addStretch()
        row.addWidget(start, 1)
        rules = Card("Правила")
        rules.body.addWidget(label(content.RULES_TITLE, "muted", wrap=True))
        for r in content.RULES:
            line = QHBoxLayout()
            line.setSpacing(8)
            ic = QLabel()
            ic.setPixmap(icons.pixmap("check", 16, theme.ACCENT))
            line.addWidget(ic, 0, Qt.AlignTop)
            line.addWidget(label(r[:1].upper() + r[1:], wrap=True), 1)
            rules.body.addLayout(line)
        rules.body.addStretch()
        row.addWidget(rules, 1)
        lay.addLayout(row)

        help_card = Card()
        help_card.body.addWidget(label(content.HELP, "body", wrap=True))
        lay.addWidget(help_card)
        lay.addStretch()

        state.account_changed.connect(self._update_start)
        self._update_start()
        if service.last is not None and server:
            self.show_status(service.last)

    def _update_start(self) -> None:
        use_account = self.state.accounts_enabled and (self.state.catalog.accounts_required or self.state.signed_in)
        self.start_text.setText(content.HOW_TO_START_ACCOUNT if use_account else content.HOW_TO_START_NICK)

    def show_status(self, st) -> None:
        if st.online:
            self.dot.set_state("online")
            ping = f", пинг {st.latency_ms:.0f} мс" if st.latency_ms is not None else ""
            self.status.setText(f"Онлайн: {st.players} из {st.max_players} игроков{ping}")
        else:
            self.dot.set_state("error")
            self.status.setText("Сервер сейчас недоступен")
