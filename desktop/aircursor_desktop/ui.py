"""Windows service lifecycle and native application windows, with a Qt Quick desktop surface."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

import psutil
from PySide6.QtCore import Qt, QPoint, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QIcon, QKeySequence, QShortcut
from PySide6.QtQuickWidgets import QQuickWidget
from PySide6.QtWidgets import QApplication, QFileDialog, QInputDialog, QMainWindow, QMessageBox
from .chrome import Panel, stylesheet
from .control import Control, Preferences, PROFILES
from .design import ICONS, MaterialProvider
from .hotspot import AutoSwitch, Hotspot
from .inject import Controller, backend
from .lock import PasswordLock
from .server import RemoteHost, local_addresses
from .settings import Settings
from .surface import Shell
from . import trust
from .widgets import Workers, asset, confirm


class Desktop(QMainWindow):
    message = Signal(str)
    receiver_changed = Signal()
    names = [n for n in ICONS if n not in ("Terminal", "Downloads")]

    def __init__(self, settings=None):
        super().__init__()
        self.s = settings or Settings()
        self.auth, self.hot = PasswordLock(self.s), Hotspot(self.s)
        self.host = RemoteHost(Controller(backend()), self.receiver_changed.emit)
        self.host.ctl.speed = self.s.get("pointer_speed", 1.0)
        self.host.ctl.natural_scroll = self.s.get("natural_scroll", True)
        self.host.paused = True
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
        self.battery = ""
        self.external = []
        self.last_windows = 0
        self.window_job = False
        self.network_start = None
        self.usage = None
        self.busy_until = 0
        self.hot.traffic_busy = lambda: time.monotonic() < self.busy_until
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.message.connect(self.notify)
        self.receiver_changed.connect(self.refresh_control)
        self.setWindowTitle("AirCursor")
        self.setWindowIcon(QIcon(asset("aircursor.ico")))
        self.resize(1180, 740)
        self.setMinimumSize(640, 450)
        self.shell = Shell(self)
        self.material = MaterialProvider()
        self.view = QQuickWidget(self)
        self.view.setResizeMode(QQuickWidget.ResizeMode.SizeRootObjectToView)
        self.view.setClearColor(QColor("#203445"))
        self.view.engine().addImageProvider("material", self.material)
        self.view.rootContext().setContextProperty("shell", self.shell)
        self.view.setSource(QUrl.fromLocalFile(str(Path(__file__).parent / "qml/Shell.qml")))
        if self.view.status() == QQuickWidget.Status.Error:
            raise ValueError("\n".join(e.toString() for e in self.view.errors()))
        self.setCentralWidget(self.view)
        for key, fn in [
            ("F11", self.fullscreen),
            ("Ctrl+Space", lambda: self.show_overlay("spotlight")),
            ("Ctrl+Shift+L", self.lock_now),
            ("Ctrl+1", lambda: self.switch_space(1)),
            ("Ctrl+2", lambda: self.switch_space(2)),
        ]:
            QShortcut(QKeySequence(key), self, activated=fn)
        self.apply_style()
        self.timer.start(15000)
        if trust.active(self.s):
            self.workspace()

    def apply_style(self):
        QApplication.instance().setStyleSheet(stylesheet(self.shell.dark))
        self.shell.changed.emit()

    def probe_interval(self):
        return 60 if self.s.get("profile") in ("Battery Saver", "Mobile Hotspot") else 15

    def unlock(self, password, again, remember):
        if not self.locked:
            return
        try:
            if not self.auth.has_password:
                self.auth.set_password(password, again)
            elif not self.auth.check(password):
                wait = self.auth.wait_left()
                self.shell.feedback.emit(f"Try again in {wait} seconds." if wait else "Incorrect password.")
                return
            if remember and os.name == "nt":
                trust.remember(self.s)
        except (ValueError, OSError) as exc:
            self.shell.feedback.emit(str(exc))
            return
        self.workspace()

    def reset(self):
        if not confirm(
            self,
            "Reset AirCursor",
            "Remove the password, paired hotspot, saved settings and chats? A fresh browser profile will open on the next sign-in.",
        ):
            return
        try:
            if self.hot.phone_ssid:
                self.hot.forget_phone()
            if os.name == "nt":
                from .intelligence import credential

                credential("OpenRouter", "")
            self.s.clear()
            self.s.set(browser_profile=uuid.uuid4().hex)
        except OSError as exc:
            self.shell.feedback.emit(str(exc))
            return
        self.close()

    def lock_now(self):
        if self.locked:
            return
        self.host.paused = True
        self.locked = True
        self.auto.stop()
        self.persist()
        self.s.set(trusted_logon=None)
        self.clipboard.clear()
        self.material.previews.clear()
        self.external = []
        self.shell.previews.clear()
        if "Air Browser" in self.panels:
            self.panels["Air Browser"].content.close_private()
        for panel in self.panels.values():
            panel.hide()
        self.shell.lockedChanged.emit()
        self.raise_()
        self.activateWindow()

    def workspace(self):
        self.locked = False
        self.host.paused = False
        self.shell.lockedChanged.emit()
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
            for name, space in dict(self.opened or self.s.get("session_panels", {})).items():
                if name in self.names and space == self.space:
                    self.open(name)
                elif name in self.names:
                    self.opened[name] = space
        self.shell.appsChanged.emit()

    def effects(self):
        return PROFILES.get(self.s.get("profile"), PROFILES["Balanced"])[1]

    def preference(self, key, value):
        self.s.set(**{key: value})
        if key == "clipboard" and not value:
            self.clipboard.clear()
        if key == "browser_history_enabled" and not value:
            self.s.set(browser_history=[])
        self.apply_style()
        self.shell.appsChanged.emit()

    def persist(self):
        self.s.set(session_panels=dict(self.opened))

    def notify(self, message):
        if not self.locked:
            self.messages = ([message] + self.messages)[:50]
            self.shell.feedback.emit(message)
            self.shell.changed.emit()

    def refresh_control(self):
        if not self.locked:
            self.shell.changed.emit()
            if "Control Center" in self.panels:
                self.panels["Control Center"].content.refresh()

    def show_overlay(self, name):
        if self.locked:
            return
        self.raise_()
        self.activateWindow()
        self.view.setFocus()
        self.shell.overlayRequested.emit(name)

    def pin(self, path):
        self.s.set(pins=list(dict.fromkeys([path] + self.s.get("pins", [])))[:4])
        self.shell.appsChanged.emit()

    def unpin(self, path):
        self.s.set(pins=[p for p in self.s.get("pins", []) if p != path])
        self.shell.appsChanged.emit()

    def fullscreen(self):
        if not self.locked:
            (self.showNormal if self.isFullScreen() else self.showFullScreen)()
            self.shell.changed.emit()

    def exit_desktop(self):
        if self.isFullScreen():
            self.showNormal()
            self.shell.changed.emit()

    def switch_space(self, n):
        if self.locked:
            return
        self.space = n
        for panel in self.panels.values():
            panel.hide()
        for name, space in list(self.opened.items()):
            if space == n:
                self.open(name)
        self.shell.changed.emit()

    def list_dialog(self, title, values):
        if not values:
            self.notify(title + " is empty.")
            return
        value, ok = QInputDialog.getItem(self, title, title, values, editable=False)
        if ok:
            return value

    def profile(self, value, save=True):
        if value not in PROFILES:
            value = "Balanced"
        if save:
            self.s.set(profile=value)
        self.auto.every = self.probe_interval()
        self.timer.start(PROFILES[value][0] * 1000)

    def tick(self):
        self.shell.changed.emit()
        if self.locked:
            return
        ram = psutil.virtual_memory()
        cpu = psutil.cpu_percent()
        io = psutil.net_io_counters()
        t = time.monotonic()
        disk = shutil.disk_usage(Path.home())
        try:
            battery = psutil.sensors_battery()
        except (OSError, NotImplementedError):
            battery = None
        battery_text = f"{battery.percent:.0f}%" if battery else "Unavailable"
        self.battery = battery_text if battery else ""
        rates = ""
        if self.usage and self.usage[0] and io:
            old, stamp = self.usage
            dt = max(0.001, t - stamp)
            if max(io.bytes_recv - old.bytes_recv, io.bytes_sent - old.bytes_sent) / dt > 128 * 1024:
                self.busy_until = t + 15
            rates = f"\n↓ {max(0, io.bytes_recv - old.bytes_recv) / dt / 1024:.1f} KiB/s   ↑ {max(0, io.bytes_sent - old.bytes_sent) / dt / 1024:.1f} KiB/s"
        self.usage = io, t
        if io and self.network_start is None:
            self.network_start = io
        if io and self.network_start:
            rates += f"\nThis session · received {max(0, io.bytes_recv - self.network_start.bytes_recv) / 2**20:.1f} MiB · sent {max(0, io.bytes_sent - self.network_start.bytes_sent) / 2**20:.1f} MiB"
        self.metrics_text = (
            f"CPU {cpu:.0f}% · RAM {ram.used / 2**30:.1f} / {ram.total / 2**30:.1f} GiB\n"
            f"Disk free {disk.free / 2**30:.1f} GiB · Battery {battery_text}" + rates
        )
        if not self.net_pending and t - self.last_net >= self.probe_interval():
            self.net_pending = True

            def check():
                info = self.hot.wifi()
                health = self.hot.health
                if not (self.s.get("auto_switch", True) and self.hot.phone_ssid):
                    self.hot.check_internet()
                    health = self.hot.health
                state = health.state if health else "Checking internet…"
                latency = f" · {health.latency_ms} ms" if health and health.latency_ms is not None else ""
                return (
                    f"{info['ssid']}  {info['signal']}\n" if info and info["ssid"] else ""
                ) + state + latency, local_addresses()

            def done(result):
                self.net_pending = False
                self.last_net = time.monotonic()
                self.network_text, self.addresses = result
                if not self.locked:
                    self.shell.changed.emit()
                    self.refresh_control()

            self.work.run(check, done, lambda err: done((err, [])))
        if (
            os.name == "nt"
            and not self.window_job
            and t - self.last_windows >= max(6, PROFILES.get(self.s.get("profile"), PROFILES["Balanced"])[0])
        ):
            from .windows import list_windows

            self.window_job = True

            def update(items):
                self.window_job = False
                self.last_windows = time.monotonic()
                if self.external != items:
                    self.external = items
                    self.shell.appsChanged.emit()

            self.work.run(list_windows, update, lambda _: update([]))
        self.refresh_control()

    def open(self, name):
        if self.locked or name not in self.names:
            return
        if name in ("Overview", "Launcher"):
            self.show_overlay("overview" if name == "Overview" else "spotlight")
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

                content = Store(lambda: self.show_overlay("spotlight"))
            elif name == "Air AI":
                from .intelligence import Intelligence

                content = Intelligence(self.s, self.browse, self.terminal)
            elif name == "Settings":
                content = Preferences(self)
            else:
                content = Control(self)
            panel = self.panels[name] = Panel(self, name, content)
            area = self.screen().availableGeometry()
            nominal = (1040, 690) if name in ("Files", "Air Browser", "Air Store", "Air AI") else (810, 590)
            panel.resize(min(nominal[0], area.width() - 18), min(nominal[1], area.height() - 18))
            panel.move(
                area.center() - panel.rect().center() + QPoint(9 * len(self.panels), 5 * len(self.panels))
            )
        panel = self.panels[name]
        self.opened[name] = self.space
        if self.s.get("stage", False):
            for other in self.panels.values():
                if other != panel:
                    other.hide()
        panel.present()
        self.shell.appsChanged.emit()
        self.persist()
        return panel.content

    def panel_closed(self, name):
        self.opened.pop(name, None)
        if name == "Air Browser":
            self.panels[name].content.close_private()
        if not self.locked:
            self.shell.appsChanged.emit()
            self.persist()

    def browse(self, url):
        self.open("Air Browser").add(url)

    def terminal(self):
        if os.name == "nt":
            try:
                subprocess.Popen(
                    [os.environ.get("COMSPEC", "cmd.exe")],
                    cwd=Path.home(),
                    creationflags=subprocess.CREATE_NEW_CONSOLE,
                )
            except OSError as exc:
                self.notify(str(exc))
        else:
            self.notify("The Windows coding terminal is available on Windows.")

    def add_shortcut(self):
        value, _ = QFileDialog.getOpenFileName(self, "Add desktop shortcut")
        if value:
            self.s.set(
                desktop_items=list(
                    dict.fromkeys(self.s.get("desktop_items", ["Files", "Air Browser"]) + [value])
                )
            )
            self.shell.appsChanged.emit()

    def remember_clip(self):
        if not self.locked and self.s.get("clipboard", False):
            mime = QApplication.clipboard().mimeData()
            if (
                mime
                and mime.hasText()
                and not any(
                    "excludeclipboard" in f.lower() or "canincludeinclipboardhistory" in f.lower()
                    for f in mime.formats()
                )
            ):
                text = mime.text()
                if 0 < len(text) <= 2048:
                    self.clipboard = ([text] + [s for s in self.clipboard if s != text])[:20]

    def clip_history(self):
        value = self.list_dialog("Clipboard history", self.clipboard)
        if value:
            QApplication.clipboard().setText(value)

    def closeEvent(self, event):
        if not self.locked:
            self.persist()
            if "Air Browser" in self.panels:
                self.panels["Air Browser"].content.save()
        self.locked = True
        self.timer.stop()
        self.auto.stop()
        self.host.stop()
        for panel in self.panels.values():
            panel.hide()
        self.view.setSource(QUrl())
        event.accept()
        QApplication.quit()


def main():
    if QApplication.instance() is None:
        QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    app = QApplication.instance() or QApplication(sys.argv)
    app.setApplicationName("AirCursor")
    app.setOrganizationName("AirCursor")
    app.setWindowIcon(QIcon(asset("aircursor.ico")))
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes

        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateMutexW.restype = wintypes.HANDLE
        mutex = kernel.CreateMutexW(None, False, "Local\\AirCursor.Windows.Workspace")
        if not mutex:
            QMessageBox.critical(None, "AirCursor", "Windows could not open the workspace session.")
            return 1
        if ctypes.get_last_error() == 183:
            QMessageBox.information(
                None, "AirCursor", "Your AirCursor workspace is already open. Select it from the taskbar."
            )
            kernel.CloseHandle(wintypes.HANDLE(mutex))
            return 0
        app.aboutToQuit.connect(lambda: kernel.CloseHandle(wintypes.HANDLE(mutex)))
    try:
        window = Desktop()
    except (OSError, ValueError) as exc:
        QMessageBox.critical(None, "AirCursor settings", f"Could not read your saved settings.\n{exc}")
        return 1
    app.clipboard().dataChanged.connect(window.remember_clip)
    app.styleHints().colorSchemeChanged.connect(lambda _: window.apply_style())
    window.show()
    return app.exec()
