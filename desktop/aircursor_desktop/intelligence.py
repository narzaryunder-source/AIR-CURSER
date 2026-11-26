"""Official web access and optional OpenAI-compatible chat completions, including loopback models."""
import ctypes as ct
from ctypes import wintypes as w
import json
from pathlib import Path
from urllib import error, request

from PySide6.QtWidgets import (QCheckBox, QComboBox, QInputDialog, QLineEdit, QListWidget,
                               QPlainTextEdit, QVBoxLayout, QWidget)
from .widgets import Workers, button, label, row

SITES = {"ChatGPT": "https://chatgpt.com/", "Claude": "https://claude.ai/",
         "Gemini": "https://gemini.google.com/", "Perplexity": "https://www.perplexity.ai/"}
ENDPOINTS = {"Ollama": "http://127.0.0.1:11434/v1", "LM Studio": "http://127.0.0.1:1234/v1",
             "OpenRouter": "https://openrouter.ai/api/v1"}


class Credential(ct.Structure):
    _fields_ = [("flags", w.DWORD), ("kind", w.DWORD), ("target", w.LPWSTR), ("comment", w.LPWSTR),
                ("modified", w.FILETIME), ("size", w.DWORD), ("blob", ct.c_void_p), ("persist", w.DWORD),
                ("count", w.DWORD), ("attrs", ct.c_void_p), ("alias", w.LPWSTR), ("user", w.LPWSTR)]


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
    cred = Credential(kind=1, target=target, size=len(blob.raw)-1, blob=ct.addressof(blob), persist=2, user=provider)
    if not api.CredWriteW(ct.byref(cred), 0):
        raise ct.WinError(ct.get_last_error())


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def api_call(provider, path, payload=None):
    headers = {"Content-Type": "application/json"}
    if provider == "OpenRouter":
        key = credential(provider)
        if key:
            headers["Authorization"] = "Bearer " + key
    req = request.Request(ENDPOINTS[provider] + path, headers=headers,
                          data=json.dumps(payload).encode() if payload is not None else None)
    handlers = [NoRedirect()]
    if provider != "OpenRouter":
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
        layout = QVBoxLayout(self)
        row(layout, *[button(name, lambda u=u: browse(u)) for name, u in SITES.items()])
        self.provider, self.model = QComboBox(), QComboBox()
        self.provider.addItems(ENDPOINTS)
        self.free = QCheckBox("Zero token price")
        row(layout, self.provider, button("Discover models", self.discover), self.model, self.free)
        self.free.toggled.connect(self.filter)
        self.provider.currentTextChanged.connect(self.changed)
        self.model.currentIndexChanged.connect(self.pricing)
        self.sessions = QComboBox()
        self.conversations = self.s.get("conversations", [])
        for c in self.conversations:
            self.sessions.addItem(c["title"])
        row(layout, self.sessions, button("New conversation", self.new), button("API key…", self.key),
            button("Coding terminal", terminal))
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        layout.addWidget(self.log)
        self.prompt = QPlainTextEdit()
        self.prompt.setPlaceholderText("Message the selected model")
        self.prompt.setMaximumHeight(88)
        layout.addWidget(self.prompt)
        self.status = label("")
        row(layout, self.status, button("Send", self.send))
        self.sessions.currentIndexChanged.connect(self.render)
        if not self.conversations:
            self.new()
        self.render()
        self.changed()

    def changed(self, *_):
        self.models = []
        self.filter()
        remote = self.provider.currentText() == "OpenRouter"
        self.free.setEnabled(remote)
        self.status.setText("Provider charges may apply." if remote else "Start the local model server, then discover models.")

    def discover(self):
        provider = self.provider.currentText()
        self.status.setText("Requesting available models…")
        def done(result):
            if self.provider.currentText() == provider:
                self.models = result["data"]
                self.filter()
                if not self.model.count():
                    self.status.setText("No matching models were returned.")
        self.work.run(lambda: api_call(provider, "/models"), done, self.status.setText)

    def filter(self, *_):
        self.model.clear()
        for m in self.models:
            if self.provider.currentText() != "OpenRouter" or not self.free.isChecked() or free_model(m):
                self.model.addItem(m["id"], m)
        self.pricing()

    def pricing(self, *_):
        m = self.model.currentData() or {}
        if not m:
            return
        p = m.get("pricing", {})
        self.status.setText(f"USD per token · input {p.get('prompt', 'unknown')} · output {p.get('completion', 'unknown')}"
                            if self.provider.currentText() == "OpenRouter" else "Runs on the local model server.")

    def key(self):
        value, ok = QInputDialog.getText(self, "OpenRouter credential", "API key (empty removes saved key)",
                                          QLineEdit.EchoMode.Password)
        if ok:
            try:
                credential("OpenRouter", value)
                self.status.setText("Windows credential updated.")
            except (AttributeError, OSError) as exc:
                self.status.setText(str(exc))

    def new(self):
        self.conversations.append({"title": "New conversation", "messages": []})
        self.sessions.addItem("New conversation")
        self.sessions.setCurrentIndex(self.sessions.count() - 1)

    def render(self, *_):
        i = self.sessions.currentIndex()
        if i >= 0:
            self.log.setPlainText("\n\n".join(m["role"].capitalize() + "\n" + m["content"]
                                            for m in self.conversations[i]["messages"]))

    def send(self):
        text, model = self.prompt.toPlainText().strip(), self.model.currentText()
        if self.pending or not model or not text:
            self.status.setText("Choose a discovered model, enter a message, and wait for any active request.")
            return
        i = self.sessions.currentIndex()
        conv = self.conversations[i]
        provider = self.provider.currentText()
        messages = conv["messages"] + [{"role": "user", "content": text}]
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
            conv.update(title=messages[0]["content"][:45], messages=messages + [{"role": "assistant", "content": answer}])
            self.sessions.setItemText(i, conv["title"])
            self.s.set(conversations=self.conversations)
            self.render()
            self.status.setText(f"{model} · {result.get('usage', {}).get('total_tokens', 'Unknown')} tokens")
        def failed(message):
            self.pending = False
            if not self.prompt.toPlainText():
                self.prompt.setPlainText(text)
            self.status.setText(message)
        self.work.run(lambda: api_call(provider, "/chat/completions", {"model": model, "messages": messages}), done, failed)
