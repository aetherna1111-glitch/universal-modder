"""Design tokens and the application stylesheet (underwater palette of launcher 1.x, refined).

Contrast (WCAG): TEXT on CARD 11.9:1, MUTED on CARD 5.4:1, ACCENT on BG 10.5:1, BG on ACCENT 10.5:1.
"""
from __future__ import annotations

import sys

BG = "#0a1418"
PANEL = "#0f1e26"
CARD = "#132a32"
CARD_SOLID = "#132a32"
CARD_HOVER = "#183541"
BORDER = "#1e3d47"
BORDER_STRONG = "#2a5563"
ACCENT = "#3dd6c4"
ACCENT_HOVER = "#5fe3d3"
ACCENT_PRESSED = "#2bb5a4"
ACCENT_DIM = "#0d3b33"
TEXT = "#d4eae6"
MUTED = "#86aaa9"
SUBTLE = "#5f8a8b"
GREEN = "#4ade80"
YELLOW = "#facc15"
RED = "#ff6b6b"
RED_DIM = "#3a1d22"
KELP = "#7fbf3f"  # from the banner wordmark

FONT_FAMILY = "Segoe UI" if sys.platform == "win32" else "Noto Sans, DejaVu Sans, Segoe UI"

SPACE = {"xs": 4, "sm": 8, "md": 12, "lg": 16, "xl": 24, "xxl": 32}
RADIUS = {"sm": 6, "md": 8, "lg": 12}


