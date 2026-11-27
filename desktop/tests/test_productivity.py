import json
import os
import shutil
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from aircursor_desktop import applications, connectivity, media, store
from aircursor_desktop.file_actions import file_mime, file_selection, valid_name
from aircursor_desktop.files import Files
from aircursor_desktop.hotspot import Hotspot
from aircursor_desktop.intelligence import api_call, endpoint_url, key_target
from aircursor_desktop.settings import Settings
from PySide6.QtCore import QMimeData, QUrl
from PySide6.QtGui import QColor, QImage
from PySide6.QtWidgets import QApplication


@pytest.fixture
def app(monkeypatch):
    if os.name != "nt" and not os.getenv("DISPLAY"):
        monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    return QApplication.instance() or QApplication([])


def test_package_rows_and_exact_selected_identity():
    lines = [
        "Name                Id                     Version",
        "--------------------------------------------------",
        "Visual Studio Code  Microsoft.VSCode       1.0",
        "Unusable row        Vendor.Truncat…        1.1",
    ]
    assert store.search_rows("\r\n".join(lines)) == [("Visual Studio Code", "Microsoft.VSCode")]
    assert store.search_rows("Access denied") == []
    apps = [("Codebook", "startapp:B"), ("Visual Studio Code", "startapp:A")]
    assert applications.match_apps("Code", apps) == [apps[1]]


def test_real_clipboard_and_restored_directories(app, tmp_path):
    paths = [tmp_path / "one.txt", tmp_path / "two.txt"]
    app.clipboard().setMimeData(file_mime(paths, True))
    assert file_selection(app.clipboard().mimeData()) == (paths, True)
    for path in paths:
        path.write_text("test")
    settings = Settings(str(tmp_path / "settings.json"))
    window = Files(settings)
    window.go(tmp_path)
    window.add(tmp_path.parent)
    window.tabs.currentWidget().toggle()
    window.tabs.setCurrentIndex(0)
    saved = window.session()
    second = Files(settings)
    second.restore_session(saved)
    assert second.session() == saved
    external = QMimeData()
    external.setUrls([QUrl("https://example.org/")])
    assert file_selection(external) == ([], False)
    app.clipboard().clear()
    second.deleteLater()
    window.deleteLater()


@pytest.mark.parametrize("name", ["", "CON", "bad.", "a/b", "a:b", "a\\b", "bad?", ".."])
def test_invalid_windows_names(name):
    assert not valid_name(name)


def test_photo_rotates_and_scales_without_overwriting(app, tmp_path):
    source, dest = tmp_path / "source.png", tmp_path / "new.png"
    img = QImage(8, 4, QImage.Format.Format_RGB32)
    img.fill(QColor("red"))
    img.setPixelColor(0, 0, QColor("blue"))
    assert img.save(str(source))
    original = source.read_bytes()
    media.export_photo(source, dest, 90, 0)
    edited = QImage(str(dest))
    assert (edited.width(), edited.height()) == (4, 8)
    assert edited.pixelColor(3, 0) == QColor("blue")
    with pytest.raises(FileExistsError):
        media.export_photo(source, dest, 90, 4)
    media.export_photo(source, tmp_path / "small.png", 90, 4)
    small = QImage(str(tmp_path / "small.png"))
    assert (small.width(), small.height()) == (2, 4)
    assert original == source.read_bytes()


@pytest.mark.skipif(
    not shutil.which("ffmpeg") or not shutil.which("ffprobe"), reason="requires installed FFmpeg"
)
def test_video_trim_through_files_action(app, tmp_path, monkeypatch):
    source, dest = tmp_path / "original.mp4", tmp_path / "clip.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x64:rate=20",
            "-t",
            "2",
            "-c:v",
            "mpeg4",
            str(source),
        ],
        check=True,
        timeout=20,
    )
    before = source.read_bytes()
    window = Files(Settings(str(tmp_path / "settings.json")))
    monkeypatch.setattr(media, "parameters", lambda *args: [0.3, 1.1])
    monkeypatch.setattr(media, "destination", lambda *args: str(dest))
    media.trim_video(window, source)
    assert dest.exists(), window.status.text()
    result = subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(dest)],
        text=True,
        timeout=10,
    )
    assert float(result.strip()) == pytest.approx(0.8, abs=0.06)
    assert before == source.read_bytes()
    monkeypatch.setattr(media, "parameters", lambda *args: [3.0, 4.0])
    missing = tmp_path / "past-the-end.mp4"
    monkeypatch.setattr(media, "destination", lambda *args: str(missing))
    media.trim_video(window, source)
    assert not missing.exists()
    window.deleteLater()


