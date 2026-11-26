"""AirCursor's optional desktop workspace. Windows Explorer remains the operating system shell."""
from datetime import datetime
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import psutil
from PySide6.QtCore import QEvent, QPoint, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QDesktopServices, QFont, QIcon, QKeySequence, QPalette, QShortcut
from PySide6.QtWidgets import (QApplication, QDialog, QFileDialog, QFileIconProvider, QFrame, QHBoxLayout,
                               QInputDialog, QLabel, QListWidget, QListWidgetItem, QMainWindow, QMenu,
                               QMessageBox, QPushButton, QSizeGrip, QToolButton,
                               QVBoxLayout, QWidget)
from .control import Control, Preferences, PROFILES
from .design import COLORS, DockButton, Landscape, icon
from .hotspot import AutoSwitch, Hotspot
from .inject import Controller, backend
from .launcher import Launcher
from .lock import PasswordLock
from .material import apply_backdrop
from .server import RemoteHost, local_addresses
from .settings import Settings
from .widgets import Workers, asset, button, confirm, label, open_path, password, row, system_link


class Title(QFrame):
    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.window().windowHandle().startSystemMove()

    def mouseDoubleClickEvent(self, event):
        win = self.window()
        (win.showNormal if win.isMaximized() else win.showMaximized)()


class Panel(QMainWindow):
    def __init__(self, desk, name, content):
        super().__init__(desk, Qt.WindowType.Window | Qt.WindowType.FramelessWindowHint)
        self.desk, self.name, self.content = desk, name, content
        self.setWindowTitle(name + " · AirCursor")
        self.setWindowIcon(icon(name))
        wrap = QFrame()
        wrap.setObjectName("panel")
        layout = QVBoxLayout(wrap)
        layout.setContentsMargins(1, 1, 1, 1)
        title = Title()
        bar = QHBoxLayout(title)
        bar.setContentsMargins(13, 8, 12, 3)
        for color, tip, fn in [("#ef867d", "Close", self.close), ("#ebc168", "Minimize", self.showMinimized),
                                ("#7bc59b", "Maximize or restore", self.zoom)]:
            dot = button("", fn, tip)
            dot.setAccessibleName(tip)
            dot.setFixedSize(14, 14)
            dot.setStyleSheet(f"background:{color};border:1px solid rgba(0,0,0,28);border-radius:7px;padding:0")
            bar.addWidget(dot)
        bar.addSpacing(8)
        bar.addWidget(label(name))
        bar.addStretch()
        layout.addWidget(title)
        layout.addWidget(content, 1)
        grip = QSizeGrip(wrap)
        grip.setFixedSize(15, 12)
        layout.addWidget(grip, alignment=Qt.AlignmentFlag.AlignRight)
        self.setCentralWidget(wrap)
        self.resize(830, 565)
        if apply_backdrop(self, not desk.surface.light):
            wrap.setStyleSheet("QFrame#panel {background:transparent}")
            content.setAutoFillBackground(True)
        QShortcut(QKeySequence("Ctrl+Space"), self, activated=lambda: desk.open("Launcher"))
        QShortcut(QKeySequence("Ctrl+Shift+L"), self, activated=desk.lock_now)
        QShortcut(QKeySequence("Ctrl+Alt+Left"), self, activated=lambda: self.snap(False))
        QShortcut(QKeySequence("Ctrl+Alt+Right"), self, activated=lambda: self.snap(True))

    def snap(self, right):
        r = self.screen().availableGeometry()
        w = r.width()//2
        self.showNormal()
        self.setGeometry(r.x() + (w if right else 0), r.y(), w, r.height())

    def zoom(self):
        (self.showNormal if self.isMaximized() else self.showMaximized)()

    def closeEvent(self, event):
        self.desk.panel_closed(self.name)
        super().closeEvent(event)


