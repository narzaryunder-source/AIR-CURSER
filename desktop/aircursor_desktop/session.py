"""Use the active Windows desktop and session notifications for workspace access."""

import ctypes
from ctypes import wintypes
import os

SESSION_CHANGE = 0x02B1
POWER_CHANGE = 0x0218
SESSION_AWAY = frozenset({2, 4, 6, 7})
SESSION_BACK = frozenset({1, 3, 5, 8})
POWER_SUSPEND = 4
POWER_RESUME = frozenset({7, 18})


def interactive_desktop():
    if os.name != "nt":
        return True
    user = ctypes.windll.user32
    user.OpenInputDesktop.restype = wintypes.HANDLE
    raw = user.OpenInputDesktop(0, False, 1)  # DESKTOP_READOBJECTS
    if not raw:
        return False
    desktop = wintypes.HANDLE(raw)
    try:
        name = ctypes.create_unicode_buffer(128)
        needed = wintypes.DWORD()
        # The secure Windows desktop is distinct from the regular Default desktop.
        if not user.GetUserObjectInformationW(desktop, 2, name, ctypes.sizeof(name), ctypes.byref(needed)):
            return False
        return name.value.casefold() == "default"
    finally:
        user.CloseDesktop(desktop)


def lock_workstation():
    return os.name == "nt" and bool(ctypes.windll.user32.LockWorkStation())


def watch(hwnd, enabled):
    api = ctypes.windll.wtsapi32
    handle = wintypes.HWND(hwnd)
    if enabled:
        return bool(api.WTSRegisterSessionNotification(handle, 0))
    return bool(api.WTSUnRegisterSessionNotification(handle))