@pytest.mark.parametrize(
    "url",
    [
        "http://example.org/v1",
        "https://user:key@example.org/v1",
        "https://example.org/v1?key=test",
        "https://example.org/v1#secret",
    ],
)
def test_rejects_unsafe_endpoints(url):
    with pytest.raises(ValueError):
        endpoint_url(url)


def test_compatible_api_requests_and_endpoint_scoped_credentials():
    requests = []

    class API(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.path)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"data": [{"id": "contract-fixture"}]}')

        def do_POST(self):
            requests.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
            self.send_response(503)
            self.end_headers()

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), API) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}/v1"
        try:
            assert api_call("Custom", "/models", custom=base)["data"]
            with pytest.raises(ValueError, match="503"):
                api_call("Custom", "/chat/completions", {"messages": []}, base)
        finally:
            server.shutdown()
    assert requests == ["/v1/models", {"messages": []}]
    assert key_target("Custom", base) != key_target("Custom", base + "/other")


def test_hotspot_requires_selected_interface_even_when_host_is_online(tmp_path, monkeypatch):
    settings = Settings(str(tmp_path / "s.json"))
    settings.set(phone_ssid="Phone")

    class Native:
        def interfaces(self):
            return [{"ssid": "Phone", "state": "connected", "id": b"phone"}]

        def interface_index(self, guid):
            assert guid == b"phone"
            return 17

    hot = Hotspot(settings, runner=lambda *_: None, online=lambda: True, sleep=lambda *_: None)
    hot.native = Native()

    def failed(index):
        assert index == 17
        return connectivity.Health("Possible captive portal")

    monkeypatch.setattr("aircursor_desktop.hotspot.probe_interface", failed)
    assert "captive portal" in hot.connect()
    monkeypatch.setattr(
        "aircursor_desktop.hotspot.probe_interface", lambda i: connectivity.Health("Internet verified")
    )
    assert hot.connect() == ""


@pytest.mark.skipif(os.name != "nt", reason="requires genuine Windows Shell and Credential Manager")
def test_native_start_catalog_and_credential_roundtrip():
    from uuid import uuid4

    from aircursor_desktop.intelligence import credential

    identifier = "Test-" + str(uuid4())
    assert applications.registered_apps()
    try:
        credential(identifier, "test-fixture")
        assert credential(identifier) == "test-fixture"
    finally:
        credential(identifier, "")


@pytest.mark.skipif(os.name != "nt", reason="requires Winsock IP_UNICAST_IF")
def test_native_interface_bound_http():
    import ctypes
    import socket
    import sys

    route = ctypes.c_uint32()
    address = int.from_bytes(socket.inet_aton("127.0.0.1"), sys.byteorder)
    assert ctypes.windll.iphlpapi.GetBestInterface(ctypes.c_uint32(address), ctypes.byref(route)) == 0
    index = route.value

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(204)
            self.end_headers()

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        conn = connectivity.InterfaceHTTPConnection(f"127.0.0.1:{server.server_port}", index, 2)
        try:
            conn.request("GET", "/")
            assert conn.getresponse().status == 204
        finally:
            conn.close()
            server.shutdown()


@pytest.mark.skipif(os.name == "nt", reason="POSIX test process for winget output replay")
def test_store_search_click_and_package_approval(app, tmp_path, monkeypatch):
    import time

    from PySide6.QtCore import QProcess
    from PySide6.QtTest import QTest

    fixture = tmp_path / "winget.exe"
    fixture.write_text(
        "#!/bin/sh\nprintf 'Name         Id              Version\\n------------------------------------\\nEditor       Vendor.Editor   2.0\\n'\n"
    )
    fixture.chmod(0o700)
    monkeypatch.setenv("PATH", str(tmp_path))
    window = store.Store()
    window.show()
    window.query.setText("Editor")
    window.run("search")
    deadline = time.monotonic() + 3
    while window.process.state() != QProcess.ProcessState.NotRunning and time.monotonic() < deadline:
        QTest.qWait(10)
    assert window.apps.currentItem().text() == "Editor"
    assert window.query.text() == "Vendor.Editor"
    approvals = []
    monkeypatch.setattr(
        store, "confirm", lambda parent, title, message: approvals.append(message) and False
    )
    window.install.click()
    assert "Vendor.Editor" in approvals[0]
    assert window.process.state() == QProcess.ProcessState.NotRunning
    window.close()


