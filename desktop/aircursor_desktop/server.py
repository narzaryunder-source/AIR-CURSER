"""The computer's side of "phone as keyboard & touchpad": a TCP server that the AirCursor phone
app finds by itself (network discovery, like AirCursor PC) or by its address, behind a 6-digit code.

One phone at a time (a newly paired phone takes over), at most 3 phones trying codes at once, and
nothing happens while AirCursor is locked.
"""
import ipaddress
import time

import psutil
import platform
import socket
import threading

from . import protocol

DEFAULT_PORT = 47821
MAX_HANDSHAKES = 3


def local_addresses():
    """This computer's network addresses (IPv4, not 127.x) – the phone connects to one of them."""
    found = []
    try:
        for a in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = a[4][0]
            if not ip.startswith("127.") and ip not in found:
                found.append(ip)
    except OSError:
        pass
    try:   # the address of the way out (no packet is sent)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            ip = s.getsockname()[0]
            if not ip.startswith("127.") and ip not in found:
                found.insert(0, ip)
    except OSError:
        pass
    return found


def on_local_link(address):
    """Accept loopback and IPv4 addresses on an actual interface subnet, including USB tethering."""
    peer = ipaddress.IPv4Address(address)
    if peer.is_loopback:
        return True
    for entries in psutil.net_if_addrs().values():
        for entry in entries:
            if entry.family == socket.AF_INET and entry.netmask and entry.netmask != "0.0.0.0":
                if peer in ipaddress.IPv4Network((entry.address, entry.netmask), strict=False):
                    return True
    return False


class RemoteHost:
    def __init__(self, controller, on_change=lambda: None, name=None, port=DEFAULT_PORT):
        self.ctl = controller
        self.on_change = on_change
        self.name = (name or platform.node() or "Computer").replace("\n", " ").replace("|", " ")[:40]
        self.want_port = port
        self.port = 0
        self.code = protocol.new_code()
        self.gate = protocol.PairingGate(lambda: self.code, self.renew_code)
        self._paused = False
        self.events = 0
        self.last_seen = None
        self.client = None
        self.client_addr = ""
        self.server = None
        self._handshakes = 0
        self._lock = threading.RLock()
        self._zc = None

    @property
    def running(self):
        return self.server is not None

    @property
    def paused(self):
        return self._paused

    @paused.setter
    def paused(self, value):
        with self._lock:
            self._paused = bool(value)
            if value:
                self.ctl.release_all()

    def renew_code(self):
        with self._lock:
            self.code = protocol.new_code()
            self._close_client(bye=True)
        self.on_change()

    def start(self):
        if self.server:
            return
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("0.0.0.0", self.want_port))
        except OSError:
            s.bind(("0.0.0.0", 0))   # the usual port is taken: any free one
        s.listen(8)
        self.server = s
        self.port = s.getsockname()[1]
        threading.Thread(target=self._accept_loop, args=(s,), name="remote-accept", daemon=True).start()
        threading.Thread(target=self._advertise, args=(s,), name="remote-discovery", daemon=True).start()
        self.on_change()

    def stop(self):
        with self._lock:
            s, self.server = self.server, None
            if s:
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                s.close()
            self._close_client(bye=True)
            zc, self._zc = self._zc, None
        if zc:
            threading.Thread(target=zc.close, daemon=True).start()
        self.on_change()

    def _advertise(self, server):
        """Lets the phone find this computer by itself. Without it, the phone connects by address."""
        try:
            from zeroconf import ServiceInfo, Zeroconf
        except ImportError:
            return
        zc = None
        try:
            ips = [socket.inet_aton(ip) for ip in local_addresses()]
            info = ServiceInfo(protocol.SERVICE_TYPE, "AirCursor – %s.%s" % (self.name, protocol.SERVICE_TYPE),
                               addresses=ips, port=self.port, properties={"v": "1"})
            zc = Zeroconf()
            zc.register_service(info)
            with self._lock:
                if self.server is server:
                    self._zc, zc = zc, None
        except (OSError, RuntimeError, ValueError):
            pass
        finally:
            if zc:
                zc.close()

    def _accept_loop(self, s):
        while self.server is s:
            try:
                sock, addr = s.accept()
            except OSError:
                break
            try:
                allowed = on_local_link(addr[0])
            except (OSError, ValueError):
                allowed = False
            with self._lock:
                if not allowed or self.server is not s or self._handshakes >= MAX_HANDSHAKES:
                    sock.close()
                    continue
                self._handshakes += 1
            threading.Thread(target=self._serve, args=(sock, addr, s), name="remote-client", daemon=True).start()

    def _close_client(self, bye=False):
        c, self.client = self.client, None
        self.client_addr = ""
        if c:
            if bye:
                try:
                    c.sendall(b"BYE\n")
                except OSError:
                    pass
            try:
                c.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            c.close()
        self.ctl.release_all()

    def _serve(self, sock, addr, listener):
        shaking = True
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(8)
            with sock.makefile("r", encoding="utf-8", errors="replace", newline="\n") as stream:
                first = stream.readline(200)
                code = protocol.parse_hello(first)
                if not first.endswith("\n") or not self.gate.check(code):
                    sock.sendall(b"NO\n")
                    return
                with self._lock:
                    if self.server is not listener or self.paused or code != self.code:
                        sock.sendall(b"NO\n")
                        return
                    self._handshakes -= 1
                    shaking = False
                    self._close_client(bye=True)
                    self.client = sock
                    self.client_addr = addr[0]
                    self.events = 0
                    self.last_seen = time.monotonic()
                    sock.sendall(("OK %s|w1\n" % self.name).encode("utf-8"))
                self.on_change()
                sock.settimeout(30)
                while self.client is sock:
                    line = stream.readline(protocol.MAX_TEXT * 2 + 2)
                    if not line.endswith("\n"):
                        break
                    cmd = protocol.parse(line)
                    with self._lock:
                        if self.client is not sock:
                            break
                        if cmd:
                            self.last_seen = time.monotonic()
                            if cmd[0] != "ping" and not self.paused:
                                self.ctl.apply(cmd)
                                self.events += 1
        except (OSError, ValueError):
            pass
        finally:
            with self._lock:
                if shaking:
                    self._handshakes -= 1
                current = self.client is sock
                if current:
                    self._close_client()
            sock.close()
            if current:
                self.on_change()
