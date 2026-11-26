from contextlib import nullcontext
import socket
import pytest
from aircursor_desktop import connectivity


@pytest.mark.parametrize(
    ("status", "body", "quality"),
    [
        (200, b"Microsoft Connect Test", "Internet verified"),
        (302, b"", "Possible captive portal"),
        (200, b"Sign in to Wi-Fi", "Possible captive portal"),
        (503, b"", "Internet reachable · probe filtered"),
    ],
)
def test_http_probe_classification(monkeypatch, status, body, quality):
    class Response:
        def read(self, n):
            return body

    reply = Response()
    reply.status = status

    class Connection:
        def request(self, *args):
            pass

        def getresponse(self):
            return reply

        def close(self):
            pass

    monkeypatch.setattr(connectivity.http.client, "HTTPConnection", lambda *args, **kw: Connection())
    monkeypatch.setattr(socket, "create_connection", lambda *_: nullcontext())
    assert connectivity.probe().state == quality


def test_dns_failure_even_when_tcp_route_works(monkeypatch):
    class Connection:
        def request(self, *args):
            raise socket.gaierror()

        def close(self):
            pass

    monkeypatch.setattr(connectivity.http.client, "HTTPConnection", lambda *args, **kw: Connection())
    monkeypatch.setattr(socket, "create_connection", lambda *_: nullcontext())
    result = connectivity.probe()
    assert result.state == "DNS lookup failed" and not result.usable


def test_proxy_only_connection_does_not_trigger_failover(monkeypatch):
    class Direct:
        def request(self, *args):
            raise OSError()

        def close(self):
            pass

    def blocked(*args):
        raise OSError()

    class Proxied:
        status = 200

    monkeypatch.setattr(connectivity.http.client, "HTTPConnection", lambda *args, **kw: Direct())
    monkeypatch.setattr(socket, "create_connection", blocked)
    monkeypatch.setattr(connectivity.urllib.request, "getproxies", lambda: {"https": "proxy"})
    monkeypatch.setattr(connectivity.urllib.request, "urlopen", lambda *args, **kw: nullcontext(Proxied()))
    assert connectivity.probe().usable
