"""Narrow Qt Quick action boundary. The shell never executes program text or receives credentials from web content."""

from datetime import datetime
import os
from pathlib import Path
import time
from urllib.parse import quote

from PySide6.QtCore import QObject, Property, QUrl, Qt, Signal, Slot, QPoint, QTimer
from PySide6.QtWidgets import QApplication, QMenu
from .design import ICONS
from .launcher import calculate, indexed_files, shortcuts
from .widgets import asset, open_path, system_link


class Shell(QObject):
    changed = Signal()
    lockedChanged = Signal()
    appsChanged = Signal()
    resultsChanged = Signal()
    windowsChanged = Signal()
    feedback = Signal(str)
    overlayRequested = Signal(str)

    def __init__(self, desk):
        super().__init__(desk)
        self.d = desk
        self.found = []
        self.previews = []
        self.index = None
        self.query = ""
        self.file_query = None
        self.file_results = []
        self.file_pending = False
        self.file_timer = QTimer(self, singleShot=True, interval=350)
        self.file_timer.timeout.connect(self.find_files)
        self.covered = []

    @Property(str, constant=True)
    def assets(self):
        return QUrl.fromLocalFile(asset("") + os.sep).toString()

    @Property(bool, constant=True)
    def canTrust(self):
        return os.name == "nt"

    @Property(bool, notify=lockedChanged)
    def locked(self):
        return self.d.locked

    @Property(bool, notify=lockedChanged)
    def codeLogin(self):
        from .second_factor import alternative

        return alternative(self.d.s)

    @Property(bool, notify=lockedChanged)
    def first(self):
        return not self.d.auth.has_password

    @Property(bool, notify=changed)
    def dark(self):
        mode = self.d.s.get("appearance", "Automatic")
        if mode == "Automatic":
            return QApplication.styleHints().colorScheme() == Qt.ColorScheme.Dark
        return mode == "Dark"

    @Property(str, notify=changed)
    def scene(self):
        scene = self.d.s.get("wallpaper", "Fjord").lower()
        return scene if scene in ("fjord", "violet", "copper") else "fjord"

    @Property(float, notify=changed)
    def dusk(self):
        if not self.d.effects():
            return 0.0
        hour = datetime.now().hour
        return max(0.0, abs(hour - 12) - 4) / 65

    @Property(str, notify=changed)
    def clockLine(self):
        return datetime.now().strftime("%a %d %b   %H:%M")

    @Property(str, notify=changed)
    def timeLine(self):
        return datetime.now().strftime("%H:%M")

    @Property(str, notify=changed)
    def dateLine(self):
        return datetime.now().strftime("%A, %d %B")

    @Property(str, notify=changed)
    def network(self):
        return self.d.network_text.split("\n")[0]

    @Property(str, notify=changed)
    def battery(self):
        return self.d.battery

    @Property(str, notify=changed)
    def profileName(self):
        return self.d.s.get("profile", "Balanced")

    @Property(bool, notify=changed)
    def calendarVisible(self):
        return self.d.s.get("calendar_widget", False)

    @Property(str, notify=changed)
    def spaceName(self):
        return f"Workspace {self.d.space}"

    @Property(str, notify=changed)
    def receiver(self):
        return (
            "Connected"
            if self.d.host.client
            else "Waiting for phone"
            if self.d.host.running
            else "Receiver off"
        )

    @Property(bool, notify=changed)
    def fullscreen(self):
        return self.d.isFullScreen()

    @Property(bool, notify=changed)
    def motion(self):
        return self.d.effects()

    @Property("QStringList", notify=changed)
    def notes(self):
        return self.d.messages

    def art(self, name):
        return self.assets + "icons/" + ICONS.get(name, "folder") + ".svg"

    @Property("QVariantList", notify=appsChanged)
    def dock(self):
        names = [
            "Files",
            "Air Browser",
            "Air Store",
            "Air AI",
            "Launcher",
            "Terminal",
            "Settings",
            "Overview",
            "Downloads",
        ]
        names += [n for n in self.d.opened if n not in names]
        pins = self.d.s.get("pins", [])
        entries = [
            dict(
                target=n,
                label=Path(n).stem if n in pins else n,
                art=self.art(n),
                separator=n in ("Launcher", "Downloads"),
                running=n in self.d.opened,
            )
            for n in names + pins
        ]

        for process, handle, app, path in self.dock_windows(self.d.external):
            entries.append(
                dict(target=f"external:{handle}", label=app,
                     art=self.window_art(dict(pid=process, path=path)), separator=len(entries) == len(names + pins),
                     running=True)
            )
        return entries

    @staticmethod
    def dock_windows(windows):
        visible = {}
        for window in windows:
            process = window["pid"]
            visible.setdefault(process, (process, window["handle"], window["app"], window["path"]))
            if len(visible) == 3:
                break
        return list(visible.values())

    def window_art(self, window):
        from PySide6.QtCore import QFileInfo
        from PySide6.QtWidgets import QFileIconProvider

        key = "icon-" + str(window["pid"])
        if key not in self.d.material.previews and window["path"]:
            im = QFileIconProvider().icon(QFileInfo(window["path"])).pixmap(80, 80).toImage()
            if not im.isNull():
                self.d.material.previews[key] = im
        return "image://material/preview/" + key if key in self.d.material.previews else self.art("Overview")

    @Property("QVariantList", notify=appsChanged)
    def items(self):
        return [
            dict(
                target=k,
                label=k if k in ICONS else Path(k).name,
                icon="folder" if k in ("Downloads", "Files") else ICONS.get(k, "folder"),
            )
            for k in self.d.s.get("desktop_items", ["Files", "Downloads"])
        ]

    @Property("QVariantList", notify=resultsChanged)
    def results(self):
        return self.found

    @Property("QVariantList", notify=windowsChanged)
    def windows(self):
        return self.previews

    @Slot()
    def capture(self):
        if self.locked:
            return
        preview = self.d.material.previews
        for key in list(preview):
            if not key.startswith("icon-"):
                del preview[key]
        self.previews = []
        stamp = time.monotonic_ns()
        for name, panel in self.d.panels.items():
            if name in self.d.opened:
                key = ICONS[name]
                preview[key] = (
                    panel.grab()
                    .toImage()
                    .scaled(
                        530,
                        340,
                        Qt.AspectRatioMode.KeepAspectRatio,
                        Qt.TransformationMode.SmoothTransformation,
                    )
                )
                self.previews.append(
                    dict(
                        label=name, target="window:" + name, preview=f"image://material/preview/{key}/{stamp}"
                    )
                )
        for window in self.d.external:
            # External app previews use their installed icon. Protected or minimized windows remain switchable.
            self.previews.append(
                dict(
                    label=window["title"],
                    target=f"external:{window['handle']}",
                    preview=self.window_art(window),
                )
            )
        self.windowsChanged.emit()

    @Slot(str)
    def cover(self, name):
        if self.locked:
            self.covered.clear()
            return
        if name and not self.covered:
            self.covered = [p for p in self.d.panels.values() if p.isVisible() and not p.isMinimized()]
            for panel in self.covered:
                panel.hide()
        elif not name:
            for panel in self.covered:
                if self.d.opened.get(panel.name) == self.d.space:
                    panel.show()
            self.covered.clear()

    @Slot(str)
    def pinResult(self, target):
        if not self.locked and Path(target).is_absolute():
            self.d.pin(target)
            self.feedback.emit("Shortcut pinned to the Dock.")

    @Slot(str, str, bool)
    def authenticate(self, password, again, remember):
        self.d.unlock(password, again, remember)

    @Slot(str, bool)
    def authenticateCode(self, code, remember):
        self.d.unlock_with_code(code, remember)

    def find_files(self):
        query = self.query.strip()
        if self.locked or self.file_pending or not 2 <= len(query) <= 100:
            return
        self.file_pending = True

        def received(results):
            self.file_pending = False
            if self.locked:
                return
            self.file_query, self.file_results = query, results
            self.search(self.query)

        self.d.work.run(lambda: indexed_files(query), received, lambda error: received([]))

    @Slot(str)
    def search(self, query):
        if self.locked:
            self.found = []
            self.resultsChanged.emit()
            return
        self.query = query
        if self.index is None:
            self.index = []

            def ready(data):
                self.index = data
                self.search(self.query)

            self.d.work.run(shortcuts, ready, self.feedback.emit)
        q = query.casefold().strip()
        results = []

        def add(label, kind, target, art="Files"):
            results.append(dict(label=label, kind=kind, target=target, art=self.art(art)))

        result = calculate(q)
        if result is not None:
            add("= " + result, "Copy result", "copy:" + result, "Launcher")
        for name in ICONS:
            if name != "Launcher" and q in name.casefold():
                add(name, "Application", name, name)
        from .control import SYSTEM

        if q:
            for name, uri in SYSTEM.items():
                if q in name.casefold():
                    add(name, "Windows settings", "settings:" + uri, "Settings")
        for name, path in self.index:
            if len(results) >= 60:
                break
            if q in name.casefold():
                add(name, "Windows application" if path.startswith("startapp:") else "Windows shortcut", path)
        if os.name == "nt" and 2 <= len(query.strip()) <= 100:
            if self.file_query == query.strip():
                for title, path in self.file_results:
                    add(title, str(Path(path).parent), path)
            elif not self.file_pending:
                self.file_timer.start()
        if q and self.d.s.get("browser_history_enabled", False):
            for url in self.d.s.get("browser_history", []):
                if q in url.casefold() and len(results) < 60:
                    add(url, "Browser history", "web:" + url, "Air Browser")
        if q:
            add("Search Windows for “" + query + "”", "Files and folders", "find:" + query)
        self.found = results
        self.resultsChanged.emit()

    @Slot(int, int)
    def reorder(self, old, new):
        if self.locked:
            return
        items = [i["target"] for i in self.items]
        if 0 <= old < len(items):
            value = items.pop(old)
            items.insert(max(0, min(new, len(items))), value)
            self.d.s.set(desktop_items=items)
            self.appsChanged.emit()

    @Slot(str)
    def action(self, cmd):
        if self.locked:
            if cmd == "reset":
                self.d.reset()
            return
        if cmd in ("Launcher", "Overview"):
            self.d.show_overlay("spotlight" if cmd == "Launcher" else "overview")
        elif cmd == "Downloads":
            self.d.open("Files").downloads()
        elif cmd == "Terminal":
            self.d.terminal()
        elif cmd in self.d.names:
            self.d.open(cmd)
        elif cmd == "remote":
            self.d.open("Control Center").setCurrentIndex(1)
        elif cmd in ("next-space", "space:1", "space:2"):
            self.d.switch_space((3 - self.d.space) if cmd == "next-space" else int(cmd[-1]))
        elif cmd == "exit-full":
            self.d.exit_desktop()
        elif cmd == "toggle-appearance":
            self.d.preference("appearance", "Light" if self.dark else "Dark")
        elif cmd.startswith("startapp:"):
            from .applications import launch_registered
            if any(target == cmd for _, target in self.index or []):
                try:
                    launch_registered(cmd)
                except (ValueError, OSError) as exc:
                    self.d.notify(str(exc))
        elif cmd.startswith("external:"):
            from .windows import activate

            window = next((w for w in self.d.external if cmd == f"external:{w['handle']}"), None)
            if not window or not activate(window):
                self.d.notify("Windows could not focus that window. Use Alt+Tab to switch.")
        elif cmd.startswith("window:"):
            name = cmd[7:]
            if name in self.d.opened:
                self.d.switch_space(self.d.opened[name])
                self.d.open(name)
        elif cmd.startswith("settings:"):
            system_link(self.d, "ms-settings:" + cmd[9:])
        elif cmd.startswith("copy:"):
            QApplication.clipboard().setText(cmd[5:])
        elif cmd.startswith("find:"):
            system_link(self.d, "search-ms:query=" + quote(cmd[5:], safe=""))
        elif cmd.startswith("web:"):
            self.d.browse(cmd[4:])
        elif Path(cmd).is_absolute():
            open_path(self.d, cmd)

    @Slot(str, float, float)
    def menu(self, name, x, y):
        if self.locked:
            return
        d = self.d
        m = QMenu(d)
        if name in ("File", "Desktop"):
            m.addAction("Open Files", lambda: d.open("Files"))
            m.addAction("Add desktop shortcut…", d.add_shortcut)
            m.addAction("Search…", lambda: d.show_overlay("spotlight"))
            m.addAction("Hide calendar widget" if self.calendarVisible else "Show calendar widget",
                        lambda: d.preference("calendar_widget", not self.calendarVisible))
        if name in ("AirCursor", "Desktop", "View"):
            m.addAction("System settings…", lambda: d.open("Settings"))
            m.addAction("Control Center", lambda: d.show_overlay("quick"))
            m.addAction("Notifications", lambda: d.show_overlay("notifications"))
            m.addAction("Clipboard history", d.clip_history)
            m.addSeparator()
            m.addAction("Exit desktop mode" if d.isFullScreen() else "Enter desktop mode", d.fullscreen)
        if name == "AirCursor":
            m.addAction("Lock AirCursor", d.lock_now)
            m.addAction("Quit AirCursor", d.close)
        if name in ("Window", "View"):
            m.addAction("Window overview", lambda: d.show_overlay("overview"))
            for i in (1, 2):
                m.addAction(f"Workspace {i}", lambda i=i: d.switch_space(i))
            for app in d.opened:
                m.addAction(app, lambda app=app: self.action("window:" + app))
        if name.startswith("shortcut:"):
            i = int(name.split(":")[1])

            def remove():
                items = [v["target"] for v in self.items]
                items.pop(i)
                d.s.set(desktop_items=items)
                self.appsChanged.emit()

            m.addAction("Remove desktop shortcut", remove)
        if name.startswith("dock:"):
            target = name[5:]
            m.addAction("Open " + Path(target).stem, lambda: self.action(target))
            if target in d.s.get("pins", []):
                m.addAction("Unpin from Dock", lambda: d.unpin(target))
            if target in d.opened and target in d.panels:
                m.addAction("Close", d.panels[target].close)
        m.exec(d.view.mapToGlobal(QPoint(round(x), round(y))))
