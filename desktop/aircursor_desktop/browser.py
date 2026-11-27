"""Tabbed Qt WebEngine browser. Application navigation and profiles are separate from web content."""

import os
import time
from pathlib import Path
from urllib.parse import quote_plus, urlsplit

from PySide6.QtCore import QStandardPaths, Qt, QTimer, QUrl
from PySide6.QtGui import QDesktopServices, QShortcut, QKeySequence
from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QInputDialog,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QMenu,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)
from .settings import data_dir
from .browser_home import NewTab
from .widgets import WINDOWS_INSTALLERS, button, confirm, label, open_path, password, row


def web_url(text):
    value = text.strip()
    try:
        parsed = urlsplit(value)
        if parsed.scheme in ("https", "http") and parsed.hostname:
            return value
        if "://" not in value and not any(character.isspace() for character in value):
            candidate = urlsplit("//" + value)
            host = candidate.hostname or ""
            if host and candidate.username is None and (host == "localhost" or "." in host or ":" in host):
                # A bare development server needs HTTP unless the user specifies HTTPS.
                scheme = "http" if host in ("localhost", "127.0.0.1", "::1") else "https"
                candidate.port  # Reject malformed or out-of-range ports.
                return f"{scheme}://{value}"
    except ValueError:
        pass
    return "https://duckduckgo.com/?q=" + quote_plus(value)


MAX_SAVED_TABS = 100
DISCARD_AFTER_SECONDS = 300


class RestoredTab(QWidget):
    def __init__(self, url):
        super().__init__()
        self.target = url
        layout = QVBoxLayout(self)
        layout.addWidget(label(url), alignment=Qt.AlignmentFlag.AlignCenter)


