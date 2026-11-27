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
            window = Desktop(Settings(str(Path(folder) / "test.json")))
            window.show()
            app.processEvents()
            assert window.locked and not window.host.running
            lines.append(f"AirCursor {VERSION}, lock frame {time.perf_counter() - start:.3f}s")
            if sys.platform == "win32":
                b = inject.backend()
                lines.append(f"Virtual desktop {b.screen()}, cursor {b.cursor()}")
            if sys.platform == "win32":
                from aircursor_desktop import second_factor, trust
                from aircursor_desktop.auth_dialogs import qr_image

                window.s.set(auto_switch=False, restore=False)
                password = "AirCursor self-test master password"
                window.auth.set_password(password)
                secret, recovery = second_factor.enrollment()
                assert not qr_image(second_factor.enrollment_uri(secret, "Windows self-test")).isNull()
                if time.time() % 30 > 29:
                    time.sleep(1)
                previous = int(time.time()) // 30 - 1
                second_factor.enable(window.s, secret, recovery, second_factor.code(secret, previous))
                window.unlock_with_code(second_factor.code(secret, previous + 1), True)
                assert not window.locked and trust.active(window.s)
                window.lock_now()
                assert window.locked and not trust.active(window.s)
                window.unlock_with_code(recovery[0], True)
                assert not window.locked and not trust.active(window.s)
                assert len(window.s.get("phone_factor")["recovery_codes"]) == 7
                window.lock_now()
                lines.append("Authenticator QR, code sign-in, recovery and DPAPI trust verified.")
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
    try:
        from aircursor_desktop.ui import main
    except ImportError as exc:
        if os.name == "nt":
            import ctypes

            ctypes.windll.user32.MessageBoxW(
                None, f"AirCursor could not load its Windows runtime.\n{exc}", "AirCursor startup", 0x10
            )
        raise
    sys.exit(main())
