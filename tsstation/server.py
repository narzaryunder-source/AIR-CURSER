#!/usr/bin/env python3
"""TS Station backend (AirCursor PC edition).

A small local server for the TS Station interface: finds the installed games, gets their art
(the game's own screenshots from its AppStream metadata, or its icon), starts games through the
AirCursor launcher (game mode, FPS overlay) and keeps settings and play history.
Only 127.0.0.1 and a random key: other apps on the phone cannot use it.
"""
import glob
import html
import json
import mimetypes
import os
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
UI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui")
DATA = os.path.join(HOME, ".config", "ts-station")
CACHE = os.path.join(HOME, ".cache", "ts-station")
SETTINGS = os.path.join(DATA, "settings.json")
MY_GAMES = os.path.join(HOME, ".local", "share", "applications")
APP_DIRS = [MY_GAMES, "/usr/local/share/applications", "/usr/share/applications"]
TOKEN = secrets.token_hex(16)
NOT_GAMES = re.compile(r"(stockfish|mangohud|cloudpc-|aircursor-)", re.I)

os.makedirs(DATA, exist_ok=True)
os.makedirs(os.path.join(CACHE, "art"), exist_ok=True)
_lock = threading.Lock()


# ---- settings + history ------------------------------------------------------------------------

def load_settings():
    try:
        with open(SETTINGS) as f:
            return json.load(f)
    except Exception:
        return {}


def save_settings(d):
    with _lock:
        tmp = SETTINGS + ".tmp"
        with open(tmp, "w") as f:
            json.dump(d, f, indent=1)
        os.replace(tmp, SETTINGS)


def defaults(s):
    s.setdefault("theme", "native")
    s.setdefault("sounds", True)
    s.setdefault("favorites", [])
    s.setdefault("played", {})     # id -> {"last": epoch, "count": n}
    return s


# ---- games -------------------------------------------------------------------------------------

def read_desktop(path):
    entry, group = {}, None
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.rstrip("\n")
                if line.startswith("["):
                    group = line.strip()
                    continue
                if group != "[Desktop Entry]" or "=" not in line or line.startswith("#"):
                    continue
                k, v = line.split("=", 1)
                entry.setdefault(k.strip(), v.strip())
    except OSError:
        return None
    return entry


def find_games():
    seen, games = set(), []
    for d in APP_DIRS:
        for path in sorted(glob.glob(os.path.join(d, "*.desktop"))):
            gid = os.path.basename(path)[:-8]
            if gid in seen:
                continue
            e = read_desktop(path)
            if not e:
                continue
            seen.add(gid)
            cats = e.get("Categories", "")
            if "Game" not in cats.split(";") and "X-TSStation" not in cats:
                continue
            if e.get("NoDisplay", "").lower() == "true" or e.get("Hidden", "").lower() == "true":
                continue
            if NOT_GAMES.search(gid):
                continue
            games.append({
                "id": gid,
                "name": e.get("Name", gid),
                "about": e.get("Comment", ""),
                "icon": e.get("Icon", ""),
                "file": path,
                "mine": d == MY_GAMES,
                "genre": genre(cats),
            })
    return games


def genre(cats):
    for key, label in (("ActionGame", "Action"), ("ArcadeGame", "Arcade"), ("BoardGame", "Board"),
                       ("CardGame", "Cards"), ("LogicGame", "Puzzle"), ("Simulation", "Simulation"),
                       ("SportsGame", "Racing & sports"), ("StrategyGame", "Strategy"),
                       ("AdventureGame", "Adventure"), ("BlocksGame", "Blocks"), ("KidsGame", "Kids")):
        if key in cats:
            return label
    return "Game"


# ---- art ---------------------------------------------------------------------------------------

def metainfo_screenshots(gid):
    for path in glob.glob("/usr/share/metainfo/*.xml") + glob.glob("/usr/share/appdata/*.xml"):
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue
        ids = [t.text or "" for t in root.iter("launchable")] + [t.text or "" for t in root.findall("id")]
        if not any(i.strip().removesuffix(".desktop") == gid for i in ids):
            continue
        urls = []
        for shot in root.iter("screenshot"):
            imgs = shot.findall("image")
            src = [i for i in imgs if i.get("type", "source") == "source"] or imgs
            if src and src[0].text:
                urls.append(src[0].text.strip())
        return urls
    return []


