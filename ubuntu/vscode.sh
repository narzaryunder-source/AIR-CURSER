#!/bin/bash
# cloudpc-vscode [folder|file] – VS Code in AirCursor PC.
#
# It runs code-server (the way the CodeWithHarry guide does it on a phone: code-server inside the
# Ubuntu) and shows it in Air, the phone's own browser engine, as an app window – smooth, no PC
# browser needed. The first start installs code-server (ubuntu-extras install vscode, one download).
# If that is not possible and Microsoft's VS Code package is installed, its "serve-web" is used.
#
# code-server listens only on 127.0.0.1 on a random port and asks for a password. The password is made
# once and filled in by a tiny one-time page, so nobody has to type it – and other apps on the phone
# cannot just open the editor.
. /tmp/cloudpc-session.env 2>/dev/null
export LANG=C.UTF-8
CONF=/root/.config/cloudpc-vscode
mkdir -p "$CONF" && chmod 700 "$CONF"

FOLDER=/root
for a in "$@"; do
    case "$a" in --*|"") continue ;; esac
    if [ -d "$a" ]; then FOLDER=$(readlink -f "$a"); elif [ -f "$a" ]; then FOLDER=$(dirname "$(readlink -f "$a")"); fi
done

show() {   # $1 = address: its own app window in Air when there is Air, else the PC's browser
    if command -v cloudpc-app >/dev/null 2>&1; then exec cloudpc-app vscode max "VS Code" "$1"; fi
    exec cloudpc-web "$1"
}
tcp_up() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
random_port() { echo $(( (RANDOM * 32768 + RANDOM) % 40000 + 20000 )); }
fail() {
    zenity --error --no-markup --width=560 --title="VS Code" --text="$1" >/dev/null 2>&1
    exit 1
}

# ---- code-server ---------------------------------------------------------------------------
cs_install() {
    ( ubuntu-extras install vscode 2>&1 | sed -u 's/^/# /' ) |
        zenity --progress --pulsate --auto-close --no-cancel --width=520 --title="VS Code" \
            --text="Installing VS Code (one download, about 240 MB)…" 2>/dev/null
    command -v code-server >/dev/null 2>&1
}

cs_start() {
    command -v code-server >/dev/null 2>&1 || cs_install || return 1
    local pass port
    pass=$(cat "$CONF/pass" 2>/dev/null)
    if [ -z "$pass" ]; then
        pass=$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')
        printf '%s' "$pass" > "$CONF/pass"
        chmod 600 "$CONF/pass"
    fi
    port=$(cat "$CONF/port" 2>/dev/null)
    if [ -z "$port" ] || ! tcp_up "$port"; then
        port=$(random_port)
        notify-send -a "AirCursor PC" -i code "Starting VS Code…" "The first start takes a little while." >/dev/null 2>&1 &
        ( cd /root && PASSWORD="$pass" nohup code-server --bind-addr "127.0.0.1:$port" --auth password \
            --disable-telemetry --disable-update-check "$FOLDER" > /tmp/vscode-cs.log 2>&1 & )
        local i
        for i in $(seq 1 120); do tcp_up "$port" && break; sleep 1; done
        tcp_up "$port" || return 1
        printf '%s' "$port" > "$CONF/port"
    fi
    # The one-time page that signs in (it serves itself once, then ends).
    local lp
    lp=$(random_port)
    python3 - "$lp" "$port" "$pass" "$FOLDER" > /dev/null 2>&1 <<'PY' &
import html, sys, time, urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
lp, port, pw, folder = sys.argv[1:5]
to = urllib.parse.quote("/?folder=" + folder, safe="")
page = (
    '<!doctype html><meta charset="utf-8"><title>VS Code</title>'
    '<body style="background:#1e1e1e;color:#bbb;font:15px sans-serif;display:grid;place-items:center;height:100vh">Opening VS Code…'
    '<form id="f" method="post" action="http://127.0.0.1:%s/login?to=%s">'
    '<input type="hidden" name="password" value="%s"></form>'
    '<script>document.getElementById("f").submit()</script>' % (port, to, html.escape(pw, quote=True))
).encode()


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/favicon"):
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(page)))
        self.end_headers()
        self.wfile.write(page)
        self.server.served = True

    def log_message(self, *a):
        pass


srv = HTTPServer(("127.0.0.1", int(lp)), H)
srv.served = False
srv.timeout = 5
end = time.time() + 90
while not srv.served and time.time() < end:
    srv.handle_request()
PY
    sleep 0.4
    show "http://127.0.0.1:$lp/"
}

# ---- Microsoft's VS Code (serve-web), only if its package is installed ----------------------
ms_start() {
    local CODE=/usr/share/code PORT=8765 TOKEN T i
    [ -x "$CODE/bin/code" ] || return 1
    [ -s "$CONF/token" ] || head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$CONF/token"
    TOKEN=$(cat "$CONF/token")
    if ! tcp_up "$PORT"; then
        T="$CODE/bin/code-tunnel"
        [ -x "$T" ] || T="$CODE/bin/code"
        ( cd /root && nohup "$T" serve-web --host 127.0.0.1 --port "$PORT" --connection-token "$TOKEN" \
            --accept-server-license-terms > /tmp/vscode-web.log 2>&1 & )
        for i in $(seq 1 180); do tcp_up "$PORT" && break; sleep 1; done
        tcp_up "$PORT" || return 1
    fi
    show "http://127.0.0.1:$PORT/?tkn=$TOKEN&folder=$FOLDER"
}

MODE=$(cat "$CONF/mode2" 2>/dev/null)
if [ "$MODE" = ms ]; then
    ms_start
fi
cs_start
ms_start
fail "VS Code could not start. Check the internet and try again.

$(tail -n 6 /tmp/vscode-cs.log 2>/dev/null)"
