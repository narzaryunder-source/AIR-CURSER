"""Windows service lifecycle and native application windows, with a Qt Quick desktop surface."""

import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import psutil
from PySide6.QtCore import QByteArray, Qt, QPoint, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QIcon, QKeySequence, QShortcut
from PySide6.QtQuickWidgets import QQuickWidget
from PySide6.QtWidgets import (
    QApplication,
    QDialog,
    QFileDialog,
    QInputDialog,
    QMainWindow,
    QMessageBox,
)
from .chrome import Panel, stylesheet
from .control import Control, Preferences, PROFILES
from .design import ICONS, MaterialProvider
from .hotspot import AutoSwitch, Hotspot
from .inject import Controller, backend
from .server import RemoteHost
from .settings import Settings
from .surface import Shell
from . import session
from .widgets import Workers, asset


class Desktop(QMainWindow):
    message = Signal(str)
    receiver_changed = Signal()
    names = [n for n in ICONS if n not in ("Terminal", "Downloads")]

    def __init__(self, settings=None, *, safe_mode=False):
        super().__init__()
        self.s = settings or Settings()
        self.safe_mode = safe_mode
        self.restore_enabled = not safe_mode and self.s.get("restore", True)
        self.hot = Hotspot(self.s)
        self.windows_away = False
        self.windows_suspended = False
        self.startup_pending = True
        self.host = RemoteHost(Controller(backend()), self.receiver_changed.emit)
        self.host.ctl.speed = self.s.get("pointer_speed", 1.0)
        self.host.ctl.natural_scroll = self.s.get("natural_scroll", True)
        self.host.paused = True
        self.auto = AutoSwitch(self.hot, self.message.emit, every=self.probe_interval())
        self.work = Workers(self)
        self.panels, self.opened, self.clipboard, self.messages = {}, {}, [], []
        self.locked = True
        self.space = 2 if self.restore_enabled and self.s.get("active_space") == 2 else 1
        geometry = self.s.get("panel_geometry", {})
        self.saved_geometry = dict(geometry) if self.restore_enabled and isinstance(geometry, dict) else {}
        self.net_pending = False
        self.last_net = -1000
        self.metrics_pending = False
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
        self.layout_save_timer = QTimer(self, interval=300, singleShot=True)
        self.layout_save_timer.timeout.connect(self.save_layout)
        self.message.connect(self.notify)
        self.receiver_changed.connect(self.refresh_control)
        self.setWindowTitle("AirCursor · Safe Mode" if safe_mode else "AirCursor")
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
            ("Ctrl+Shift+L", self.lock_windows),
            ("Ctrl+1", lambda: self.switch_space(1)),
            ("Ctrl+2", lambda: self.switch_space(2)),
        ]:
            QShortcut(QKeySequence(key), self, activated=fn)
        self.apply_style()
        self.timer.start(15000)
        self.session_watching = False
        self.security_timer = QTimer(self, interval=1000)
        if os.name == "nt":
            self.session_watching = session.watch(int(self.winId()), True)
            self.security_timer.timeout.connect(self.check_windows_session)
            self.security_timer.start()

    def showEvent(self, event):
        super().showEvent(event)
        if self.startup_pending:
            self.startup_pending = False
            self.check_windows_session()

    def check_windows_session(self):
        if self.startup_pending:
            return
        if self.windows_away or self.windows_suspended:
            self.suspend_workspace()
            return
        interactive = session.interactive_desktop()
        if self.locked and interactive:
            self.workspace()
        elif not self.locked:
            paused = not interactive or (os.name == "nt" and not self.session_watching)
            if paused != self.host.paused:
                self.host.paused = paused

    def handle_session_message(self, message, event):
        if message == session.SESSION_CHANGE:
            if event in session.SESSION_AWAY:
                self.windows_away = True
            elif event in session.SESSION_BACK:
                self.windows_away = False
            else:
                return
        elif message == session.POWER_CHANGE:
            if event == session.POWER_SUSPEND:
                self.windows_suspended = True
            elif event in session.POWER_RESUME:
                self.windows_suspended = False
            else:
                return
        else:
            return
        self.check_windows_session()

    def nativeEvent(self, event_type, message):
        if os.name == "nt" and event_type == b"windows_generic_MSG" and hasattr(self, "security_timer"):
            msg = wintypes.MSG.from_address(int(message))
            self.handle_session_message(msg.message, msg.wParam)
        return super().nativeEvent(event_type, message)

    def lock_windows(self):
        if session.lock_workstation():
            self.windows_away = self.session_watching
            self.suspend_workspace()
        else:
            self.notify("Windows could not lock the current workstation.")

    def apply_style(self):
        QApplication.instance().setStyleSheet(stylesheet(self.shell.dark))
        self.shell.changed.emit()

    def probe_interval(self):
        return 60 if self.s.get("profile") in ("Battery Saver", "Mobile Hotspot") else 15

    def dismiss_dialogs(self):
        for dialog in self.findChildren(QDialog):
            if dialog.isVisible():
                dialog.reject()

    def suspend_workspace(self):
        if self.locked:
            return
        self.host.paused = True
        self.locked = True
        self.auto.stop()
        self.layout_save_timer.stop()
        # Block local entry and phone input before running cleanup that can touch the disk.
        self.shell.lockedChanged.emit()
        self.dismiss_dialogs()
        for panel in self.panels.values():
            panel.hide()
        try:
            self.persist()
        except OSError:
            self.shell.feedback.emit("AirCursor locked, but Windows could not save the session state.")
        self.clipboard.clear()
        self.material.previews.clear()
        self.external = []
        self.shell.previews.clear()
        self.shell.appsChanged.emit()
        self.shell.file_timer.stop()
        self.shell.file_query, self.shell.file_results = None, []
        if "Air Browser" in self.panels:
            self.panels["Air Browser"].content.close_private()

    def workspace(self):
        self.locked = False
        self.host.paused = not self.session_watching and os.name == "nt"
        self.shell.lockedChanged.emit()
        if (
            not self.safe_mode
            and sys.platform == "win32"
            and self.session_watching
            and self.s.get("remote_on")
            and not self.host.running
        ):
            try:
                self.host.start()
            except OSError as exc:
                self.notify(str(exc))
        if not self.safe_mode and self.s.get("auto_switch", True):
            self.auto.start()
        self.profile(self.s.get("profile", "Balanced"), save=False)
        self.tick()
        if not self.safe_mode and self.s.get("restore", True):
            self.restore_panels()
        elif self.safe_mode:
            self.notify("Safe Mode is active. Saved windows and tabs will be available on a normal launch.")
        self.shell.appsChanged.emit()

    def restore_panels(self):
        saved = self.opened or self.s.get("session_panels", {})
        if not isinstance(saved, dict):
            self.notify("AirCursor skipped an unreadable workspace layout.")
            return
        self.opened = {
            name: space for name, space in saved.items() if name in self.names and space in (1, 2)
        }
        for name, space in list(self.opened.items()):
            if space == self.space:
                try:
                    self.open(name)
                except (OSError, TypeError, ValueError):
                    self.opened.pop(name, None)
                    self.notify(f"AirCursor skipped {name}. Open it again from the Dock.")

    def effects(self):
        return not self.safe_mode and PROFILES.get(self.s.get("profile"), PROFILES["Balanced"])[1]

    def preference(self, key, value):
        self.s.set(**{key: value})
        if key == "clipboard" and not value:
            self.clipboard.clear()
        if key == "browser_history_enabled" and not value:
            self.s.set(browser_history=[])
        self.apply_style()
        self.shell.appsChanged.emit()

    def save_layout(self):
        if not self.locked:
            try:
                self.persist()
            except OSError:
                self.notify("AirCursor could not save the workspace layout.")

    def persist(self):
        if self.safe_mode:
            return
        for name, panel in self.panels.items():
            if name in self.opened:
                self.saved_geometry[name] = bytes(panel.saveGeometry().toHex()).decode("ascii")
        state = dict(
            session_panels=dict(self.opened), active_space=self.space, panel_geometry=self.saved_geometry
        )
        if "Files" in self.opened and "Files" in self.panels:
            state["files_session"] = self.panels["Files"].content.session()
        if any(self.s.get(key) != value for key, value in state.items()):
            self.s.set(**state)

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
        self.layout_save_timer.start()
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

    @staticmethod
    def sample_metrics():
        ram = psutil.virtual_memory()
        cpu = psutil.cpu_percent(interval=0.15)
        io = psutil.net_io_counters()
        disk = shutil.disk_usage(Path.home())
        try:
            battery = psutil.sensors_battery()
        except (OSError, NotImplementedError):
            battery = None
        return ram, cpu, io, disk, battery, time.monotonic()

    def metrics_ready(self, data):
        self.metrics_pending = False
        if data is None or self.locked:
            return
        ram, cpu, io, disk, battery, t = data
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
        self.refresh_control()

    def tick(self):
        self.shell.changed.emit()
        if self.locked:
            return
        t = time.monotonic()
        if not self.metrics_pending:
            self.metrics_pending = True
            self.work.run(self.sample_metrics, self.metrics_ready, lambda _: self.metrics_ready(None))
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
                    (f"{info['ssid']}  {info['signal']}\n" if info and info["ssid"] else "")
                    + state
                    + latency
                )

            def done(result):
                self.net_pending = False
                self.last_net = time.monotonic()
                self.network_text = result
                if not self.locked:
                    self.refresh_control()

            self.work.run(check, done, done)
        if (
            os.name == "nt"
            and not self.window_job
            and t - self.last_windows
            >= max(6, PROFILES.get(self.s.get("profile"), PROFILES["Balanced"])[0])
        ):
            from .windows import list_windows

            self.window_job = True

            def update(items):
                self.window_job = False
                self.last_windows = time.monotonic()
                if self.locked:
                    return
                previous = self.shell.dock_windows(self.external)
                self.external = items
                if previous != self.shell.dock_windows(items):
                    self.shell.appsChanged.emit()

            self.work.run(list_windows, update, lambda _: update([]))

    def open(self, name):
        if self.locked or name not in self.names:
            return
        if name in ("Overview", "Launcher"):
            self.show_overlay("overview" if name == "Overview" else "spotlight")
            return
        if name not in self.panels:
            if name == "Air Browser":
                from .browser import Browser

                content = Browser(self.s, recovery_mode=self.safe_mode)
            elif name == "Files":
                from .files import Files

                content = Files(self.s)
                if not self.safe_mode and self.s.get("restore", True) and name in self.opened:
                    content.restore_session(self.s.get("files_session", {}))
            elif name == "Air Store":
                from .store import Store

                content = Store(lambda: setattr(self.shell, "index", None))
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
            geometry = self.saved_geometry.get(name)
            if isinstance(geometry, str) and len(geometry) < 4096 and geometry.isascii():
                panel.restoreGeometry(QByteArray.fromHex(geometry.encode("ascii")))
        panel = self.panels[name]
        newly_open = name not in self.opened
        self.opened[name] = self.space
        if self.s.get("stage", False):
            for other in self.panels.values():
                if other != panel:
                    other.hide()
        panel.present()
        if newly_open:
            self.shell.appsChanged.emit()
        self.layout_save_timer.start()
        return panel.content

    def panel_closed(self, name):
        self.opened.pop(name, None)
        if name == "Air Browser":
            self.panels[name].content.close_private()
        if not self.locked:
            self.shell.appsChanged.emit()
            self.layout_save_timer.start()

    def browse(self, url):
        browser = self.open("Air Browser")
        if browser is not None:
            browser.add(url)

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
            try:
                self.persist()
                if "Air Browser" in self.panels:
                    self.panels["Air Browser"].content.save()
            except OSError:
                if sys.stderr is not None:
                    sys.stderr.write("AirCursor could not save the session before closing.\n")
        self.locked = True
        self.timer.stop()
        self.layout_save_timer.stop()
        self.security_timer.stop()
        if self.session_watching:
            session.watch(int(self.winId()), False)
        self.auto.stop()
        self.host.stop()
        for panel in self.panels.values():
            panel.hide()
        self.view.setSource(QUrl())
        event.accept()
        QApplication.quit()


def main(*, safe_mode=False):
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
        window = Desktop(safe_mode=True) if safe_mode else Desktop()
    except (OSError, ValueError) as exc:
        QMessageBox.critical(None, "AirCursor settings", f"Could not read your saved settings.\n{exc}")
        return 1
    app.clipboard().dataChanged.connect(window.remember_clip)
    app.styleHints().colorSchemeChanged.connect(lambda _: window.apply_style())
    window.show()
    return app.exec()
