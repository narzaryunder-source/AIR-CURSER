"""Air Store runs winget directly and displays its verbatim manifest, progress and exit status."""

from pathlib import Path
import shutil
import re
import codecs
from functools import lru_cache

from PySide6.QtCore import QProcess, Qt, QSize
from PySide6.QtGui import QIcon, QPainter, QPixmap
from PySide6.QtWidgets import (
    QFrame,
    QComboBox,
    QGridLayout,
    QStackedWidget,
    QFileDialog,
    QInputDialog,
    QLineEdit,
    QListWidget,
    QPlainTextEdit,
    QVBoxLayout,
    QWidget,
)
from .navigation import sidebar, item, heading
from .catalog import CATALOG, LOGOS, STORE_IDS
from .design import icon
from .applications import launch_cli, launch_registered, match_apps, registered_apps
from .widgets import WINDOWS_INSTALLERS, Workers, asset, button, confirm, label, open_path, row


def arguments(action, value, source="winget"):
    if not value.strip() or value.startswith("-") or any(c in value for c in "\r\n\x00"):
        raise ValueError("Enter a package name or ID.")
    if source not in ("winget", "msstore"):
        raise ValueError("Choose an application source.")
    selector = ["--query", value] if action == "search" else ["--id", value, "--exact"]
    return [action, *selector, "--source", source, "--disable-interactivity"]


def search_rows(output):
    """Read winget's fixed-width list, ignoring partial IDs and unrecognized output."""
    lines = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", output).splitlines()
    for index, line in enumerate(lines[:-1]):
        if not re.fullmatch(r"\s*-{4,}\s*", lines[index + 1]):
            continue
        header = re.search(r"(?<!\S)Id(?=\s)", line, re.IGNORECASE)
        if not header:
            continue
        rows = []
        for entry in lines[index + 2 :]:
            title = entry[: header.start()].strip()
            fields = entry[header.start() :].split()
            if title and fields and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", fields[0]):
                # Refuse an offset inside a wide or truncated display name.
                if len(entry) > header.start() and entry[header.start() - 1].isspace():
                    rows.append((title, fields[0]))
        return rows
    return []


