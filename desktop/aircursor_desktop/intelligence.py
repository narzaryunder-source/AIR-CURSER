"""Official web access and optional OpenAI-compatible chat completions, including loopback models."""

import ctypes as ct
from ctypes import wintypes as w
import json
import hashlib
import ipaddress
import os
from urllib.parse import urlsplit
from urllib import error, request
from uuid import uuid4

from PySide6.QtCore import Qt
from PySide6.QtGui import QTextCursor
from .design import icon
from .navigation import sidebar, item, heading
from PySide6.QtWidgets import (
    QCheckBox,
    QApplication,
    QComboBox,
    QFileDialog,
    QFrame,
    QLabel,
    QStackedWidget,
    QHBoxLayout,
    QInputDialog,
    QLineEdit,
    QPlainTextEdit,
    QVBoxLayout,
    QWidget,
)
from .widgets import Workers, confirm, button, label, row
from .ai_context import checked_context, computer_context, read_text_context
from .scheduled import show_schedules

SITES = {
    "ChatGPT": "https://chatgpt.com/",
    "Claude": "https://claude.ai/",
    "Gemini": "https://gemini.google.com/",
    "Perplexity": "https://www.perplexity.ai/",
}
ENDPOINTS = {
    "Ollama": "http://127.0.0.1:11434/v1",
    "LM Studio": "http://127.0.0.1:1234/v1",
    "OpenRouter": "https://openrouter.ai/api/v1",
}
OLLAMA_BASE = ENDPOINTS["Ollama"].removesuffix("/v1")
LIGHT_MODEL = "qwen3:0.6b"
LIGHT_CONTEXT = 2048
LIGHT_OUTPUT = 256
LIGHT_PROMPT_BYTES = 8192


class Credential(ct.Structure):
    _fields_ = [
        ("flags", w.DWORD),
        ("kind", w.DWORD),
        ("target", w.LPWSTR),
        ("comment", w.LPWSTR),
        ("modified", w.FILETIME),
        ("size", w.DWORD),
        ("blob", ct.c_void_p),
        ("persist", w.DWORD),
        ("count", w.DWORD),
        ("attrs", ct.c_void_p),
        ("alias", w.LPWSTR),
        ("user", w.LPWSTR),
    ]


def credential(provider, value=None):
    api = ct.WinDLL("advapi32", use_last_error=True)
    target = "AirCursor/API/" + provider
    if value is None:
        pointer = ct.POINTER(Credential)()
        if not api.CredReadW(ct.c_wchar_p(target), 1, 0, ct.byref(pointer)):
            if ct.get_last_error() == 1168:
                return ""
            raise ct.WinError(ct.get_last_error())
        try:
            return ct.string_at(pointer.contents.blob, pointer.contents.size).decode("utf-8")
        finally:
            api.CredFree(pointer)
    if not value:
        if not api.CredDeleteW(ct.c_wchar_p(target), 1, 0) and ct.get_last_error() != 1168:
            raise ct.WinError(ct.get_last_error())
        return
    blob = ct.create_string_buffer(value.encode("utf-8"))
    cred = Credential(
        kind=1, target=target, size=len(blob.raw) - 1, blob=ct.addressof(blob), persist=2, user=provider
    )
    if not api.CredWriteW(ct.byref(cred), 0):
        raise ct.WinError(ct.get_last_error())


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def local_url(url):
    host = urlsplit(url).hostname
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return host == "localhost"


def endpoint_url(text):
    url = text.strip().rstrip("/")
    parts = urlsplit(url)
    if (
        not parts.hostname
        or parts.username is not None
        or parts.password is not None
        or parts.query
        or parts.fragment
        or any(ord(c) < 33 or c == chr(92) for c in url)
    ):
        raise ValueError("Use an API base URL without credentials, whitespace, a query or a fragment.")
    if parts.scheme != "https" and not (parts.scheme == "http" and local_url(url)):
        raise ValueError("Use HTTPS for remote providers. HTTP is available on loopback.")
    _ = parts.port
    return url


def key_target(provider, url):
    return ("Custom-" + hashlib.sha256(url.encode()).hexdigest()) if provider == "Custom" else provider


def read_json_response(response):
    data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError("Provider response exceeds 8 MiB.")
    return json.loads(data)


