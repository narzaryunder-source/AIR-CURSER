import socket
import time

from aircursor_desktop import protocol
from aircursor_desktop.server import RemoteHost


class Ctl:
    def __init__(self):
        self.cmds, self.released = [], 0

    def apply(self, c):
        self.cmds.append(c)

    def release_all(self):
        self.released += 1


def connect(host, code):
    s = socket.create_connection(("127.0.0.1", host.port), timeout=5)
    s.sendall((protocol.hello(code) + "\n").encode())
    f = s.makefile("r")
    return s, f, f.readline().strip()


def wait(cond, t=3.0):
    end = time.time() + t
    while time.time() < end and not cond():
        time.sleep(0.02)
    return cond()


def test_phone_pairs_and_drives_the_computer():
    ctl = Ctl()
    h = RemoteHost(ctl, name="TestPC", port=0)
    h.gate.pause = 0
    h.start()
    try:
        s, f, answer = connect(h, "000000" if h.code != "000000" else "111111")
        assert answer == "NO"
        s.close()
        s, f, answer = connect(h, h.code)
        assert answer == "OK TestPC|w1"
        s.sendall(b"M 10 5\nC l\nP\nbad line\n")
        assert wait(lambda: len(ctl.cmds) == 2)
        assert ctl.cmds == [("move", 10.0, 5.0), ("click", "l")]
        h.paused = True                       # AirCursor locked: the phone does nothing
        s.sendall(b"C r\n")
        time.sleep(0.3)
        assert ("click", "r") not in ctl.cmds
        h.paused = False
        # A second phone pairs: it takes over, the first one is told BYE.
        s2, f2, answer2 = connect(h, h.code)
        assert answer2.startswith("OK")
        assert f.readline().strip() == "BYE"
        s2.sendall(b"C m\n")
        assert wait(lambda: ("click", "m") in ctl.cmds)
        f2.close()   # a socket only really closes once its file object is closed too
        s2.close()
        assert wait(lambda: h.client is None)
        assert ctl.released >= 1
    finally:
        h.stop()


def test_pending_connection_cannot_pair_after_stop(monkeypatch):
    ctl = Ctl()
    host = RemoteHost(ctl, port=0)
    monkeypatch.setattr(host, "_advertise", lambda _: None)
    host.start()
    with socket.create_connection(("127.0.0.1", host.port), timeout=3) as phone:
        assert wait(lambda: host._handshakes == 1)
        host.stop()
        phone.sendall((protocol.hello(host.code) + "\n").encode())
        assert phone.recv(16) == b"NO\n"
    assert host.client is None


def test_lock_releases_held_input_and_rejects_new_session(monkeypatch):
    ctl = Ctl()
    host = RemoteHost(ctl, port=0)
    monkeypatch.setattr(host, "_advertise", lambda _: None)
    host.start()
    try:
        s, f, ok = connect(host, host.code)
        assert ok.startswith("OK ")
        s.sendall(b"KD 65507\n")
        assert wait(lambda: host.events == 1)
        released = ctl.released
        host.paused = True
        assert ctl.released == released + 1
        s2, f2, denied = connect(host, host.code)
        assert denied == "NO"
        f2.close()
        s2.close()
        f.close()
        s.close()
    finally:
        host.stop()
