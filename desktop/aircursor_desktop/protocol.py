"""The phone's "keyboard & touchpad" protocol (the same as the AirCursor phone app speaks to
AirCursor PC – see app/src/main/java/com/aircursor/core/RemoteInput.kt). One TCP connection, one
command per text line; the first line is the 6-digit pairing code shown on the computer.

  HELLO 1 <code>   pairing; the answer is "OK <name>" or "NO"
  M dx dy          move the pointer        B l|m|r d|u   button down / up
  C l|m|r          click                   W dy dx       wheel steps (dy > 0 = down)
  S dx dy / SE     two-finger scrolling (content follows the fingers) / scrolling ended
  K sym            key press (X11 keysym)  KD sym / KU sym   key held / released
  T base64         type text               P             keep-alive
"""
import base64
import secrets
import threading
import time

PROTOCOL = 1
CODE_LENGTH = 6
SERVICE_TYPE = "_aircursorpc._tcp.local."
MAX_MOVE = 4000.0
MAX_TEXT = 4000


def hello(code):
    return "HELLO %d %s" % (PROTOCOL, code)


def parse_hello(line):
    p = line.strip().split(" ")
    if len(p) != 3 or p[0] != "HELLO" or p[1] != str(PROTOCOL):
        return None
    return p[2] if len(p[2]) == CODE_LENGTH and p[2].isascii() and p[2].isdigit() else None


def _button(s):
    return s if s in ("l", "m", "r") else None


def _keysym(s):
    v = int(s)
    return v if 0x20 <= v <= 0x0110FFFF else None


def _num(s):
    v = float(s)
    if v != v or abs(v) > MAX_MOVE:   # NaN or too far
        raise ValueError
    return v


def parse(line):
    """One line → a tuple command, or None for anything malformed or out of range."""
    if len(line) > MAX_TEXT * 2:
        return None
    p = line.strip().split(" ")
    try:
        c = p[0]
        if c in ("M", "S") and len(p) == 3:
            return ("move" if c == "M" else "scroll", _num(p[1]), _num(p[2]))
        if c == "SE" and len(p) == 1:
            return ("scroll_end",)
        if c == "B" and len(p) == 3 and _button(p[1]) and p[2] in ("d", "u"):
            return ("button", p[1], p[2] == "d")
        if c == "C" and len(p) == 2 and _button(p[1]):
            return ("click", p[1])
        if c == "W" and len(p) == 3:
            dy, dx = int(p[1]), int(p[2])
            return ("wheel", dy, dx) if abs(dy) <= 50 and abs(dx) <= 50 else None
        if c == "K" and len(p) == 2:
            k = _keysym(p[1])
            return ("key", k) if k else None
        if c in ("KD", "KU") and len(p) == 2:
            k = _keysym(p[1])
            return ("key_state", k, c == "KD") if k else None
        if c == "T" and len(p) == 2:
            t = base64.b64decode(p[1], validate=True).decode("utf-8")
            return ("text", t) if 0 < len(t) <= MAX_TEXT else None
        if c == "P" and len(p) == 1:
            return ("ping",)
    except (ValueError, UnicodeDecodeError):
        return None
    return None


def new_code():
    return "".join(str(secrets.randbelow(10)) for _ in range(CODE_LENGTH))


class PairingGate:
    """Anyone on the same Wi-Fi can try codes: one check at a time, a pause after each wrong code,
    and a new code after [max_wrong] wrong ones in a row (like the phone app)."""

    def __init__(self, current, renew, max_wrong=5, pause=1.0, sleep=time.sleep):
        self.current, self.renew, self.max_wrong, self.pause, self.sleep = current, renew, max_wrong, pause, sleep
        self.wrong = 0
        self._lock = threading.Lock()

    def check(self, given):
        with self._lock:
            if given is not None and secrets.compare_digest(given, self.current()):
                self.wrong = 0
                return True
            self.wrong += 1
            if self.wrong >= self.max_wrong:
                self.wrong = 0
                self.renew()
            if self.pause:
                self.sleep(self.pause)
            return False
