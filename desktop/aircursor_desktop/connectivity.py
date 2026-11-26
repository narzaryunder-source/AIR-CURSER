"""A bounded HTTP content check, with a TCP fallback when an organization filters Windows NCSI."""
import http.client
import socket
import time
from dataclasses import dataclass


@dataclass(frozen=True)
class Health:
    state: str
    latency_ms: int | None = None

    @property
    def usable(self):
        return self.state in ("Internet verified", "Internet reachable · probe filtered")


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
        return Health(reason)