class DesktopIcons(QListWidget):
    def __init__(self, desk):
        super().__init__()
        self.desk = desk
        self.setObjectName("desktopIcons")
        self.setViewMode(QListWidget.ViewMode.ListMode)
        self.setDragDropMode(QListWidget.DragDropMode.InternalMove)
        self.setDefaultDropAction(Qt.DropAction.MoveAction)
        self.setIconSize(QSize(27, 27))
        self.setFixedWidth(234)
        self.setMaximumHeight(145)
        self.itemDoubleClicked.connect(self.activate)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.customContextMenuRequested.connect(self.menu)
        self.load()

    def load(self):
        self.clear()
        for target in self.desk.s.get("desktop_items", ["Files", "Air Browser"]):
            item = QListWidgetItem(icon(target), target if target in COLORS else Path(target).name)
            item.setData(Qt.ItemDataRole.UserRole, target)
            self.addItem(item)

    def activate(self, item):
        target = item.data(Qt.ItemDataRole.UserRole)
        if target in COLORS:
            self.desk.open(target)
        else:
            open_path(self, target)

    def save(self):
        self.desk.s.set(desktop_items=[self.item(i).data(Qt.ItemDataRole.UserRole) for i in range(self.count())])

    def dropEvent(self, event):
        super().dropEvent(event)
        self.save()

    def menu(self, pos):
        menu = QMenu(self)
        menu.addAction("Add shortcut…", self.desk.add_shortcut)
        if self.itemAt(pos):
            def remove():
                self.takeItem(self.row(self.itemAt(pos)))
                self.save()
            menu.addAction("Remove shortcut", remove)
        menu.exec(self.viewport().mapToGlobal(pos))