def hero_art(gid):
    """Path of a cached big picture for the game, downloading it once (None if there is none)."""
    for ext in ("jpg", "png"):
        p = os.path.join(CACHE, "art", "%s.%s" % (gid, ext))
        if os.path.exists(p) and os.path.getsize(p) > 0:
            return p
    miss = os.path.join(CACHE, "art", gid + ".none")
    if os.path.exists(miss) and time.time() - os.path.getmtime(miss) < 3 * 86400:
        return None
    for url in metainfo_screenshots(gid)[:2]:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "TS-Station/0.1"})
            with urllib.request.urlopen(req, timeout=15) as r:
                data = r.read(8_000_000)
            ext = "png" if data[:4] == b"\x89PNG" else "jpg"
            p = os.path.join(CACHE, "art", "%s.%s" % (gid, ext))
            with open(p, "wb") as f:
                f.write(data)
            return p
        except Exception:
            continue
    open(miss, "w").close()
    return None


_theme = None


def icon_path(name):
    if not name:
        return None
    if name.startswith("/"):
        return name if os.path.exists(name) else None
    global _theme
    try:
        if _theme is None and not os.environ.get("DISPLAY"):
            _theme = False
        if _theme is None:
            import gi
            gi.require_version("Gtk", "3.0")
            from gi.repository import Gtk
            _theme = Gtk.IconTheme.get_default() or False
        if _theme:
            info = _theme.lookup_icon(name, 256, 0)
            if info and info.get_filename():
                return info.get_filename()
    except Exception:
        pass
    for pattern in ("/usr/share/icons/hicolor/256x256/apps/%s.png", "/usr/share/icons/hicolor/128x128/apps/%s.png",
                    "/usr/share/icons/hicolor/scalable/apps/%s.svg", "/usr/share/pixmaps/%s.png",
                    "/usr/share/pixmaps/%s.xpm", "/usr/share/icons/hicolor/64x64/apps/%s.png"):
        p = pattern % name
        if os.path.exists(p):
            return p
    return None


# ---- system ------------------------------------------------------------------------------------

def read(path, default=""):
    try:
        with open(path) as f:
            return f.read().strip()
    except OSError:
        return default


def system_info():
    fps = re.search(r"^fps_limit=(\d+)", read("/etc/cloudpc/MangoHud.conf"), re.M)
    mem = {}
    for line in read("/proc/meminfo").splitlines():
        k, _, v = line.partition(":")
        mem[k] = int(v.split()[0]) if v.split() else 0
    res = ""
    try:
        out = subprocess.run(["xrandr"], capture_output=True, text=True, timeout=5).stdout
        m = re.search(r"current (\d+) x (\d+)", out)
        res = "%sx%s" % m.groups() if m else ""
    except Exception:
        pass
    st = os.statvfs("/")
    return {
        "cores": os.cpu_count(),
        "memTotalGB": round(mem.get("MemTotal", 0) / 1048576, 1),
        "memFreeGB": round(mem.get("MemAvailable", 0) / 1048576, 1),
        "storageFreeGB": round(st.f_bavail * st.f_frsize / 1e9, 1),
        "fpsLimit": int(fps.group(1)) if fps else 0,
        "graphics": "Phone GPU (VirGL)" if read("/etc/cloudpc/gpu-mode") == "virgl" else "CPU (all cores)",
        "resolution": res,
        "programs": len(glob.glob("/proc/[0-9]*")),
        "programLimit": read("/etc/cloudpc/child-limit", "unknown"),
        "overlay": not os.path.exists(os.path.join(HOME, ".config/cloudpc/no-game-overlay")),
    }


def spawn(cmd):
    subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)


def launch(gid):
    g = next((g for g in find_games() if g["id"] == gid), None)
    if not g:
        return False
    s = defaults(load_settings())
    p = s["played"].setdefault(gid, {"count": 0})
    p["count"] = p.get("count", 0) + 1
    p["last"] = int(time.time())
    save_settings(s)
    spawn(["gio", "launch", g["file"]])
    return True


