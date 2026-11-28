"""Record real AirCursor windows and browser interactions on the Windows desktop."""

from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import sys
import tempfile
import threading
import time

from PySide6.QtCore import QPoint, Qt, QTimer
from PySide6.QtGui import QCursor
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from aircursor_desktop import VERSION
from aircursor_desktop.settings import Settings
from aircursor_desktop.ui import Desktop

FPS = 15


class Page(BaseHTTPRequestHandler):
    def do_GET(self):
        number = escape(self.path.rsplit("/", 1)[-1])
        content = f"""<!doctype html>
<html lang="en"><meta charset="UTF-8"><title>Test {number}</title>
<style>
body {{ margin:0; background:#f2f7fb; font:18px 'Segoe UI',sans-serif; color:#173549; }}
header {{ background:#13354b; color:#bceee6; padding:20px 36px; font-weight:600; }}
main {{ padding:50px 52px; }}
small {{ letter-spacing:3px; font-weight:600; color:#35726c; }}
h1 {{ font-size:54px; line-height:1.1; margin:18px 0; }}
nav {{ display:flex; flex-wrap:wrap; gap:6px; margin-top:32px; }}
nav span {{ display:grid; place-content:center; width:33px; height:37px; border-radius:6px;
  background:#dce9ef; color:#19384e; font-size:14px; }}
nav span.current {{ background:#256359; color:white; }}
</style>
<header>AirCursor · Windows browser benchmark</header>
<main><small>LOCAL TEST PAGE</small><h1>Tab {number} of 15</h1>
<p>Switching between already loaded pages in the real Air Browser.</p><nav>"""
        for index in range(1, 16):
            content += f'<span class="{ "current" if str(index) == number else "" }">{index}</span>'
        body = (content + "</nav></main></html>").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def wait_for(condition):
    deadline = time.monotonic() + 15
    while not condition():
        if time.monotonic() >= deadline:
            raise RuntimeError("A browser benchmark page did not finish loading")
        QTest.qWait(35)


def main():
    assert VERSION == "2.2.2" and sys.platform == "win32"
    frames = Path("performance-frames").resolve()
    frames.mkdir()
    QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    app = QApplication([])
    with (
        tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder,
        ThreadingHTTPServer(("127.0.0.1", 0), Page) as server,
    ):
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        os.environ["AIRCURSOR_HOME"] = folder
        settings = Settings()
        settings.set(auto_switch=False, restore=False, appearance="Light")
        desktop = Desktop(settings)
        desktop.showFullScreen()
        QTest.qWait(650)
        screen = desktop.screen()
        assert not desktop.locked
        frame = 0

        def point(x, y):
            QCursor.setPos(desktop.mapToGlobal(QPoint(x, y)))

        def record(seconds, interact=lambda _: None):
            nonlocal frame
            start = time.perf_counter()
            for index in range(seconds * FPS):
                delay = round(1000 * (start + index / FPS - time.perf_counter()))
                if delay > 0:
                    QTest.qWait(delay)
                interact(index)
                app.processEvents()
                image = screen.grabWindow(0)
                if image.isNull():
                    raise RuntimeError("Windows screenshot is empty")
                result = image.scaled(
                    960, 620, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation
                )
                if not result.save(str(frames / f"{frame:04d}.jpg"), "JPG", 68):
                    raise OSError(f"Could not save frame {frame}")
                frame += 1

        def place(name):
            panel = desktop.panels[name]
            w, h = min(desktop.width() - 100, 1140), min(desktop.height() - 130, 720)
            panel.resize(w, h)
            panel.move(desktop.mapToGlobal(QPoint((desktop.width()-w)//2, (desktop.height()-h)//2)))
            panel.raise_()
            panel.activateWindow()
            return panel

        center = desktop.width() // 2
        point(12, desktop.height() // 2)

        def dock_motion(index):
            if FPS <= index < FPS * 4:
                offset = (index - FPS) * 330 // (3 * FPS) - 165
                point(center + offset, desktop.height() - 40)
            elif index == FPS * 4:
                point(12, desktop.height() // 2)

        record(5, dock_motion)
        print("Desktop and Dock recorded", flush=True)
        urls = [f"http://127.0.0.1:{server.server_port}/tab/{number}" for number in range(1, 16)]
        settings.set(restore=True, browser_tabs=urls)
        browser = desktop.open("Air Browser")
        browser_panel = place("Air Browser")
        for index in range(15):
            browser.tabs.setCurrentIndex(index)

            def ready():
                current = browser.current()
                return hasattr(current, "page") and current.title() == f"Test {index+1}" and not current.page().isLoading()

            wait_for(ready)
        browser.tabs.setCurrentIndex(0)
        QTest.qWait(110)
        switches = {FPS: 4, FPS * 2: 9, FPS * 3: 14, FPS * 4: 6, FPS * 5: 11, FPS * 6: 2}

        def switch(index):
            if index in switches:
                browser.tabs.setCurrentIndex(switches[index])

        record(7, switch)
        print("Fifteen loaded Chromium tabs recorded", flush=True)
        browser_panel.close()
        desktop.open("Air AI")
        panel = place("Air AI")
        QTest.qWait(350)
        record(5)
        print("Air AI recorded", flush=True)
        panel.close()
        files = desktop.open("Files")
        files.go(ROOT / "assets")
        place("Files")
        QTest.qWait(400)
        record(2)
        errors = []

        def record_preview():
            try:
                dialog = app.activeModalWidget()
                if dialog is None:
                    raise RuntimeError("AirCursor file preview did not open")
                record(5)
            except Exception as exc:
                errors.append(exc)
            finally:
                if dialog is not None:
                    dialog.reject()

        QTimer.singleShot(450, record_preview)
        files.preview(ROOT / "assets" / "fjord.jpg")
        if errors:
            raise errors[0]
        print("Files and the real image preview recorded", flush=True)
        assert frame == FPS * 24
        desktop.close()
        app.processEvents()
        server.shutdown()
        thread.join()


if __name__ == "__main__":
    main()
