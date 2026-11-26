"""Opt-in browser integration on a desktop that supports Chromium's normal sandbox."""

import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("AIRCURSOR_TEST_WEBENGINE") != "1", reason="requires a desktop with the Chromium sandbox"
)


def test_normal_and_private_storage_download_and_recovery(tmp_path, monkeypatch):
    from PySide6.QtCore import QEvent, QCoreApplication, QUrl
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication, QFileDialog
    from aircursor_desktop.browser import Browser
    from aircursor_desktop.settings import Settings

    monkeypatch.setenv("AIRCURSOR_HOME", str(tmp_path))
    app = QApplication.instance() or QApplication([])

    class Site(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/large.bin":
                self.send_response(200)
                self.send_header("Content-Disposition", 'attachment; filename="large.bin"')
                self.send_header("Content-Length", str(4 * 1024 * 1024))
                self.end_headers()
                try:
                    for _ in range(512):
                        self.wfile.write(b"x" * 8192)
                        time.sleep(0.008)
                except (ConnectionError, OSError):
                    pass
                return
            data = (
                b"AirCursor download test"
                if self.path == "/sample.txt"
                else b"<title>Local browser integration</title><p>Local content</p>"
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/plain" if self.path == "/sample.txt" else "text/html")
            if self.path == "/sample.txt":
                self.send_header("Content-Disposition", 'attachment; filename="sample.txt"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    def until(check):
        stop = time.monotonic() + 12
        while time.monotonic() < stop and not check():
            QTest.qWait(20)
        assert check()

    def javascript(view, source):
        answer = []
        view.page().runJavaScript(source, 0, answer.append)
        until(lambda: len(answer) > 0)
        return answer[0]

    with ThreadingHTTPServer(("127.0.0.1", 0), Site) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        origin = f"http://127.0.0.1:{server.server_port}"
        settings = Settings()
        settings.set(browser_history_enabled=True)
        browser = Browser(settings)
        browser.show()
        normal = browser.add(origin + "/regular")
        until(lambda: normal.title() == "Local browser integration")
        javascript(normal, "localStorage.setItem('test','normal'); document.cookie='normal=one'; 'ok'")
        private = browser.add(origin + "/private", private=True)
        until(lambda: private.title() == "Local browser integration")
        assert private.page().profile().isOffTheRecord()
        assert javascript(private, "String(localStorage.getItem('test'))") == "null"
        assert "normal=one" not in javascript(private, "document.cookie")
        javascript(private, "localStorage.setItem('test','private'); 'ok'")
        assert javascript(normal, "localStorage.getItem('test')") == "normal"
        browser.save()
        assert settings.get("browser_tabs") == [origin + "/regular"]
        assert settings.get("browser_history") == [origin + "/regular"]
        browser.close_tab(browser.tabs.indexOf(private))
        QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        private = browser.add(origin + "/again", private=True)
        until(lambda: private.title() == "Local browser integration")
        assert javascript(private, "String(localStorage.getItem('test'))") == "null"
        dest = tmp_path / "sample.txt"
        monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *args: (str(dest), ""))
        normal.page().download(QUrl(origin + "/sample.txt"), "sample.txt")
        until(lambda: dest.is_file() and dest.read_bytes() == b"AirCursor download test")
        opened = []
        from PySide6.QtCore import QTimer
        from PySide6.QtWidgets import QPushButton
        monkeypatch.setattr("aircursor_desktop.browser.open_path", lambda owner, path: opened.append(path))

        def open_download():
            dialog = app.activeModalWidget()
            action = next(b for b in dialog.findChildren(QPushButton) if b.text() == "Open file…")
            action.click()
            dialog.accept()

        until(lambda: browser.downloads and browser.downloads[-1].isFinished())
        QTimer.singleShot(0, open_download)
        browser.show_downloads()
        assert opened == [dest]
        unfinished = tmp_path / "large.bin"
        monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *args: (str(unfinished), ""))
        normal.page().download(QUrl(origin + "/large.bin"), "large.bin")
        until(lambda: len(browser.downloads) == 2 and browser.downloads[-1].receivedBytes() > 0)

        def cancel_download():
            from PySide6.QtWidgets import QListWidget
            dialog = app.activeModalWidget()
            dialog.findChild(QListWidget).setCurrentRow(1)
            next(b for b in dialog.findChildren(QPushButton) if b.text() == "Cancel download").click()
            dialog.accept()

        QTimer.singleShot(0, cancel_download)
        browser.show_downloads()
        until(lambda: browser.downloads[-1].state().name == "DownloadCancelled")
        assert not unfinished.exists()
        browser.close()
        browser.deleteLater()
        QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        app.processEvents()
        server.shutdown()
