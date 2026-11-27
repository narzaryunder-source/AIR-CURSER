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


def test_corrupt_settings_cannot_open_a_fresh_unprotected_workspace(tmp_path):
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
    from PySide6.QtTest import QTest
    from PySide6.QtCore import Qt
    from aircursor_desktop.ui import Desktop

    app = QApplication.instance() or QApplication([])
    s = Settings(str(tmp_path / "settings.json"))
    s.set(auto_switch=False, restore=False)
    desktop = Desktop(s)
    monkeypatch.setattr(desktop.work, "run", lambda *args: None)
    desktop.show()
    from PySide6.QtCore import QObject

    fields = [desktop.view.rootObject().findChild(QObject, n) for n in ("password", "confirmPassword")]
    for field in fields:
        field.setProperty("text", "workspace-test-master")
    fields[-1].forceActiveFocus()
    QTest.keyClick(desktop.view, Qt.Key.Key_Return)
    assert not desktop.locked and desktop.auth.has_password
    desktop.preference("calendar_widget", True)
    calendar = desktop.view.rootObject().findChild(QObject, "desktopCalendar")
    assert calendar and calendar.isVisible()
    original_month = calendar.property("month")
    calendar.shift(1)
    assert calendar.property("month") != original_month
    desktop.preference("calendar_widget", False)
    for name in ["Files", "Air Store", "Air AI", "Air Browser", "Settings", "Control Center"]:
        assert desktop.open(name) is not None
        assert desktop.panels[name].isVisible()
    desktop.show_overlay("spotlight")
    assert desktop.view.rootObject().property("overlay") == "spotlight"
    assert desktop.shell.results
    desktop.view.rootObject().setProperty("overlay", "")
    desktop.fullscreen()
    assert desktop.isFullScreen()
    desktop.exit_desktop()
    assert not desktop.isFullScreen()
    desktop.lock_now()
    assert desktop.host.paused and not any(p.isVisible() for p in desktop.panels.values())
    assert desktop.open("Air Browser") is None
    desktop.close()
    app.processEvents()


def test_normal_entry_point_reaches_locked_window(tmp_path):
    import subprocess
    import sys
    from pathlib import Path

    probe = """
from PySide6.QtCore import QTimer
from aircursor_desktop.ui import Desktop, main
show = Desktop.show
def opened(window):
    assert window.locked and window.view.rootObject() is not None
    show(window)
    QTimer.singleShot(50, window.close)
Desktop.show = opened
raise SystemExit(main())
"""
    env = {**os.environ, "AIRCURSOR_HOME": str(tmp_path), "QT_QPA_PLATFORM": "offscreen", "QT_QUICK_BACKEND": "software"}
    result = subprocess.run([sys.executable,"-c",probe],cwd=Path(__file__).resolve().parents[1],
                            env=env,capture_output=True,text=True,timeout=30)
    assert result.returncode==0, result.stderr


def test_authenticator_lock_screen_recovery_and_idle_lock(tmp_path, monkeypatch):
    import time
    from PySide6.QtCore import Qt, QObject, QMetaObject
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication
    from aircursor_desktop import second_factor, session, trust
    from aircursor_desktop.auth_dialogs import qr_image
    from aircursor_desktop.ui import Desktop

    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    app = QApplication.instance() or QApplication([])
    settings = Settings(str(tmp_path / "auth.json"))
    settings.set(auto_switch=False, restore=False)
    master = "private test master password"
    from aircursor_desktop.lock import PasswordLock
    PasswordLock(settings).set_password(master)
    monkeypatch.setattr(trust, "protect", lambda data, decrypt=False: bytes(x ^ 42 for x in data))
    secret, recovery = second_factor.enrollment()
    uri = second_factor.enrollment_uri(secret, "Office / 東京")
    from urllib.parse import parse_qs, urlsplit
    assert parse_qs(urlsplit(uri).query) == {
        "secret": [secret], "issuer": ["AirCursor"], "algorithm": ["SHA1"], "digits": ["6"], "period": ["30"],
    }
    qr = qr_image(uri)
    assert qr.pixelColor(0, 0).name() == "#ffffff"
    assert qr.pixelColor(20, 20).name() == "#000000"
    assert qr.pixelColor(25, 25).name() == "#ffffff"
    second_factor.enable(settings, secret, recovery, second_factor.code(secret, int(time.time()) // 30))
    window = Desktop(settings)
    window.show()
    app.processEvents()
    root = window.view.rootObject()
    choice = root.findChild(QObject, "authMethod")
    assert choice and choice.property("visible")
    assert QMetaObject.invokeMethod(choice, "clicked", Qt.ConnectionType.DirectConnection)
    entry = root.findChild(QObject, "password")
    assert entry.property("placeholderText") == "Phone or recovery code"
    entry.setProperty("text", recovery[0])
    entry.forceActiveFocus()
    QTest.keyClick(window.view, Qt.Key.Key_Return)
    assert not window.locked
    assert not window.host.paused
    assert len(settings.get("phone_factor")["recovery_codes"]) == 7
    monkeypatch.setattr(session, "idle_seconds", lambda: window.idle_timeout())
    window.check_inactivity()
    assert window.locked and window.host.paused
    assert entry.property("text") == ""
    window.unlock_with_code(recovery[0], False)
    assert window.locked
    window.unlock(master, "", False)
    assert not window.locked
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        ctypes.windll.user32.SendMessageW(wintypes.HWND(int(window.winId())), 0x02B1, 7, 0)
        assert window.locked and window.host.paused
    window.close()
    app.processEvents()
