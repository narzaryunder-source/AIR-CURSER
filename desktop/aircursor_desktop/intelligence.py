"""Official web access and optional OpenAI-compatible chat completions, including loopback models."""

import ctypes as ct
from ctypes import wintypes as w
import json
import hashlib
import ipaddress
import os
from urllib.parse import urlsplit
from urllib import error, request

from PySide6.QtCore import Qt
from .design import icon
from .navigation import sidebar, item, heading
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
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


def api_call(provider, path, payload=None, custom=None):
    url = endpoint_url(custom) if provider == "Custom" else ENDPOINTS[provider]
    headers = {"Content-Type": "application/json"}
    if os.name == "nt" and provider in ("OpenRouter", "Custom"):
        key = credential(key_target(provider, url))
        if key:
            headers["Authorization"] = "Bearer " + key
    req = request.Request(
        url + path,
        headers=headers,
        data=json.dumps(payload).encode() if payload is not None else None,
    )
    handlers = [NoRedirect()]
    if local_url(url):
        handlers.append(request.ProxyHandler({}))
    try:
        with request.build_opener(*handlers).open(req, timeout=90 if payload else 8) as response:
            data = response.read(8 * 1024 * 1024 + 1)
            if len(data) > 8 * 1024 * 1024:
                raise ValueError("Provider response exceeds 8 MiB.")
            return json.loads(data)
    except error.HTTPError as exc:
        raise ValueError(f"{provider} returned HTTP {exc.code}: {exc.reason}") from exc


def free_model(model):
    prices = model.get("pricing", {})
    try:
        return all(float(prices[k]) == 0 for k in ("prompt", "completion"))
    except (ValueError, TypeError, KeyError):
        return False


