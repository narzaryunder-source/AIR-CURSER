"""Read the switchable top-level Windows windows and activate one at the user's request."""

import ctypes as c
from ctypes import wintypes as w
import os
from pathlib import Path

import psutil


def list_windows():
    if os.name != "nt":
        return []
    user = c.WinDLL("user32", use_last_error=True)
    user.IsWindowVisible.argtypes = [w.HWND]
    user.GetWindow.argtypes = [w.HWND, w.UINT]
    user.GetWindow.restype = w.HWND
    user.GetWindowTextW.argtypes = [w.HWND, w.LPWSTR, c.c_int]
    user.GetWindowThreadProcessId.argtypes = [w.HWND, c.POINTER(w.DWORD)]
    get_long = user.GetWindowLongW
    get_long.argtypes = [w.HWND, c.c_int]
    items = []
    callback = c.WINFUNCTYPE(w.BOOL, w.HWND, w.LPARAM)

    def inspect(handle, unused):
        if not user.IsWindowVisible(handle) or get_long(handle, -20) & 0x80 or user.GetWindow(handle, 4):
            return True
        pid = w.DWORD()
        user.GetWindowThreadProcessId(handle, c.byref(pid))
        if pid.value == os.getpid():
            return True
        title = c.create_unicode_buffer(256)
        if not user.GetWindowTextW(handle, title, len(title)) or title.value == "Program Manager":
            return True
        cloaked = w.DWORD()
        c.windll.dwmapi.DwmGetWindowAttribute(w.HWND(handle), 14, c.byref(cloaked), c.sizeof(cloaked))
        if cloaked.value:
            return True
        try:
            path = psutil.Process(pid.value).exe()
        except psutil.Error:
            path = ""
        items.append(
            dict(
                handle=int(handle),
                pid=pid.value,
                title=title.value,
                path=path,
                app=Path(path).stem if path else title.value,
            )
        )
        return True

    user.EnumWindows(callback(inspect), 0)
    return items


def activate(item):
    hwnd = w.HWND(item["handle"])
    user = c.WinDLL("user32")
    pid = w.DWORD()
    user.GetWindowThreadProcessId(hwnd, c.byref(pid))
    if pid.value != item["pid"] or not user.IsWindowVisible(hwnd):
        return False
    if user.IsIconic(hwnd):
        user.ShowWindowAsync(hwnd, 9)
    return bool(user.SetForegroundWindow(hwnd))
