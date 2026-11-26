"""Turns the phone's touchpad and keyboard commands into the computer's mouse and keyboard.

[Controller] holds the logic (pointer speed, scrolling, held keys, shortcuts) and talks to a small
backend: [WinBackend] on Windows (SendInput, the same way a real mouse and keyboard arrive), or a
recording fake in the tests.
"""
import sys
import time

from . import keymap

PAD_ACROSS = 470.0     # touchpad points (after the phone's acceleration) that cross the screen
SCROLL_GAIN = 2.4      # wheel units per touchpad point while scrolling with two fingers
WHEEL_STEP = 120       # one wheel notch
CTRL_ALT_WIN = {0xA2, 0xA3, 0xA4, 0xA5, 0x5B, 0x5C}


class Controller:
    def __init__(self, backend):
        self.b = backend
        self.speed = 1.0
        self.natural_scroll = True
        self.held_keys = set()
        self.held_buttons = set()
        self._fx = self._fy = 0.0      # where the pointer is, with the part of a pixel
        self._sx = self._sy = 0.0      # scrolling not sent yet (less than one unit)

    # ---- pointer ----

    def move(self, dx, dy):
        w, h, left, top = self.b.screen()
        x, y = self.b.cursor()
        # Start from where the cursor is now (a real mouse may have moved it), keep the fraction.
        if int(self._fx) != x or int(self._fy) != y:
            self._fx, self._fy = float(x), float(y)
        span = self.b.pointer_span() if hasattr(self.b, "pointer_span") else w
        k = span * self.speed / PAD_ACROSS
        self._fx = min(max(self._fx + dx * k, left), left + w - 1)
        self._fy = min(max(self._fy + dy * k, top), top + h - 1)
        self.b.move_to(int(self._fx), int(self._fy))

    def button(self, b, down):
        if down:
            self.held_buttons.add(b)
        else:
            self.held_buttons.discard(b)
        self.b.button(b, down)

    def click(self, b):
        self.b.button(b, True)
        self.b.button(b, False)

    def wheel(self, dy, dx):
        if dy:
            self.b.wheel(-dy * WHEEL_STEP)
        if dx:
            self.b.hwheel(dx * WHEEL_STEP)

    def scroll(self, dx, dy):
        """Two fingers on the pad: the page follows them (fingers up = page up = scroll down)."""
        if not self.natural_scroll:
            dx, dy = -dx, -dy
        self._sy += dy * SCROLL_GAIN
        self._sx += -dx * SCROLL_GAIN
        iy, ix = int(self._sy), int(self._sx)
        if iy:
            self._sy -= iy
            self.b.wheel(iy)
        if ix:
            self._sx -= ix
            self.b.hwheel(ix)

    def scroll_end(self):
        self._sx = self._sy = 0.0

    # ---- keyboard ----

    def key(self, keysym):
        vk = keymap.VK.get(keysym)
        if vk is not None:
            self._tap(vk)
            return
        ch = keymap.char_of(keysym)
        if ch is None:
            return
        if self.held_keys & CTRL_ALT_WIN:
            vk = keymap.vk_for_shortcut(ch) or self.b.vk_for_char(ch)
            if vk:
                self._tap(vk)
                return
        self._type_char(ch)

    def key_state(self, keysym, down):
        vk = keymap.VK.get(keysym)
        if vk is None:
            return
        if down:
            if vk in self.held_keys:
                return
            self.held_keys.add(vk)
        else:
            if vk not in self.held_keys:
                return
            self.held_keys.discard(vk)
        self.b.key(vk, down, vk in keymap.EXTENDED)

    def text(self, text):
        for ch in text:
            if ch == "\n":
                self._tap(0x0D)
            elif ch == "\t":
                self._tap(0x09)
            else:
                self._type_char(ch)

    def _tap(self, vk):
        ext = vk in keymap.EXTENDED
        self.b.key(vk, True, ext)
        self.b.key(vk, False, ext)

    def _type_char(self, ch):
        # UTF-16 units: characters beyond the first 65536 (emoji) are two.
        data = ch.encode("utf-16-le")
        units = [int.from_bytes(data[i:i + 2], "little") for i in range(0, len(data), 2)]
        for u in units:
            self.b.unicode(u, True)
        for u in units:
            self.b.unicode(u, False)

    # ---- the phone went away ----

    def release_all(self):
        for vk in list(self.held_keys):
            self.b.key(vk, False, vk in keymap.EXTENDED)
        self.held_keys.clear()
        for b in list(self.held_buttons):
            self.b.button(b, False)
        self.held_buttons.clear()
        self.scroll_end()

    def apply(self, cmd):
        kind = cmd[0]
        if kind == "move":
            self.move(cmd[1], cmd[2])
        elif kind == "button":
            self.button(cmd[1], cmd[2])
        elif kind == "click":
            self.click(cmd[1])
        elif kind == "wheel":
            self.wheel(cmd[1], cmd[2])
        elif kind == "scroll":
            self.scroll(cmd[1], cmd[2])
        elif kind == "scroll_end":
            self.scroll_end()
        elif kind == "key":
            self.key(cmd[1])
        elif kind == "key_state":
            self.key_state(cmd[1], cmd[2])
        elif kind == "text":
            self.text(cmd[1])


