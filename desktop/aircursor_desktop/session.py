"""Observe inactivity and Windows session and power transitions."""

import ctypes
from ctypes import wintypes

IDLE_MINUTES = (1, 5, 10, 15, 30)
DEFAULT_IDLE = 10


class LastInput(ctypes.Structure):
    _fields_ = [("size", wintypes.UINT), ("tick", wintypes.DWORD)]


def idle_seconds():
    info = LastInput(ctypes.sizeof(LastInput), 0)
    if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
        return 0
    # GetLastInputInfo uses the same wrapping 32-bit tick count as GetTickCount.
    now = ctypes.windll.kernel32.GetTickCount()
    return ((now - info.tick) & 0xFFFFFFFF) / 1000


def watch(hwnd, enabled):
    api = ctypes.windll.wtsapi32
    handle = wintypes.HWND(hwnd)
    if enabled:
        return bool(api.WTSRegisterSessionNotification(handle, 0))
    return bool(api.WTSUnRegisterSessionNotification(handle))


def lock_message(message, event):
    return (message == 0x02B1 and event in (2, 4, 6, 7)) or (message == 0x0218 and event in (4, 7, 18))
