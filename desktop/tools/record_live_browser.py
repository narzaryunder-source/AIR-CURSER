"""Record ordinary clicks, page loads and scrolling in the released Windows Air Browser."""

import ctypes
import json
import os
from pathlib import Path
import sys
import tempfile
import time

from PySide6.QtCore import QPoint, QTimer, Qt
from PySide6.QtGui import QColor, QCursor, QPainter, QPen, QPolygon
from PySide6.QtQuick import QQuickItem
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QPushButton

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from aircursor_desktop import VERSION
from aircursor_desktop.settings import Settings
from aircursor_desktop.ui import Desktop

SITES = [
    ("GitHub", "https://github.com/narzaryunder-source/AIR-CURSER"),
    ("Pixabay", "https://pixabay.com/"),
    ("NASA", "https://www.nasa.gov/"),
]
BLOCKING = ("just a moment", "verify you are human", "checking your browser", "attention required",
            "access denied", "request blocked", "captcha", "robot check", "enable javascript and cookies")


def descendants(item):
    for child in item.childItems():
        yield child
        yield from descendants(child)


def main():
    assert VERSION == "2.2.2" and sys.platform == "win32"
    QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    app = QApplication([])
    output = Path("performance-frames").resolve()
    output.mkdir()
    meta = {"release": VERSION, "events": [], "sites": [], "frames": []}
    origin = 0
    click_stamp = -2

    def elapsed():
        return time.perf_counter() - origin

    def event(kind, **data):
        at = elapsed()
        meta["events"].append(dict(type=kind, at=at, **data))
        print(f"{at:.3f} {kind} {data}", flush=True)
        return at

    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        os.environ["AIRCURSOR_HOME"] = folder
        settings = Settings()
        settings.set(auto_switch=False, restore=False, appearance="Light")
        desktop = Desktop(settings)
        desktop.showFullScreen()
        QTest.qWait(700)
        assert not desktop.locked
        screen = desktop.screen()

        def capture():
            at = elapsed()
            pixmap = screen.grabWindow(0)
            if pixmap.isNull():
                return
            image = pixmap.scaled(
                1080, 640, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation
            ).toImage()
            geometry = screen.geometry()
            cursor = QCursor.pos() - geometry.topLeft()
            x = round(cursor.x() * image.width() / geometry.width())
            y = round(cursor.y() * image.height() / geometry.height())
            painter = QPainter(image)
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            if at - click_stamp < 0.25:
                painter.setPen(QPen(QColor("#35dcba"), 2))
                painter.drawEllipse(QPoint(x, y), 13, 13)
            painter.setPen(QPen(QColor("#14212a"), 1.25))
            painter.setBrush(QColor("#ffffff"))
            painter.drawPolygon(QPolygon([
                QPoint(x, y), QPoint(x + 4, y + 18), QPoint(x + 8, y + 13),
                QPoint(x + 12, y + 20), QPoint(x + 15, y + 18),
                QPoint(x + 10, y + 11), QPoint(x + 17, y + 10),
            ]))
            painter.end()
            frame = f"{len(meta['frames']):05d}.jpg"
            if image.save(str(output / frame), "JPG", 60):
                meta["frames"].append(dict(file=frame, at=at))

        def click(widget, pos=None):
            nonlocal click_stamp
            pos = widget.rect().center() if pos is None else pos
            QCursor.setPos(widget.mapToGlobal(pos))
            click_stamp = elapsed()
            QTest.mouseClick(widget, Qt.MouseButton.LeftButton, pos=pos)

        def until(condition, timeout):
            end = time.perf_counter() + timeout
            while not condition() and time.perf_counter() < end:
                QTest.qWait(25)
            return condition()

        def load(browser, name, url):
            click(browser.address)
            QTest.keyClick(browser.address, Qt.Key.Key_A, Qt.KeyboardModifier.ControlModifier)
            QTest.keyClicks(browser.address, url, delay=3)
            started = event("navigation", site=name, url=url)
            QTest.keyClick(browser.address, Qt.Key.Key_Return)
            view = browser.current()
            assert hasattr(view, "page"), "The URL stayed on a new tab"
            finished = []
            view.page().loadFinished.connect(
                lambda ok: finished.append((bool(ok), elapsed())) if not finished else None
            )
            until(lambda: bool(finished), 6.8)
            result = []

            def received(data):
                if isinstance(data, dict):
                    result.append(data)

            view.page().runJavaScript(
                "({title:document.title, text:(document.body?.innerText||'').slice(0,320),"
                " height:document.documentElement.scrollHeight, y:scrollY})", received
            )
            until(lambda: bool(result), 0.65)
            visible = result[0] if result else {}
            text = (visible.get("title", "") + " " + visible.get("text", "")).lower()
            block = next((marker for marker in BLOCKING if marker in text), None)
            status = (
                "Security check" if block else
                "Loaded" if finished and finished[0][0] else
                "Load failed" if finished else "Still loading"
            )
            stamp = finished[0][1] if finished else elapsed()
            record = dict(name=name, requested=url, actual=view.url().toString(), title=view.title(),
                          status=status, navigation_at=started, reported_at=elapsed(),
                          seconds=round(stamp - started, 3), viewport=visible)
            meta["sites"].append(record)
            event("page_result", site=name, status=status, seconds=record["seconds"], title=record["title"])
            if status == "Loaded" or status == "Still loading":
                QCursor.setPos(view.mapToGlobal(QPoint(view.width() * 3 // 4, view.height() * 3 // 4)))
                for step in range(8):
                    ctypes.windll.user32.mouse_event(0x0800, 0, 0, -120, 0)
                    QTest.qWait(125 if step < 5 else 170)
                QTest.qWait(440)
                view.page().runJavaScript("scrollY", lambda y: record.update(scroll_y=y))
                QTest.qWait(170)
            else:
                QTest.qWait(1500)

        desktop.view.setFocus()
        dock = desktop.view.rootObject().findChild(QQuickItem, "workspaceDock")
        assert desktop.shell.dock[1]["target"] == "Air Browser"
        browser_icon = next(item for item in descendants(dock)
                            if item.metaObject().indexOfProperty("grow") >= 0 and item.property("index") == 1)
        coordinate = browser_icon.mapToScene(
            QPoint(round(browser_icon.width() / 2), round(browser_icon.height() / 2))
        ).toPoint()
        QCursor.setPos(desktop.view.mapToGlobal(QPoint(40, desktop.view.height() // 2)))
        timer = QTimer()
        timer.setTimerType(Qt.TimerType.PreciseTimer)
        timer.setInterval(48)
        timer.timeout.connect(capture)
        origin = time.perf_counter()
        timer.start()
        QTest.qWait(620)
        QCursor.setPos(desktop.view.mapToGlobal(coordinate))
        QTest.qWait(700)
        event("dock_click")
        click(desktop.view, coordinate)
        assert until(lambda: "Air Browser" in desktop.panels and desktop.panels["Air Browser"].isVisible(), 4)
        event("browser_visible")
        browser = desktop.panels["Air Browser"].content
        QTest.qWait(700)

        for i, (name, url) in enumerate(SITES):
            if i:
                plus = next(button for button in browser.findChildren(QPushButton) if button.text() == "+")
                prev = browser.tabs.count()
                click(plus)
                assert until(lambda: browser.tabs.count() == prev + 1, 2)
                QTest.qWait(250)
            load(browser, name, url)
        QTest.qWait(650)
        timer.stop()
        meta["duration"] = elapsed()
        assert len(meta["frames"]) > 60 and all(Path(output / f["file"]).stat().st_size for f in meta["frames"])
        (output / "capture.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
        desktop.close()
        app.processEvents()


if __name__ == "__main__":
    main()
