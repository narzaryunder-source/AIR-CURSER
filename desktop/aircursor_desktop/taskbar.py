"""Tell Windows which AirCursor windows belong to the current full-screen workspace."""

import ctypes
from ctypes import wintypes
from uuid import UUID


class Taskbar:
    def __init__(self):
        self.windows = set()

    @staticmethod
    def mark(windows, fullscreen):
        ole = ctypes.windll.ole32
        # Qt initializes OLE on the GUI thread. Use the existing apartment.
        klass = (ctypes.c_ubyte * 16).from_buffer_copy(UUID("56fdf344-fd6d-11d0-958a-006097c9a090").bytes_le)
        interface = (ctypes.c_ubyte * 16).from_buffer_copy(UUID("602d4995-b13a-429b-a66e-1935e44f4317").bytes_le)
        obj = ctypes.c_void_p()
        if ole.CoCreateInstance(ctypes.byref(klass), None, 1, ctypes.byref(interface), ctypes.byref(obj)) != 0:
            return set()
        table = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p)))[0]
        release = ctypes.WINFUNCTYPE(wintypes.ULONG, ctypes.c_void_p)(table[2])
        initialize = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_void_p)(table[3])
        mark_fullscreen = ctypes.WINFUNCTYPE(
            ctypes.c_long, ctypes.c_void_p, wintypes.HWND, wintypes.BOOL
        )(table[8])
        try:
            if initialize(obj) < 0:
                return set()
            return {hwnd for hwnd in windows if mark_fullscreen(obj, hwnd, fullscreen) >= 0}
        finally:
            release(obj)

    def update(self, windows):
        if removed := self.windows - windows:
            self.mark(removed, False)
        if added := windows - self.windows:
            self.windows |= self.mark(added, True)
        self.windows &= windows