class Desktop(QMainWindow):
    message = Signal(str)
    receiver_changed = Signal()
    names = list(COLORS)
    def __init__(self, settings=None):
        super().__init__()
        self.s = settings or Settings()
        self.auth, self.hot = PasswordLock(self.s), Hotspot(self.s)
        self.host = RemoteHost(Controller(backend()), self.receiver_changed.emit)
        self.auto = AutoSwitch(self.hot, self.message.emit, every=self.probe_interval())
        self.work = Workers(self)
        self.panels, self.opened, self.clipboard, self.messages = {}, {}, [], []
        self.locked = True
        self.space = 1
        self.net_pending = False
        self.last_net = -1000
        self.addresses = []
        self.network_text = "Checking connectivity…"
        self.metrics_text = ""
        self.usage = None
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.message.connect(self.notify)
        self.receiver_changed.connect(self.refresh_control)
        self.setWindowTitle("AirCursor Workspace")
        self.setWindowIcon(QIcon(asset("aircursor.ico")))
        self.resize(1120, 720)
        self.setMinimumSize(680, 465)
        for key, fn in [("F11", self.fullscreen), ("Escape", self.exit_desktop),
                        ("Ctrl+Space", lambda: self.open("Launcher")), ("Ctrl+Shift+L", self.lock_now),
                        ("Ctrl+1", lambda: self.switch_space(1)), ("Ctrl+2", lambda: self.switch_space(2))]:
            QShortcut(QKeySequence(key), self, activated=fn)
        self.show_lock()

    def probe_interval(self):
        return 60 if self.s.get("profile") in ("Battery Saver", "Mobile Hotspot") else 15

    def show_lock(self):
        self.locked = True
        self.host.paused = True
        self.timer.stop()
        self.auto.stop()
        self.clipboard.clear()
        if self.isFullScreen():
            self.showNormal()
        self.surface = Landscape()
        self.setCentralWidget(self.surface)
        outer = QVBoxLayout(self.surface)
        outer.setAlignment(Qt.AlignmentFlag.AlignCenter)
        card = QFrame()
        card.setObjectName("lockCard")
        card.setFixedWidth(360)
        box = QVBoxLayout(card)
        box.setContentsMargins(30, 28, 30, 28)
        mark = QLabel()
        mark.setPixmap(self.windowIcon().pixmap(48, 48))
        box.addWidget(mark)
        first = not self.auth.has_password
        title = label("Welcome to AirCursor" if first else "AirCursor is locked")
        title.setObjectName("heading")
        box.addWidget(title)
        box.addWidget(label("Create your workspace password." if first else "Enter your workspace password."))
        secret = password("Password")
        again = password("Confirm password")
        box.addWidget(secret)
        if first:
            box.addWidget(again)
        else:
            again.deleteLater()
        status = label("")
        box.addWidget(status)
        def unlock():
            try:
                if first:
                    self.auth.set_password(secret.text(), again.text())
                elif not self.auth.check(secret.text()):
                    wait = self.auth.wait_left()
                    status.setText(f"Try again in {wait}s." if wait else "Incorrect password.")
                    secret.clear()
                    return
            except (ValueError, OSError) as exc:
                status.setText(str(exc))
                return
            self.workspace()
        box.addWidget(button("Create workspace" if first else "Unlock", unlock))
        secret.returnPressed.connect(again.setFocus if first else unlock)
        if first:
            again.returnPressed.connect(unlock)
        else:
            box.addWidget(button("Reset AirCursor…", self.reset))
        outer.addWidget(card)
        self.style_workspace()
        secret.setFocus()

    def reset(self):
        if not confirm(self, "Reset workspace", "Forget the phone hotspot and remove AirCursor settings, saved chats and password? Browser profile files stay on disk."):
            return
        # Browser data must not become accessible through a password reset in this running session.
        try:
            self.hot.forget_phone()
            self.s.clear()
        except OSError as exc:
            QMessageBox.warning(self, "Reset", str(exc))
            return
        self.close()

    def lock_now(self):
        if self.locked:
            return
        self.persist()
        for panel in self.panels.values():
            panel.hide()
        self.show_lock()
        self.raise_()
        self.activateWindow()

    def workspace(self):
        self.locked = False
        self.host.paused = False
        self.surface = Landscape()
        self.setCentralWidget(self.surface)
        layout = QVBoxLayout(self.surface)
        layout.setContentsMargins(15, 12, 15, 15)
        bar = QFrame()
        bar.setObjectName("menuBar")
        top = QHBoxLayout(bar)
        top.setContentsMargins(12, 2, 10, 2)
        brand = QLabel("AirCursor")
        brand.setObjectName("brand")
        top.addWidget(brand)
        self.spaces = button("Workspace  1", self.space_menu)
        top.addWidget(self.spaces)
        top.addWidget(button("Overview", self.overview))
        top.addStretch()
        top.addWidget(button("Control Center", lambda: self.open("Control Center")))
        top.addWidget(button("Lock", self.lock_now))
        self.full_button = button("Full screen", self.fullscreen)
        top.addWidget(self.full_button)
        layout.addWidget(bar)
        middle = QHBoxLayout()
        middle.setContentsMargins(30, 28, 24, 8)
        left = QVBoxLayout()
        self.date = label("")
        self.date.setObjectName("date")
        self.clock = label("")
        self.clock.setObjectName("clock")
        left.addWidget(self.date)
        left.addWidget(self.clock)
        self.icons = DesktopIcons(self)
        left.addWidget(self.icons)
        left.addStretch()
        middle.addLayout(left)
        middle.addStretch()
        right = QVBoxLayout()
        self.widget = QFrame()
        self.widget.setFixedWidth(234)
        self.widget.setObjectName("glass")
        form = QVBoxLayout(self.widget)
        form.setContentsMargins(17, 14, 17, 15)
        form.addWidget(label("Connection"))
        self.net_label = label(self.network_text)
        form.addWidget(self.net_label)
        form.addWidget(label("AirBoost"))
        self.boost_label = label("")
        form.addWidget(self.boost_label)
        right.addWidget(self.widget)
        right.addStretch()
        middle.addLayout(right)
        layout.addLayout(middle, 1)
        self.toast = label("")
        self.toast.setObjectName("toast")
        self.toast.hide()
        layout.addWidget(self.toast, alignment=Qt.AlignmentFlag.AlignHCenter)
        self.dock = QFrame()
        self.dock.setObjectName("dock")
        self.dock_layout = QHBoxLayout(self.dock)
        self.dock_layout.setContentsMargins(8, 2, 8, 2)
        self.dock_layout.setSpacing(0)
        layout.addWidget(self.dock, alignment=Qt.AlignmentFlag.AlignHCenter)
        self.build_dock()
        self.style_workspace()
        self.surface.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.surface.customContextMenuRequested.connect(self.desktop_menu)
        if sys.platform == "win32" and self.s.get("remote_on") and not self.host.running:
            try:
                self.host.start()
            except OSError as exc:
                self.notify(str(exc))
        if self.s.get("auto_switch", True):
            self.auto.start()
        self.profile(self.s.get("profile", "Balanced"), save=False)
        self.tick()
        if self.s.get("restore", True):
            restored = dict(self.opened or self.s.get("session_panels", {}))
            for name, space in restored.items():
                if name in self.names and space == self.space:
                    self.open(name)
                elif name in self.names:
                    self.opened[name] = space

    def build_dock(self):
        while self.dock_layout.count():
            self.dock_layout.takeAt(0).widget().deleteLater()
        names = ["Files", "Air Browser", "Air Store", "Air AI", "Launcher", "Settings"]
        names.extend(n for n in self.opened if n not in names)
        for name in names:
            action = DockButton(name, lambda checked=False, n=name: self.open(n), self.effects)
            if name in self.opened:
                action.setToolTip(name + " · running")
                action.setStyleSheet("border-bottom:2px solid #a3d9cd")
            self.dock_layout.addWidget(action)
        for path in self.s.get("pins", []):
            pin = button(Path(path).stem[:15], lambda p=path: open_path(self, p))
            pin.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
            pin.customContextMenuRequested.connect(lambda pos, p=path: self.unpin(p))
            self.dock_layout.addWidget(pin)
        self.fit_dock()

    def fit_dock(self):
        size = max(49, min(82, (self.width()-65)//max(1, self.dock_layout.count())))
        for i in range(self.dock_layout.count()):
            item = self.dock_layout.itemAt(i).widget()
            item.setFixedWidth(size)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if not self.locked and hasattr(self, "dock_layout"):
            self.fit_dock()
            self.widget.setVisible(self.width() > 740)

    def pin(self, path):
        self.s.set(pins=list(dict.fromkeys([path] + self.s.get("pins", [])))[:4])
        self.build_dock()

    def unpin(self, path):
        if confirm(self, "Unpin shortcut", f"Remove {Path(path).stem} from the Dock?"):
            self.s.set(pins=[p for p in self.s.get("pins", []) if p != path])
            self.build_dock()

    def effects(self):
        return PROFILES.get(self.s.get("profile"), PROFILES["Balanced"])[1]

    def preference(self, key, value):
        self.s.set(**{key: value})
        if key == "clipboard" and not value:
            self.clipboard.clear()
        if key == "browser_history_enabled" and not value:
            self.s.set(browser_history=[])
        self.style_workspace()

    def style_workspace(self):
        mode = self.s.get("appearance", "Automatic")
        light = mode == "Light" or mode == "Automatic" and QApplication.styleHints().colorScheme() == Qt.ColorScheme.Light
        self.surface.light = light
        self.surface.scene = self.s.get("wallpaper", "Fjord")
        self.surface.update()
        bg, fg, field, border = ("#ecf3f1", "#19343c", "#fcfdfb", "#bbcecb") if light else (
                                  "#192c34", "#eaf3f0", "#223943", "#435e65")
        QApplication.instance().setStyleSheet(f"""
            QWidget {{ color:{fg};font-family:'Segoe UI','DejaVu Sans';font-size:13px }}
            QMainWindow,QDialog,QTabWidget::pane,QFrame#panel,QMenu {{background:{bg}}}
            QLabel,QWidget#desktopIcons {{background:transparent}}
            QLineEdit,QPlainTextEdit,QListWidget,QTreeView,QListView,QComboBox {{background:{field};border:1px solid {border};border-radius:6px;padding:5px}}
            QPushButton,QToolButton,QTabBar::tab {{background:{field};border:1px solid {border};border-radius:7px;padding:6px 10px}}
            QPushButton:hover,QToolButton:hover,QMenu::item:selected,QTabBar::tab:selected {{background:#486e74;color:white}}
            QFrame#menuBar,QFrame#dock,QFrame#glass,QFrame#lockCard {{background:rgba(18,41,47,205);border:1px solid rgba(223,255,246,67);border-radius:13px}}
            QFrame#menuBar QPushButton,QToolButton#dockButton {{background:transparent;border:0;color:#f1faf9}}
            QFrame#menuBar QPushButton:hover,QToolButton#dockButton:hover {{background:rgba(230,255,250,28)}}
            QFrame#menuBar QLabel,QFrame#glass QLabel,QFrame#lockCard QLabel {{color:#f2f9f4}}
            QLabel#brand {{font-weight:700;padding-right:10px}}
            QLabel#clock {{font-size:70px;font-weight:300;color:#f4fff8}}
            QLabel#date {{font-size:16px;color:#e1f2e9}}
            QLabel#heading {{font-size:23px;font-weight:600}}
            QLabel#toast {{background:#183039;color:white;border-radius:7px;padding:8px 15px}}
            QListWidget#desktopIcons {{background:transparent;border:0;color:white;padding:0;font-weight:600}}
            QListWidget#desktopIcons::item {{padding:5px;border-radius:7px}}
            QListWidget#desktopIcons::item:selected {{background:rgba(202,240,233,48)}}
            QToolButton#dockButton {{font-size:11px;padding:1px}}
            QMenu::item {{padding:6px 16px}}
        """)

    def profile(self, value, save=True):
        if value not in PROFILES:
            value = "Balanced"
        if save:
            self.s.set(profile=value)
        self.auto.every = self.probe_interval()
        self.timer.start(PROFILES[value][0]*1000)

    def tick(self):
        if self.locked:
            return
        now = datetime.now()
        self.clock.setText(now.strftime("%H:%M"))
        self.date.setText(now.strftime("%A, %d %B"))
        self.surface.phase = now.hour if self.effects() else 12
        self.surface.update()
        ram = psutil.virtual_memory()
        cpu = psutil.cpu_percent()
        io = psutil.net_io_counters()
        t = time.monotonic()
        self.boost_label.setText(f"{self.s.get('profile','Balanced')}\nCPU {cpu:.0f}%   ·   RAM {ram.percent:.0f}%")
        disk = shutil.disk_usage(Path.home())
        try:
            battery = psutil.sensors_battery()
        except (OSError, NotImplementedError):
            battery = None
        battery_text = f"{battery.percent:.0f}%" if battery else "Unavailable"
        rates = ""
        if self.usage and self.usage[0] and io:
            old, stamp = self.usage
            dt = max(0.001, t-stamp)
            rates = f"\n↓ {max(0,io.bytes_recv-old.bytes_recv)/dt/1024:.1f} KiB/s   ↑ {max(0,io.bytes_sent-old.bytes_sent)/dt/1024:.1f} KiB/s"
        self.usage = io, t
        self.metrics_text = (f"CPU {cpu:.0f}% · RAM {ram.used/2**30:.1f} / {ram.total/2**30:.1f} GiB\n"
            f"Disk free {disk.free/2**30:.1f} GiB · Battery {battery_text}" + rates)
        if not self.net_pending and t-self.last_net >= self.probe_interval():
            self.net_pending = True
            def check():
                info = self.hot.wifi()
                health = self.hot.health
                if not (self.s.get("auto_switch", True) and self.hot.phone_ssid):
                    self.hot.check_internet()
                    health = self.hot.health
                state = health.state if health else "Checking internet…"
                latency = f" · {health.latency_ms} ms" if health and health.latency_ms is not None else ""
                return (f"{info['ssid']}  {info['signal']}\n" if info and info["ssid"] else "") + state + latency, local_addresses()
            def done(result):
                self.net_pending = False
                self.last_net = time.monotonic()
                self.network_text, self.addresses = result
                if not self.locked:
                    self.net_label.setText(self.network_text)
                    self.refresh_control()
            self.work.run(check, done, lambda err: done((err, [])))
        self.refresh_control()

    def refresh_control(self):
        panel = self.panels.get("Control Center")
        if panel and not self.locked:
            panel.content.refresh()

    def notify(self, message):
        if self.locked:
            return
        self.messages = ([message] + self.messages)[:50]
        previous = self.toast
        previous.setText(message)
        previous.show()
        QTimer.singleShot(5500, previous, previous.hide)

    def persist(self):
        self.s.set(session_panels=dict(self.opened))

    def open(self, name):
        if self.locked or name not in self.names:
            return
        if name == "Overview":
            self.overview()
            return
        if name not in self.panels:
            if name == "Air Browser":
                from .browser import Browser
                content = Browser(self.s)
            elif name == "Files":
                from .files import Files
                content = Files(self.s)
            elif name == "Air Store":
                from .store import Store
                content = Store(lambda: self.open("Launcher"))
            elif name == "Air AI":
                from .intelligence import Intelligence
                content = Intelligence(self.s, self.browse, self.terminal)
            elif name == "Settings":
                content = Preferences(self)
            elif name == "Launcher":
                content = Launcher(self)
            else:
                content = Control(self)
            panel = self.panels[name] = Panel(self, name, content)
            area = self.screen().availableGeometry()
            panel.resize(min(850, area.width()-22), min(590, area.height()-25))
            panel.move(area.center()-panel.rect().center()+QPoint(9*len(self.panels), 5*len(self.panels)))
        panel = self.panels[name]
        self.opened[name] = self.space
        if self.s.get("stage", False):
            for other in self.panels.values():
                if other != panel:
                    other.hide()
        if panel.isMinimized():
            panel.showNormal()
        panel.show()
        panel.raise_()
        panel.activateWindow()
        if name == "Launcher":
            panel.content.query.setFocus()
        self.build_dock()
        self.persist()
        return panel.content

    def panel_closed(self, name):
        self.opened.pop(name, None)
        if not self.locked:
            self.build_dock()
            self.persist()

    def browse(self, url):
        self.open("Air Browser").add(url)

    def terminal(self):
        if os.name == "nt":
            try:
                subprocess.Popen([os.environ.get("COMSPEC", "cmd.exe")], cwd=Path.home(),
                                 creationflags=subprocess.CREATE_NEW_CONSOLE)
            except OSError as exc:
                self.notify(str(exc))
        else:
            self.notify("The Windows coding terminal is available on Windows.")

    def add_shortcut(self):
        value, _ = QFileDialog.getOpenFileName(self, "Add desktop shortcut")
        if value:
            self.s.set(desktop_items=list(dict.fromkeys(self.s.get("desktop_items", ["Files","Air Browser"])+[value])))
            self.icons.load()

    def fullscreen(self):
        if not self.locked:
            if self.isFullScreen():
                self.exit_desktop()
            else:
                self.showFullScreen()
                self.full_button.setText("Exit desktop  ·  Esc")

    def exit_desktop(self):
        if self.isFullScreen():
            self.showNormal()
            self.full_button.setText("Full screen")

    def space_menu(self):
        menu = QMenu(self)
        for n in (1, 2):
            menu.addAction(f"Workspace {n}", lambda n=n: self.switch_space(n))
        menu.addAction("Notifications", lambda: self.list_dialog("Notifications", self.messages))
        menu.addAction("Clipboard history", self.clip_history)
        menu.addAction("Exit desktop mode", self.exit_desktop)
        menu.exec(self.spaces.mapToGlobal(self.spaces.rect().bottomLeft()))

    def switch_space(self, n):
        if self.locked:
            return
        self.space = n
        self.spaces.setText(f"Workspace  {n}")
        for panel in self.panels.values():
            panel.hide()
        for name, space in list(self.opened.items()):
            if space == n:
                self.open(name)

    def overview(self):
        if self.locked:
            return
        values = [f"{name}   ·   Workspace {space}" for name, space in self.opened.items()]
        val = self.list_dialog("Window overview", values)
        if val:
            name = val.split("   ·")[0]
            self.switch_space(self.opened[name])
            self.open(name)

    def list_dialog(self, title, values):
        if not values:
            self.notify(title + " is empty.")
            return
        value, ok = QInputDialog.getItem(self, title, title, values, editable=False)
        if ok:
            return value

    def remember_clip(self):
        if not self.locked and self.s.get("clipboard", False):
            mime = QApplication.clipboard().mimeData()
            if mime and mime.hasText() and not any("excludeclipboard" in f.lower() or "canincludeinclipboardhistory" in f.lower() for f in mime.formats()):
                text = mime.text()
                if 0 < len(text) <= 2048:
                    self.clipboard = ([text] + [s for s in self.clipboard if s != text])[:20]

    def clip_history(self):
        value = self.list_dialog("Clipboard history", self.clipboard)
        if value:
            QApplication.clipboard().setText(value)

    def desktop_menu(self, pos):
        menu = QMenu(self)
        menu.addAction("Add desktop shortcut…", self.add_shortcut)
        menu.addAction("Change wallpaper…", lambda: self.open("Settings"))
        menu.addAction("Window overview", self.overview)
        menu.addAction("Exit desktop mode", self.exit_desktop)
        menu.exec(self.surface.mapToGlobal(pos))

    def closeEvent(self, event):
        if not self.locked:
            self.persist()
        self.locked = True
        self.timer.stop()
        self.auto.stop()
        self.host.stop()
        for panel in self.panels.values():
            panel.hide()
        event.accept()
        QApplication.quit()


def main():
    app = QApplication.instance() or QApplication(sys.argv)
    app.setApplicationName("AirCursor")
    app.setOrganizationName("AirCursor")
    app.setWindowIcon(QIcon(asset("aircursor.ico")))
    try:
        window = Desktop()
    except (OSError, ValueError) as exc:
        QMessageBox.critical(None, "AirCursor settings", f"Could not read your saved settings.\n{exc}")
        return 1
    app.clipboard().dataChanged.connect(window.remember_clip)
    app.styleHints().colorSchemeChanged.connect(lambda _: window.style_workspace())
    window.show()
    return app.exec()
