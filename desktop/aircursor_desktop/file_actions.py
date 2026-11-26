"""Windows Shell Open With and interoperable file clipboard formats."""

import ctypes
import os
import struct
from ctypes import wintypes
from pathlib import Path, PureWindowsPath

from PySide6.QtCore import QMimeData, QUrl

DROP_EFFECT = 'application/x-qt-windows-mime;value="Preferred DropEffect"'


def file_mime(paths, move=False):
    mime = QMimeData()
    mime.setUrls([QUrl.fromLocalFile(str(path)) for path in paths])
    mime.setData(DROP_EFFECT, struct.pack("<I", 2 if move else 1))
    return mime


def file_selection(mime):
    if mime is None or not mime.hasUrls() or any(not url.isLocalFile() for url in mime.urls()):
        return [], False
    paths = [Path(url.toLocalFile()) for url in mime.urls()]
    return paths, bytes(mime.data(DROP_EFFECT)) == struct.pack("<I", 2)


def valid_name(name):
    return bool(
        name
        and name not in (".", "..")
        and not name.endswith((" ", "."))
        and not any(ord(c) < 32 or c in '<>:"/\\|?*' for c in name)
        and not PureWindowsPath(name).is_reserved()
    )


def open_with(parent, path):
    if os.name != "nt":
        raise OSError("The Open With chooser requires Windows.")

    class OpenAsInfo(ctypes.Structure):
        _fields_ = [("file", wintypes.LPCWSTR), ("class_name", wintypes.LPCWSTR), ("flags", wintypes.DWORD)]

    info = OpenAsInfo(str(path), None, 4)  # OAIF_EXEC: launch the chosen application with the file.
    fn = ctypes.windll.shell32.SHOpenWithDialog
    fn.argtypes = [wintypes.HWND, ctypes.POINTER(OpenAsInfo)]
    fn.restype = ctypes.c_long
    result = fn(int(parent.winId()), ctypes.byref(info))
    if result < 0 and result & 0xFFFFFFFF != 0x800704C7:
        raise OSError(f"Windows Open With failed (0x{result & 0xFFFFFFFF:08x}).")
