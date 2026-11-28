"""Where AirCursor keeps its settings: %APPDATA%\\AirCursor\\settings.json (one small JSON file).

The phone hotspot's password is never kept here: Windows keeps it in its own Wi-Fi profile.
"""
import json
import os
import tempfile
import threading


def data_dir():
    base = os.environ.get("AIRCURSOR_HOME")   # tests
    if not base:
        base = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AirCursor")
    os.makedirs(base, exist_ok=True)
    return base


class Settings:
    """A dict saved to disk after every change (written to a new file, then swapped in)."""

    def __init__(self, path=None):
        self.path = path or os.path.join(data_dir(), "settings.json")
        self._lock = threading.Lock()
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data = json.load(f)
            if not isinstance(self.data, dict):
                raise ValueError("AirCursor settings must be a JSON object.")
        except FileNotFoundError:
            self.data = {}

    def get(self, key, default=None):
        with self._lock:
            return self.data.get(key, default)

    def set(self, **values):
        with self._lock:
            updated = self.data.copy()
            for k, v in values.items():
                if v is None:
                    updated.pop(k, None)
                else:
                    updated[k] = v
            folder = os.path.dirname(self.path)
            fd, tmp = tempfile.mkstemp(dir=folder, prefix=".settings-", suffix=".json")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    # json.dumps uses the native encoder with indentation disabled.
                    f.write(json.dumps(updated, separators=(",", ":")))
                os.replace(tmp, self.path)
                self.data = updated
            finally:
                if os.path.exists(tmp):
                    os.unlink(tmp)

    def clear(self):
        with self._lock:
            try:
                os.remove(self.path)
            except FileNotFoundError:
                pass
            self.data = {}