class Browser(QWidget):
    def __init__(self, settings, parent=None):
        super().__init__(parent)
        self.s = settings
        self.normal = None
        self.private = None
        self.downloads = []
        self._recent_downloads = [
            path for path in self.s.get("browser_downloads", []) if isinstance(path, str)
        ][:50]
        self.tools = []
        self._view_type = ()
        self._changing_tabs = False
        layout = QVBoxLayout(self)
        layout.setContentsMargins(12, 7, 12, 10)
        self.address = QLineEdit()
        self.address.setPlaceholderText("Search or enter a web address")
        self.address.returnPressed.connect(lambda: self.navigate(self.current(), self.address.text()))
        more = button("⋯", self.menu, "Browser menu")
        self.more = more
        row(
            layout,
            button("←", lambda: self.web_action("back"), "Back"),
            button("→", lambda: self.web_action("forward"), "Forward"),
            button("↻", lambda: self.web_action("reload"), "Reload"),
            self.address,
            button("+", lambda: self.add(), "New tab"),
            button("Downloads", self.show_downloads),
            more,
        )
        self.tabs = QTabWidget()
        self.tabs.setTabsClosable(True)
        self.tabs.setDocumentMode(True)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.tabs.currentChanged.connect(self.select_tab)
        self.tabs.setMovable(True)
        self.tabs.tabBar().tabMoved.connect(lambda *_: self.saver.start())
        layout.addWidget(self.tabs)
        self.saver = QTimer(self, interval=800, singleShot=True)
        self.saver.timeout.connect(self.save)
        self.restore_timer = QTimer(self, interval=0, singleShot=True)
        self.restore_timer.timeout.connect(self.restore_selected)
        self.sleeper = QTimer(self, interval=15000)
        self.sleeper.timeout.connect(self.freeze_idle_tabs)
        self._changing_tabs = True
        urls = self.s.get("browser_tabs", []) if self.s.get("restore", True) else []
        for url in urls[:MAX_SAVED_TABS]:
            if isinstance(url, str) and url.startswith(("https://", "http://")):
                self.tabs.addTab(RestoredTab(url), (QUrl(url).host() or "Restored tab")[:32])
        if self.tabs.count():
            active = self.s.get("browser_active", 0)
            self.tabs.setCurrentIndex(
                min(max(0, active if isinstance(active, int) else 0), self.tabs.count() - 1)
            )
        else:
            self.add()
        self._changing_tabs = False
        self.select_tab()
        for keys, cb in [
            ("Ctrl+L", self.address.setFocus),
            ("Ctrl+T", self.add),
            ("Ctrl+Shift+J", self.show_downloads),
            ("Ctrl+W", lambda: self.close_tab(self.tabs.currentIndex())),
        ]:
            QShortcut(QKeySequence(keys), self, activated=cb)

    def current(self):
        return self.tabs.currentWidget()

    def web_action(self, method):
        view = self.current()
        if isinstance(view, self._view_type):
            getattr(view, method)()

    def showEvent(self, event):
        super().showEvent(event)
        if isinstance(self.current(), RestoredTab):
            self.restore_timer.start()

    def hideEvent(self, event):
        super().hideEvent(event)
        self.restore_timer.stop()
        if self._view_type:
            self.sleeper.start()

    def select_tab(self, *_):
        if self._changing_tabs:
            return
        if self._view_type:
            self.sleeper.start()
        current = self.current()
        if isinstance(current, self._view_type):
            current.idle_since = None
            page = current.page()
            if page.lifecycleState() != page.LifecycleState.Active:
                page.setLifecycleState(page.LifecycleState.Active)
        self.address.setModified(False)
        self.sync()
        if self.isVisible() and isinstance(current, RestoredTab):
            self.restore_timer.start()

    def restore_selected(self):
        current = self.current()
        if self.isVisible() and isinstance(current, RestoredTab):
            self.navigate(current, current.target)

    def freeze_idle_tabs(self):
        now = time.monotonic()
        memory_saver = self.s.get("browser_memory_saver", False)
        downloading = {request.page() for request in self.downloads if not request.isFinished()}
        hidden = False
        for index in range(self.tabs.count()):
            view = self.tabs.widget(index)
            if not isinstance(view, self._view_type) or view.isVisible():
                continue
            hidden = True
            if view.idle_since is None:
                view.idle_since = now
            page = view.page()
            state = page.LifecycleState
            recommended = page.recommendedState()
            if recommended == state.Active or page in downloading:
                continue
            if (
                memory_saver
                and now - view.idle_since >= DISCARD_AFTER_SECONDS
                and page.lifecycleState() != state.Discarded
                and recommended == state.Discarded
            ):
                page.setLifecycleState(state.Discarded)
            elif page.lifecycleState() == state.Active:
                page.setLifecycleState(state.Frozen)
        if not hidden:
            self.sleeper.stop()

    def navigate(self, view, text):
        if not text.strip() or view is None:
            return
        url = web_url(text)
        self.address.setModified(False)
        if isinstance(view, self._view_type):
            view.setUrl(QUrl(url))
        else:
            index = self.tabs.indexOf(view)
            if index < 0:
                return
            selected = self.current() is view
            private = isinstance(view, NewTab) and view.private
            self._changing_tabs = True
            self.tabs.removeTab(index)
            view.deleteLater()
            try:
                self.add(url, private=private, focus=selected, position=index)
            finally:
                self._changing_tabs = False
            self.select_tab()

    def add(self, url=None, private=False, focus=True, position=-1):
        if url is None:
            home = NewTab(private, self.s.get("bookmarks", []))
            home.navigate.connect(lambda text: self.navigate(home, text))
            index = self.tabs.insertTab(
                position if position >= 0 else self.tabs.count(),
                home,
                "Private tab" if private else "New tab",
            )
            if focus:
                self.tabs.setCurrentIndex(index)
            return home
        return self.add_web_view(private, focus, position, QUrl(web_url(url)))

    def add_web_view(self, private, focus=True, position=-1, url=None):
        from .browser_engine import Page, QWebEngineProfile, QWebEngineSettings, QWebEngineView

        self._view_type = QWebEngineView
        if not private and self.normal is None:
            self.normal = QWebEngineProfile("AirBrowser", self)
            prefix = Path(data_dir()) / self.s.get("browser_profile", "")
            self.normal.setPersistentStoragePath(str(prefix / "WebProfile"))
            self.normal.setCachePath(str(prefix / "WebCache"))
            self.normal.downloadRequested.connect(self.download)

        if private and self.private is None:
            self.private = QWebEngineProfile(self)
            self.private.downloadRequested.connect(lambda request: self.download(request, private=True))
        view = QWebEngineView(self)
        engine = Page(self.private if private else self.normal, view)
        view.setPage(engine)
        view.target = url
        view.idle_since = None
        engine.settings().setAttribute(QWebEngineSettings.WebAttribute.FullScreenSupportEnabled, True)
        engine.permissionRequested.connect(
            lambda perm: (
                perm.grant()
                if self.isVisible()
                and confirm(
                    self,
                    "Website permission",
                    f"Allow {perm.origin().toDisplayString()} to use {perm.permissionType().name}?",
                )
                else perm.deny()
            )
        )
        engine.authenticationRequired.connect(self.authenticate)
        engine.fullScreenRequested.connect(lambda request: self.fullscreen(view, request))
        engine.newWindowRequested.connect(lambda req: self.popup(req, private))
        index = self.tabs.insertTab(
            position if position >= 0 else self.tabs.count(), view, "Private" if private else "New tab"
        )
        if focus:
            self.tabs.setCurrentIndex(index)
        view.titleChanged.connect(
            lambda title: self.tabs.setTabText(
                self.tabs.indexOf(view), ("Private · " if private else "") + (title[:32] or "New tab")
            )
        )
        view.urlChanged.connect(lambda address: self.url_changed(view, address))
        view.loadFinished.connect(lambda ok: self.loaded(view, private, ok))
        if url is not None:
            view.setUrl(url)
        if view is not self.current():
            self.sleeper.start()
        return view

    def popup(self, request, private):
        if request.isUserInitiated() and request.requestedUrl().scheme() in ("https", "http", ""):
            request.openIn(self.add_web_view(private).page())

    def authenticate(self, url, auth):
        if not self.isVisible():
            return
        dialog = QDialog(self)
        dialog.setWindowTitle("Website sign-in")
        layout = QVBoxLayout(dialog)
        layout.addWidget(label(url.host()))
        user, secret = QLineEdit(), password("Password")
        user.setPlaceholderText("Username")
        layout.addWidget(user)
        layout.addWidget(secret)
        controls = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        controls.accepted.connect(dialog.accept)
        controls.rejected.connect(dialog.reject)
        layout.addWidget(controls)
        if dialog.exec():
            auth.setUser(user.text())
            auth.setPassword(secret.text())
        dialog.deleteLater()

    def fullscreen(self, view, request):
        if request.toggleOn() and not confirm(
            self, "Full-screen playback", f"Allow {request.origin().host()} to enter full screen?"
        ):
            request.reject()
            return
        request.accept()
        win = self.window()
        if request.toggleOn():
            self.was_full = win.isFullScreen()
            win.showFullScreen()
        elif not getattr(self, "was_full", False):
            win.showNormal()
        if hasattr(win, "title"):
            win.title.setVisible(not request.toggleOn())
        self.tabs.tabBar().setVisible(not request.toggleOn())
        for i in range(self.layout().itemAt(0).layout().count()):
            self.layout().itemAt(0).layout().itemAt(i).widget().setVisible(not request.toggleOn())

    def loaded(self, view, private, ok):
        url = view.url().toString()
        if (
            ok
            and not private
            and self.s.get("browser_history_enabled", False)
            and url.startswith(("https://", "http://"))
        ):
            history = self.s.get("browser_history", [])
            self.s.set(browser_history=([url] + [u for u in history if u != url])[:300])

    def close_tab(self, index):
        view = self.tabs.widget(index)
        if view is None:
            return
        self.tabs.removeTab(index)
        view.deleteLater()
        if self.private and not any(
            isinstance(self.tabs.widget(i), self._view_type)
            and self.tabs.widget(i).page().profile() == self.private
            for i in range(self.tabs.count())
        ):
            old = self.private
            self.private = None
            view.destroyed.connect(old.deleteLater)
        if not self.tabs.count():
            self.add()
        self.saver.start()

    def close_private(self):
        for tool in self.tools[:]:
            tool.close()
            tool.deleteLater()
        self.tools.clear()
        for index in reversed(range(self.tabs.count())):
            tab = self.tabs.widget(index)
            if (isinstance(tab, NewTab) and tab.private) or (
                isinstance(tab, self._view_type) and tab.page().profile().isOffTheRecord()
            ):
                self.close_tab(index)
        self.save()

    def url_changed(self, view, address):
        view.target = address
        if view is self.current():
            self.sync()
        self.saver.start()

    def tab_url(self, widget):
        if isinstance(widget, RestoredTab):
            return widget.target
        if isinstance(widget, self._view_type) and widget.target:
            return widget.target.toString()
        return ""

    def sync(self):
        if not self.address.isModified():
            self.address.setText(self.tab_url(self.current()))
        self.saver.start()

    def save(self):
        if not self.s.get("restore", True):
            return
        urls = []
        active = 0
        for index in range(self.tabs.count()):
            view = self.tabs.widget(index)
            if isinstance(view, self._view_type) and view.page().profile() != self.normal:
                continue
            url = self.tab_url(view)
            if url.startswith(("https://", "http://")):
                if index == self.tabs.currentIndex():
                    active = len(urls)
                urls.append(url)
        values = urls[:MAX_SAVED_TABS]
        active = min(active, max(0, len(values) - 1))
        if self.s.get("browser_tabs", []) != values or self.s.get("browser_active", 0) != active:
            self.s.set(browser_tabs=values, browser_active=active)

    def menu(self):
        m = QMenu(self)
        m.addAction("New private tab", lambda: self.add(private=True))
        active = isinstance(self.current(), self._view_type)
        m.addAction("Bookmark this page", self.bookmark).setEnabled(active)
        m.addAction("Bookmarks", lambda: self.choose("Bookmarks", self.s.get("bookmarks", [])))
        m.addAction("History", lambda: self.choose("History", self.s.get("browser_history", [])))
        m.addAction("Downloads", self.show_downloads)
        memory_saver = m.addAction("Memory Saver · reload long-idle tabs when selected")
        memory_saver.setCheckable(True)
        memory_saver.setChecked(self.s.get("browser_memory_saver", False))
        memory_saver.toggled.connect(lambda enabled: self.s.set(browser_memory_saver=enabled))
        m.addAction(
            "Open in default browser", lambda: QDesktopServices.openUrl(self.current().url())
        ).setEnabled(active)
        m.addAction("Developer tools", self.devtools).setEnabled(active)
        m.exec(self.more.mapToGlobal(self.more.rect().bottomLeft()))

    def bookmark(self):
        url = self.current().url().toString()
        if url.startswith(("https://", "http://")) and url not in self.s.get("bookmarks", []):
            if self.current().page().profile().isOffTheRecord() and not confirm(
                self, "Save bookmark", "Save this private page to your normal bookmarks?"
            ):
                return
            self.s.set(bookmarks=[url] + self.s.get("bookmarks", []))

    def choose(self, title, values):
        if values:
            url, ok = QInputDialog.getItem(self, title, "Open page", values, editable=False)
            if ok:
                self.add(url)
        else:
            from PySide6.QtWidgets import QMessageBox

            QMessageBox.information(self, title, "No saved pages.")

    def devtools(self):
        view = self._view_type(self.window())
        view.setWindowFlag(Qt.WindowType.Window, True)
        view.resize(880, 620)
        view.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose)
        self.current().page().setDevToolsPage(view.page())
        self.tools.append(view)
        view.destroyed.connect(lambda: self.tools.remove(view) if view in self.tools else None)
        view.show()

    def download(self, request, private=False):
        if not self.isVisible():
            request.cancel()
            return
        dest = Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.DownloadLocation))
        name, _ = QFileDialog.getSaveFileName(
            self, "Save download", str(dest / Path(request.suggestedFileName()).name)
        )
        if not name:
            request.cancel()
            return
        path = Path(name)
        request.setDownloadDirectory(str(path.parent))
        request.setDownloadFileName(path.name)
        self.downloads.append(request)
        request.destroyed.connect(
            lambda: self.downloads.remove(request) if request in self.downloads else None
        )
        request.isFinishedChanged.connect(lambda: self.download_finished(request, path, private))
        request.accept()

    def download_finished(self, request, path, private):
        if request.state().name == "DownloadCompleted":
            if os.name == "nt" and path.suffix.lower() in WINDOWS_INSTALLERS:
                try:
                    mark_internet_download(path)
                except OSError:
                    # Downloads to drives that lack alternate streams are still visible in Explorer.
                    pass
            if not private:
                value = str(path)
                self._recent_downloads = [value, *(p for p in self._recent_downloads if p != value)][:50]
                try:
                    self.s.set(browser_downloads=self._recent_downloads)
                except OSError:
                    pass  # File completed; only its entry in the recent list could not be saved.
        finished = [entry for entry in self.downloads if entry.isFinished()]
        for entry in finished[:-50]:
            self.downloads.remove(entry)
            entry.deleteLater()

    def show_downloads(self):
        dialog = QDialog(self)
        dialog.setWindowTitle("Downloads")
        layout = QVBoxLayout(dialog)
        listing = QListWidget()
        layout.addWidget(listing)

        def selected():
            entry = listing.currentItem()
            return rows.get(entry.data(Qt.ItemDataRole.UserRole)) if entry is not None else None

        def controls():
            record = selected()
            complete = record is not None and record[2] == "DownloadCompleted"
            open_file.setEnabled(complete)
            reveal.setEnabled(complete)
            stop.setEnabled(record is not None and record[2] == "DownloadInProgress")

        def open_selected(folder=False):
            record = selected()
            if record is None or record[2] != "DownloadCompleted":
                return
            path = record[1]
            exists = path.parent.is_dir() if folder else path.is_file()
            if not exists:
                QMessageBox.information(
                    dialog, "Downloads", "The downloaded file or its folder has moved or was deleted."
                )
                return
            if not folder and os.name == "nt" and path.suffix.lower() in WINDOWS_INSTALLERS:
                try:
                    mark_internet_download(path)
                except OSError:
                    QMessageBox.warning(
                        dialog,
                        "Downloads",
                        "Move the installer to your Windows Downloads folder before opening it. This drive cannot preserve the internet security marker.",
                    )
                    return
            open_path(dialog, path.parent if folder else path)

        def cancel():
            record = selected()
            if record and record[0] is not None:
                record[0].cancel()

        def clear_history():
            try:
                self.s.set(browser_downloads=[])
            except OSError as exc:
                QMessageBox.warning(dialog, "Downloads", str(exc))
                return
            self._recent_downloads.clear()
            self.downloads[:] = [item for item in self.downloads if not item.isFinished()]
            update()

        open_file = button("Open file…", open_selected)
        reveal = button("Open folder", lambda: open_selected(True))
        stop = button("Cancel download", cancel)
        row(layout, open_file, reveal, stop, button("Clear history", clear_history))
        listing.itemSelectionChanged.connect(controls)
        listing.itemDoubleClicked.connect(lambda *_: open_selected())

        rows = {}
        painted = []

        def update():
            entries = []
            live_paths = set()
            for request in self.downloads:
                path = Path(request.downloadDirectory()) / request.downloadFileName()
                status = request.state().name
                total = f"{request.totalBytes():,}" if request.totalBytes() >= 0 else "unknown size"
                text = f"{path.name} · {request.receivedBytes():,} / {total} bytes · {status}"
                entries.append((f"live-{id(request)}", request, path, status, text))
                live_paths.add(str(path))
            for value in self._recent_downloads:
                if value not in live_paths:
                    path = Path(value)
                    entries.append(
                        (value, None, path, "DownloadCompleted", f"{path.name} · Download completed")
                    )
            if not listing.count() and not entries:
                controls()
                return
            snapshot = [(key, text) for key, _, _, _, text in entries]
            if snapshot == painted:
                return
            painted[:] = snapshot
            previous = listing.currentItem()
            selection = previous.data(Qt.ItemDataRole.UserRole) if previous else None
            listing.blockSignals(True)
            if list(rows) != [key for key, *_ in entries]:
                listing.clear()
                for key, _, _, _, text in entries:
                    entry = QListWidgetItem(text, listing)
                    entry.setData(Qt.ItemDataRole.UserRole, key)
                    if key == selection:
                        listing.setCurrentItem(entry)
            else:
                for index, (_, _, _, _, text) in enumerate(entries):
                    listing.item(index).setText(text)
            rows.clear()
            rows.update({key: (request, path, status) for key, request, path, status, _ in entries})
            if listing.currentRow() < 0 and listing.count():
                listing.setCurrentRow(0)
            listing.blockSignals(False)
            controls()

        timer = QTimer(dialog, interval=500)
        timer.timeout.connect(update)
        timer.start()
        update()
        dialog.resize(640, 340)
        dialog.exec()
        dialog.deleteLater()


def mark_internet_download(path):
    """Preserve an existing zone marker or attach the standard Windows internet zone."""
    try:
        with open(str(path) + ":Zone.Identifier", "xb") as stream:
            stream.write(b"[ZoneTransfer]\r\nZoneId=3\r\n")
    except FileExistsError:
        pass