def stylesheet() -> str:
    return f"""
* {{
    font-family: {FONT_FAMILY};
    font-size: 13px;
    color: {TEXT};
    outline: none;
}}
QWidget#Root {{ background: {BG}; }}
QToolTip {{ background: {PANEL}; color: {TEXT}; border: 1px solid {BORDER_STRONG}; padding: 6px 8px; border-radius: 6px; }}

/* ---------- text roles ---------- */
QLabel {{ background: transparent; }}
QLabel[role="display"] {{ font-size: 24px; font-weight: 700; }}
QLabel[role="title"] {{ font-size: 16px; font-weight: 600; }}
QLabel[role="section"] {{ font-size: 12px; font-weight: 700; color: {ACCENT}; letter-spacing: 0.6px; }}
QLabel[role="muted"] {{ color: {MUTED}; }}
QLabel[role="caption"] {{ color: {MUTED}; font-size: 11px; }}
QLabel[role="error"] {{ color: {RED}; font-size: 12px; }}
QLabel[role="success"] {{ color: {GREEN}; font-size: 12px; }}
QLabel[role="body"] {{ font-size: 14px; line-height: 150%; }}
QLabel a {{ color: {ACCENT}; }}

/* ---------- cards ---------- */
QFrame#Card {{
    background: rgba(19, 42, 50, 0.86);
    border: 1px solid {BORDER};
    border-radius: {RADIUS['lg']}px;
}}
QFrame#CardFlat {{ background: {CARD}; border: 1px solid {BORDER}; border-radius: {RADIUS['md']}px; }}
QFrame#Banner {{ background: rgba(61, 214, 196, 0.08); border: 1px solid {BORDER_STRONG}; border-radius: {RADIUS['md']}px; }}
QFrame#BannerError {{ background: {RED_DIM}; border: 1px solid #6b2f37; border-radius: {RADIUS['md']}px; }}
QFrame#Divider {{ background: {BORDER}; max-height: 1px; min-height: 1px; border: none; }}

/* ---------- buttons ---------- */
QPushButton {{
    background: {CARD_HOVER};
    border: 1px solid {BORDER_STRONG};
    border-radius: {RADIUS['md']}px;
    padding: 8px 14px;
    font-weight: 600;
}}
QPushButton:hover {{ background: #1d404d; border-color: {ACCENT_PRESSED}; }}
QPushButton:pressed {{ background: {ACCENT_DIM}; }}
QPushButton:focus {{ border-color: {ACCENT}; }}
QPushButton:disabled {{ color: {SUBTLE}; background: {PANEL}; border-color: {BORDER}; }}

QPushButton[variant="primary"] {{ background: {ACCENT}; color: {BG}; border: 1px solid {ACCENT}; }}
QPushButton[variant="primary"]:hover {{ background: {ACCENT_HOVER}; border-color: {ACCENT_HOVER}; }}
QPushButton[variant="primary"]:pressed {{ background: {ACCENT_PRESSED}; }}
QPushButton[variant="primary"]:focus {{ border: 2px solid {TEXT}; }}
QPushButton[variant="primary"]:disabled {{ background: #24403f; color: #7c9c9a; border-color: #24403f; }}

QPushButton[variant="play"] {{
    background: {ACCENT}; color: {BG}; border: none; border-radius: {RADIUS['lg']}px;
    font-size: 18px; font-weight: 800; letter-spacing: 1px; padding: 0 24px;
}}
QPushButton[variant="play"]:hover {{ background: {ACCENT_HOVER}; }}
QPushButton[variant="play"]:pressed {{ background: {ACCENT_PRESSED}; }}
QPushButton[variant="play"]:focus {{ border: 2px solid {TEXT}; }}
QPushButton[variant="play"]:disabled {{ background: #24403f; color: #8fb3b0; }}

QPushButton[variant="ghost"] {{ background: transparent; border: 1px solid transparent; color: {MUTED}; }}
QPushButton[variant="ghost"]:hover {{ background: rgba(61, 214, 196, 0.08); color: {TEXT}; }}
QPushButton[variant="ghost"]:focus {{ border-color: {ACCENT_PRESSED}; }}
QPushButton[variant="link"] {{ background: transparent; border: none; color: {ACCENT}; padding: 2px 0; font-weight: 600; text-align: left; }}
QPushButton[variant="link"]:hover {{ color: {ACCENT_HOVER}; text-decoration: underline; }}
QPushButton[variant="danger"] {{ background: transparent; border: 1px solid #6b2f37; color: {RED}; }}
QPushButton[variant="danger"]:hover {{ background: {RED_DIM}; }}

QPushButton[variant="nav"] {{
    background: transparent; border: none; border-radius: {RADIUS['md']}px;
    text-align: left; padding: 10px 12px; font-weight: 500; color: {TEXT};
}}
QPushButton[variant="nav"]:hover {{ background: rgba(20, 42, 51, 0.85); }}
QPushButton[variant="nav"]:focus {{ background: rgba(20, 42, 51, 0.85); }}
QPushButton[variant="nav"]:checked {{ background: rgba(13, 59, 51, 0.92); color: {ACCENT}; font-weight: 700; }}

QPushButton[variant="segment"] {{ background: rgba(10, 20, 24, 0.55); border: 1px solid {BORDER}; padding: 8px 6px; font-weight: 600; }}
QPushButton[variant="segment"]:hover {{ border-color: {ACCENT_PRESSED}; }}
QPushButton[variant="segment"]:checked {{ background: {ACCENT_DIM}; border-color: {ACCENT}; color: {ACCENT}; }}

QPushButton[variant="window"] {{ background: transparent; border: none; border-radius: 6px; padding: 4px; }}
QPushButton[variant="window"]:hover {{ background: {CARD_HOVER}; }}
QPushButton#CloseButton:hover {{ background: #c0392b; }}

/* ---------- inputs ---------- */
QLineEdit, QPlainTextEdit, QComboBox {{
    background: rgba(10, 20, 24, 0.7);
    border: 1px solid {BORDER_STRONG};
    border-radius: {RADIUS['md']}px;
    padding: 8px 10px;
    selection-background-color: {ACCENT_PRESSED};
    selection-color: {BG};
}}
QLineEdit:hover, QComboBox:hover {{ border-color: {ACCENT_PRESSED}; }}
QLineEdit:focus, QPlainTextEdit:focus, QComboBox:focus {{ border: 1px solid {ACCENT}; }}
QLineEdit[invalid="true"] {{ border: 1px solid {RED}; }}
QLineEdit:disabled {{ color: {SUBTLE}; }}
QComboBox::drop-down {{ border: none; width: 24px; }}
QComboBox QAbstractItemView {{ background: {PANEL}; border: 1px solid {BORDER_STRONG}; selection-background-color: {ACCENT_DIM}; selection-color: {ACCENT}; }}

QCheckBox, QRadioButton {{ spacing: 8px; background: transparent; }}
QCheckBox::indicator, QRadioButton::indicator {{ width: 18px; height: 18px; }}
QCheckBox::indicator {{ border: 2px solid {BORDER_STRONG}; border-radius: 4px; background: rgba(10,20,24,0.7); }}
QCheckBox::indicator:checked {{ background: {ACCENT}; border-color: {ACCENT}; }}
QCheckBox::indicator:focus, QRadioButton::indicator:focus {{ border-color: {ACCENT}; }}
QRadioButton::indicator {{ width: 14px; height: 14px; border: 2px solid {BORDER_STRONG}; border-radius: 9px; background: rgba(10,20,24,0.7); }}
QRadioButton::indicator:checked {{ background: {ACCENT}; border: 2px solid {ACCENT}; }}

QProgressBar {{ background: rgba(10, 20, 24, 0.7); border: 1px solid {BORDER}; border-radius: 4px; height: 8px; text-align: center; color: transparent; }}
QProgressBar::chunk {{ background: {ACCENT}; border-radius: 3px; }}

/* ---------- lists & tables ---------- */
QTableWidget, QListWidget {{ background: rgba(10, 20, 24, 0.5); border: 1px solid {BORDER}; border-radius: {RADIUS['md']}px; gridline-color: transparent; }}
QTableWidget::item, QListWidget::item {{ padding: 6px; border: none; }}
QTableWidget::item:selected, QListWidget::item:selected {{ background: {ACCENT_DIM}; color: {TEXT}; }}
QHeaderView::section {{ background: {PANEL}; color: {MUTED}; border: none; border-bottom: 1px solid {BORDER}; padding: 8px 6px; font-weight: 600; }}

/* ---------- scrollbars ---------- */
QScrollArea {{ background: transparent; border: none; }}
QScrollArea > QWidget > QWidget {{ background: transparent; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: {BORDER_STRONG}; border-radius: 3px; min-height: 30px; }}
QScrollBar::handle:vertical:hover {{ background: {ACCENT_PRESSED}; }}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; width: 0; }}
QScrollBar::add-page, QScrollBar::sub-page {{ background: transparent; }}
QScrollBar:horizontal {{ background: transparent; height: 10px; margin: 2px; }}
QScrollBar::handle:horizontal {{ background: {BORDER_STRONG}; border-radius: 3px; min-width: 30px; }}

QDialog {{ background: {BG}; }}
QMessageBox {{ background: {BG}; }}
QTabBar::tab {{ background: transparent; color: {MUTED}; padding: 8px 16px; border-bottom: 2px solid transparent; font-weight: 600; }}
QTabBar::tab:selected {{ color: {ACCENT}; border-bottom-color: {ACCENT}; }}
QTabBar::tab:hover {{ color: {TEXT}; }}
QTabWidget::pane {{ border: none; }}
"""
