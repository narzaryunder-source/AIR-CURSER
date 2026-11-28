"""Air Browser entry point for the signed-in Windows user."""
import os
import sys
from pathlib import Path

from PySide6.QtCore import Qt, QTimer
from PySide6.QtWidgets import QApplication
from . import session
from .browser import Browser
from .settings import Settings, data_dir


def main():
    QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    app = QApplication(sys.argv[:1])
    app.setApplicationName("Air Browser")
    # Keep cookies, recovery data and preferences isolated from a concurrently open desktop.
    root = Path(data_dir()) / "StandaloneBrowser"
    os.environ["AIRCURSOR_HOME"] = str(root)
    window = Browser(Settings())
    window.setWindowTitle("Air Browser")
    window.resize(1080, 720)
    window.show()
    QTimer.singleShot(100, window, window.prepare_normal_profile)
    app.aboutToQuit.connect(window.close_private)
    timer = QTimer(window, interval=1000)
    timer.timeout.connect(lambda: None if session.interactive_desktop() else app.quit())
    timer.start()
    if "--browser-smoke-test" in sys.argv:
        QTimer.singleShot(250, app.quit)
    return app.exec()
