"""Windows application entry point and installed-runtime smoke test."""

import os
from pathlib import Path
import sys
import tempfile
import traceback


def self_test():
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import threading
    import time

    start = time.perf_counter()
    lines = []
    try:
        import psutil
        import zeroconf
        from PySide6.QtCore import QEventLoop, QTimer, QUrl
        from PySide6.QtWidgets import QApplication
        from aircursor_desktop import VERSION, inject
        from aircursor_desktop.browser import Browser
        from aircursor_desktop.ui import Desktop
        from aircursor_desktop.settings import Settings

        app = QApplication([])

        class PageHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                data = b"<title>AirCursor test</title><p>Web engine ready</p>"
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        with (
            tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder,
            ThreadingHTTPServer(("127.0.0.1", 0), PageHandler) as http,
        ):
            threading.Thread(target=http.serve_forever, daemon=True).start()
            url = f"http://127.0.0.1:{http.server_port}/"
            os.environ["AIRCURSOR_HOME"] = folder
            settings = Settings(str(Path(folder) / "test.json"))
            settings.set(auto_switch=False, restore=False, password={"hash": "legacy-preview-fixture"})
            window = Desktop(settings)
            window.show()
            app.processEvents()
            assert not window.locked and not window.host.running
            lines.append(f"AirCursor {VERSION}, workspace opened in {time.perf_counter() - start:.3f}s")
            if sys.platform == "win32":
                from aircursor_desktop import session

                b = inject.backend()
                lines.append(f"Virtual desktop {b.screen()}, cursor {b.cursor()}")
                window.handle_session_message(session.SESSION_CHANGE, 7)
                assert window.locked and window.host.paused
                window.handle_session_message(session.SESSION_CHANGE, 8)
                assert not window.locked
                lines.append("Windows session pause and resume verified; AirCursor opened without an app password.")
            tabs = [f"{url}?tab={index}" for index in range(15)]
            window.s.set(restore=True, browser_tabs=tabs, browser_active=6)
            browser = Browser(window.s)
            assert browser.normal is None and browser.tabs.count() == 15
            browser.save()
            assert window.s.get("browser_tabs") == tabs
            browser.show()
            app.processEvents()
            assert sum(hasattr(browser.tabs.widget(i), "page") for i in range(15)) == 1
            lines.append("Fifteen restored tabs, only the selected page loaded.")
            view = browser.add(url, private=True)
            assert view.page().profile().isOffTheRecord()
            loop = QEventLoop()
            loaded = []
            view.loadFinished.connect(lambda ok: (loaded.append(ok), loop.quit()))
            QTimer.singleShot(18000, loop.quit)
            loop.exec()
            assert loaded and loaded[-1] and view.title() == "AirCursor test", "Chromium render failed"
            assert not browser.normal.isOffTheRecord()
            http.shutdown()
            lines.append("Private WebEngine page rendered.")
            lines.append(f"RSS {psutil.Process().memory_info().rss} bytes; zeroconf {zeroconf.__version__}")
            window.close()
        lines.append("OK")
        status = 0
    except Exception:
        lines.append(traceback.format_exc())
        status = 1
    (Path(tempfile.gettempdir()) / "aircursor-selftest.txt").write_text("\n".join(lines), encoding="utf-8")
    return status


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    safe_mode = "--safe-mode" in sys.argv
    if safe_mode:
        os.environ["QT_QUICK_BACKEND"] = "software"
        sys.argv.remove("--safe-mode")
    try:
        from aircursor_desktop.ui import main
    except ImportError as exc:
        if os.name == "nt":
            import ctypes

            ctypes.windll.user32.MessageBoxW(
                None, f"AirCursor could not load its Windows runtime.\n{exc}", "AirCursor startup", 0x10
            )
        raise
    sys.exit(main(safe_mode=safe_mode))
