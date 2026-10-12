# launcher.py — Luminary GUI (с автообновлением)
import os
import sys
import uuid
import re
import threading
import subprocess

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtGui import QIcon, QPixmap
from PySide6.QtWidgets import (
    QApplication, QWidget, QLabel, QLineEdit, QPushButton, QVBoxLayout,
    QHBoxLayout, QFrame, QRadioButton, QButtonGroup, QMessageBox,
    QGridLayout, QScrollArea, QStackedWidget
)

from mcstatus import JavaServer

# модуль автообновления (лежит рядом)
from updater import check_and_update

# =========================================================
#  ПУТИ И НАСТРОЙКИ
# =========================================================
BASE_DIR     = os.path.dirname(os.path.abspath(__file__))
NICK_FILE    = os.path.join(BASE_DIR, 'nickname.txt')
RAM_FILE     = os.path.join(BASE_DIR, 'ram.txt')
MC_DIR       = os.path.join(BASE_DIR, '.minecraft')
LAUNCHER_EXE = os.path.join(BASE_DIR, 'LuminaryLauncher.exe')
JAVA_PATH    = os.path.join(BASE_DIR, 'jre', 'bin', 'javaw.exe')
ICON_PATH    = os.path.join(BASE_DIR, 'luminary.ico')
ASSETS_DIR   = os.path.join(BASE_DIR, 'assets')

HERO_BG      = os.path.join(ASSETS_DIR, 'hero_bg.png')
HERO_BANNER  = os.path.join(ASSETS_DIR, 'hero_banner.png')
LEFT_BG      = os.path.join(ASSETS_DIR, 'left_bg.png')
RIGHT_BG     = os.path.join(ASSETS_DIR, 'right_bg.png')
SERVER_IMAGE = os.path.join(ASSETS_DIR, 'server_image.png')

SERVER_HOST = "135.106.221.145"
SERVER_PORT = "27195"
SERVER_NAME = "Luminary"

NICK_PATTERN = re.compile(r'^[A-Za-z0-9]{3,16}$')

# =========================================================
#  ТЕКСТ ДЛЯ ВКЛАДКИ "СЕРВЕР" — 2 части
# =========================================================
SERVER_TEXT_PART1 = """Добро пожаловать на сервер Luminary! Luminary — это уютный сервер для игры с друзьями. Здесь ты можешь строить, выживать, исследовать мир и участвовать в ивентах, а мы стараемся поддерживать дружелюбную атмосферу и интересный геймплей. У нас есть несколько основных режимов и активностей. Выживание с друзьями — классический режим, где можно строить базы, фермы и целые города. ПвП-ивенты — регулярные турниры и сражения между игроками. Своя экономика — торгуй с другими игроками, открывай магазины и зарабатывай. Дружное сообщество — у нас нет токсичности, все помогают друг другу."""

SERVER_TEXT_PART2 = """Чтобы начать играть, введи свой ник в лаунчере на главной вкладке, выбери количество оперативной памяти и нажми кнопку «Играть». После запуска Minecraft подключишься к серверу автоматически.

На сервере действуют простые правила: уважай других игроков, не используй читы и сторонние программы, не гриферь и не воруй, соблюдай правила, установленные администрацией. Если у тебя возникли вопросы — обратись к администрации в Discord или в чате сервера. Приятной игры!"""

# =========================================================
#  ЦВЕТА — подводная тема
# =========================================================
BG           = "#0a1418"
PANEL        = "#0f1e26"
CARD         = "#142a33"
BORDER       = "#1e3d47"
ACCENT       = "#3dd6c4"
ACCENT_DARK  = "#0d3b33"
ACCENT_HOVER = "#2bb5a4"
TEXT         = "#d4eae6"
MUTED        = "#5f8a8b"
GREEN        = "#4ade80"
RED          = "#ff6b6b"

