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


def test_image_preview_decodes_on_worker(app, tmp_path, monkeypatch):
    import threading
    from PySide6.QtCore import QTimer
    from aircursor_desktop import files

    image = QImage(1600, 900, QImage.Format.Format_RGB32)
    image.fill(0xff123a53)
    path = tmp_path / "preview.jpg"
    assert image.save(str(path))
    preview = files.read_preview(path)
    assert (preview.width(), preview.height()) == (720, 405)

    window = Files(Settings(str(tmp_path / "settings.json")))
    result = []
    read = files.read_preview

    def record(path):
        result.append(threading.get_ident())
        return read(path)

    monkeypatch.setattr(files, "read_preview", record)
    timer = QTimer(window, interval=10)

    def dismiss():
        dialog = app.activeModalWidget()
        if result and dialog:
            dialog.reject()

    timer.timeout.connect(dismiss)
    timer.start()
    QTimer.singleShot(3000, window, lambda: app.activeModalWidget() and app.activeModalWidget().reject())
    window.preview(path)
    timer.stop()
    assert result and result[0] != threading.get_ident()
    window.deleteLater()


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


def test_lightweight_ollama_download_and_chat_from_air_ai(app, tmp_path, monkeypatch):
    from PySide6.QtTest import QTest
    from aircursor_desktop import intelligence as ai

    calls = []
    installed = []

    class Ollama(BaseHTTPRequestHandler):
        def do_GET(self):
            calls.append((self.path, None))
            self.reply(json.dumps({"models": installed}).encode())

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            calls.append((self.path, payload))
            if self.path == "/api/pull":
                installed.append({"name": ai.LIGHT_MODEL, "size": 523_000_000})
                self.reply(b'{"status":"pulling"}\n{"status":"success"}\n')
            else:
                self.reply(b'{"done":true,"message":{"content":"Paris"},"eval_count":2,"prompt_eval_count":8}')

        def reply(self, data):
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_):
            pass

    def until(check):
        for _ in range(250):
            if check():
                return
            QTest.qWait(10)
        assert check()

    with ThreadingHTTPServer(("127.0.0.1", 0), Ollama) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        monkeypatch.setattr(ai, "OLLAMA_BASE", f"http://127.0.0.1:{server.server_port}")
        settings = Settings(str(tmp_path / "ai.json"))
        panel = ai.Intelligence(settings, lambda _: None, lambda: None)
        panel.lightweight.setChecked(True)
        until(lambda: calls and "download it once" in panel.status.text())
        monkeypatch.setattr(ai, "confirm", lambda *_: False)
        panel.download_lightweight()
        assert not any(path == "/api/pull" for path, _ in calls)
        monkeypatch.setattr(ai, "confirm", lambda *_: True)
        panel.download_lightweight()
        until(lambda: panel.model.currentText() == ai.LIGHT_MODEL)
        panel.prompt.setPlainText("What is the capital of France?")
        panel.send()
        until(lambda: not panel.pending)
        assert "Paris" in panel.log.toPlainText()
        assert "10 tokens" in panel.status.text()
        assert settings.get("ai_lightweight") is True
        assert settings.get("conversations")[0]["messages"][-1]["content"] == "Paris"
        panel.close()
        server.shutdown()
    assert [path for path, _ in calls] == ["/api/tags", "/api/pull", "/api/tags", "/api/chat"]
    payload = calls[-1][1]
    assert payload["model"] == ai.LIGHT_MODEL
    assert payload["think"] is False and payload["keep_alive"] == 0
    assert payload["options"]["num_ctx"] == 2048 and payload["options"]["num_predict"] == 256
    assert 1 <= payload["options"]["num_thread"] <= 4


def test_lightweight_chat_bounds_input_without_trimming_saved_history():
    from aircursor_desktop.intelligence import LIGHT_PROMPT_BYTES, lightweight_messages, read_model_download
    from io import BytesIO

    user = {"role": "user", "content": "问"}
    assistant = {"role": "assistant", "content": "答" * 1000}
    history = [user, assistant] * 6 + [user]
    assert lightweight_messages(history) == history[-5:]
    assert len(history) == 13
    with pytest.raises(ValueError, match="Shorten"):
        lightweight_messages([{"role": "user", "content": "é" * LIGHT_PROMPT_BYTES}])
    with pytest.raises(ValueError, match="without reporting success"):
        read_model_download(BytesIO(b'{"status":"pulling"}\n'))


