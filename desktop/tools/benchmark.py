"""Repeatable measurements. Network policy clocks are synthetic; receiver timings use a real local socket."""

import argparse
from pathlib import Path
import os
import sys
import json
import socket
import statistics
import threading
import time
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=["policy", "receiver", "startup", "dock"])
parser.add_argument("--source", type=Path, default=ROOT)
parser.add_argument("--notification", action="store_true")
args = parser.parse_args()
sys.path.insert(0, str(args.source))


def policy():
    from aircursor_desktop.hotspot import AutoSwitch
    from aircursor_desktop.settings import Settings

    with tempfile.TemporaryDirectory() as path:

        class Link:
            phone_ssid = "Phone"
            native = None
            s = Settings(str(Path(path) / "settings.json"))

            def online(self):
                return now == 0

            def networks(self):
                return [{"ssid": "Phone", "signal": 88}]

            def on_phone(self):
                return False

            def connect(self, *args):
                return ""

            def traffic_busy(self):
                return False

        now = 0
        manager = AutoSwitch(Link(), clock=lambda: now)
        while now < 60:
            if manager.step() == "switched":
                return {
                    "decision_seconds_since_loss": round(now - 0.01, 3),
                    "probe_and_join_duration": "excluded",
                }
            if now == 0 and args.notification:
                now = 0.36
            elif manager.down_since is not None and hasattr(manager, "retry_at"):
                now += max(1, min(manager.every, manager.down_since + manager.after - now))
            else:
                now += manager.every
        raise RuntimeError("No switch decision")


def receiver():
    from aircursor_desktop.server import RemoteHost

    seen = threading.Event()
    arrivals = []

    class Sink:
        def apply(self, cmd):
            arrivals.append(time.perf_counter_ns())
            seen.set()

        def release_all(self):
            pass

    host = RemoteHost(Sink(), port=0)
    host._advertise = lambda *args: None
    host.start()
    samples = []
    try:
        with socket.create_connection(("127.0.0.1", host.port)) as phone:
            phone.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            phone.sendall(f"HELLO 1 {host.code}\n".encode())
            assert phone.recv(256).startswith(b"OK ")
            for i in range(330):
                seen.clear()
                start = time.perf_counter_ns()
                phone.sendall(b"M 1 1\n")
                assert seen.wait(1)
                if i >= 30:
                    samples.append((arrivals[-1] - start) / 1e6)
    finally:
        host.stop()
    return {
        "samples": len(samples),
        "median_ms": round(statistics.median(samples), 4),
        "p95_ms": round(sorted(samples)[int(0.95 * len(samples))], 4),
    }


def startup():
    start = time.perf_counter()
    from PySide6.QtCore import QEventLoop, QTimer
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.ui import Desktop
    import psutil

    with tempfile.TemporaryDirectory() as path:
        os.environ["AIRCURSOR_HOME"] = path
        app = QApplication([])
        window = Desktop()
        window.resize(1366, 768)
        window.show()
        app.processEvents()
        assert not window.view.grabFramebuffer().isNull()
        elapsed = time.perf_counter() - start
        process = psutil.Process()
        rss = process.memory_info().rss / 2**20
        window.s.set(auto_switch=False)
        window.unlock("benchmark-session", "benchmark-session", False)
        app.processEvents()
        assert not window.locked
        loop = QEventLoop()
        intervals = []
        last = time.perf_counter()

        def tick():
            nonlocal last
            now = time.perf_counter()
            intervals.append((now - last) * 1000)
            last = now
            if len(intervals) >= 100:
                loop.quit()

        timer = QTimer(interval=16)
        timer.timeout.connect(tick)
        timer.start()
        loop.exec()
        timer.stop()
        workspace_rss = process.memory_info().rss / 2**20
        window.close()
        return {
            "lock_paint_seconds": round(elapsed, 3),
            "lock_rss_mib": round(rss, 1),
            "workspace_rss_mib": round(workspace_rss, 1),
            "timer_interval_p95_ms": round(sorted(intervals)[94], 2),
        }


def dock():
    from PySide6.QtQuick import QQuickItem
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.design import MaterialProvider
    from aircursor_desktop.ui import Desktop

    counts = []
    old = MaterialProvider.requestImage

    def measure(self, key, size, requested):
        if not key.startswith("preview/"):
            counts.append(key)
        return old(self, key, size, requested)

    MaterialProvider.requestImage = measure
    with tempfile.TemporaryDirectory() as folder:
        os.environ["AIRCURSOR_HOME"] = folder
        app = QApplication([])
        window = Desktop()
        window.work.run = lambda *args: None
        window.resize(1366, 768)
        window.show()
        window.s.set(auto_switch=False)
        window.unlock("benchmark-session", "benchmark-session", False)
        root = window.view.rootObject()
        item = root.findChild(QQuickItem, "workspaceDock")
        if item is None:
            item = next(
                child
                for child in root.findChildren(QQuickItem)
                if child.metaObject().indexOfProperty("active") >= 0
                and child.metaObject().indexOfProperty("mouseX") >= 0
            )
        QTest.qWait(350)
        counts.clear()
        assert item.setProperty("active", True) and item.property("active")
        width = item.property("width")
        widths = set()

        def animated(widgets):
            for widget in widgets:
                if widget.metaObject().indexOfProperty("grow") >= 0:
                    yield widget
                yield from animated(widget.childItems())

        slots = list(animated(item.childItems()))
        assert slots, "Dock items are unavailable"
        motion = set()
        started = time.process_time()
        for step in range(72):
            item.setProperty("mouseX", 25 + (width - 50) * (step % 36) / 35)
            QTest.qWait(16)
            widths.add(round(item.property("width")))
            motion.update(round(child.property("grow"), 1) for child in slots)
        elapsed = time.process_time() - started
        QTest.qWait(150)
        assert len(motion) > 1, "Dock scale did not animate"
        result = {
            "hover_steps": 72,
            "glass_renders": len(counts),
            "dock_widths": len(widths),
            "process_cpu_seconds": round(elapsed, 3),
        }
        window.close()
        app.processEvents()
        return result


if __name__ == "__main__":
    print(json.dumps({"mode": args.mode, "source": args.source.name, **globals()[args.mode]()}))
