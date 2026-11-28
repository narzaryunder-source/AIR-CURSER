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
parser.add_argument("mode", choices=[
    "policy", "association", "receiver", "startup", "dock", "materials", "tabs",
    "workspace", "store", "catalog", "settings_write", "ai_reply", "photo_export",
])
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


def association():
    from aircursor_desktop.hotspot import Hotspot
    from aircursor_desktop.settings import Settings

    def run(association_at, route_at):
        with tempfile.TemporaryDirectory() as folder:
            clock = 0
            requested = False
            checks = 0

            class Adapter:
                def interfaces(self):
                    ssid = "Phone" if requested and clock >= association_at else "Home"
                    return [{"id": b"a", "ssid": ssid, "state": "connected"}]

                def networks(self):
                    return [{"id": b"a", "ssid": "Phone"}]

                def connect(self, ssid, adapter):
                    nonlocal requested
                    requested = True

            def sleep(delay):
                nonlocal clock
                clock += delay

            def verify(_):
                nonlocal checks
                checks += 1
                return clock >= route_at

            settings = Settings(str(Path(folder) / "settings.json"))
            settings.set(phone_ssid="Phone", phone_adapter="61")
            link = Hotspot(settings, runner=lambda _: None, sleep=sleep)
            link.native = Adapter()
            link.verify_adapter = verify
            error = link.connect()
            return {"simulated_seconds": clock, "verification_checks": checks, "verified": not error}

    return {"early_association": run(0.2, 0.2), "late_ip_route": run(2.2, 3.0)}


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
        if hasattr(window, "unlock"):  # Earlier preview sources still use a password screen.
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
        graphics = window.view.quickWindow().rendererInterface().graphicsApi().name
        window.close()
        return {
            "workspace_paint_seconds": round(elapsed, 3),
            "first_frame_rss_mib": round(rss, 1),
            "workspace_rss_mib": round(workspace_rss, 1),
            "timer_interval_p95_ms": round(sorted(intervals)[94], 2),
            "qt_graphics_api": graphics,
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
        if hasattr(window, "unlock"):  # Earlier preview sources still use a password screen.
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


def tabs():
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    import psutil
    from PySide6.QtCore import QCoreApplication, QEvent, Qt
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication

    visits = set()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.startswith("/tab/"):
                visits.add(self.path)
            data = b"<title>AirCursor 15-tab benchmark</title><p>Local page</p>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_):
            pass

    QApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    app = QApplication([])
    with (
        tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder,
        ThreadingHTTPServer(("127.0.0.1", 0), Handler) as http,
    ):
        from aircursor_desktop.settings import Settings

        os.environ["AIRCURSOR_HOME"] = folder
        threading.Thread(target=http.serve_forever, daemon=True).start()
        settings = Settings()
        settings.set(
            browser_history_enabled=True,
            browser_tabs=[f"http://127.0.0.1:{http.server_port}/tab/{i}" for i in range(15)],
            conversations=[{"title": "Saved chat", "messages": [
                {"role": "assistant", "content": ("This entry models a saved conversation. " * 1000)}
                for _ in range(25)
            ]}],
        )
        writes = 0
        save_settings = settings.set

        def record_write(**values):
            nonlocal writes
            writes += 1
            save_settings(**values)

        settings.set = record_write
        started = time.perf_counter()
        from aircursor_desktop.browser import Browser

        window = Browser(settings)
        window.resize(1080, 720)
        window.show()
        constructor_ms = 1000 * (time.perf_counter() - started)
        try:
            stop = time.monotonic() + 25
            while time.monotonic() < stop:
                QTest.qWait(20)
                current = window.current()
                if hasattr(current, "title") and current.title() == "AirCursor 15-tab benchmark":
                    break
            else:
                raise RuntimeError("Selected tab did not render")
            selected_ms = 1000 * (time.perf_counter() - started)
            QTest.qWait(750)
            processes = [psutil.Process(), *psutil.Process().children(recursive=True)]
            memory = 0
            for process in processes:
                try:
                    memory += process.memory_info().rss
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
            cold_requests = len(visits)
            writes = 0
            for index in range(15):
                window.tabs.setCurrentIndex(index)
                address = settings.get("browser_tabs")[index]
                stop = time.monotonic() + 15
                while time.monotonic() < stop:
                    QTest.qWait(5)
                    current = window.current()
                    if (
                        hasattr(current, "page") and current.url().toString() == address
                        and current.title() == "AirCursor 15-tab benchmark" and not current.page().isLoading()
                    ):
                        break
                else:
                    raise RuntimeError(f"Tab {index + 1} did not finish loading")
            window.save()
            switches = []
            for index in (1, 9, 2, 14, 3, 11, 0, 5, 2, 10, 14, 6, 4, 9, 7):
                start = time.perf_counter()
                window.tabs.setCurrentIndex(index)
                app.processEvents()
                switches.append(1000 * (time.perf_counter() - start))
            loaded_writes = writes
            window.save()
            writes = 0
            for index in (1, 9, 2, 14, 3):
                window.tabs.setCurrentIndex(index)
                QTest.qWait(1050)
            window.save()
            assert settings.get("browser_active") == 3
            return {
                "saved_tabs": window.tabs.count(),
                "browser_window_ms": round(constructor_ms, 1),
                "selected_page_title_ms": round(selected_ms, 1),
                "page_requests_after_settle": cold_requests,
                "tabs_loaded": len(visits),
                "settings_writes_loading_remaining_tabs": loaded_writes,
                "settings_writes_five_warm_switches": writes,
                "warm_switch_median_ms": round(statistics.median(switches), 1),
                "warm_switch_max_ms": round(max(switches), 1),
                "process_tree_rss_mib": round(memory / 2**20, 1),
            }
        finally:
            window.close()
            window.deleteLater()
            QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
            app.processEvents()
            http.shutdown()


def workspace():
    from PySide6.QtWidgets import QApplication
    from PySide6.QtTest import QTest
    from aircursor_desktop.ui import Desktop
    from aircursor_desktop.settings import Settings

    app = QApplication([])
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        os.environ["AIRCURSOR_HOME"] = folder
        settings = Settings()
        names = ("Files", "Air Store", "Air AI", "Settings", "Air Browser")
        settings.set(auto_switch=False, session_panels=dict.fromkeys(names, 1))
        started = time.perf_counter()
        window = Desktop(settings)
        window.resize(1366, 768)
        window.show()
        assert not window.view.grabFramebuffer().isNull()
        frame_ms = (time.perf_counter() - started) * 1000
        initial_panels = len(window.panels)
        try:
            stop = time.monotonic() + 10
            while len(window.panels) < len(names) and time.monotonic() < stop:
                QTest.qWait(5)
            assert set(window.panels) == set(names)
            assert all(panel.isVisible() for panel in window.panels.values())
            return {
                "saved_apps": len(names),
                "apps_constructed_before_first_frame": initial_panels,
                "first_frame_ms": round(frame_ms, 1),
                "apps_restored_ms": round((time.perf_counter() - started) * 1000, 1),
            }
        finally:
            window.close()
            app.processEvents()


def store():
    from PySide6.QtGui import QPixmap
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.store import Studio

    app = QApplication([])
    banner = Studio(lambda: None)
    banner.resize(1000, 180)
    banner.show()
    buffer = QPixmap(banner.size() * banner.devicePixelRatioF())
    buffer.setDevicePixelRatio(banner.devicePixelRatioF())
    banner.render(buffer)
    samples = []
    for _ in range(150):
        start = time.perf_counter()
        banner.render(buffer)
        samples.append((time.perf_counter() - start) * 1000)
    banner.close()
    app.processEvents()
    return {
        "paints": len(samples),
        "paint_median_ms": round(statistics.median(samples), 3),
        "paint_p95_ms": round(sorted(samples)[int(len(samples) * 0.95)], 3),
    }


def materials():
    from PySide6.QtCore import QSize
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.design import MaterialProvider

    app = QApplication([])
    provider = MaterialProvider()
    sizes = [(600, 60), (650, 400), (1100, 580)]
    result = {}
    for width, height in sizes:
        samples = []
        for x in range(21):
            start = time.process_time_ns()
            frame = provider.requestImage(
                f"fjord/dark/{x}/40/{width}/{height}/1920/1080/24/100", QSize(), QSize()
            )
            if x:
                samples.append((time.process_time_ns() - start) / 1e6)
            assert (frame.width(), frame.height()) == (width, height)
        result[f"{width}x{height}_median_cpu_ms"] = round(statistics.median(samples), 2)
    return result


def catalog():
    from aircursor_desktop.applications import registered_apps

    if os.name != "nt":
        return {"available": False}
    samples = []
    for _ in range(5):
        start = time.perf_counter_ns()
        apps = registered_apps()
        samples.append((time.perf_counter_ns() - start) / 1e6)
        assert apps
    return {
        "entries": len(apps), "first_lookup_ms": round(samples[0], 2),
        "repeat_lookup_median_ms": round(statistics.median(samples[1:]), 3),
    }


def settings_write():
    from aircursor_desktop.settings import Settings

    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "settings.json"
        settings = Settings(str(path))
        messages = [{"role": "assistant", "content": ("Saved conversation — " * 300)} for _ in range(350)]
        settings.set(conversations=[{"title": "Saved chat", "messages": messages}])
        samples = []
        for index in range(25):
            start = time.perf_counter_ns()
            settings.set(browser_active=index)
            samples.append((time.perf_counter_ns() - start) / 1e6)
        assert Settings(str(path)).get("conversations")[0]["messages"] == messages
        return {"file_bytes": path.stat().st_size, "write_median_ms": round(statistics.median(samples), 2)}


def ai_reply():
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop import intelligence as ai
    from aircursor_desktop.settings import Settings

    app = QApplication([])
    with tempfile.TemporaryDirectory() as folder:
        settings = Settings(str(Path(folder) / "settings.json"))
        saved = {"title": "Long chat", "named": True, "endpoint": ai.ENDPOINTS["Ollama"], "messages": [
            {"role": "user" if index % 2 == 0 else "assistant", "content": "A saved line of text.\n" * 20}
            for index in range(240)
        ]}
        settings.set(conversations=[saved])
        panel = ai.Intelligence(settings, lambda _: None, lambda: None)
        panel.resize(1100, 780)
        panel.show()
        app.processEvents()
        # Isolate transcript updates from disk I/O and model inference.
        panel.s.set = lambda **values: None
        panel.model.addItem("fixture-model")
        panel.model.setCurrentIndex(0)
        jobs = []
        panel.work.run = lambda fetch, done, failed: jobs.append(done)
        samples = []
        for _ in range(15):
            panel.prompt.setPlainText("Summarize this note.")
            panel.send()
            start = time.perf_counter_ns()
            jobs.pop()(("A short result.", 8))
            app.processEvents()
            samples.append((time.perf_counter_ns() - start) / 1e6)
        assert panel.log.toPlainText().endswith("A short result.")
        panel.close()
        return {"starting_messages": 240, "reply_update_median_ms": round(statistics.median(samples), 2)}


def photo_export():
    from PySide6.QtGui import QImage, QPainter, QColor
    from aircursor_desktop.media import export_photo

    import psutil

    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        source = root / "24mp.jpg"
        image = QImage(6000, 4000, QImage.Format.Format_RGB32)
        image.fill(QColor("#98afcf"))
        painter = QPainter(image)
        for x in range(0, 6000, 40):
            painter.fillRect(x, 100, 20, 3800, QColor.fromHsv(x % 256, 200, 200))
        painter.end()
        assert image.save(str(source), quality=92)
        del image
        peak = []
        stop = threading.Event()
        process = psutil.Process()
        before = process.memory_info().rss

        def sample():
            while not stop.wait(0.005):
                peak.append(process.memory_info().rss)

        thread = threading.Thread(target=sample)
        thread.start()
        samples = []
        try:
            for index in range(7):
                destination = root / f"{index}.png"
                start = time.perf_counter_ns()
                export_photo(source, destination, 90, 1200)
                samples.append((time.perf_counter_ns() - start) / 1e6)
            result = QImage(str(destination))
            assert (result.width(), result.height()) == (800, 1200)
        finally:
            stop.set()
            thread.join()
        return {
            "input_megapixels": 24, "longest_output_edge": 1200,
            "export_median_ms": round(statistics.median(samples), 2),
            "sampled_peak_rss_delta_mib": round((max([before, *peak]) - before) / 2**20, 1),
        }


if __name__ == "__main__":
    print(json.dumps({"mode": args.mode, "source": args.source.name, **globals()[args.mode]()}))
