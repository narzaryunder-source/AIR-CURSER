#!/usr/bin/env python3
"""Opens a web page you made (an HTML/JavaScript game or site) in Air instead of the PC's browser.

html-serve.py FILE.html

The PC's own browsers draw every frame with the phone's CPU, so games stutter. Air draws with the
phone's GPU. Air cannot read files inside the PC, so this serves the page's folder on this phone only
(127.0.0.1, a secret path that changes every time) and opens it in an Air app window. Edit the file and
press Reload: nothing is cached. The server ends by itself after 2 hours without requests.
"""
import hashlib
import http.server
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.parse

IDLE_SECONDS = int(os.environ.get("AIRCURSOR_HTML_IDLE", 2 * 3600))   # tests use a few seconds
STATE = os.environ.get("AIRCURSOR_HTML_STATE", "/tmp/cloudpc-html")


class Handler(http.server.SimpleHTTPRequestHandler):
    token = ""
    extensions_map = dict(http.server.SimpleHTTPRequestHandler.extensions_map, **{
        ".js": "text/javascript", ".mjs": "text/javascript", ".wasm": "application/wasm",
        ".json": "application/json", ".svg": "image/svg+xml", ".webp": "image/webp",
    })

    def log_message(self, *a):
        pass

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _strip(self):
        """Only /<token>/... is served (other apps on the phone cannot guess it)."""
        prefix = "/" + self.token + "/"
        if not self.path.startswith(prefix):
            self.send_error(404)
            return False
        self.path = self.path[len(prefix) - 1:]
        self.server.last = time.time()
        return True

    def do_GET(self):
        if self._strip():
            super().do_GET()

    def do_HEAD(self):
        if self._strip():
            super().do_HEAD()


def alive(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False


def serve(folder):
    """A server for this folder: the running one, or a new one in the background. → (port, token)."""
    os.makedirs(STATE, mode=0o700, exist_ok=True)
    rec = os.path.join(STATE, hashlib.sha1(folder.encode()).hexdigest()[:16])
    try:
        with open(rec) as f:
            port, token = f.read().split()
            if alive(int(port)):
                return int(port), token
    except (OSError, ValueError):
        pass
    token = secrets.token_urlsafe(18)
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), lambda *a, **k: Handler(*a, directory=folder, **k))
    Handler.token = token
    srv.last = time.time()
    port = srv.server_address[1]
    if os.fork() == 0:          # the child keeps serving; the parent opens the window
        os.setsid()
        devnull = os.open(os.devnull, os.O_RDWR)
        for fd in (0, 1, 2):
            os.dup2(devnull, fd)

        def watch():
            while time.time() - srv.last < IDLE_SECONDS:
                time.sleep(min(60, IDLE_SECONDS))
            srv.shutdown()
        threading.Thread(target=watch, daemon=True).start()
        srv.serve_forever()
        try:
            os.remove(rec)
        except OSError:
            pass
        os._exit(0)
    srv.socket.close()
    fd = os.open(rec, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write("%d %s\n" % (port, token))
    return port, token


def main(argv):
    if len(argv) < 2 or not os.path.isfile(argv[1]):
        print("usage: html-serve.py FILE.html", file=sys.stderr)
        return 1
    path = os.path.realpath(argv[1])
    folder, name = os.path.dirname(path), os.path.basename(path)
    port, token = serve(folder)
    url = "http://127.0.0.1:%d/%s/%s" % (port, token, urllib.parse.quote(name))
    if os.environ.get("AIRCURSOR_PRINT_ONLY"):
        print(url)
        return 0
    title = os.path.basename(folder) or os.path.splitext(name)[0]
    key = "html-" + hashlib.sha1(folder.encode()).hexdigest()[:10]
    return subprocess.call(["cloudpc-app", key, "max", title[:60], url])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
