import os
import zipfile

import pytest
from aircursor_desktop.browser import web_url
from aircursor_desktop.files import extract_new, zip_new
from aircursor_desktop.intelligence import free_model
from aircursor_desktop.launcher import calculate
from aircursor_desktop.settings import Settings
from aircursor_desktop.store import arguments


def test_browser_uses_local_address_and_port_for_development_apps():
    assert web_url("localhost:8080") == "http://localhost:8080"
    assert web_url("[::1]:9200/index") == "http://[::1]:9200/index"
    assert web_url("example.org:443/download") == "https://example.org:443/download"
    assert web_url("javascript:alert(1)").startswith("https://duckduckgo.com/?q=")


def test_store_arguments_are_exact_and_restricted_to_source():
    assert arguments("install", "Mozilla.Firefox") == [
        "install",
        "--id",
        "Mozilla.Firefox",
        "--exact",
        "--source",
        "winget",
        "--disable-interactivity",
    ]
    with pytest.raises(ValueError):
        arguments("install", "--force")


def test_web_navigation_and_free_prices():
    assert web_url("file:///C:/Windows").startswith("https://duckduckgo.com/")
    assert web_url("example.org") == "https://example.org"
    assert free_model({"pricing": {"prompt": "0", "completion": "0.0"}})
    assert not free_model({"pricing": {"prompt": 0}})
    assert not free_model({"pricing": {"prompt": "0", "completion": "1"}})


@pytest.mark.parametrize(
    "text,result", [("(2+3)*4", "20"), ("9/0", None), ("2**40000", None), ("abs(-4)", None)]
)
def test_search_calculator(text, result):
    assert calculate(text) == result


def test_archive_roundtrip_without_overwrite(tmp_path):
    file = tmp_path / "note.txt"
    file.write_text("notes")
    archive = zip_new(file)
    with pytest.raises(FileExistsError):
        zip_new(file)
    with pytest.raises(FileExistsError):
        extract_new(archive)
    file.unlink()
    assert (extract_new(archive) / "note.txt").read_text() == "notes"


@pytest.mark.parametrize(
    "entry", ["../elsewhere", "C:/Windows/test", "folder/name:stream", "CON", "trailing."]
)
def test_archive_rejects_unsafe_names(tmp_path, entry):
    archive = tmp_path / "bad.zip"
    with zipfile.ZipFile(archive, "w") as z:
        z.writestr("normal", "file")
        z.writestr(entry, "test")
    with pytest.raises(ValueError):
        extract_new(archive)
    assert not (tmp_path / "bad").exists()


def test_corrupt_settings_preserves_the_saved_file(tmp_path):
    path = tmp_path / "settings.json"
    path.write_text("{")
    with pytest.raises(ValueError):
        Settings(str(path))
    assert path.read_text() == "{"


def test_failed_write_keeps_old_state(tmp_path, monkeypatch):
    path = tmp_path / "settings.json"
    saved = Settings(str(path))
    saved.set(phone_ssid="Known")

    def fail(*_):
        raise OSError("disk unavailable")

    monkeypatch.setattr(os, "replace", fail)
    with pytest.raises(OSError):
        saved.set(phone_ssid="Other")
    assert saved.get("phone_ssid") == "Known"
    assert Settings(str(path)).get("phone_ssid") == "Known"


def test_real_widget_paths_lock_and_exit(tmp_path, monkeypatch):
    if os.name != "nt":
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.ui import Desktop

    app = QApplication.instance() or QApplication([])
    s = Settings(str(tmp_path / "settings.json"))
    s.set(auto_switch=False, restore=False)
    desktop = Desktop(s)
    monkeypatch.setattr(desktop.work, "run", lambda *args: None)
    desktop.show()
    from PySide6.QtCore import QObject

    assert not desktop.locked
    assert desktop.view.rootObject().findChild(QObject, "password") is None
    desktop.preference("calendar_widget", True)
    calendar = desktop.view.rootObject().findChild(QObject, "desktopCalendar")
    assert calendar and calendar.isVisible()
    original_month = calendar.property("month")
    calendar.shift(1)
    assert calendar.property("month") != original_month
    desktop.preference("calendar_widget", False)
    for name in ["Files", "Air Store", "Air AI", "Air Browser", "Settings"]:
        assert desktop.open(name) is not None
        assert desktop.panels[name].isVisible()
    desktop.shell.action("hotspot")
    settings = desktop.panels["Settings"].content
    assert settings.nav.currentItem().text() == "Hotspot"
    desktop.shell.action("remote")
    assert settings.nav.currentItem().text() == "Connected phone"
    assert "Control Center" not in desktop.panels
    from types import SimpleNamespace

    def stopped():
        error = OSError("The service has not been started")
        error.winerror = 1062
        raise error

    monkeypatch.setattr(desktop.hot, "wifi", stopped)
    monkeypatch.setattr(desktop.hot, "check_internet", lambda: setattr(
        desktop.hot, "health", SimpleNamespace(state="Internet verified", latency_ms=9)))
    assert desktop.sample_network_status() == "Windows Wi-Fi service is stopped.\nInternet verified · 9 ms"
    desktop.show_overlay("spotlight")
    assert desktop.view.rootObject().property("overlay") == "spotlight"
    assert desktop.shell.results
    desktop.view.rootObject().setProperty("overlay", "")
    desktop.fullscreen()
    assert desktop.isFullScreen()
    desktop.exit_desktop()
    assert not desktop.isFullScreen()
    desktop.suspend_workspace()
    assert desktop.host.paused and not any(p.isVisible() for p in desktop.panels.values())
    assert desktop.open("Air Browser") is None
    desktop.close()
    app.processEvents()


