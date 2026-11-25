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
                self.data = {}
        except (OSError, ValueError):
            self.data = {}

    def get(self, key, default=None):
        with self._lock:
            return self.data.get(key, default)

    def set(self, **values):
        with self._lock:
            for k, v in values.items():
                if v is None:
                    self.data.pop(k, None)
                else:
                    self.data[k] = v
            folder = os.path.dirname(self.path)
            fd, tmp = tempfile.mkstemp(dir=folder, prefix=".settings-", suffix=".json")
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(self.data, f, indent=1)
            os.replace(tmp, self.path)

    def clear(self):
        with self._lock:
            self.data = {}
            try:
                os.remove(self.path)
            except OSError:
                pass