def test_phone_factor_enrollment_replay_throttle_and_recovery(tmp_path, monkeypatch):
    import base64

    from aircursor_desktop import second_factor as factor

    # RFC 6238 SHA-1 vector at time 59, truncated to six digits.
    assert factor.code(base64.b32encode(b"12345678901234567890").decode(), 1) == "287082"
    clock = [1700000000]
    monkeypatch.setattr(factor.time, "time", lambda: clock[0])
    monkeypatch.setattr(factor.trust, "protect", lambda data, decrypt=False: bytes(x ^ 42 for x in data))
    s = Settings(str(tmp_path / "s.json"))
    s.set(trusted_logon="old")
    secret, recovery = factor.enrollment()
    current = lambda: factor.code(secret, clock[0] // 30)
    factor.enable(s, secret, recovery, current())
    saved = (tmp_path / "s.json").read_text()
    assert secret not in saved and all(value not in saved for value in recovery)
    assert not s.get("trusted_logon")
    with pytest.raises(ValueError, match="fresh six-digit"):
        factor.verify(s, current())
    with pytest.raises(ValueError, match="again in"):
        factor.verify(Settings(str(tmp_path / "s.json")), recovery[0])
    clock[0] += 30
    assert factor.verify(s, current()) == "verified"
    assert factor.verify(s, recovery[0]) == "recovered"
    assert len(s.get("phone_factor")["recovery_codes"]) == 7
    with pytest.raises(ValueError):
        factor.verify(s, recovery[0])


def test_publisher_checksum_comparison():
    from aircursor_desktop.installer import checksum_matches

    expected = "ab" * 32
    assert checksum_matches("", expected)
    assert checksum_matches("  " + expected.upper(), expected)
    assert not checksum_matches(expected[:-1], expected)
    assert not checksum_matches("00" * 32, expected)


@pytest.mark.skipif(os.name != "nt", reason="requires Windows file sharing and Authenticode")
def test_inspected_installer_cannot_be_replaced_while_approved(tmp_path):
    from pathlib import Path
    from aircursor_desktop.installer import locked_inspection

    original = Path(os.environ["SystemRoot"]) / "System32" / "whoami.exe"
    copied = tmp_path / "system-tool.exe"
    shutil.copyfile(original, copied)
    lease, result = locked_inspection(copied)
    with lease:
        assert len(result["sha256"]) == 64
        with pytest.raises(PermissionError):
            copied.unlink()
    copied.unlink()


def test_external_drop_never_tells_source_to_delete_when_copying(app, tmp_path, monkeypatch):
    from PySide6.QtCore import QPointF, Qt
    from PySide6.QtGui import QDropEvent

    files = Files(Settings(str(tmp_path / "s.json")))
    dropped = tmp_path / "source"
    dropped.write_text("original")
    mime = file_mime([dropped])
    calls = []
    monkeypatch.setattr(files, "transfer", lambda paths, move: calls.append((paths, move)))
    monkeypatch.setattr("aircursor_desktop.files.confirm", lambda *_: True)

    def drop(actions):
        event = QDropEvent(QPointF(10, 10), actions, mime, Qt.MouseButton.LeftButton, Qt.KeyboardModifier.ShiftModifier)
        files.tabs.currentWidget().dropEvent(event)
        return event

    assert not drop(Qt.DropAction.MoveAction).isAccepted()
    event = drop(Qt.DropAction.MoveAction | Qt.DropAction.CopyAction)
    assert event.isAccepted() and event.dropAction() == Qt.DropAction.CopyAction
    assert calls == [([dropped], False)]
    files.deleteLater()


@pytest.mark.skipif(os.name != "nt", reason="requires Windows DPAPI and shell copy/move")
def test_native_trust_ticket_and_file_operations(tmp_path):
    from aircursor_desktop import trust
    from aircursor_desktop.files import shell_transfer
    from aircursor_desktop.lock import PasswordLock

    settings = Settings(str(tmp_path / "settings.json"))
    lock = PasswordLock(settings)
    lock.set_password("test-master-password", "test-master-password")
    trust.remember(settings)
    assert trust.active(settings)
    lock.set_password("changed-test-password", "changed-test-password")
    assert not trust.active(settings)
    source = tmp_path / "first.txt"
    source.write_text("Native Shell transfer")
    folder = tmp_path / "folder"
    folder.mkdir()
    shell_transfer([source], folder, False, 0)
    assert (folder / source.name).read_bytes() == source.read_bytes()
    second = tmp_path / "second"
    second.mkdir()
    shell_transfer([folder / source.name], second, True, 0)
    assert not (folder / source.name).exists()
    assert (second / source.name).read_bytes() == source.read_bytes()