@pytest.mark.parametrize("fullscreen", [True, False])
def test_normal_entry_point_opens_without_an_aircursor_password(tmp_path, fullscreen):
    import subprocess
    import sys
    from pathlib import Path

    Settings(str(tmp_path / "settings.json")).set(start_fullscreen=fullscreen)
    probe = f"""
from PySide6.QtCore import QTimer
from aircursor_desktop.ui import Desktop, main
start = Desktop.start
def opened(window):
    start(window)
    assert not window.locked and window.isFullScreen() is {fullscreen} and window.view.rootObject() is not None
    window.exit_desktop()
    assert not window.isFullScreen()
    QTimer.singleShot(50, window.close)
Desktop.start = opened
raise SystemExit(main())
"""
    env = {
        **os.environ,
        "AIRCURSOR_HOME": str(tmp_path),
        "QT_QPA_PLATFORM": "offscreen",
        "QT_QUICK_BACKEND": "software",
    }
    result = subprocess.run(
        [sys.executable, "-c", probe],
        cwd=Path(__file__).resolve().parents[1],
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr


def test_windows_session_controls_workspace_and_phone_input(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop import session
    from aircursor_desktop.ui import Desktop

    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    app = QApplication.instance() or QApplication([])
    settings = Settings(str(tmp_path / "session.json"))
    settings.set(
        auto_switch=False, restore=False, password={"hash": "legacy-preview"}, phone_factor={"legacy": True}
    )
    monkeypatch.setattr(session, "interactive_desktop", lambda: True)
    monkeypatch.setattr(session, "watch", lambda *_: True)
    window = Desktop(settings)
    window.work.run = lambda *_: None
    window.show()
    assert not window.locked and not window.host.paused
    assert settings.get("password") == {"hash": "legacy-preview"}
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes

        ctypes.windll.user32.SendMessageW(wintypes.HWND(int(window.winId())), session.SESSION_CHANGE, 7, 0)
    else:
        window.handle_session_message(session.SESSION_CHANGE, 7)
    assert window.locked and window.host.paused
    window.handle_session_message(session.POWER_CHANGE, 18)
    assert window.locked and window.host.paused
    window.handle_session_message(session.SESSION_CHANGE, 8)
    assert not window.locked and not window.host.paused
    monkeypatch.setattr(session, "interactive_desktop", lambda: False)
    window.check_windows_session()
    assert window.host.paused and not window.locked
    window.close()
    app.processEvents()


def test_safe_mode_preserves_saved_layout_and_tabs(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.ui import Desktop

    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    app = QApplication.instance() or QApplication([])
    settings = Settings(str(tmp_path / "safe.json"))
    saved = {
        "restore": True,
        "active_space": 2,
        "session_panels": {"Files": 2, "Air Browser": 2},
        "browser_tabs": ["https://example.org/previous"],
        "auto_switch": True,
        "remote_on": True,
    }
    settings.set(**saved)
    desk = Desktop(settings, safe_mode=True)
    desk.work.run = lambda *_: None
    desk.show()
    assert not desk.locked and not desk.panels and not desk.host.running
    assert desk.space == 1 and not desk.effects()
    browser = desk.open("Air Browser")
    assert browser.tabs.count() == 1 and browser.normal is None
    browser.save()
    desk.close()
    app.processEvents()
    assert all(settings.get(key) == value for key, value in saved.items())


@pytest.mark.parametrize("stage", [False, True])
def test_workspace_restores_after_first_frame_and_respects_space_and_overlays(tmp_path, monkeypatch, stage):
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.ui import Desktop
    from aircursor_desktop import session

    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    monkeypatch.setattr(session, "interactive_desktop", lambda: True)
    app = QApplication.instance() or QApplication([])
    saved = {"Files": 1, "Air Browser": 1, "Air Store": 2}
    settings = Settings(str(tmp_path / "restore.json"))
    settings.set(auto_switch=False, session_panels=saved, stage=stage)
    desk = Desktop(settings)
    desk.work.run = lambda *_: None

    def until(check):
        for _ in range(100):
            if check():
                return
            QTest.qWait(10)
        assert check()

    try:
        desk.show()
        assert not desk.panels
        desk.show_overlay("spotlight")
        QTest.qWait(75)
        assert not desk.panels
        desk.view.rootObject().setProperty("overlay", "")
        expected = {"Air Browser"} if stage else {"Files", "Air Browser"}
        until(lambda: set(desk.panels) == expected)
        desk.switch_space(2)
        until(lambda: "Air Store" in desk.panels and desk.panels["Air Store"].isVisible())
        assert all(not desk.panels[name].isVisible() for name in expected)
        assert desk.opened == saved
        desk.switch_space(1)
        desk.suspend_workspace()
        QTest.qWait(75)
        assert not any(panel.isVisible() for panel in desk.panels.values())
        assert settings.get("session_panels") == saved
    finally:
        desk.close()
        app.processEvents()


def test_unreadable_saved_layout_does_not_prevent_unlock(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.ui import Desktop

    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    app = QApplication.instance() or QApplication([])
    settings = Settings(str(tmp_path / "broken-layout.json"))
    settings.set(panel_geometry=["invalid"], session_panels=["invalid"], auto_switch=False)
    desk = Desktop(settings)
    desk.work.run = lambda *_: None
    desk.show()
    assert not desk.locked
    assert desk.open("Files") is not None
    desk.close()
    app.processEvents()


def test_spotlight_shows_shortcuts_while_start_catalog_is_pending(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop import surface
    from aircursor_desktop.ui import Desktop

    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    app = QApplication.instance() or QApplication([])
    settings = Settings(str(tmp_path / "spotlight.json"))
    settings.set(auto_switch=False, restore=False)
    desk = Desktop(settings)
    pending = {}
    desk.work.run = lambda task, done, failed: pending.update({task: (done, failed)})
    try:
        desk.show()
        desk.shell.search("AirCursor test")
        assert surface.shortcuts in pending and surface.registered_apps in pending
        eager, _ = pending.pop(surface.shortcuts)
        shortcut = str(tmp_path / "AirCursor test.lnk")
        eager([("AirCursor test", shortcut)])
        assert any(result["target"] == shortcut for result in desk.shell.results)
        later, _ = pending.pop(surface.registered_apps)
        later([("AirCursor test app", "startapp:AirCursor.Test")])
        assert {shortcut, "startapp:AirCursor.Test"} <= {result["target"] for result in desk.shell.results}
    finally:
        desk.close()
        app.processEvents()


def test_inactive_workspace_only_samples_hotspot_traffic(tmp_path, monkeypatch):
    from PySide6.QtCore import Qt
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.ui import Desktop

    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    app = QApplication.instance() or QApplication([])
    settings = Settings(str(tmp_path / "performance.json"))
    settings.set(auto_switch=False, restore=False)
    desk = Desktop(settings)
    jobs = []
    desk.work.run = lambda task, done, failed: jobs.append(task)
    try:
        desk.show()
        desk.application_state(Qt.ApplicationState.ApplicationInactive)
        jobs.clear()
        desk.metrics_pending = False
        desk.net_pending = False
        desk.tick()
        assert jobs == [] and desk.timer.interval() == 30_000
        settings.set(auto_switch=True, phone_ssid="Saved phone")
        desk.profile("Balanced", save=False)
        desk.tick()
        assert len(jobs) == 1 and desk.timer.interval() <= 8000
        desk.metrics_pending = False
        jobs.clear()
        desk.application_state(Qt.ApplicationState.ApplicationActive)
        assert desk.sample_metrics in jobs and not desk.background
    finally:
        desk.close()
        app.processEvents()


def test_a_failed_save_does_not_interrupt_workspace_shutdown(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop.ui import Desktop

    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    app = QApplication.instance() or QApplication([])
    settings = Settings(str(tmp_path / "shutdown.json"))
    settings.set(auto_switch=False)
    desk = Desktop(settings)
    desk.work.run = lambda *_: None
    desk.show()

    def fail():
        raise OSError("fixture write failure")

    monkeypatch.setattr(desk, "persist", fail)
    stopped = []
    original_stop = desk.host.stop

    def stop():
        stopped.append(True)
        original_stop()

    monkeypatch.setattr(desk.host, "stop", stop)
    assert desk.close() and stopped and desk.locked
    assert not desk.timer.isActive() and not desk.security_timer.isActive()
    app.processEvents()
