"""Shared native Qt controls, worker delivery and shell integration."""

import os
from pathlib import Path
import threading

from PySide6.QtCore import QObject, Qt, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QHBoxLayout, QLabel, QLineEdit, QMessageBox, QPushButton


def button(text, action, tip=None):
    item = QPushButton(text)
    item.clicked.connect(lambda: action())
    if tip:
        item.setToolTip(tip)
    return item


def row(layout, *items):
    bar = QHBoxLayout()
    for item in items:
        bar.addWidget(item)
    layout.addLayout(bar)
    return bar


def label(text=""):
    item = QLabel(text)
    item.setTextFormat(Qt.TextFormat.PlainText)
    item.setWordWrap(True)
    return item


def password(placeholder):
    edit = QLineEdit()
    edit.setPlaceholderText(placeholder)
    edit.setEchoMode(QLineEdit.EchoMode.Password)
    return edit


def confirm(parent, title, message):
    dlg = QMessageBox(
        QMessageBox.Icon.Question,
        title,
        message,
        QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.Cancel,
        parent,
    )
    dlg.setTextFormat(Qt.TextFormat.PlainText)
    dlg.setDefaultButton(QMessageBox.StandardButton.Cancel)
    return dlg.exec() == QMessageBox.StandardButton.Yes


WINDOWS_INSTALLERS = frozenset(
    {".exe", ".msi", ".msp", ".msix", ".msixbundle", ".appx", ".appxbundle", ".appinstaller"}
)


def open_path(parent, path):
    path = Path(path)
    if os.name == "nt" and path.suffix.lower() in {".exe", ".msi", ".msp"}:
        from .installer import approve

        approve(parent, path)
        return
    if path.suffix.lower() in WINDOWS_INSTALLERS | {
        ".cmd",
        ".bat",
        ".ps1",
        ".lnk",
        ".com",
        ".scr",
        ".hta",
        ".js",
        ".vbs",
        ".reg",
    }:
        if not confirm(
            parent, "Launch with Windows?", f"Open this file using your Windows account?\n{path}"
        ):
            return
    if not QDesktopServices.openUrl(QUrl.fromLocalFile(str(path))):
        QMessageBox.warning(parent, "Open file", f"Windows could not open {path}.")


class Workers(QObject):
    finished = Signal(object, object, object)

    def __init__(self, parent):
        super().__init__(parent)
        self.finished.connect(self.deliver, Qt.ConnectionType.QueuedConnection)

    def run(self, task, done, failed):
        def work():
            try:
                result = task()
                callback = done
            except Exception as exc:
                result, callback = str(exc), failed
            try:
                self.finished.emit(callback, result, None)
            except RuntimeError:  # Window closed before the operation finished.
                pass

        threading.Thread(target=work, daemon=True).start()

    def deliver(self, callback, result, unused):
        callback(result)


def asset(name):
    return str(Path(__file__).resolve().parent.parent / "assets" / name)


def system_link(parent, uri):
    if os.name != "nt" or not QDesktopServices.openUrl(QUrl(uri)):
        QMessageBox.information(
            parent, "Windows integration", "This action requires the corresponding Windows feature."
        )