def add_game(name, command):
    name = re.sub(r"[\r\n]", " ", name).strip()[:60]
    command = re.sub(r"[\r\n]", " ", command).strip()[:400]
    if not name or not command:
        return None
    gid = "ts-" + (re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "game")
    os.makedirs(MY_GAMES, exist_ok=True)
    with open(os.path.join(MY_GAMES, gid + ".desktop"), "w") as f:
        f.write("[Desktop Entry]\nType=Application\nName=%s\nExec=cloudpc-launch %s.desktop %s\n"
                "Icon=applications-games\nCategories=Game;X-TSStation;\n" % (name, gid, command))
    return gid


def remove_game(gid):
    p = os.path.join(MY_GAMES, gid + ".desktop")
    if gid.startswith("ts-") and os.path.exists(p):
        os.remove(p)
        return True
    return False


# ---- HTTP ---------------------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json", cache=False):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=86400" if cache else "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def file(self, path):
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError:
            return self.send(404, {"error": "not found"})
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        self.send(200, data, ctype, cache=True)

    def authorized(self, q):
        return q.get("k", [""])[0] == TOKEN or self.headers.get("X-TS-Key") == TOKEN

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        if not u.path.startswith("/api/"):
            rel = u.path.lstrip("/") or "index.html"
            full = os.path.normpath(os.path.join(UI, rel))
            if not full.startswith(UI + os.sep):
                return self.send(404, {"error": "not found"})
            return self.file(full)
        if not self.authorized(q):
            return self.send(403, {"error": "key"})
        if u.path == "/api/games":
            s = defaults(load_settings())
            games = find_games()
            for g in games:
                p = s["played"].get(g["id"], {})
                g["last"] = p.get("last", 0)
                g["count"] = p.get("count", 0)
                g["favorite"] = g["id"] in s["favorites"]
                g.pop("file", None)
            return self.send(200, {"games": games})
        if u.path == "/api/settings":
            return self.send(200, defaults(load_settings()))
        if u.path == "/api/system":
            return self.send(200, system_info())
        if u.path.startswith("/api/icon/"):
            g = next((g for g in find_games() if g["id"] == u.path[10:]), None)
            p = icon_path(g["icon"]) if g else None
            return self.file(p) if p else self.send(404, {"error": "no icon"})
        if u.path.startswith("/api/art/"):
            p = hero_art(u.path[9:])
            return self.file(p) if p else self.send(404, {"error": "no art"})
        if u.path == "/api/doctor":
            try:
                out = subprocess.run(["pc-doctor"], capture_output=True, text=True, timeout=30).stdout
            except Exception as e:
                out = str(e)
            return self.send(200, {"text": out})
        return self.send(404, {"error": "unknown"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        if not self.authorized(q):
            return self.send(403, {"error": "key"})
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(min(n, 65536)) or b"{}")
        except Exception:
            body = {}
        if u.path == "/api/launch":
            return self.send(200, {"ok": launch(str(body.get("id", "")))})
        if u.path == "/api/settings":
            s = defaults(load_settings())
            for k in ("theme", "sounds", "favorites", "name"):
                if k in body:
                    s[k] = body[k]
            save_settings(s)
            return self.send(200, s)
        if u.path == "/api/add":
            return self.send(200, {"id": add_game(str(body.get("name", "")), str(body.get("command", "")))})
        if u.path == "/api/remove":
            return self.send(200, {"ok": remove_game(str(body.get("id", "")))})
        if u.path == "/api/tool":
            tools = {"benchmark": ["cloudpc-benchmark"], "store": ["aircursor-store"],
                     "light": ["cloudpc-light", "0"], "taskmanager": ["xfce4-taskmanager"]}
            t = tools.get(str(body.get("name", "")))
            if t:
                spawn(t)
            return self.send(200, {"ok": bool(t)})
        if u.path == "/api/quit":
            self.send(200, {"ok": True})
            threading.Thread(target=lambda: (time.sleep(0.3), os._exit(0)), daemon=True).start()
            return None
        return self.send(404, {"error": "unknown"})


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    url = "http://127.0.0.1:%d/#k=%s" % (srv.server_address[1], TOKEN)
    print(url, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
