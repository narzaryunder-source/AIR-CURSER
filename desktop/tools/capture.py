"""Capture the running Qt application on a dedicated desktop with an isolated AirCursor profile."""

import argparse
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    target = args.output.resolve()
    target.mkdir(parents=True, exist_ok=True)
    from PySide6.QtCore import QPoint
    from PySide6.QtGui import QCursor
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.settings import Settings
    from aircursor_desktop.ui import Desktop

    app = QApplication([])
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        os.environ["AIRCURSOR_HOME"] = folder
        s = Settings()
        s.set(auto_switch=False, restore=False, appearance="Light")
        d = Desktop(s)
        d.showFullScreen()
        QTest.qWait(350)
        screen = d.screen()

        def grab(name):
            QTest.qWait(350)
            pix = screen.grabWindow(0)
            if pix.isNull() or not pix.save(str(target / name), quality=92):
                raise RuntimeError("Could not capture " + name)
            return pix

        assert not d.locked
        d.view.setFocus()
        grab("workspace.png")
        w, h = d.width(), d.height()
        QCursor.setPos(d.mapToGlobal(QPoint(w // 2, h - 38)))
        QTest.mouseMove(d.view, QPoint(w // 2, h - 38))
        pix = grab("dock-and-menu.jpg")
        pix.copy(0, 0, pix.width(), 42).save(str(target / "menu-bar.png"))
        pix.copy((pix.width() - 800) // 2, pix.height() - 136, 800, 136).save(str(target / "dock.png"))
        QCursor.setPos(d.mapToGlobal(QPoint(8, h // 2)))
        for name, file in [
            ("Files", "files"),
            ("Air Browser", "browser"),
            ("Air Store", "store"),
            ("Air AI", "ai"),
            ("Settings", "settings"),
        ]:
            pane = d.open(name)
            if name == "Files":
                pane.go(ROOT / "assets")
            QTest.qWait(400)
            grab(file + ".jpg")
            if name == "Air Store":
                pane.nav.setCurrentRow(1)
                grab("pc-games.jpg")
            d.panels[name].close()
            QTest.qWait(240)
        for mode, file in [("quick", "control-center"), ("spotlight", "spotlight")]:
            d.show_overlay(mode)
            grab(file + ".jpg")
            d.view.rootObject().setProperty("overlay", "")
        d.preference("appearance", "Dark")
        d.show_overlay("quick")
        grab("control-center-dark.jpg")
        d.view.rootObject().setProperty("overlay", "")
        d.exit_desktop()
        d.resize(1366, 768)
        QTest.qWait(350)
        assert d.view.grabFramebuffer().save(str(target / "workspace-1366.png"))
        d.close()
        app.processEvents()


if __name__ == "__main__":
    main()
