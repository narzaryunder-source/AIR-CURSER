"""Bounded launcher index and calculator. Windows Search owns the filesystem index."""
import ast
import math
import operator
import os
from pathlib import Path
from urllib.parse import quote

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QLineEdit, QListWidget, QListWidgetItem, QVBoxLayout, QWidget
from .widgets import Workers, button, label, open_path, row, system_link

OPS = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv,
       ast.Mod: operator.mod}


def calculate(text):
    if len(text) > 120 or not any(ch in "+-*/%" for ch in text):
        return None
    def number(node):
        if isinstance(node, ast.Constant) and type(node.value) in (int, float):
            return float(node.value)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
            return (-1 if isinstance(node.op, ast.USub) else 1) * number(node.operand)
        if isinstance(node, ast.BinOp) and type(node.op) in OPS:
            return OPS[type(node.op)](number(node.left), number(node.right))
        raise ValueError
    try:
        result = number(ast.parse(text, mode="eval").body)
        return format(result, ".12g") if math.isfinite(result) else None
    except (ArithmeticError, SyntaxError, ValueError, RecursionError):
        return None


def shortcuts():
    items = []
    for base in ("APPDATA", "PROGRAMDATA"):
        if os.environ.get(base):
            root = Path(os.environ[base]) / "Microsoft/Windows/Start Menu/Programs"
            items.extend((p.stem, str(p)) for p in root.rglob("*.lnk"))
    if os.environ.get("APPDATA"):
        recent = Path(os.environ["APPDATA"]) / "Microsoft/Windows/Recent"
        items.extend((p.stem, str(p)) for p in recent.glob("*.lnk"))
    return sorted(set(items), key=lambda pair: pair[0].casefold())


class Launcher(QWidget):
    def __init__(self, desk):
        super().__init__()
        self.desk, self.work = desk, Workers(self)
        layout = QVBoxLayout(self)
        self.query = QLineEdit()
        self.query.setPlaceholderText("Search apps, recent files, settings, or calculate")
        layout.addWidget(self.query)
        self.results = QListWidget()
        layout.addWidget(self.results)
        self.status = label("")
        layout.addWidget(self.status)
        row(layout, button("Open", self.activate), button("Pin shortcut to Dock", self.pin),
            button("Search files in Windows", self.search_files))
        self.items = []
        self.query.textChanged.connect(self.search)
        self.query.returnPressed.connect(self.activate)
        self.results.itemDoubleClicked.connect(self.activate)
        self.work.run(shortcuts, self.ready, self.status.setText)
        self.search()

    def ready(self, items):
        self.items = items
        self.search()

    def search_files(self):
        system_link(self, "search-ms:query=" + quote(self.query.text(), safe=""))

    def search(self, *_):
        query = self.query.text().casefold().strip()
        self.results.clear()
        def add(title, kind, value):
            item = QListWidgetItem(title)
            item.setData(Qt.ItemDataRole.UserRole, (kind, value))
            self.results.addItem(item)
        result = calculate(query)
        if result is not None:
            add("= " + result, "copy", result)
        for name in self.desk.names:
            if query in name.casefold() and name != "Launcher":
                add(name, "panel", name)
        from .control import SYSTEM
        for name, uri in SYSTEM.items():
            if query and query in name.casefold():
                add(name + " · Windows settings", "settings", uri)
        for name, path in self.items:
            if query in name.casefold() and self.results.count() < 120:
                add(name, "path", path)
        if query and self.desk.s.get("browser_history_enabled", False):
            for url in self.desk.s.get("browser_history", []):
                if query in url.casefold() and self.results.count() < 140:
                    add(url, "web", url)
        self.results.setCurrentRow(0)
        self.status.setText(f"{self.results.count()} results")

    def activate(self, *_):
        item = self.results.currentItem()
        if item:
            kind, value = item.data(Qt.ItemDataRole.UserRole)
            if kind == "copy":
                from PySide6.QtWidgets import QApplication
                QApplication.clipboard().setText(value)
            elif kind == "panel":
                self.desk.open(value)
            elif kind == "settings":
                system_link(self, "ms-settings:" + value)
            elif kind == "web":
                self.desk.browse(value)
            else:
                open_path(self, value)

    def pin(self):
        item = self.results.currentItem()
        if item and item.data(Qt.ItemDataRole.UserRole)[0] == "path":
            self.desk.pin(item.data(Qt.ItemDataRole.UserRole)[1])
        else:
            self.status.setText("Select a Windows shortcut to pin.")