def api_call(provider, path, payload=None, custom=None):
    url = endpoint_url(custom) if provider == "Custom" else ENDPOINTS[provider]
    headers = {}
    if os.name == "nt" and provider in ("OpenRouter", "Custom"):
        key = credential(key_target(provider, url))
        if key:
            headers["Authorization"] = "Bearer " + key
    return api_request(provider, url, path, payload, headers, read_json_response)


def api_request(provider, url, path, payload, headers, read_response):
    req = request.Request(
        url + path,
        headers={"Content-Type": "application/json", **headers},
        data=json.dumps(payload).encode() if payload is not None else None,
    )
    handlers = [NoRedirect()]
    if local_url(url):
        handlers.append(request.ProxyHandler({}))
    try:
        with request.build_opener(*handlers).open(req, timeout=90 if payload else 8) as response:
            return read_response(response)
    except error.HTTPError as exc:
        raise ValueError(f"{provider} returned HTTP {exc.code}: {exc.reason}") from exc


def ollama_call(path, payload=None, *, read_response=read_json_response):
    return api_request("Ollama", OLLAMA_BASE, path, payload, {}, read_response)


def read_model_download(response):
    while line := response.readline(65537):
        if len(line) > 65536:
            raise ValueError("Ollama returned an oversized download status.")
        update = json.loads(line)
        if update.get("error"):
            raise ValueError(update["error"])
        if update.get("status") == "success":
            return
    raise ValueError("Ollama ended the download without reporting success.")


def lightweight_messages(messages):
    start = len(messages) - 1
    used = len(messages[start]["content"].encode("utf-8"))
    if used > LIGHT_PROMPT_BYTES:
        raise ValueError("Shorten your message or turn off lightweight mode for a longer prompt.")
    # Keep complete recent turns. The server applies the final token limit.
    for index in range(start - 2, max(-1, start - 9), -2):
        turn = messages[index:index + 2]
        used += sum(len(message["content"].encode("utf-8")) for message in turn)
        if used > LIGHT_PROMPT_BYTES:
            break
        start = index
    return messages[start:]