OVERLAY_CENTER = "rgba(10, 20, 24, 70)"
OVERLAY_LEFT   = "rgba(10, 20, 24, 90)"
OVERLAY_RIGHT  = "rgba(10, 20, 24, 90)"
CARD_OVERLAY   = "rgba(20, 42, 51, 200)"


# =========================================================
#  ПОТОК: ПРОВЕРКА СЕРВЕРА
# =========================================================
class ServerCheckThread(QThread):
    result = Signal(str, str)

    def run(self):
        try:
            server = JavaServer.lookup(f"{SERVER_HOST}:{SERVER_PORT}")
            status = server.status()
            text = (f"● Онлайн\n"
                    f"Игроков: {status.players.online}/{status.players.max}\n"
                    f"Пинг: {round(status.latency, 2)} ms")
            self.result.emit(text, GREEN)
        except Exception:
            self.result.emit("● Оффлайн\nСервер недоступен", RED)


# =========================================================
#  ПОТОК: ЗАПУСК MINECRAFT
# =========================================================
class LaunchThread(QThread):
    error = Signal(str)

    def __init__(self, nick, ram):
        super().__init__()
        self.nick = nick
        self.ram = ram

    def run(self):
        player_uuid = str(uuid.uuid3(uuid.NAMESPACE_DNS,
                                     'OfflinePlayer:' + self.nick))
        env = os.environ.copy()
        env['JAVA_TOOL_OPTIONS'] = f'-Xmx{self.ram}'

        cmd = [
            LAUNCHER_EXE,
            '--main-dir', MC_DIR,
            'start',
            '--username', self.nick,
            '--uuid', player_uuid,
            '--join-server', SERVER_HOST,
            '--join-server-port', SERVER_PORT,
            'fabric:1.20.1',
            '--jvm', JAVA_PATH
        ]

        try:
            subprocess.run(cmd, env=env, cwd=BASE_DIR)
        except Exception as e:
            self.error.emit(str(e))


