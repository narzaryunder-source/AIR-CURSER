"""A bounded HTTP content check, with a TCP fallback when an organization filters Windows NCSI."""

import http.client
import socket
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass(frozen=True)
class Health:
    state: str
    latency_ms: int | None = None

    @property
    def usable(self):
        return self.state in (
            "Internet verified",
            "Internet reachable · probe filtered",
            "Internet reachable · system proxy",
        )


def probe(timeout=1.5):
    start = time.monotonic()
    conn = http.client.HTTPConnection("www.msftconnecttest.com", timeout=timeout)
    reason = "Internet unreachable"
    try:
        conn.request("GET", "/connecttest.txt")
        response = conn.getresponse()
        if response.status == 200 and response.read(128).strip() == b"Microsoft Connect Test":
            return Health("Internet verified", round(1000 * (time.monotonic() - start)))
        if 300 <= response.status < 400 or response.status in (200, 407, 511):
            return Health("Possible captive portal")
    except socket.gaierror:
        reason = "DNS lookup failed"
    except (OSError, http.client.HTTPException):
        pass
    finally:
        conn.close()
    try:
        start = time.monotonic()
        with socket.create_connection(("1.1.1.1", 443), timeout):
            if reason == "DNS lookup failed":
                return Health(reason)
            return Health("Internet reachable · probe filtered", round(1000 * (time.monotonic() - start)))
    except OSError:
        # A managed PC may allow web access exclusively through a configured HTTPS proxy.
        if urllib.request.getproxies().get("https"):
            started = time.monotonic()
            try:
                req = urllib.request.Request("https://www.microsoft.com/", method="HEAD")
                with urllib.request.urlopen(req, timeout=timeout) as response:
                    if 200 <= response.status < 400:
                        return Health(
                            "Internet reachable · system proxy", round(1000 * (time.monotonic() - started))
                        )
            except (OSError, urllib.error.URLError, http.client.HTTPException):
                pass
        return Health(reason)


class InterfaceHTTPConnection(http.client.HTTPConnection):
    """Force IPv4 traffic to a specific Windows interface even when Ethernet or VPN wins routing."""

    def __init__(self, host, index, timeout):
        super().__init__(host, timeout=timeout)
        self.index = index

    def connect(self):
        import struct

        addresses = socket.getaddrinfo(self.host, self.port, socket.AF_INET, socket.SOCK_STREAM)
        if not addresses:
            raise OSError("No IPv4 address for the connectivity check.")
        last_error = None
        deadline = time.monotonic() + self.timeout
        for family, kind, proto, _, address in addresses:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            sock = socket.socket(family, kind, proto)
            try:
                sock.settimeout(remaining)
                # Winsock IP_UNICAST_IF takes the IPv4 interface index in network byte order.
                sock.setsockopt(socket.IPPROTO_IP, getattr(socket, "IP_UNICAST_IF", 31),
                                struct.pack("!I", self.index))
                sock.connect(address)
            except OSError as exc:
                sock.close()
                last_error = exc
            else:
                self.sock = sock
                return
        raise last_error or TimeoutError("Wi-Fi route timed out.")


def probe_interface(index, timeout=1.5):
    started = time.monotonic()
    conn = InterfaceHTTPConnection("www.msftconnecttest.com", index, timeout)
    try:
        conn.request("GET", "/connecttest.txt")
        response = conn.getresponse()
        if response.status == 200 and response.read(128).strip() == b"Microsoft Connect Test":
            return Health("Internet verified", round(1000 * (time.monotonic() - started)))
        if response.status in (200, 407, 511) or 300 <= response.status < 400:
            return Health("Possible captive portal")
        return Health("Wi-Fi verification endpoint unavailable")
    except socket.gaierror:
        return Health("DNS lookup failed")
    except (OSError, http.client.HTTPException):
        return Health("Wi-Fi internet route unverified")
    finally:
        conn.close()
