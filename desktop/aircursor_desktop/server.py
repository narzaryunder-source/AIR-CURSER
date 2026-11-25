"""The computer's side of "phone as keyboard & touchpad": a TCP server that the AirCursor phone
app finds by itself (network discovery, like AirCursor PC) or by its address, behind a 6-digit code.

One phone at a time (a newly paired phone takes over), at most 3 phones trying codes at once, and
nothing happens while AirCursor is locked.
"""
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


class RemoteHost:
    def __init__(self, controller, on_change=lambda: None, name=None, port=DEFAULT_PORT):
        self.ctl = controller
        self.on_change = on_change
        self.name = (name or platform.node() or "Computer").replace("\n", " ").replace("|", " ")[:40]
        self.want_port = port
        self.port = 0
        self.code = protocol.new_code()
        self.gate = protocol.PairingGate(lambda: self.code, self.renew_code)
        self.paused = False
        self.client = None
        self.client_addr = ""
        self.server = None
        self._handshakes = 0
        self._lock = threading.Lock()
        self._zc = None

    @property
    def running(self):
        return self.server is not None

    def renew_code(self):
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
        self._advertise()
        self.on_change()

    def stop(self):
        s, self.server = self.server, None
        if s:
            try:
                s.close()
            except OSError:
                pass
        self._close_client(bye=True)
        if self._zc:
            try:
                self._zc.close()
            except Exception:
                pass
            self._zc = None
        self.on_change()

    def _advertise(self):
        """Lets the phone find this computer by itself. Without it, the phone connects by address."""
        try:
            from zeroconf import ServiceInfo, Zeroconf
        except ImportError:
            return
        try:
            ips = [socket.inet_aton(ip) for ip in local_addresses()]
            info = ServiceInfo(protocol.SERVICE_TYPE, "AirCursor – %s.%s" % (self.name, protocol.SERVICE_TYPE),
                               addresses=ips, port=self.port, properties={"v": "1"})
            self._zc = Zeroconf()
            self._zc.register_service(info)
        except Exception:
            self._zc = None

    def _accept_loop(self, s):
        while self.server is s:
            try:
                sock, addr = s.accept()
            except OSError:
                break
            with self._lock:
                if self._handshakes >= MAX_HANDSHAKES:
                    sock.close()
                    continue
                self._handshakes += 1
            threading.Thread(target=self._serve, args=(sock, addr), name="remote-client", daemon=True).start()

    def _close_client(self, bye=False):
        c, self.client = self.client, None
        if c:
            if bye:
                try:
                    c.sendall(b"BYE\n")
                except OSError:
                    pass
            try:
                c.close()
            except OSError:
                pass
        self.ctl.release_all()

    def _serve(self, sock, addr):
        shaking = True
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(8)
            f = sock.makefile("r", encoding="utf-8", errors="replace", newline="\n")
            first = f.readline(200)
            if not self.gate.check(protocol.parse_hello(first)):
                sock.sendall(b"NO\n")
                return
            with self._lock:
                self._handshakes -= 1
            shaking = False
            self._close_client(bye=True)   # one phone at a time: the newest paired one wins
            self.client = sock
            self.client_addr = addr[0]
            sock.sendall(("OK %s|w1\n" % self.name).encode("utf-8"))
            self.on_change()
            sock.settimeout(30)   # the phone pings every few seconds
            while self.client is sock:
                line = f.readline(protocol.MAX_TEXT * 2 + 16)
                if not line:
                    break
                cmd = protocol.parse(line)
                if cmd is None or cmd[0] == "ping" or self.paused:
                    continue
                self.ctl.apply(cmd)
        except (OSError, ValueError):
            pass
        finally:
            if shaking:
                with self._lock:
                    self._handshakes -= 1
            try:
                sock.close()
            except OSError:
                pass
            if self.client is sock:
                self.client = None
                self.client_addr = ""
                self.ctl.release_all()
                self.on_change()
