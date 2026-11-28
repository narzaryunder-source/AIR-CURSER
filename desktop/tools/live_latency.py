"""Compare source builds with fresh profiles and the same two real public sites."""
import json
import os
from pathlib import Path
import sys
import tempfile
import time

path, mode = sys.argv[1:3]
sys.path.insert(0, path)
os.environ["AIRCURSOR_HOME"] = tempfile.mkdtemp()
started = time.perf_counter()
from PySide6.QtCore import Qt, QTimer
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
from aircursor_desktop.settings import Settings
QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
app = QApplication([])
settings = Settings()
settings.set(auto_switch=False, restore=False)
if mode == "desktop":
    from aircursor_desktop.ui import Desktop
    window = Desktop(settings)
    window.start()
    browser = window.open("Air Browser")
else:
    from aircursor_desktop.standalone import BrowserWindow
    browser = window = BrowserWindow(settings)
    window.resize(1080, 720)
    window.show()
    QTimer.singleShot(100, window, window.prepare_normal_profile)
app.processEvents()
import psutil
print(json.dumps(dict(mode=mode, open_ms=round((time.perf_counter()-started)*1000),
                      main_rss_mib=round(psutil.Process().memory_info().rss/2**20))), flush=True)
QTest.qWait(1200)
for url in ("https://github.com/narzaryunder-source/AIR-CURSER","https://www.nasa.gov/"):
    browser.address.setText(url)
    begin=time.perf_counter()
    QTest.keyClick(browser.address, Qt.Key.Key_Return)
    view=browser.current()
    results=[]
    view.loadFinished.connect(results.append)
    while not results and time.perf_counter()-begin < 20:
        QTest.qWait(10)
    wall=round((time.perf_counter()-begin)*1000)
    metrics=[]
    view.page().runJavaScript("""JSON.stringify({ttfb:performance.getEntriesByType('navigation')[0]?.responseStart,
    paint:performance.getEntriesByName('first-contentful-paint')[0]?.startTime})""",metrics.append)
    end=time.perf_counter()+1
    while not metrics and time.perf_counter()<end:
        QTest.qWait(10)
    print(json.dumps(dict(mode=mode,url=url,title=view.title(),loaded=bool(results and results[0]),
                          enter_to_loaded_ms=wall,timing=json.loads(metrics[0]) if metrics and metrics[0] else {})), flush=True)
window.close()
app.processEvents()