def test_scheduled_chat_runs_once_and_keeps_project_when_air_ai_is_closed(app, tmp_path, monkeypatch):
    from types import SimpleNamespace
    from PySide6.QtTest import QTest
    from aircursor_desktop import intelligence, scheduled

    now = 1_780_000_000
    monkeypatch.setattr(scheduled, "time", SimpleNamespace(time=lambda: now))
    calls = []

    def chat(messages):
        calls.append(messages)
        return "A real client would render this response.", 8

    monkeypatch.setattr(intelligence, "lightweight_chat", chat)
    settings = Settings(str(tmp_path / "schedule.json"))
    settings.set(ai_projects=[{"id": "home", "name": "Home"}])
    tasks = scheduled.Scheduler(settings)
    tasks.add("One-time task", "Summarize these notes", now - 5, False, "home")
    tasks.run_due()
    assert calls == []
    tasks.activate(True)
    for _ in range(100):
        if settings.get("conversations"):
            break
        QTest.qWait(10)
    tasks.run_due()
    assert len(calls) == 1
    assert tasks.tasks()[0]["status"] == "Completed"
    assert not tasks.tasks()[0]["enabled"] and not tasks.timer.isActive()
    saved = Settings(str(tmp_path / "schedule.json")).get("conversations")
    assert saved[0]["project"] == "home"
    assert saved[0]["messages"][-1]["content"] == "A real client would render this response."
    air_ai = intelligence.Intelligence(settings, lambda _: None, lambda: None, scheduler=tasks)
    air_ai.project.setCurrentIndex(air_ai.project.findData("home"))
    assert air_ai.sessions.currentText() == "One-time task"
    air_ai.new()
    assert air_ai.selected_chat()["project"] == "home"
    air_ai.close()
    tasks.activate(False)


def test_ai_connector_requires_remote_approval_and_excludes_attachment_from_saved_chat(app, tmp_path, monkeypatch):
    from aircursor_desktop import intelligence as ai
    from aircursor_desktop.ai_context import MAX_CONTEXT_BYTES, read_text_context

    path = tmp_path / "note.txt"
    path.write_text("Selected source text", encoding="utf-8")
    huge = tmp_path / "huge.txt"
    huge.write_bytes(b"x" * (MAX_CONTEXT_BYTES + 1))
    with pytest.raises(ValueError, match="24 KiB"):
        read_text_context(huge)

    settings = Settings(str(tmp_path / "connectors.json"))
    panel = ai.Intelligence(settings, lambda _: None, lambda: None)
    panel.attach(*read_text_context(path))
    panel.provider.setCurrentText("OpenRouter")
    panel.model.addItem("fixture-model")
    panel.model.setCurrentIndex(0)
    panel.prompt.setPlainText("Summarize the attachment")
    recorded = []

    def api_call(provider, path, payload, **kwargs):
        recorded.append(payload)
        return {"choices": [{"message": {"content": "Summary"}}]}

    monkeypatch.setattr(ai, "api_call", api_call)
    monkeypatch.setattr(panel.work, "run", lambda fetch, done, fail: done(fetch()))
    monkeypatch.setattr(ai, "confirm", lambda *_: False)
    panel.send()
    assert not recorded and panel.attachment
    monkeypatch.setattr(ai, "confirm", lambda *_: True)
    panel.send()
    assert "Selected source text" in recorded[0]["messages"][-1]["content"]
    assert settings.get("conversations")[0]["messages"][0]["content"] == "Summarize the attachment"
    assert panel.attachment is None
    panel.close()


def test_ai_replies_append_only_to_the_matching_conversation(app, tmp_path, monkeypatch):
    from aircursor_desktop import intelligence as ai

    settings = Settings(str(tmp_path / "ai.json"))
    panel = ai.Intelligence(settings, lambda _: None, lambda: None)
    panel.model.addItem("test-model")
    panel.model.setCurrentIndex(0)
    jobs = []
    panel.work.run = lambda fetch, done, failed: jobs.append(done)
    panel.prompt.setPlainText("First question")
    panel.send()
    panel.new()
    jobs.pop()(("First answer", 5))
    assert panel.log.toPlainText() == ""
    panel.sessions.setCurrentIndex(0)
    assert panel.log.toPlainText() == "User\nFirst question\n\nAssistant\nFirst answer"
    assert panel.sessions.itemText(0) == "First question"
    monkeypatch.setattr(panel.log, "setPlainText", lambda _: pytest.fail("replaced the entire transcript"))
    panel.prompt.setPlainText("Second question")
    panel.send()
    jobs.pop()(("Second answer", 8))
    assert panel.log.toPlainText().endswith("\n\nUser\nSecond question\n\nAssistant\nSecond answer")
    assert len(settings.get("conversations")[0]["messages"]) == 4
    panel.close()