# =========================================================
#  ГЛАВНОЕ ОКНО
# =========================================================
class LuminaryLauncher(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Luminary Launcher")
        self.setFixedSize(1300, 700)

        self.setWindowFlags(Qt.FramelessWindowHint | Qt.Window)

        self.setStyleSheet(
            f"QWidget {{ background-color: {BG}; color: {TEXT}; "
            f"font-family: 'Segoe UI'; }}"
        )

        if os.path.exists(ICON_PATH):
            self.setWindowIcon(QIcon(ICON_PATH))

        self.nick = self.load_nick() or ""
        self.ram = self.load_ram() or "4G"

        self.build_ui()
        self.check_server()

    # =====================================================
    #  ПЕРЕТАСКИВАНИЕ ОКНА
    # =====================================================
    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self._drag_pos = event.globalPosition().toPoint()

    def mouseMoveEvent(self, event):
        if event.buttons() & Qt.LeftButton and hasattr(self, '_drag_pos'):
            delta = event.globalPosition().toPoint() - self._drag_pos
            self.move(self.pos() + delta)
            self._drag_pos = event.globalPosition().toPoint()

    def mouseReleaseEvent(self, event):
        if hasattr(self, '_drag_pos'):
            del self._drag_pos

    # =====================================================
    #  СБОРКА UI
    # =====================================================
    def build_ui(self):
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        root.addWidget(self.make_titlebar())

        middle = QHBoxLayout()
        middle.setContentsMargins(0, 0, 0, 0)
        middle.setSpacing(0)
        middle.addWidget(self.make_left_panel())

        # стек вкладок
        self.stack = QStackedWidget()
        self.stack.addWidget(self.make_main_tab())     # 0 — Главная
        self.stack.addWidget(self.make_server_tab())   # 1 — Сервер
        middle.addWidget(self.stack, 1)

        root.addLayout(middle, 1)
        root.addWidget(self.make_statusbar())

    # =====================================================
    #  ХЕЛПЕР: панель с фоновой картинкой + затемнением
    # =====================================================
    def make_panel_with_bg(self, bg_path, overlay_color, object_name,
                           border_side=""):
        outer = QFrame()
        outer.setObjectName(object_name)

        if os.path.exists(bg_path):
            path = bg_path.replace("\\", "/")
            outer.setStyleSheet(f"""
                QFrame#{object_name} {{
                    background-image: url("{path}");
                    background-position: center;
                    background-repeat: no-repeat;
                    background-color: {PANEL};
                    {border_side}
                }}
            """)
        else:
            outer.setStyleSheet(f"""
                QFrame#{object_name} {{
                    background-color: {PANEL};
                    {border_side}
                }}
            """)

        overlay = QFrame(outer)
        overlay.setStyleSheet(f"background: {overlay_color}; border: none;")
        overlay.setGeometry(0, 0, outer.width(), outer.height())

        def resize_event(e, w=overlay, o=outer):
            w.setGeometry(0, 0, o.width(), o.height())
            QFrame.resizeEvent(o, e)
        outer.resizeEvent = resize_event

        return outer, overlay

    # ---------- TITLE BAR ----------
    def make_titlebar(self):
        bar = QFrame()
        bar.setFixedHeight(40)
        bar.setStyleSheet(
            f"background-color: {PANEL}; "
            f"border-bottom: 1px solid {BORDER};"
        )

        layout = QHBoxLayout(bar)
        layout.setContentsMargins(15, 0, 10, 0)
        layout.setSpacing(8)

        icon_label = QLabel()
        icon_label.setStyleSheet("background: transparent;")
        if os.path.exists(ICON_PATH):
            pix = QIcon(ICON_PATH).pixmap(24, 24)
            icon_label.setPixmap(pix)
        icon_label.setFixedSize(28, 28)
        layout.addWidget(icon_label)

        title = QLabel("Luminary")
        title.setStyleSheet(
            f"color: {ACCENT}; font-weight: bold; font-size: 14px; "
            f"background: transparent;"
        )
        layout.addWidget(title)
        layout.addStretch()

        btn_min = QPushButton("—")
        btn_min.setFixedSize(32, 26)
        btn_min.setCursor(Qt.PointingHandCursor)
        btn_min.setStyleSheet(f"""
            QPushButton {{
                background: transparent; color: {MUTED};
                border: none; font-size: 14px;
            }}
            QPushButton:hover {{
                background: {CARD}; color: {TEXT}; border-radius: 4px;
            }}
        """)
        btn_min.clicked.connect(self.showMinimized)
        layout.addWidget(btn_min)

        btn_close = QPushButton("✕")
        btn_close.setFixedSize(32, 26)
        btn_close.setCursor(Qt.PointingHandCursor)
        btn_close.setStyleSheet(f"""
            QPushButton {{
                background: transparent; color: {MUTED};
                border: none; font-size: 14px;
            }}
            QPushButton:hover {{
                background: #c0392b; color: white; border-radius: 4px;
            }}
        """)
        btn_close.clicked.connect(self.close)
        layout.addWidget(btn_close)

        return bar

    # ---------- ЛЕВЫЙ БЛОК ----------
    def make_left_panel(self):
        outer, overlay = self.make_panel_with_bg(
            LEFT_BG, OVERLAY_LEFT, "leftPanel",
            border_side=f"border-right: 1px solid {BORDER};"
        )
        outer.setFixedWidth(220)

        layout = QVBoxLayout(overlay)
        layout.setContentsMargins(10, 15, 10, 15)
        layout.setSpacing(4)

        self.menu_buttons = []

        items = [
            ("🏠", "Главная", 0),
            ("📦", "Сервер", 1),
            ("⚙", "Настройки", -1),
            ("🧩", "Моды", -1),
            ("👥", "Друзья", -1),
        ]
        for icon, name, index in items:
            btn = self.make_menu_item(icon, name, index == 0)
            if index >= 0:
                btn.clicked.connect(
                    lambda checked=False, i=index: self.switch_tab(i)
                )
            self.menu_buttons.append((btn, index))
            layout.addWidget(btn)

        layout.addStretch()

        exit_btn = self.make_menu_item("🚪", "Выход", False)
        exit_btn.clicked.connect(self.close)
        layout.addWidget(exit_btn)

        return outer

    def make_menu_item(self, icon, text, active):
        btn = QPushButton(f"  {icon}   {text}")
        btn.setFixedHeight(42)
        btn.setCursor(Qt.PointingHandCursor)
        bg = "rgba(13, 59, 51, 180)" if active else "transparent"
        btn.setStyleSheet(f"""
            QPushButton {{
                text-align: left;
                padding-left: 12px;
                background: {bg};
                color: {ACCENT if active else TEXT};
                border: none;
                border-radius: 8px;
                font-size: 13px;
                font-weight: {'bold' if active else 'normal'};
            }}
            QPushButton:hover {{
                background: rgba(20, 42, 51, 200);
            }}
        """)
        return btn

    def switch_tab(self, index):
        if not hasattr(self, 'stack'):
            return
        self.stack.setCurrentIndex(index)

        for btn, idx in self.menu_buttons:
            active = (idx == index)
            btn.setStyleSheet(f"""
                QPushButton {{
                    text-align: left;
                    padding-left: 12px;
                    background: {'rgba(13, 59, 51, 180)' if active else 'transparent'};
                    color: {ACCENT if active else TEXT};
                    border: none;
                    border-radius: 8px;
                    font-size: 13px;
                    font-weight: {'bold' if active else 'normal'};
                }}
                QPushButton:hover {{
                    background: rgba(20, 42, 51, 200);
                }}
            """)

    # ---------- ГЛАВНАЯ ВКЛАДКА ----------
    def make_main_tab(self):
        tab = QWidget()
        tab.setStyleSheet(f"background: {BG};")
        layout = QHBoxLayout(tab)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        layout.addWidget(self.make_center_panel(), 1)
        layout.addWidget(self.make_right_panel())

        return tab

    # ---------- ВКЛАДКА "СЕРВЕР" (ручная раскладка) ----------
    def make_server_tab(self):
        tab = QWidget()
        tab.setStyleSheet(f"background: {BG};")
        tab_layout = QVBoxLayout(tab)
        tab_layout.setContentsMargins(0, 0, 0, 0)
        tab_layout.setSpacing(0)

        # --- Область прокрутки ---
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        scroll.setStyleSheet(f"""
            QScrollArea {{
                background: {BG};
                border: none;
            }}
            QScrollBar:vertical {{
                background: {PANEL};
                width: 10px;
                margin: 0;
                border-radius: 5px;
            }}
            QScrollBar::handle:vertical {{
                background: {BORDER};
                border-radius: 5px;
                min-height: 30px;
            }}
            QScrollBar::handle:vertical:hover {{
                background: {ACCENT};
            }}
            QScrollBar::add-line:vertical,
            QScrollBar::sub-line:vertical {{
                height: 0;
            }}
            QScrollBar::add-page:vertical,
            QScrollBar::sub-page:vertical {{
                background: transparent;
            }}
        """)

        # --- Контент внутри скролла ---
        content = QWidget()
        content.setStyleSheet(f"background: {BG};")
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(30, 25, 30, 25)
        content_layout.setSpacing(20)

        # ===== ВЕРХ: картинка слева + часть текста справа =====
        top_row = QHBoxLayout()
        top_row.setSpacing(25)
        top_row.setAlignment(Qt.AlignTop)

        image_label = QLabel()
        image_label.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        image_label.setStyleSheet("background: transparent;")

        if os.path.exists(SERVER_IMAGE):
            pix = QPixmap(SERVER_IMAGE)
            image_label.setPixmap(pix)
        else:
            image_label.setText("🖼\nНет изображения")
            image_label.setFixedSize(320, 240)
            image_label.setAlignment(Qt.AlignCenter)
            image_label.setStyleSheet(f"""
                background: {CARD};
                border: 2px dashed {BORDER};
                border-radius: 12px;
                color: {MUTED};
                font-size: 13px;
            """)

        top_row.addWidget(image_label, 0, Qt.AlignTop)

        text_top = QLabel(SERVER_TEXT_PART1)
        text_top.setWordWrap(True)
        text_top.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        text_top.setStyleSheet(f"""
            color: {TEXT};
            font-size: 14px;
            background: transparent;
        """)
        top_row.addWidget(text_top, 1)

        content_layout.addLayout(top_row)

        # ===== НИЗ: вторая часть текста на всю ширину =====
        text_bottom = QLabel(SERVER_TEXT_PART2)
        text_bottom.setWordWrap(True)
        text_bottom.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        text_bottom.setStyleSheet(f"""
            color: {TEXT};
            font-size: 14px;
            background: transparent;
        """)
        content_layout.addWidget(text_bottom)

        content_layout.addStretch()

        scroll.setWidget(content)
        tab_layout.addWidget(scroll)

        return tab

    # ---------- ЦЕНТРАЛЬНЫЙ БЛОК ----------
    def make_center_panel(self):
        outer, overlay = self.make_panel_with_bg(
            HERO_BG, OVERLAY_CENTER, "centerOuter"
        )

        layout = QVBoxLayout(overlay)
        layout.setContentsMargins(25, 25, 25, 25)
        layout.setSpacing(18)

        # --- HERO-баннер (710x140) ---
        hero = QLabel()
        hero.setObjectName("heroBanner")
        hero.setFixedSize(710, 140)
        hero.setAlignment(Qt.AlignCenter)

        if os.path.exists(HERO_BANNER):
            pix = QPixmap(HERO_BANNER)
            hero.setPixmap(pix)
            hero.setStyleSheet(f"""
                QLabel#heroBanner {{
                    border-radius: 12px;
                    background: transparent;
                }}
            """)
        else:
            hero.setText("🎮  Добро пожаловать в Luminary")
            hero.setStyleSheet(f"""
                QLabel#heroBanner {{
                    background: rgba(10, 20, 24, 120);
                    border: 2px dashed {BORDER};
                    border-radius: 12px;
                    color: {ACCENT};
                    font-size: 22px;
                    font-weight: bold;
                }}
            """)
        layout.addWidget(hero)

        # --- Ник ---
        nick_title = QLabel("Никнейм")
        nick_title.setStyleSheet(
            f"color: {TEXT}; font-weight: bold; font-size: 13px; "
            f"background: transparent;"
        )
        layout.addWidget(nick_title)

        self.nick_entry = QLineEdit()
        self.nick_entry.setPlaceholderText("Только A-Z, a-z, 0-9 (3-16)")
        self.nick_entry.setFixedHeight(40)
        self.nick_entry.setStyleSheet(f"""
            QLineEdit {{
                background: rgba(20, 42, 51, 210);
                border: 1px solid {ACCENT};
                border-radius: 8px;
                padding: 8px 12px;
                color: {TEXT};
                font-size: 13px;
            }}
            QLineEdit:focus {{
                border: 1px solid {ACCENT_HOVER};
            }}
        """)
        if self.nick:
            self.nick_entry.setText(self.nick)
        layout.addWidget(self.nick_entry)

        # --- hint ---
        self.hint = QLabel("")
        self.hint.setAlignment(Qt.AlignCenter)
        self.hint.setStyleSheet(
            f"color: {RED}; font-size: 11px; background: transparent;"
        )
        layout.addWidget(self.hint)

        layout.addStretch()

        # --- Кнопка Играть ---
        self.play_btn = QPushButton("▶  ИГРАТЬ")
        self.play_btn.setFixedHeight(55)
        self.play_btn.setCursor(Qt.PointingHandCursor)
        self.play_btn.setStyleSheet(f"""
            QPushButton {{
                background: {ACCENT};
                color: #0a1418;
                border: none;
                border-radius: 12px;
                font-size: 18px;
                font-weight: bold;
            }}
            QPushButton:hover {{
                background: {ACCENT_HOVER};
            }}
            QPushButton:disabled {{
                background: #2a3a3a;
                color: #6a8a8a;
            }}
        """)
        self.play_btn.clicked.connect(self.on_play)
        layout.addWidget(self.play_btn)

        return outer

    # ---------- ПРАВЫЙ БЛОК ----------
    def make_right_panel(self):
        outer, overlay = self.make_panel_with_bg(
            RIGHT_BG, OVERLAY_RIGHT, "rightPanel",
            border_side=f"border-left: 1px solid {BORDER};"
        )
        outer.setFixedWidth(320)

        layout = QVBoxLayout(overlay)
        layout.setContentsMargins(15, 15, 15, 15)
        layout.setSpacing(12)

        # --- статус сервера ---
        status_card = QFrame()
        status_card.setStyleSheet(
            f"background: {CARD_OVERLAY}; border-radius: 10px;"
        )
        sc = QVBoxLayout(status_card)
        sc.setContentsMargins(15, 12, 15, 12)

        sc_title = QLabel(f"Статус сервера {SERVER_NAME}")
        sc_title.setStyleSheet(
            f"color: {ACCENT}; font-weight: bold; font-size: 13px; "
            f"background: transparent;"
        )
        sc.addWidget(sc_title)

        self.status_label = QLabel("⏳ Проверка...")
        self.status_label.setStyleSheet(
            f"color: {MUTED}; font-size: 12px; background: transparent;"
        )
        sc.addWidget(self.status_label)
        layout.addWidget(status_card)

        # --- RAM (2x2) ---
        layout.addWidget(self.make_ram_card())

        # --- заглушки ---
        layout.addWidget(self.make_stub_card("Последние новости", "(скоро)"))
        layout.addWidget(self.make_stub_card("Топ игроков", "(скоро)"))

        layout.addStretch()
        return outer

    def make_ram_card(self):
        card = QFrame()
        card.setStyleSheet(
            f"background: {CARD_OVERLAY}; border-radius: 10px;"
        )
        cl = QVBoxLayout(card)
        cl.setContentsMargins(15, 12, 15, 12)
        cl.setSpacing(10)

        title = QLabel("Оперативная память")
        title.setStyleSheet(
            f"color: {ACCENT}; font-weight: bold; font-size: 13px; "
            f"background: transparent;"
        )
        cl.addWidget(title)

        grid = QGridLayout()
        grid.setSpacing(8)
        grid.setContentsMargins(0, 0, 0, 0)

        self.ram_group = QButtonGroup(self)

        options = [
            ("2 ГБ", "2G"), ("4 ГБ", "4G"),
            ("6 ГБ", "6G"), ("8 ГБ", "8G"),
        ]

        for idx, (label, value) in enumerate(options):
            row = idx // 2
            col = idx % 2

            rb = QRadioButton(label)
            rb.setProperty("value", value)
            rb.setCursor(Qt.PointingHandCursor)
            if value == self.ram:
                rb.setChecked(True)
            rb.setStyleSheet(f"""
                QRadioButton {{
                    color: {TEXT};
                    font-size: 13px;
                    padding: 4px;
                    background: transparent;
                }}
                QRadioButton::indicator {{
                    width: 16px; height: 16px;
                    border-radius: 8px;
                    border: 2px solid {ACCENT};
                    background: rgba(20, 42, 51, 210);
                }}
                QRadioButton::indicator:checked {{
                    background: {ACCENT};
                }}
            """)
            self.ram_group.addButton(rb)
            grid.addWidget(rb, row, col)

        cl.addLayout(grid)
        return card

    def make_stub_card(self, title, sub):
        card = QFrame()
        card.setStyleSheet(
            f"background: {CARD_OVERLAY}; border-radius: 10px;"
        )
        cl = QVBoxLayout(card)
        cl.setContentsMargins(15, 12, 15, 12)

        t = QLabel(title)
        t.setStyleSheet(
            f"color: {ACCENT}; font-weight: bold; font-size: 13px; "
            f"background: transparent;"
        )
        cl.addWidget(t)

        s = QLabel(sub)
        s.setStyleSheet(
            f"color: {MUTED}; font-size: 12px; background: transparent;"
        )
        cl.addWidget(s)
        return card

    # ---------- НИЖНИЙ СТАТУС-БАР ----------
    def make_statusbar(self):
        bar = QFrame()
        bar.setFixedHeight(35)
        bar.setStyleSheet(
            f"background-color: {PANEL}; "
            f"border-top: 1px solid {BORDER};"
        )

        layout = QHBoxLayout(bar)
        layout.setContentsMargins(15, 0, 15, 0)

        left = QLabel("🟩  Minecraft 1.20.1      ⚙  Fabric")
        left.setStyleSheet(
            f"color: {MUTED}; font-size: 12px; background: transparent;"
        )
        layout.addWidget(left)
        layout.addStretch()

        right = QLabel("●  Готов к запуску")
        right.setStyleSheet(
            f"color: {GREEN}; font-size: 12px; background: transparent;"
        )
        layout.addWidget(right)
        return bar

    # =====================================================
    #  ЛОГИКА
    # =====================================================
    def load_nick(self):
        if os.path.exists(NICK_FILE):
            with open(NICK_FILE, 'r', encoding='utf-8') as f:
                return f.read().strip()
        return None

    def load_ram(self):
        if os.path.exists(RAM_FILE):
            with open(RAM_FILE, 'r', encoding='utf-8') as f:
                return f.read().strip()
        return None

    def save_nick(self, nick):
        with open(NICK_FILE, 'w', encoding='utf-8') as f:
            f.write(nick)

    def save_ram(self, ram):
        with open(RAM_FILE, 'w', encoding='utf-8') as f:
            f.write(ram)

    def check_server(self):
        self.server_thread = ServerCheckThread()
        self.server_thread.result.connect(self.on_server_result)
        self.server_thread.start()

    def on_server_result(self, text, color):
        self.status_label.setText(text)
        self.status_label.setStyleSheet(
            f"color: {color}; font-size: 12px; background: transparent;"
        )

    def get_selected_ram(self):
        btn = self.ram_group.checkedButton()
        return btn.property("value") if btn else "4G"

    def on_play(self):
        nick = self.nick_entry.text().strip()
        if not NICK_PATTERN.match(nick):
            self.hint.setText(
                "Ник: только A-Z, a-z, 0-9, длина 3-16 символов"
            )
            return
        self.hint.setText("")

        ram = self.get_selected_ram()
        self.save_nick(nick)
        self.save_ram(ram)

        self.play_btn.setEnabled(False)
        self.play_btn.setText("Проверка обновлений...")

        # даём Qt перерисовать интерфейс
        QApplication.processEvents()

        # --- проверка обновлений ---
        try:
            can_launch = check_and_update(self)
        except Exception as e:
            print(f"[launcher] Ошибка проверки обновлений: {e}")
            can_launch = True

        if not can_launch:
            self.play_btn.setEnabled(True)
            self.play_btn.setText("▶  ИГРАТЬ")
            return

        # --- запуск Minecraft ---
        self.play_btn.setText("Запуск...")
        QApplication.processEvents()

        self.launch_thread = LaunchThread(nick, ram)
        self.launch_thread.error.connect(self.on_launch_error)
        self.launch_thread.finished.connect(self.on_launch_finished)
        self.launch_thread.start()

    def on_launch_error(self, msg):
        QMessageBox.critical(
            self, "Ошибка",
            f"Не удалось запустить Minecraft:\n{msg}"
        )
        self.play_btn.setEnabled(True)
        self.play_btn.setText("▶  ИГРАТЬ")

    def on_launch_finished(self):
        self.close()


# =========================================================
#  ЗАПУСК
# =========================================================
if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = LuminaryLauncher()
    window.show()
    sys.exit(app.exec())