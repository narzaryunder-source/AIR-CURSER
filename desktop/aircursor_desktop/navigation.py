"""Shared sidebar geometry and text hierarchy for native workspace apps."""

from PySide6.QtCore import QSize, Qt
from PySide6.QtWidgets import QFrame, QHBoxLayout, QLabel, QListWidget, QListWidgetItem, QVBoxLayout, QWidget
from .design import icon


def sidebar(parent, title, width=182):
    layout = QHBoxLayout(parent)
    layout.setContentsMargins(0, 0, 0, 0)
    layout.setSpacing(0)
    rail = QFrame()
    rail.setObjectName("sidebar")
    rail.setFixedWidth(width)
    left = QVBoxLayout(rail)
    left.setContentsMargins(11, 16, 10, 13)
    left.setSpacing(6)
    caption = QLabel(title)
    caption.setObjectName("section")
    left.addWidget(caption)
    nav = QListWidget()
    nav.setIconSize(QSize(19, 19))
    nav.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
    left.addWidget(nav, 1)
    layout.addWidget(rail)
    body = QWidget()
    right = QVBoxLayout(body)
    right.setContentsMargins(22, 17, 22, 16)
    right.setSpacing(10)
    layout.addWidget(body, 1)
    return left, nav, right


def item(nav, name, target, art=None):
    entry = QListWidgetItem(icon(art), name) if art else QListWidgetItem(name)
    entry.setData(Qt.ItemDataRole.UserRole, target)
    nav.addItem(entry)
    return entry


def heading(layout, text):
    h = QLabel(text)
    h.setObjectName("heading")
    layout.addWidget(h)
    return h