def test_windows_catalog_reuses_snapshot_until_expiry_or_package_change(monkeypatch):
    from types import SimpleNamespace
    from aircursor_desktop import powershell

    monkeypatch.setattr(applications, "os", SimpleNamespace(name="nt"))
    stamp = [0]
    runs = []
    monkeypatch.setattr(applications, "time", SimpleNamespace(monotonic=lambda: stamp[0]))

    def start_apps(script, timeout):
        runs.append(script)
        return SimpleNamespace(
            stdout=b'[{"Name":"Test","AppID":"test.id"}]', check_returncode=lambda: None
        )

    monkeypatch.setattr(powershell, "run", start_apps)
    applications.invalidate_registered_apps()
    try:
        apps = [("Test", "startapp:test.id")]
        assert applications.registered_apps() == apps
        applications.registered_apps().clear()
        assert applications.registered_apps() == apps and len(runs) == 1
        stamp[0] = 31
        assert applications.registered_apps() == apps and len(runs) == 2
        applications.invalidate_registered_apps()
        assert applications.registered_apps() == apps and len(runs) == 3
    finally:
        applications.invalidate_registered_apps()


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
        event = QDropEvent(
            QPointF(10, 10), actions, mime, Qt.MouseButton.LeftButton, Qt.KeyboardModifier.ShiftModifier
        )
        files.tabs.currentWidget().dropEvent(event)
        return event

    assert not drop(Qt.DropAction.MoveAction).isAccepted()
    event = drop(Qt.DropAction.MoveAction | Qt.DropAction.CopyAction)
    assert event.isAccepted() and event.dropAction() == Qt.DropAction.CopyAction
    assert calls == [([dropped], False)]
    files.deleteLater()


@pytest.mark.skipif(os.name != "nt", reason="requires Windows shell copy/move")
def test_native_file_operations(tmp_path):
    from aircursor_desktop.files import shell_transfer

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


def test_store_game_entries_and_logos(app):
    from aircursor_desktop.catalog import CATALOG, LOGOS
    from aircursor_desktop.widgets import asset
    from pathlib import Path

    assert CATALOG["Game Launchers"]["Steam"] == "Valve.Steam"
    assert CATALOG["Games"]["The Battle for Wesnoth"] == "Wesnoth.BattleForWesnoth"
    assert len({package for group in CATALOG.values() for package in group.values()}) >= 55
    assert all(Path(asset(f"app-logos/{slug}.png")).is_file() for slug in LOGOS.values())
    widget = store.Store()
    widget.populate("Game Launchers")
    xbox = next(
        widget.apps.item(i) for i in range(widget.apps.count()) if widget.apps.item(i).text() == "Xbox"
    )
    widget.apps.setCurrentItem(xbox)
    assert widget.source.currentData() == "msstore"
    assert store.arguments("show", widget.query.text(), widget.source.currentData())[-2] == "msstore"
    widget.close()


def test_pc_games_open_the_correct_publisher_listing_and_launcher(app, monkeypatch):
    from aircursor_desktop.catalog import GAMES

    opened, operations = [], []
    services = type(
        "Services", (), {"openUrl": staticmethod(lambda url: opened.append(url.toString()) or True)}
    )
    monkeypatch.setattr(store, "QDesktopServices", services)
    widget = store.Store()
    monkeypatch.setattr(widget, "run", lambda action: operations.append(action))
    widget.nav.setCurrentRow(1)
    assert widget.stack.currentWidget() is widget.games
    assert widget.games.games.count() == len(GAMES)
    widget.games.filter("spider man")
    assert widget.games.games.count() == 3
    widget.games.open_button.click()
    assert opened == ["https://store.steampowered.com/app/1817070/"]
    widget.games.filter("storm fortnite")
    assert not widget.games.open_button.isEnabled()
    widget.games.filter("fortnite")
    widget.games.open_button.click()
    assert opened[-1] == "https://store.epicgames.com/en-US/p/fortnite"
    widget.games.launcher_button.click()
    assert widget.query.text() == "EpicGames.EpicGamesLauncher" and operations == ["show"]
    widget.close()