class Studio(QFrame):
    def __init__(self, open_blender):
        super().__init__()
        self.pic = QPixmap(asset("studio.jpg"))
        self.setMinimumHeight(128)
        self.setMaximumHeight(190)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(22, 12, 22, 13)
        layout.addStretch()
        title = label("Blender")
        title.setStyleSheet("font-size:32px;font-weight:650;color:white")
        layout.addWidget(title)
        description = label("3D modeling, sculpting and animation")
        description.setStyleSheet("color:#f1eff9")
        layout.addWidget(description)
        link = button("View package  ›", open_blender)
        link.setStyleSheet("background:#e8e6f3;color:#29233c;border-radius:12px;padding:5px 12px")
        layout.addWidget(link, alignment=Qt.AlignmentFlag.AlignLeft)
        layout.addStretch()

    def paintEvent(self, event):
        from PySide6.QtGui import QPainterPath
        from PySide6.QtCore import QRectF

        p = QPainter(self)
        p.setRenderHints(QPainter.RenderHint.SmoothPixmapTransform | QPainter.RenderHint.Antialiasing)
        path = QPainterPath()
        path.addRoundedRect(QRectF(self.rect()), 13, 13)
        p.setClipPath(path)
        pic = self.pic.scaled(
            self.size(),
            Qt.AspectRatioMode.KeepAspectRatioByExpanding,
            Qt.TransformationMode.SmoothTransformation,
        )
        p.drawPixmap((self.width() - pic.width()) // 2, (self.height() - pic.height()) // 2, pic)


@lru_cache(maxsize=96)
def package_icon(package):
    logo = LOGOS.get(package)
    return QIcon(asset(f"app-logos/{logo}.png")) if logo else icon("Air Store")


class Store(QWidget):
    def __init__(self, apps_changed=lambda: None):
        super().__init__()
        self.apps_changed = apps_changed
        self.work = Workers(self)
        left, self.nav, layout = sidebar(self, "Browse", 190)
        for category in ["Discover", *CATALOG]:
            item(self.nav, category, category)
        self.nav.currentTextChanged.connect(self.populate)
        self.query = QLineEdit()
        self.query.setPlaceholderText("Search winget by application name or package ID")
        self.query.returnPressed.connect(lambda: self.run("search"))
        self.source = QComboBox()
        self.source.addItem("winget Community", "winget")
        self.source.addItem("Microsoft Store", "msstore")
        row(layout, self.query, self.source, button("Search", lambda: self.run("search")))
        self.stack = QStackedWidget()
        layout.addWidget(self.stack, 1)
        explore = QWidget()
        front = QVBoxLayout(explore)
        front.setContentsMargins(0, 0, 0, 0)
        front.setSpacing(9)
        heading(front, "Discover")
        front.addWidget(Studio(lambda: self.details("Graphic Design", "Blender")), 2)
        section = label("Games, creative tools and AI")
        section.setObjectName("section")
        front.addWidget(section)
        grid = QGridLayout()
        grid.setSpacing(7)
        self.featured = []
        for i, (name, category) in enumerate(
            [
                ("Steam", "Game Launchers"),
                ("Roblox", "Games"),
                ("OBS Studio", "Video Editing"),
                ("GIMP", "Photo Editing"),
                ("Visual Studio Code", "Programming"),
                ("VLC", "Entertainment"),
                ("Ollama", "Artificial Intelligence"),
            ]
        ):
            card = button(name + "  ›", lambda n=name, c=category: self.details(c, n))
            card.setIcon(package_icon(CATALOG[category][name]))
            card.setIconSize(QSize(32, 32))
            card.setStyleSheet("text-align:left;padding:8px 10px")
            grid.addWidget(card, i // 2, i % 2)
        front.addLayout(grid)
        front.addStretch()
        self.stack.addWidget(explore)
        detail = QWidget()
        self.stack.addWidget(detail)
        layout = QVBoxLayout(detail)
        layout.setContentsMargins(0, 0, 0, 0)
        self.title = heading(layout, "Packages")
        self.apps = QListWidget()
        self.apps.setIconSize(QSize(43, 43))
        self.apps.currentItemChanged.connect(self.pick)
        self.apps.setMaximumHeight(174)
        layout.addWidget(self.apps)
        self.selected = label("")
        self.selected.setObjectName("secondary")
        layout.addWidget(self.selected)
        self.active_category = next(iter(CATALOG))
        self.install = button("Install…", lambda: self.run("install"))
        self.install.setObjectName("primary")
        row(
            layout,
            self.install,
            button("Package details", lambda: self.run("show")),
            button("Installed?", lambda: self.run("list")),
            button("Update…", lambda: self.run("upgrade")),
            button("Uninstall…", lambda: self.run("uninstall")),
        )
        self.status = label("Choose Package details to request the current manifest from winget.")
        self.status.setObjectName("secondary")
        layout.addWidget(self.status)
        self.output = QPlainTextEdit()
        self.output.setReadOnly(True)
        self.output.setMaximumBlockCount(1500)
        self.output.setPlaceholderText("Package information and installation progress appear here.")
        layout.addWidget(self.output, 1)
        self.agreements = button("Retry with agreements…", self.retry_agreements)
        self.agreements.hide()
        layout.addWidget(self.agreements)
        row(
            layout,
            button("Launch selected app…", self.launch),
            button("Open downloaded installer…", self.installer),
        )
        left.addWidget(label("Windows Package Manager"))
        self.process = QProcess(self)
        self.process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.process.readyReadStandardOutput.connect(self.read)
        self.process.finished.connect(self.finished)
        self.process.errorOccurred.connect(lambda _: self.status.setText(self.process.errorString()))
        self.nav.setCurrentRow(0)

    def details(self, category, name):
        self.nav.setCurrentRow(list(CATALOG).index(category) + 1)
        matches = self.apps.findItems(name, Qt.MatchFlag.MatchExactly)
        if matches:
            self.apps.setCurrentItem(matches[0])
        self.run("show")

    def populate(self, category):
        if category == "Discover":
            self.stack.setCurrentIndex(0)
            self.query.clear()
            return
        self.stack.setCurrentIndex(1)
        self.active_category = category
        self.title.setText(category)
        self.apps.clear()
        for name, package in CATALOG[category].items():
            self.add_package(name, package, "msstore" if package in STORE_IDS else "winget")
        self.apps.setCurrentRow(0)

    def add_package(self, name, package, source):
        entry = item(self.apps, name, package)
        entry.setIcon(package_icon(package))
        entry.setData(Qt.ItemDataRole.UserRole + 1, source)

    def pick(self, entry, previous=None):
        if entry:
            package = entry.data(Qt.ItemDataRole.UserRole)
            self.query.setText(package)
            self.source.setCurrentIndex(self.source.findData(entry.data(Qt.ItemDataRole.UserRole + 1)))
            self.selected.setText(package)
        else:
            self.selected.clear()

    def launch(self):
        entry = self.apps.currentItem()
        if not entry or self.query.text().strip() != entry.data(Qt.ItemDataRole.UserRole):
            self.status.setText("Select an application from the package list first.")
            return
        name, package = entry.text(), entry.data(Qt.ItemDataRole.UserRole)
        self.status.setText("Checking installed Windows applications…")

        def choose(apps):
            if not self.isVisible():
                return
            try:
                matches = match_apps(name, apps)
                if not matches and not launch_cli(package):
                    if not apps:
                        self.status.setText(
                            f"No Windows Start entry or supported command-line tool was found for {name}."
                        )
                        return
                    if not confirm(
                        self,
                        "Choose Windows application",
                        f"Windows has no matching Start entry for {name}. Choose its registered name manually?",
                    ):
                        return
                    matches = sorted(apps, key=lambda pair: pair[0].casefold())
                if matches:
                    choices = [f"{title}  ·  {target[9:]}" for title, target in matches]
                    choice, ok = QInputDialog.getItem(
                        self, "Launch installed app", "Windows Start entry", choices, editable=False
                    )
                    if ok:
                        launch_registered(matches[choices.index(choice)][1])
                        self.status.setText("Sent the launch request to Windows.")
                else:
                    self.status.setText("Opened the installed command-line application.")
            except (OSError, ValueError) as exc:
                self.status.setText(str(exc))

        self.work.run(registered_apps, choose, self.status.setText)

    def finished(self, code, exit_status):
        self.read(final=True)
        self.agreements.setVisible(bool(code))
        if not code and self.operation == "search":
            rows = search_rows(self.output.toPlainText())
            self.title.setText("Search results")
            self.apps.clear()
            for title, package in rows:
                self.add_package(title, package, self.package_source)
            if rows:
                self.apps.setCurrentRow(0)
            self.status.setText(f"{len(rows)} selectable packages. Full winget results appear below.")
        elif not code and self.operation in ("install", "upgrade", "uninstall"):
            self.apps_changed()
            self.status.setText(f"winget completed {self.operation} for {self.package}.")
        else:
            self.status.setText(f"winget {self.operation} exited with code {code}.")

    def read(self, final=False):
        data = self.decoder.decode(bytes(self.process.readAllStandardOutput()), final=final)
        cursor = self.output.textCursor()
        cursor.movePosition(cursor.MoveOperation.End)
        cursor.insertText(data)
        self.output.setTextCursor(cursor)

    def retry_agreements(self):
        package = self.operation in ("install", "upgrade")
        terms = "the winget source agreements" + (
            " and this package’s publisher agreements" if package else ""
        )
        if confirm(
            self,
            "Accept agreements",
            f"Review the agreements and publisher links in winget’s output. Accept {terms} for {self.package} and retry?",
        ):
            self.run(self.operation, agreed=True, value=self.package, source=self.package_source)

    def run(self, action, agreed=False, value=None, source=None):
        if self.process.state() != QProcess.ProcessState.NotRunning:
            self.status.setText("Wait for the current package operation to finish.")
            return
        self.stack.setCurrentIndex(1)
        exe = shutil.which("winget.exe")
        if not exe:
            self.status.setText("Install or update Microsoft App Installer to use winget.")
            return
        value = self.query.text().strip() if value is None else value
        source = source or self.source.currentData()
        try:
            args = arguments(action, value, source)
        except ValueError as exc:
            self.status.setText(str(exc))
            return
        if action in ("install", "upgrade", "uninstall"):
            if not confirm(
                self,
                f"{action.capitalize()} package",
                f"{action.capitalize()} {value} using {source}? Review Details for publisher and license. Windows may ask for approval.",
            ):
                return
        if agreed:
            args.append("--accept-source-agreements")
            if action in ("install", "upgrade"):
                args.append("--accept-package-agreements")
        self.agreements.hide()
        self.output.clear()
        self.status.setText("Running winget " + action + "…")
        self.operation, self.package, self.package_source = action, value, source
        self.decoder = codecs.getincrementaldecoder("utf-8")("replace")
        self.process.start(exe, args)

    def installer(self):
        patterns = " ".join("*" + suffix for suffix in sorted(WINDOWS_INSTALLERS))
        path, _ = QFileDialog.getOpenFileName(
            self, "Choose installer", "", f"Windows installers ({patterns})"
        )
        if path:
            open_path(self, Path(path))
