"""Ask the Windows 11 compositor for a transient (acrylic) window backdrop and rounded corners."""
import ctypes
import sys


def apply_backdrop(window, dark):
    if sys.platform != "win32" or sys.getwindowsversion().build < 22621:
        return False
    hwnd = ctypes.c_void_p(int(window.winId()))
    dwm = ctypes.windll.dwmapi
    def attr(key, value):
        data = ctypes.c_int(value)
        return dwm.DwmSetWindowAttribute(hwnd, key, ctypes.byref(data), ctypes.sizeof(data))
    attr(20, dark)
    attr(33, 2)
    if attr(38, 3) != 0:
        return False
    margins = (ctypes.c_int * 4)(0, 0, 36, 0)
    return dwm.DwmExtendFrameIntoClientArea(hwnd, ctypes.byref(margins)) == 0