class Intelligence(QWidget):
    def __init__(self, settings, browse, terminal):
        super().__init__()
        self.s, self.work = settings, Workers(self)
        self.models = []
        self.pending = False
        left, self.web, layout = sidebar(self, "Official services", 173)
        for name, url in SITES.items():
            item(self.web, name, url)
        self.web.itemClicked.connect(lambda entry: browse(entry.data(Qt.ItemDataRole.UserRole)))
        left.addWidget(button("Configure endpoint…", self.configure))
        left.addWidget(button("Provider key…", self.key))
        left.addWidget(button("Open terminal", terminal))
        section = label("Conversations")
        section.setObjectName("section")
        left.insertWidget(2, section)
        self.sessions = QComboBox()
        self.conversations = self.s.get("conversations", [])
        for c in self.conversations:
            self.sessions.addItem(c["title"])
        left.insertWidget(3, self.sessions)
        left.insertWidget(4, button("New conversation", self.new))
        self.provider, self.model = QComboBox(), QComboBox()
        self.provider.addItems(ENDPOINTS)
        if self.s.get("custom_ai_url"):
            self.provider.addItem("Custom")
        left.insertWidget(5, button("Manage conversation…", self.manage))
        self.model.setPlaceholderText("Select model")
        self.free = QCheckBox("Zero token price")
        bar = row(layout, self.provider, button("Find models", self.discover), self.model, self.free)
        bar.addStretch()
        self.free.toggled.connect(self.filter)
        self.provider.currentTextChanged.connect(self.changed)
        self.model.currentIndexChanged.connect(self.pricing)
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
        center.addSpacing(10)
        center.addLayout(choices)
        self.transcript.addWidget(welcome)
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setFrameShape(QFrame.Shape.NoFrame)
        self.transcript.addWidget(self.log)
        self.prompt = QPlainTextEdit()
        self.prompt.setPlaceholderText("Write a message…")
        self.prompt.setMaximumHeight(76)
        layout.addWidget(self.prompt)
        self.status = label("")
        self.status.setObjectName("secondary")
        send = button("Send  ↑", self.send)
        send.setObjectName("primary")
        send.setFixedWidth(77)
        row(layout, self.status, send)
        self.sessions.currentIndexChanged.connect(self.render)
        if not self.conversations:
            self.new()
        self.render()
        self.changed()

    def changed(self, *_):
        self.models = []
        self.filter()
        remote = self.provider.currentText() == "OpenRouter" or (
            self.provider.currentText() == "Custom" and not local_url(self.s.get("custom_ai_url"))
        )
        self.free.setVisible(self.provider.currentText() == "OpenRouter")
        self.status.setText(
            "Provider charges may apply."
            if remote
            else "Start the local model server, then discover models."
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
                if not self.model.count():
                    self.status.setText("No matching models were returned.")

        def fetch():
            result = api_call(provider, "/models", custom=endpoint)
            if provider == "Ollama" and isinstance(result, dict) and isinstance(result.get("data"), list):
                try:
                    inventory = api_call("Custom", "/api/tags", custom="http://127.0.0.1:11434")
                    sizes = {entry["name"]: entry["size"] for entry in inventory.get("models", [])}
                    for model in result["data"]:
                        if isinstance(model, dict):
                            model["disk_bytes"] = sizes.get(model.get("id"))
                except (OSError, ValueError, TypeError, KeyError):
                    pass
            return result

        self.work.run(fetch, done, self.status.setText)

    def filter(self, *_):
        self.model.clear()
        for m in self.models:
            if self.provider.currentText() != "OpenRouter" or not self.free.isChecked() or free_model(m):
                self.model.addItem(m["id"], m)
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
            self.status.setText(f"Local model · {ram.available / 2**30:.1f} GiB RAM available{size_text}")
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

    def new(self):
        self.conversations.append({"title": "New conversation", "messages": []})
        self.sessions.addItem("New conversation")
        self.sessions.setCurrentIndex(self.sessions.count() - 1)

    def manage(self):
        if self.pending:
            self.status.setText("Wait for the current response before editing conversations.")
            return
        index = self.sessions.currentIndex()
        action, ok = QInputDialog.getItem(
            self, "Conversation", self.sessions.currentText(), ["Rename", "Delete"], editable=False
        )
        if not ok or index < 0:
            return
        if action == "Rename":
            title, ok = QInputDialog.getText(
                self, "Rename conversation", "Title", text=self.sessions.currentText()
            )
            if not ok or not title.strip():
                return
            self.conversations[index].update(title=title.strip()[:100], named=True)
            self.sessions.setItemText(index, self.conversations[index]["title"])
        elif confirm(self, "Delete conversation", "Permanently delete this conversation from AirCursor?"):
            self.conversations.pop(index)
            self.sessions.removeItem(index)
            if not self.conversations:
                self.new()
        self.s.set(conversations=self.conversations)
        self.render()

    def render(self, *_):
        i = self.sessions.currentIndex()
        if i >= 0:
            self.transcript.setCurrentIndex(1 if self.conversations[i]["messages"] else 0)
            self.log.setPlainText(
                "\n\n".join(
                    m["role"].capitalize() + "\n" + m["content"] for m in self.conversations[i]["messages"]
                )
            )

    def send(self):
        text, model = self.prompt.toPlainText().strip(), self.model.currentText()
        if self.pending or not model or not text:
            self.status.setText(
                "Choose a discovered model, enter a message, and wait for any active request."
            )
            return
        i = self.sessions.currentIndex()
        conv = self.conversations[i]
        provider = self.provider.currentText()
        messages = conv["messages"] + [{"role": "user", "content": text}]
        endpoint = self.s.get("custom_ai_url")
        recipient = endpoint if provider == "Custom" else ENDPOINTS[provider]
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
            self.pending = False
            try:
                answer = result["choices"][0]["message"]["content"]
                if not isinstance(answer, str):
                    raise ValueError("Provider returned no text.")
            except (ValueError, KeyError, IndexError, TypeError) as exc:
                failed(str(exc))
                return
            conv.update(
                endpoint=recipient,
                title=conv["title"] if conv.get("named") else messages[0]["content"][:45],
                messages=messages + [{"role": "assistant", "content": answer}],
            )
            self.sessions.setItemText(i, conv["title"])
            self.s.set(conversations=self.conversations)
            self.render()
            self.status.setText(
                f"{model} · {result.get('usage', {}).get('total_tokens', 'Unknown')} tokens"
            )

        def failed(message):
            self.pending = False
            if not self.prompt.toPlainText():
                self.prompt.setPlainText(text)
            self.status.setText(message)

        self.work.run(
            lambda: api_call(
                provider, "/chat/completions", {"model": model, "messages": messages}, custom=endpoint
            ),
            done,
            failed,
        )
