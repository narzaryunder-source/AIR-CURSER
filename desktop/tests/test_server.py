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
        h.paused = True  # AirCursor locked: the phone does nothing
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
        f2.close()  # a socket only really closes once its file object is closed too
        s2.close()
        assert wait(lambda: h.client is None)
        assert ctl.released >= 1
    finally:
        h.stop()


def test_pending_connection_cannot_pair_after_stop(monkeypatch):
    ctl = Ctl()
    host = RemoteHost(ctl, port=0)
    monkeypatch.setattr(host, "_advertise", lambda *_: None)
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
    monkeypatch.setattr(host, "_advertise", lambda *_: None)
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


def test_blocked_windows_input_is_reported_separately(monkeypatch):
    ctl = Ctl()
    ctl.apply = lambda cmd: False
    host = RemoteHost(ctl, port=0)
    monkeypatch.setattr(host, "_advertise", lambda *_: None)
    host.start()
    try:
        sock, stream, answer = connect(host, host.code)
        assert answer.startswith("OK")
        sock.sendall(b"C l\n")
        assert wait(lambda: host.rejected == 1)
        assert host.events == 0
        stream.close()
        sock.close()
    finally:
        host.stop()


def test_connected_addresses_include_lan_hotspot_and_usb_without_hostname_lookup(monkeypatch):
    from types import SimpleNamespace as Row
    from aircursor_desktop import server

    ips = {
        "Wi-Fi": "192.168.1.20",
        "USB": "192.168.42.100",
        "Hotspot": "192.168.43.8",
        "Loopback": "127.0.0.1",
        "Disconnected": "10.7.0.1",
    }
    monkeypatch.setattr(
        server.psutil,
        "net_if_addrs",
        lambda: {interface: [Row(family=socket.AF_INET, address=ip)] for interface, ip in ips.items()},
    )
    monkeypatch.setattr(
        server.psutil,
        "net_if_stats",
        lambda: {interface: Row(isup=interface != "Disconnected") for interface in ips},
    )
    assert server.local_addresses() == ["192.168.1.20", "192.168.42.100", "192.168.43.8"]


def test_discovery_reannounces_when_pc_switches_network(monkeypatch):
    import zeroconf
    from aircursor_desktop import server

    events = []
    change = [0]
    networks = [["192.168.1.8"], ["192.168.43.10"], [], ["192.168.42.11"]]
    monkeypatch.setattr(server, "local_addresses", lambda: networks[change[0]])

    class Discovery:
        def __init__(self, interfaces, ip_version):
            self.interfaces = interfaces

        def register_service(self, info, allow_name_change):
            events.append(("register", self.interfaces, info.port))

        def close(self):
            events.append(("close", self.interfaces))

    class Stopped:
        def is_set(self):
            return change[0] == len(networks)

        def wait(self, timeout):
            change[0] += 1

    monkeypatch.setattr(zeroconf, "Zeroconf", Discovery)
    host = RemoteHost(Ctl(), name="Switch test")
    listener = host.server = object()
    host._advertise(listener, Stopped(), 47821)
    assert host.addresses == ("192.168.42.11",)
    assert events == [
        ("register", ["192.168.1.8"], 47821),
        ("close", ["192.168.1.8"]),
        ("register", ["192.168.43.10"], 47821),
        ("close", ["192.168.43.10"]),
        ("register", ["192.168.42.11"], 47821),
        ("close", ["192.168.42.11"]),
    ]
