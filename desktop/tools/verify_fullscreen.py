"""Exercise the Windows taskbar registration and the bottom edge of the workspace."""

import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory

from PySide6.QtCore import Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from aircursor_desktop.settings import Settings
from aircursor_desktop.ui import Desktop


def wait_for(check):
    for _ in range(100):
        if check():
            return
        QTest.qWait(25)
    assert check()


def main():
    QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    app = QApplication([])
    with TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        os.environ["AIRCURSOR_HOME"] = folder
        settings = Settings()
        settings.set(low_memory=True, restore=False, auto_switch=False)
        window = Desktop(settings)
        window.start()
        window.activateWindow()

        user = ctypes.windll.user32
        user.GetAncestor.argtypes = [wintypes.HWND, wintypes.UINT]
        user.GetAncestor.restype = wintypes.HWND
        user.WindowFromPoint.argtypes = [wintypes.POINT]
        user.WindowFromPoint.restype = wintypes.HWND
        hwnd = int(window.winId())
        bounds = wintypes.RECT()
        assert user.GetWindowRect(wintypes.HWND(hwnd), ctypes.byref(bounds))
        point = wintypes.POINT((bounds.left + bounds.right) // 2, bounds.bottom - 2)

        def bottom_window():
            return user.GetAncestor(user.WindowFromPoint(point), 2)

        try:
            wait_for(lambda: hwnd in window.taskbar.windows)
            wait_for(lambda: bottom_window() == hwnd)
            window.open("Settings")
            panel = window.panels["Settings"]
            handle = int(panel.winId())
            wait_for(lambda: app.activeWindow() is panel and handle in window.taskbar.windows)
            wait_for(lambda: bottom_window() in (hwnd, handle))
            window.exit_desktop()
            wait_for(lambda: not window.taskbar.windows)
            assert not window.isFullScreen()
            print("Full-screen bottom edge verified with workspace and Settings focused. Windowed exit cleared registrations.")
        finally:
            window.close()
            app.processEvents()


if __name__ == "__main__":
    main()