class WinBackend:
    """Windows' SendInput: the same input path as a real mouse and keyboard."""

    def __init__(self):
        import ctypes
        from ctypes import wintypes
        self.ct = ctypes
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        self.user32 = user32
        self._geometry_until = 0.0
        ULONG_PTR = ctypes.c_size_t

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                        ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

        class HARDWAREINPUT(ctypes.Structure):
            _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]

        class _U(ctypes.Union):
            _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

        class INPUT(ctypes.Structure):
            _anonymous_ = ("u",)
            _fields_ = [("type", wintypes.DWORD), ("u", _U)]

        self.INPUT, self.MOUSEINPUT, self.KEYBDINPUT = INPUT, MOUSEINPUT, KEYBDINPUT
        user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
        user32.SendInput.restype = wintypes.UINT
        user32.GetCursorPos.argtypes = (ctypes.POINTER(wintypes.POINT),)
        user32.GetSystemMetrics.argtypes = (ctypes.c_int,)
        user32.VkKeyScanW.argtypes = (wintypes.WCHAR,)
        user32.VkKeyScanW.restype = ctypes.c_short

    def _send(self, inp):
        self.user32.SendInput(1, self.ct.byref(inp), self.ct.sizeof(inp))

    def screen(self):
        now = time.monotonic()
        if now >= self._geometry_until:
            m = self.user32.GetSystemMetrics
            self._geometry = (m(78), m(79), m(76), m(77))
            self._primary_width = max(1, m(0))
            self._geometry_until = now + 0.25
        return self._geometry

    def pointer_span(self):
        self.screen()
        return self._primary_width

    def cursor(self):
        from ctypes import wintypes
        p = wintypes.POINT()
        self.user32.GetCursorPos(self.ct.byref(p))
        return p.x, p.y

    def move_to(self, x, y):
        w, h, left, top = self.screen()
        nx = int((x - left) * 65535 / max(1, w - 1))
        ny = int((y - top) * 65535 / max(1, h - 1))
        inp = self.INPUT(type=0)
        inp.mi = self.MOUSEINPUT(nx, ny, 0, 0x0001 | 0x8000 | 0x4000, 0, 0)   # MOVE | ABSOLUTE | VIRTUALDESK
        self._send(inp)

    def button(self, b, down):
        flags = {"l": (0x0002, 0x0004), "r": (0x0008, 0x0010), "m": (0x0020, 0x0040)}[b][0 if down else 1]
        inp = self.INPUT(type=0)
        inp.mi = self.MOUSEINPUT(0, 0, 0, flags, 0, 0)
        self._send(inp)

    def wheel(self, delta):
        inp = self.INPUT(type=0)
        inp.mi = self.MOUSEINPUT(0, 0, delta & 0xFFFFFFFF, 0x0800, 0, 0)
        self._send(inp)

    def hwheel(self, delta):
        inp = self.INPUT(type=0)
        inp.mi = self.MOUSEINPUT(0, 0, delta & 0xFFFFFFFF, 0x1000, 0, 0)
        self._send(inp)

    def key(self, vk, down, extended=False):
        flags = (0 if down else 0x0002) | (0x0001 if extended else 0)
        inp = self.INPUT(type=1)
        inp.ki = self.KEYBDINPUT(vk, 0, flags, 0, 0)
        self._send(inp)

    def unicode(self, unit, down):
        inp = self.INPUT(type=1)
        inp.ki = self.KEYBDINPUT(0, unit, 0x0004 | (0 if down else 0x0002), 0, 0)
        self._send(inp)

    def vk_for_char(self, ch):
        r = self.user32.VkKeyScanW(ch)
        return None if r == -1 else r & 0xFF


def backend():
    return WinBackend() if sys.platform == "win32" else None