def lightweight_chat(messages, keep_warm=False):
    result = ollama_call("/api/chat", {
        "model": LIGHT_MODEL,
        "messages": messages,
        "stream": False,
        "think": False,
        "keep_alive": "2m" if keep_warm else 0,
        "options": {
            "num_ctx": LIGHT_CONTEXT,
            "num_predict": LIGHT_OUTPUT,
            "num_thread": max(1, min(4, (os.cpu_count() or 1) // 2)),
        },
    })
    if result.get("done") is not True:
        raise ValueError("Ollama returned an incomplete response.")
    counts = [result.get(key) for key in ("eval_count", "prompt_eval_count")]
    return result["message"]["content"], sum(n for n in counts if isinstance(n, int)) or "Unknown"


def free_model(model):
    prices = model.get("pricing", {})
    try:
        return all(float(prices[k]) == 0 for k in ("prompt", "completion"))
    except (ValueError, TypeError, KeyError):
        return False


class Intelligence(QWidget):
    def __init__(self, settings, browse, terminal, *, scheduler=None):
        super().__init__()
        self.s, self.work = settings, Workers(self)
        self.models = []
        self.pending = False
        self.downloading = False
        self.scheduler = scheduler
        self.attachment = None
        self.attachment_read = 0
        left, self.web, layout = sidebar(self, "Air AI", 192)
        self.project = QComboBox()
        self.projects = list(self.s.get("ai_projects", []))
        left.insertWidget(1, self.project)
        left.insertWidget(2, button("Projects…", self.manage_projects))
        if scheduler is not None:
            left.insertWidget(3, button("Scheduled…", self.schedules))
            scheduler.result_ready.connect(self.scheduled_result)
            scheduler.busy = lambda: self.pending or self.downloading
        for name, url in SITES.items():
            item(self.web, name, url)
        self.web.itemClicked.connect(lambda entry: browse(entry.data(Qt.ItemDataRole.UserRole)))
        left.addWidget(button("Connectors…", self.connectors))
        left.addWidget(button("Provider key…", self.key))
        left.addWidget(button("Open terminal", terminal))
        left.addWidget(button("Plan app launches…", self.plan_apps))
        section = label("Conversations")
        section.setObjectName("section")
        left.addWidget(section)
        self.sessions = QComboBox()
        self.conversations = list(self.s.get("conversations", []))
        left.addWidget(self.sessions)
        left.addWidget(button("New conversation", self.new))
        self.provider, self.model = QComboBox(), QComboBox()
        self.provider.addItems(ENDPOINTS)
        if self.s.get("custom_ai_url"):
            self.provider.addItem("Custom")
        left.addWidget(button("Manage conversation…", self.manage))
        self.model.setPlaceholderText("Select model")
        self.free = QCheckBox("Zero token price")
        bar = row(layout, self.provider, button("Find models", self.discover), self.model, self.free)
        bar.addStretch()
        self.free.toggled.connect(self.filter)
        self.provider.currentTextChanged.connect(self.changed)
        self.model.currentIndexChanged.connect(self.pricing)
        self.lightweight = QCheckBox("Lightweight local chat")
        self.lightweight.setChecked(self.s.get("ai_lightweight", False) is True)
        self.lightweight.setToolTip("Use Qwen3 0.6B with short replies and a 2,048-token context.")
        self.get_model = button("Get Qwen3 0.6B…", self.download_lightweight)
        self.warm = QCheckBox("Keep model ready for 2 minutes")
        self.warm.setChecked(bool(self.s.get("ai_keep_warm", False)))
        self.warm.toggled.connect(lambda value: self.s.set(ai_keep_warm=value))
        row(layout, self.lightweight, self.get_model, self.warm).addStretch()
        self.transcript = QStackedWidget()
        layout.addWidget(self.transcript, 1)
        welcome = QWidget()
        center = QVBoxLayout(welcome)
        center.setAlignment(Qt.AlignmentFlag.AlignCenter)
        mark = QLabel()
        mark.setPixmap(icon("Air AI").pixmap(64, 64))
        center.addWidget(mark, alignment=Qt.AlignmentFlag.AlignHCenter)
        title = heading(center, "Start a conversation")
        title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hint = label("Connect a local model or choose a provider.")
        hint.setObjectName("secondary")
        hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        center.addWidget(hint)
        choices = QHBoxLayout()
        for provider in ENDPOINTS:

            def select(name=provider):
                self.provider.setCurrentText(name)
                self.discover()

            choices.addWidget(button(provider, select))
        choices.addWidget(button("Qwen3 0.6B", self.select_qwen))
        center.addSpacing(10)
        center.addLayout(choices)
        self.transcript.addWidget(welcome)
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setFrameShape(QFrame.Shape.NoFrame)
        self.transcript.addWidget(self.log)
        self.prompt = QPlainTextEdit()
        self.prompt.setPlaceholderText("Write a message…")
        self.prompt.setMinimumHeight(70)
        self.prompt.setMaximumHeight(98)
        self.prompt.setStyleSheet("QPlainTextEdit { border: 1px solid #77838c; border-radius: 14px; padding: 8px; }")
        self.log.setDocumentTitle("Air AI conversation")
        self.log.document().setDocumentMargin(14)
        layout.addWidget(self.prompt)
        self.attachment_row = QWidget()
        attachment_layout = QHBoxLayout(self.attachment_row)
        attachment_layout.setContentsMargins(0, 0, 0, 0)
        self.attachment_label = label("")
        attachment_layout.addWidget(self.attachment_label, 1)
        attachment_layout.addWidget(button("Remove", self.clear_attachment))
        self.attachment_row.hide()
        layout.addWidget(self.attachment_row)
        self.status = label("")
        self.status.setObjectName("secondary")
        send = button("Send  ↑", self.send)
        send.setObjectName("primary")
        send.setFixedWidth(77)
        row(layout, self.status, send)
        self.lightweight.toggled.connect(self.set_lightweight)
        self.sessions.currentIndexChanged.connect(self.render)
        self.populate_projects(self.s.get("ai_project_filter"))
        self.project.currentIndexChanged.connect(self.project_changed)
        if not self.conversations:
            self.new()
        self.render()
        self.changed()

    def changed(self, *_):
        self.models = []
        provider = self.provider.currentText()
        self.lightweight.setVisible(provider == "Ollama")
        self.warm.setVisible(self.lightweight_enabled())
        self.free.setVisible(provider == "OpenRouter")
        remote = provider == "OpenRouter" or (
            provider == "Custom" and not local_url(self.s.get("custom_ai_url"))
        )
        self.status.setText(
            "Provider charges may apply."
            if remote
            else "Start the local model server, then discover models."
        )
        self.filter()

    def lightweight_enabled(self):
        return self.provider.currentText() == "Ollama" and self.lightweight.isChecked()

    def select_qwen(self):
        self.provider.setCurrentText("Ollama")
        if self.lightweight.isChecked():
            self.discover()
        else:
            self.lightweight.setChecked(True)

    def set_lightweight(self, enabled):
        self.s.set(ai_lightweight=enabled)
        self.warm.setVisible(self.lightweight_enabled())
        if enabled:
            self.filter()
            if not self.models and not self.downloading:
                self.discover()
        else:
            self.status.setText("Find local models or select a model you've already discovered.")
            self.filter()

    def download_lightweight(self):
        if self.pending or self.downloading or not self.lightweight_enabled():
            return
        if self.scheduler and self.scheduler.running:
            self.status.setText("Wait for the scheduled Ollama prompt to finish.")
            return
        if not confirm(self, "Download Qwen3 0.6B", "Let the local Ollama app download about 523 MB?"):
            return
        self.downloading = True
        self.get_model.setEnabled(False)
        self.status.setText("Ollama is downloading Qwen3 0.6B…")

        def finished(message=None):
            self.downloading = False
            self.get_model.setEnabled(True)
            if self.provider.currentText() == "Ollama":
                if message:
                    self.status.setText(f"{message} Start Ollama or install it from Air Store if needed.")
                else:
                    self.discover()

        self.work.run(
            lambda: ollama_call("/api/pull", {"model": LIGHT_MODEL, "stream": True},
                                read_response=read_model_download),
            finished,
            finished,
        )

    def discover(self):
        provider = self.provider.currentText()
        endpoint = self.s.get("custom_ai_url")
        self.status.setText("Requesting available models…")

        def done(result):
            if self.provider.currentText() == provider and self.s.get("custom_ai_url") == endpoint:
                raw = result.get("data") if isinstance(result, dict) else None
                if not isinstance(raw, list):
                    self.status.setText("Provider returned an invalid model list.")
                    return
                self.models = [m for m in raw if isinstance(m, dict) and isinstance(m.get("id"), str)]
                self.filter()
                if not self.model.count() and not self.lightweight_enabled():
                    self.status.setText("No matching models were returned.")

        def fetch():
            if provider == "Ollama":
                inventory = ollama_call("/api/tags")
                return {"data": [
                    {"id": model.get("name"), "disk_bytes": model.get("size")}
                    for model in inventory.get("models", []) if isinstance(model, dict)
                ]}
            return api_call(provider, "/models", custom=endpoint)

        def failed(message):
            if self.provider.currentText() == provider and self.s.get("custom_ai_url") == endpoint:
                self.status.setText(message)

        self.work.run(fetch, done, failed)

    def filter(self, *_):
        self.model.clear()
        lightweight = self.lightweight_enabled()
        for m in self.models:
            if lightweight and m["id"] != LIGHT_MODEL:
                continue
            if self.provider.currentText() != "OpenRouter" or not self.free.isChecked() or free_model(m):
                self.model.addItem(m["id"], m)
        self.get_model.setVisible(lightweight and not self.model.count())
        self.get_model.setEnabled(not self.downloading)
        if lightweight and not self.model.count() and not self.downloading:
            self.status.setText("Find Qwen3 0.6B in Ollama or download it once for offline use.")
        if self.model.count() == 1:
            self.model.setCurrentIndex(0)
        self.pricing()

    def pricing(self, *_):
        m = self.model.currentData() or {}
        if not m:
            return
        p = m.get("pricing", {})
        provider = self.provider.currentText()
        if provider in ("Ollama", "LM Studio") or (
            provider == "Custom" and local_url(self.s.get("custom_ai_url"))
        ):
            import psutil

            ram = psutil.virtual_memory()
            size = m.get("disk_bytes")
            size_text = f" · {size / 2**30:.1f} GiB model on disk" if isinstance(size, int) else ""
            mode = "Qwen3 lightweight" if self.lightweight_enabled() else "Local model"
            self.status.setText(f"{mode} · {ram.available / 2**30:.1f} GiB RAM available{size_text}")
            return
        self.status.setText(
            f"USD per token · input {p.get('prompt', 'unknown')} · output {p.get('completion', 'unknown')}"
            if self.provider.currentText() == "OpenRouter"
            else (
                "Pricing unavailable from this endpoint."
                if self.provider.currentText() == "Custom"
                else "Runs on the local model server."
            )
        )

    def configure(self):
        old = self.s.get("custom_ai_url", "")
        value, ok = QInputDialog.getText(
            self,
            "Compatible API endpoint",
            "OpenAI-compatible base URL, including its API version",
            text=old,
        )
        if not ok:
            return
        try:
            url = endpoint_url(value)
            if old and old != url and os.name == "nt":
                credential(key_target("Custom", old), "")
            self.s.set(custom_ai_url=url)
            if self.provider.findText("Custom") < 0:
                self.provider.addItem("Custom")
            self.provider.setCurrentText("Custom")
            self.changed()
            self.status.setText(f"Endpoint saved · {url}")
        except (ValueError, OSError) as exc:
            self.status.setText(str(exc))

    def key(self):
        provider = self.provider.currentText()
        if provider not in ("OpenRouter", "Custom"):
            self.status.setText("Select OpenRouter or configure a compatible endpoint to store its key.")
            return
        url = self.s.get("custom_ai_url") if provider == "Custom" else ENDPOINTS[provider]
        value, ok = QInputDialog.getText(
            self,
            "Provider credential",
            f"API key for {url} (empty removes saved key)",
            QLineEdit.EchoMode.Password,
        )
        if ok:
            try:
                credential(key_target(provider, url), value)
                self.status.setText("Windows credential updated.")
            except (AttributeError, OSError) as exc:
                self.status.setText(str(exc))

    def populate_projects(self, selected):
        self.project.blockSignals(True)
        self.project.clear()
        self.project.addItem("All chats", None)
        self.project.addItem("Unassigned", "")
        for project in self.projects:
            self.project.addItem(project["name"], project["id"])
        self.project.setCurrentIndex(max(0, self.project.findData(selected)))
        self.project.blockSignals(False)
        self.populate_chats()

    def project_changed(self):
        self.s.set(ai_project_filter=self.project.currentData())
        self.populate_chats()

    def selected_chat(self):
        index = self.sessions.currentData()
        return self.conversations[index] if index is not None else None

    def populate_chats(self, selected=None):
        project = self.project.currentData()
        self.sessions.blockSignals(True)
        self.sessions.clear()
        for index, chat in enumerate(self.conversations):
            if project is None or chat.get("project", "") == project:
                self.sessions.addItem(chat["title"], index)
                if chat is selected:
                    self.sessions.setCurrentIndex(self.sessions.count() - 1)
        self.sessions.blockSignals(False)
        self.render()

    def manage_projects(self):
        if self.pending:
            self.status.setText("Wait for the current reply before editing projects.")
            return
        identifier = self.project.currentData()
        project = next((p for p in self.projects if p["id"] == identifier), None)
        actions = ["New project"]
        if project:
            actions += ["Rename project", "Remove project"]
        action, ok = QInputDialog.getItem(self, "Projects", "Action", actions, editable=False)
        if not ok:
            return
        if action == "Remove project":
            if not confirm(self, "Remove project", "Remove this project and put its chats in Unassigned?"):
                return
            self.projects.remove(project)
            for chat in self.conversations:
                if chat.get("project") == identifier:
                    chat["project"] = ""
            identifier = ""
        else:
            name, ok = QInputDialog.getText(self, "Projects", "Name", text=project["name"] if action == "Rename project" else "")
            name = name.strip()[:80]
            if not ok or not name:
                return
            if any(p["name"].casefold() == name.casefold() and (action != "Rename project" or p is not project) for p in self.projects):
                self.status.setText("A project with this name already exists.")
                return
            if action == "New project":
                identifier = uuid4().hex
                self.projects.append(dict(id=identifier, name=name))
            else:
                project["name"] = name
        self.s.set(ai_projects=self.projects, ai_project_filter=identifier, conversations=self.conversations)
        self.populate_projects(identifier)

    def schedules(self):
        show_schedules(self, self.scheduler, self.prompt.toPlainText(), self.project.currentData() or "")

    def scheduled_result(self, chat):
        selected = self.selected_chat()
        self.conversations = [*self.conversations, chat]
        self.populate_chats(selected)

    def clear_attachment(self):
        self.attachment_read += 1
        self.attachment = None
        self.attachment_row.hide()

    def attach(self, name, text):
        self.attachment = (name, checked_context(text))
        self.attachment_label.setText(f"Attached for next message · {name}")
        self.attachment_row.show()

    def plan_apps(self):
        from .app_agent import plan_launch
        from .applications import launch_registered
        from .session import interactive_desktop

        if self.pending or self.downloading or (self.scheduler and self.scheduler.running):
            self.status.setText("Wait for the active model operation.")
            return
        task, ok = QInputDialog.getText(self, "Local app assistant", "What would you like to work on?")
        if not ok or not task.strip():
            return
        self.pending = True
        self.status.setText("Qwen3 is choosing from your Windows Start apps…")

        def done(apps):
            self.pending = False
            if not self.isVisible() or not interactive_desktop():
                return
            if not apps:
                self.status.setText("No matching installed apps were selected.")
                return
            summary = "\n".join(f"{name}  ({identity[9:]})" for name, identity in apps)
            if confirm(self, "Open these Windows apps?", summary) and interactive_desktop():
                try:
                    for _, identity in apps:
                        launch_registered(identity)
                    self.status.setText("Launch requests sent to Windows.")
                except (OSError, ValueError) as exc:
                    self.status.setText(str(exc))
            else:
                self.status.setText("App launch cancelled.")

        def failed(reason):
            self.pending = False
            self.status.setText(f"Local app assistant: {reason}")

        self.work.run(lambda: plan_launch(task), done, failed)

    def connectors(self):
        choices = ["Attach a text file", "Attach clipboard text", "Attach computer information", "Configure an API endpoint",
                   "Attach Word or PowerPoint text", "Attach coding project file list"]
        action, ok = QInputDialog.getItem(self, "Connectors", "Include context or connect a provider", choices, editable=False)
        if not ok:
            return
        if action == choices[3]:
            self.configure()
        elif action in (choices[0], choices[4], choices[5]):
            from .ai_context import office_context, project_context
            reader = read_text_context
            if action == choices[4]:
                path, _ = QFileDialog.getOpenFileName(self, "Select a document", "", "Office (*.docx *.pptx)")
                reader = office_context
            elif action == choices[5]:
                path = QFileDialog.getExistingDirectory(self, "Select a coding project")
                reader = project_context
            else:
                path, _ = QFileDialog.getOpenFileName(self, "Select a UTF-8 text file")
            if not path:
                return
            self.attachment_read += 1
            generation = self.attachment_read

            def loaded(result):
                if self.attachment_read == generation:
                    self.attach(*result)

            self.work.run(lambda: reader(path), loaded, self.status.setText)
        else:
            self.attachment_read += 1
            try:
                if action == choices[1]:
                    self.attach("Clipboard", QApplication.clipboard().text())
                else:
                    self.attach("Computer information", computer_context())
            except ValueError as exc:
                self.status.setText(str(exc))

    def new(self):
        chat = {"title": "New conversation", "messages": [], "project": self.project.currentData() or ""}
        self.conversations.append(chat)
        self.populate_chats(chat)

    def manage(self):
        if self.pending:
            self.status.setText("Wait for the current response before editing conversations.")
            return
        chat = self.selected_chat()
        if chat is None:
            return
        action, ok = QInputDialog.getItem(
            self, "Conversation", self.sessions.currentText(), ["Rename", "Move to project", "Delete"], editable=False
        )
        if not ok:
            return
        if action == "Rename":
            title, ok = QInputDialog.getText(
                self, "Rename conversation", "Title", text=self.sessions.currentText()
            )
            if not ok or not title.strip():
                return
            chat.update(title=title.strip()[:100], named=True)
        elif action == "Move to project":
            names = ["— Unassigned —", *[p["name"] for p in self.projects]]
            target, ok = QInputDialog.getItem(self, "Move conversation", "Project", names, editable=False)
            if not ok:
                return
            chat["project"] = "" if target == names[0] else self.projects[names.index(target) - 1]["id"]
        elif confirm(self, "Delete conversation", "Permanently delete this conversation from AirCursor?"):
            self.conversations.pop(self.sessions.currentData())
        self.s.set(conversations=self.conversations)
        self.populate_chats(chat)

    def render(self, *_):
        chat = self.selected_chat()
        messages = chat.get("messages", []) if chat else []
        self.transcript.setCurrentIndex(1 if messages else 0)
        self.log.setPlainText("\n\n".join(m["role"].capitalize() + "\n" + m["content"] for m in messages))

    def show_reply(self, conv, prompt, answer):
        for index in range(self.sessions.count()):
            if self.conversations[self.sessions.itemData(index)] is conv:
                if self.sessions.itemText(index) != conv["title"]:
                    self.sessions.setItemText(index, conv["title"])
                break
        if self.selected_chat() is not conv:
            return
        scroll = self.log.verticalScrollBar()
        was_at_end = scroll.value() == scroll.maximum()
        cursor = QTextCursor(self.log.document())
        cursor.movePosition(QTextCursor.MoveOperation.End)
        separator = "" if cursor.position() == 0 else "\n\n"
        cursor.insertText(f"{separator}User\n{prompt}\n\nAssistant\n{answer}")
        self.transcript.setCurrentIndex(1)
        if was_at_end:
            scroll.setValue(scroll.maximum())

    def send(self):
        text, model = self.prompt.toPlainText().strip(), self.model.currentText()
        if self.pending or not model or not text:
            self.status.setText(
                "Choose a discovered model, enter a message, and wait for any active request."
            )
            return
        if self.provider.currentText() == "Ollama" and self.scheduler and self.scheduler.running:
            self.status.setText("Wait for the scheduled Ollama prompt to finish.")
            return
        if self.selected_chat() is None:
            self.new()
        conv = self.selected_chat()
        provider = self.provider.currentText()
        messages = conv["messages"] + [{"role": "user", "content": text}]
        attachment = self.attachment
        sent = messages
        if attachment:
            name, context = attachment
            sent = [*messages[:-1], {"role": "user", "content": f"{text}\n\nContext from {name}:\n{context}"}]
        lightweight = self.lightweight_enabled()
        keep_warm = self.warm.isChecked()
        try:
            if lightweight:
                sent = lightweight_messages(sent)
        except ValueError as exc:
            self.status.setText(str(exc))
            return
        endpoint = self.s.get("custom_ai_url")
        recipient = endpoint if provider == "Custom" else ENDPOINTS[provider]
        if attachment and not local_url(recipient) and not confirm(
            self, "Share attached text", f"Send the attachment {attachment[0]} to {recipient}?"
        ):
            return
        if (
            conv["messages"]
            and conv.get("endpoint") != recipient
            and not confirm(
                self, "Share conversation", f"Send the existing conversation history to {recipient}?"
            )
        ):
            return
        self.pending = True
        self.prompt.clear()
        self.status.setText("Waiting for " + model + "…")

        def done(result):
            answer, count = result
            self.pending = False
            conv.update(
                endpoint=recipient,
                title=conv["title"] if conv.get("named") else messages[0]["content"][:45],
                messages=messages + [{"role": "assistant", "content": answer}],
            )
            if attachment is not None and self.attachment is attachment:
                self.clear_attachment()
            self.s.set(conversations=self.conversations)
            self.show_reply(conv, text, answer)
            self.status.setText(f"{model} · {count} tokens")

        def failed(message):
            self.pending = False
            if not self.prompt.toPlainText():
                self.prompt.setPlainText(text)
            self.status.setText(message)

        def fetch():
            if lightweight:
                answer, count = lightweight_chat(sent, keep_warm=keep_warm)
            else:
                result = api_call(provider, "/chat/completions", {"model": model, "messages": sent}, custom=endpoint)
                answer = result["choices"][0]["message"]["content"]
                count = (result.get("usage") or {}).get("total_tokens", "Unknown")
            if not isinstance(answer, str) or not answer.strip():
                raise ValueError("Provider returned no text.")
            return answer, count

        self.work.run(fetch, done, failed)
