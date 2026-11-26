"""Tabbed Qt WebEngine browser. Application navigation and profiles are separate from web content."""

from pathlib import Path
from urllib.parse import quote_plus, urlsplit

from PySide6.QtCore import QStandardPaths, Qt, QTimer, QUrl
from PySide6.QtGui import QDesktopServices, QShortcut, QKeySequence
from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineSettings
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QInputDialog,
    QLineEdit,
    QListWidget,
    QMenu,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)
from .settings import data_dir
from .browser_home import NewTab
from .widgets import button, confirm, label, password, row

HOME = "https://duckduckgo.com/"


def web_url(text):
    value = text.strip()
    try:
        parsed = urlsplit(value)
    except ValueError:
        return "https://duckduckgo.com/?q=" + quote_plus(value)
    if parsed.scheme in ("https", "http") and parsed.hostname:
        return value
    if not parsed.scheme and " " not in value and "." in value:
        return "https://" + value
    return "https://duckduckgo.com/?q=" + quote_plus(value)


class Page(QWebEnginePage):
    def acceptNavigationRequest(self, url, kind, main):
        return not main or url.scheme() in ("https", "http") or url.toString() == "about:blank"


class Browser(QWidget):
    def __init__(self, settings, parent=None):
        super().__init__(parent)
        self.s = settings
        self.normal = None
        self.private = None
        self.downloads = []
        self.tools = []
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
            more,
        )
        self.tabs = QTabWidget()
        self.tabs.setTabsClosable(True)
        self.tabs.setDocumentMode(True)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.tabs.currentChanged.connect(self.sync)
        layout.addWidget(self.tabs)
        self.saver = QTimer(self, interval=800, singleShot=True)
        self.saver.timeout.connect(self.save)
        urls = self.s.get("browser_tabs", [])[:20] or [None]
        for url in urls:
            self.add(url, focus=False)
        for keys, cb in [
            ("Ctrl+L", self.address.setFocus),
            ("Ctrl+T", self.add),
            ("Ctrl+W", lambda: self.close_tab(self.tabs.currentIndex())),
        ]:
            QShortcut(QKeySequence(keys), self, activated=cb)

    def current(self):
        return self.tabs.currentWidget()

    def web_action(self, method):
        view = self.current()
        if isinstance(view, QWebEngineView):
            getattr(view, method)()

    def navigate(self, view, text):
        if not text.strip():
            return
        if isinstance(view, NewTab):
            index = self.tabs.indexOf(view)
            if index < 0:
                return
            private = view.private
            self.tabs.removeTab(index)
            view.deleteLater()
            self.add(web_url(text), private=private, position=index)
        else:
            view.setUrl(QUrl(web_url(text)))

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
        if not private and self.normal is None:
            self.normal = QWebEngineProfile("AirBrowser", self)
            prefix = Path(data_dir()) / self.s.get("browser_profile", "")
            self.normal.setPersistentStoragePath(str(prefix / "WebProfile"))
            self.normal.setCachePath(str(prefix / "WebCache"))
            self.normal.downloadRequested.connect(self.download)

        if private and self.private is None:
            self.private = QWebEngineProfile(self)
            self.private.downloadRequested.connect(self.download)
        view = QWebEngineView(self)
        engine = Page(self.private if private else self.normal, view)
        view.setPage(engine)
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
        view.urlChanged.connect(self.sync)
        view.loadFinished.connect(lambda ok: self.loaded(view, private, ok))
        view.setUrl(QUrl(web_url(url)))
        return view

    def popup(self, request, private):
        if request.isUserInitiated() and request.requestedUrl().scheme() in ("https", "http", ""):
            request.openIn(self.add(request.requestedUrl().toString() or HOME, private=private).page())

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
            isinstance(self.tabs.widget(i), QWebEngineView)
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
        for tool in self.tools:
            tool.close()
            tool.deleteLater()
        self.tools.clear()
        for index in reversed(range(self.tabs.count())):
            tab = self.tabs.widget(index)
            if (isinstance(tab, NewTab) and tab.private) or (
                isinstance(tab, QWebEngineView) and tab.page().profile().isOffTheRecord()
            ):
                self.close_tab(index)
        self.save()

    def sync(self, *_):
        if self.current():
            self.address.setText(
                self.current().url().toDisplayString() if isinstance(self.current(), QWebEngineView) else ""
            )
        self.saver.start()

    def save(self):
        urls = [
            self.tabs.widget(i).url().toString()
            for i in range(self.tabs.count())
            if isinstance(self.tabs.widget(i), QWebEngineView)
            and self.tabs.widget(i).page().profile() == self.normal
        ]
        self.s.set(browser_tabs=[u for u in urls if u.startswith(("https://", "http://"))][:20])

    def menu(self):
        m = QMenu(self)
        m.addAction("New private tab", lambda: self.add(private=True))
        active = isinstance(self.current(), QWebEngineView)
        m.addAction("Bookmark this page", self.bookmark).setEnabled(active)
        m.addAction("Bookmarks", lambda: self.choose("Bookmarks", self.s.get("bookmarks", [])))
        m.addAction("History", lambda: self.choose("History", self.s.get("browser_history", [])))
        m.addAction("Downloads", self.show_downloads)
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
        view = QWebEngineView(self.window())
        view.setWindowFlag(Qt.WindowType.Window, True)
        view.resize(880, 620)
        self.current().page().setDevToolsPage(view.page())
        self.tools.append(view)
        view.show()

    def download(self, request):
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
        request.accept()

    def show_downloads(self):
        dialog = QDialog(self)
        dialog.setWindowTitle("Downloads")
        layout = QVBoxLayout(dialog)
        listing = QListWidget()
        layout.addWidget(listing)

        def update():
            index = listing.currentRow()
            listing.clear()
            for d in self.downloads:
                total = f"{d.totalBytes():,}" if d.totalBytes() >= 0 else "unknown size"
                listing.addItem(
                    f"{d.downloadFileName()} · {d.receivedBytes():,} / {total} bytes · {d.state().name}"
                )
            listing.setCurrentRow(index)

        def cancel():
            if 0 <= listing.currentRow() < len(self.downloads):
                self.downloads[listing.currentRow()].cancel()

        layout.addWidget(button("Cancel selected download", cancel))
        timer = QTimer(dialog, interval=500)
        timer.timeout.connect(update)
        timer.start()
        update()
        dialog.resize(600, 320)
        dialog.exec()
