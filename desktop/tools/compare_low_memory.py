"""Compare workspace memory with identical saved windows and tabs."""

import json
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from time import perf_counter

import psutil

ROOT = Path(__file__).resolve().parents[1]


def main(lightweight):
    started = perf_counter()
    from PySide6.QtCore import Qt
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.settings import Settings
    from aircursor_desktop.ui import Desktop

    QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    app = QApplication([])
    with TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        os.environ["AIRCURSOR_HOME"] = folder
        settings = Settings()
        settings.set(
            auto_switch=False, low_memory=lightweight,
            session_panels={"Air Browser": 1, "Files": 1, "Air AI": 1, "Settings": 1},
            browser_tabs=["https://example.org/" + str(i) for i in range(12)],
        )
        workspace = Desktop(settings)
        workspace.start()
        app.processEvents()
        assert not workspace.view.grabFramebuffer().isNull()
        first_frame = perf_counter() - started
        QTest.qWait(2500)
        restored = list(workspace.panels)
        assert len(restored) == (0 if lightweight else 4), restored
        main = psutil.Process()
        total = sum(proc.memory_info().rss for proc in [main, *main.children(recursive=True)])
        print(json.dumps({
            "low_memory": lightweight, "first_frame_ms": round(first_frame * 1000),
            "working_set_mib": round(total / 2**20, 1), "auto_opened": restored,
        }), flush=True)
        workspace.close()
        app.processEvents()


if __name__ == "__main__":
    sys.path.insert(0, str(ROOT))
    main(sys.argv[1] == "on")
