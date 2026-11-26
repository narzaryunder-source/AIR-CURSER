import os
from pathlib import Path
import zipfile

import pytest
from aircursor_desktop.browser import web_url
from aircursor_desktop.files import extract_new, zip_new
from aircursor_desktop.intelligence import free_model
from aircursor_desktop.launcher import calculate
from aircursor_desktop.settings import Settings
from aircursor_desktop.store import arguments


def test_store_arguments_are_exact_and_restricted_to_source():
    assert arguments("install", "Mozilla.Firefox") == ["install", "--id", "Mozilla.Firefox", "--exact",
                                                         "--source", "winget", "--disable-interactivity"]
    with pytest.raises(ValueError):
        arguments("install", "--force")


def test_web_navigation_and_free_prices():
    assert web_url("file:///C:/Windows").startswith("https://duckduckgo.com/")
    assert web_url("example.org") == "https://example.org"
    assert free_model({"pricing": {"prompt": "0", "completion": "0.0"}})
    assert not free_model({"pricing": {"prompt": 0}})
    assert not free_model({"pricing": {"prompt": "0", "completion": "1"}})


@pytest.mark.parametrize("text,result", [("(2+3)*4","20"), ("9/0",None), ("2**40000",None), ("abs(-4)",None)])
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


@pytest.mark.parametrize("entry", ["../elsewhere", "C:/Windows/test", "folder/name:stream", "CON", "trailing."])
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
    from PySide6.QtWidgets import QApplication, QLineEdit
    from PySide6.QtTest import QTest
    from PySide6.QtCore import Qt
    from aircursor_desktop.ui import Desktop
    app = QApplication.instance() or QApplication([])
    s = Settings(str(tmp_path / "settings.json"))
    s.set(auto_switch=False, restore=False)
    desktop = Desktop(s)
    monkeypatch.setattr(desktop.work, "run", lambda *args: None)
    desktop.show()
    fields = desktop.findChildren(QLineEdit)
    for field in fields:
        field.setText("workspace-test")
    QTest.keyClick(fields[-1], Qt.Key.Key_Return)
    assert not desktop.locked and desktop.auth.has_password
    for name in ["Files", "Air Store", "Air AI", "Launcher", "Settings", "Control Center"]:
        assert desktop.open(name) is not None
        assert desktop.panels[name].isVisible()
    desktop.fullscreen()
    assert desktop.isFullScreen()
    desktop.exit_desktop()
    assert not desktop.isFullScreen()
    desktop.lock_now()
    assert desktop.host.paused and not any(p.isVisible() for p in desktop.panels.values())
    assert desktop.open("Air Browser") is None
    desktop.close()
