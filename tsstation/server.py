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
os.makedirs(os.path.join(CACHE, "flathub"), exist_ok=True)
_lock = threading.Lock()
UA = {"User-Agent": "TS-Station/0.2 (AirCursor PC)"}

# ---- TS Store: free games that run on this PC ----------------------------------------------------
# pkg = Ubuntu packages to install, match = which installed menu entries belong to it,
# fh = Flathub id (only for pictures), mb = download size, fit = how it runs on a phone CPU/GPU.
STORE = [
    # (pkg, name, genre, about, match, flathub id, MB, fit)
    ("supertuxkart", "SuperTuxKart", "Racing", "Kart racing with items, 20+ tracks, Grand Prix and battle mode.",
     r"supertuxkart", "net.supertuxkart.SuperTuxKart", 640, "Heavy"),
    ("extremetuxracer", "Extreme Tux Racer", "Racing", "Slide down snowy mountains, collect herrings, beat the clock.",
     r"tuxracer|^etr", "net.sourceforge.ExtremeTuxRacer", 41, "Good"),
    ("trigger-rally", "Trigger Rally", "Racing", "Rally driving on dirt, snow and tarmac against the clock.",
     r"trigger", "io.sourceforge.trigger_rally.TriggerRally", 120, "Heavy"),
    ("xmoto", "X-Moto", "Racing", "2D motocross with real physics – hundreds of tricky levels.",
     r"xmoto", "org.tuxfamily.XMoto", 33, "Smooth"),
    ("armagetronad", "Armagetron Advanced", "Racing", "Light-cycle duels: trap your rivals with your trail.",
     r"armagetron", "org.armagetronad.ArmagetronAdvanced", 2, "Smooth"),
    ("torcs", "TORCS", "Racing", "Realistic car racing simulator with many cars and tracks.",
     r"torcs", "net.sourceforge.torcs", 335, "Heavy"),
    ("minetest", "Luanti (Minetest)", "Sandbox", "Build, mine and explore an endless block world.",
     r"minetest|luanti", "org.luanti.luanti", 30, "Good"),
    ("supertux", "SuperTux", "Platformer", "Classic side-scrolling jump-and-run adventure with Tux.",
     r"supertux2?$|supertux2", "org.supertuxproject.SuperTux", 165, "Smooth"),
    ("neverball", "Neverball", "Arcade", "Tilt the floor to roll the ball to the goal – 3D puzzle racing.",
     r"neverball", "org.neverball.Neverball", 39, "Good"),
    ("pingus", "Pingus", "Puzzle", "Guide a crowd of penguins safely to the exit.",
     r"pingus", "", 12, "Smooth"),
    ("chromium-bsu", "Chromium B.S.U.", "Arcade", "Fast scrolling space shooter.",
     r"chromium-bsu", "net.sourceforge.chromium-bsu", 1, "Good"),
    ("kobodeluxe", "Kobo Deluxe", "Arcade", "Destroy space fortresses in this quick arcade shooter.",
     r"kobo", "net.olofson.KoboDeluxe", 1, "Smooth"),
    ("teeworlds", "Teeworlds", "Shooter", "Fast 2D online shooter with grappling hooks.",
     r"teeworlds", "com.teeworlds.Teeworlds", 8, "Smooth"),
    ("openarena", "OpenArena", "Shooter", "Fast arena first-person shooter.",
     r"openarena", "io.github.ec_.Quake3e.OpenArena", 420, "Heavy"),
    ("hedgewars", "Hedgewars", "Strategy", "Turn-based artillery battles with hedgehogs.",
     r"hedgewars", "org.hedgewars.Hedgewars", 150, "Good"),
    ("wesnoth", "Battle for Wesnoth", "Strategy", "Fantasy turn-based strategy with long campaigns.",
     r"wesnoth", "org.wesnoth.Wesnoth", 280, "Smooth"),
    ("warzone2100", "Warzone 2100", "Strategy", "Real-time strategy: build a base, design units, win the war.",
     r"warzone", "net.wz2100.wz2100", 150, "Heavy"),
    ("openttd openttd-opengfx", "OpenTTD", "Simulation", "Build a transport empire with trains, ships and planes.",
     r"openttd", "org.openttd.OpenTTD", 16, "Smooth"),
    ("lincity-ng", "LinCity-NG", "Simulation", "Build and run your own city.",
     r"lincity", "io.github.lincity_ng.lincity-ng", 47, "Smooth"),
    ("endless-sky", "Endless Sky", "Simulation", "Space trading, exploring and combat.",
     r"endless", "io.github.endless_sky.endless_sky", 245, "Good"),
    ("blobby", "Blobby Volley", "Sports", "Funny two-player volleyball.",
     r"blobby", "de.blobbyvolley.BlobbyVolley2", 1, "Smooth"),
    ("frozen-bubble", "Frozen Bubble", "Puzzle", "Pop coloured bubbles before they reach the bottom.",
     r"frozen", "org.frozen_bubble.frozen-bubble", 22, "Smooth"),
    ("quadrapassel", "Quadrapassel", "Puzzle", "Falling-blocks classic.",
     r"quadrapassel", "org.gnome.Quadrapassel", 1, "Smooth"),
    ("gnome-2048", "2048", "Puzzle", "Slide tiles, join numbers, reach 2048.",
     r"2048", "org.gnome.TwentyFortyEight", 1, "Smooth"),
    ("gnome-mines", "Mines", "Puzzle", "Minesweeper.",
     r"mines", "org.gnome.Mines", 1, "Smooth"),
    ("aisleriot", "Solitaire", "Cards", "Klondike, Spider, FreeCell and 80 more card games.",
     r"sol$|aisleriot", "org.gnome.Aisleriot", 2, "Smooth"),
    ("gnome-chess", "Chess", "Board", "Chess against the computer or a friend.",
     r"chess", "org.gnome.Chess", 1, "Smooth"),
]
STORE = [dict(zip(("pkg", "name", "genre", "about", "match", "fh", "mb", "fit"), e)) for e in STORE]
for e in STORE:
    e["id"] = e["pkg"].split()[0]
STORE_BY_ID = {e["id"]: e for e in STORE}

_dpkg = {"mtime": 0, "installed": set()}


def installed_packages():
    """Package names dpkg lists as installed (cached until dpkg's database changes)."""
    try:
        m = os.path.getmtime("/var/lib/dpkg/status")
    except OSError:
        return set()
    if m != _dpkg["mtime"]:
        names, name = set(), None
        with open("/var/lib/dpkg/status", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if line.startswith("Package: "):
                    name = line[9:].strip()
                elif line.startswith("Status: ") and line.rstrip().endswith(" installed") and name:
                    names.add(name)
        _dpkg.update(mtime=m, installed=names)
    return _dpkg["installed"]


def store_entry_for(gid):
    """The TS Store entry an installed game belongs to (by its menu entry name)."""
    for e in STORE:
        if re.search(e["match"], gid, re.I) and e["id"] in installed_packages():
            return e
    for e in STORE:
        if re.search(e["match"], gid, re.I):
            return e
    return None


def fetch(url, limit=8_000_000):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read(limit)


def flathub(fid):
    """Flathub's public app data (screenshots, icon) – cached for a week."""
    if not fid:
        return None
    p = os.path.join(CACHE, "flathub", fid + ".json")
    if os.path.exists(p) and time.time() - os.path.getmtime(p) < 7 * 86400:
        try:
            with open(p) as f:
                return json.load(f) or None
        except Exception:
            pass
    try:
        d = json.loads(fetch("https://flathub.org/api/v2/appstream/" + urllib.parse.quote(fid)))
    except Exception:
        d = {}
    with open(p, "w") as f:
        json.dump(d, f)
    return d or None


def cached_download(name, url_fn):
    """Cached file CACHE/art/<name>.(jpg|png), downloaded once from url_fn() (None if impossible)."""
    for ext in ("jpg", "png"):
        p = os.path.join(CACHE, "art", "%s.%s" % (name, ext))
        if os.path.exists(p) and os.path.getsize(p) > 0:
            return p
    miss = os.path.join(CACHE, "art", name + ".none")
    if os.path.exists(miss) and time.time() - os.path.getmtime(miss) < 3 * 86400:
        return None
    for url in url_fn():
        try:
            data = fetch(url)
            ext = "png" if data[:4] == b"\x89PNG" else "jpg"
            p = os.path.join(CACHE, "art", "%s.%s" % (name, ext))
            with open(p + ".tmp", "wb") as f:
                f.write(data)
            os.replace(p + ".tmp", p)
            return p
        except Exception:
            continue
    open(miss, "w").close()
    return None


def flathub_shots(fid, width=1248):
    d = flathub(fid) or {}
    urls = []
    for shot in d.get("screenshots") or []:
        sizes = [s for s in shot.get("sizes") or [] if s.get("src")]
        fit = [s for s in sizes if int(s.get("width") or 0) <= width] or sizes
        if fit:
            urls.append(max(fit, key=lambda s: int(s.get("width") or 0))["src"])
    return urls


def store_art(sid, hd=False):
    """The store picture: small (752 px, for cards and tiles) or big (1248 px, for the background)."""
    e = STORE_BY_ID.get(sid)
    if not e:
        return None
    w = 1248 if hd else 752
    return cached_download("store-%s-%d" % (sid, w), lambda: flathub_shots(e["fh"], w)[:2])


def store_icon(sid):
    e = STORE_BY_ID.get(sid)
    if not e:
        return None
    return cached_download("store-icon-" + sid, lambda: [u for u in [(flathub(e["fh"]) or {}).get("icon")] if u])


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
            st = store_entry_for(gid)
            games.append({
                "id": gid,
                "name": e.get("Name", gid),
                "about": (st or {}).get("about") or e.get("Comment", ""),
                "icon": e.get("Icon", ""),
                "file": path,
                "mine": d == MY_GAMES,
                "genre": st["genre"] if st else genre(cats),
                "source": "Added by you" if d == MY_GAMES else ("TS Store" if st else "Ubuntu"),
                "store": st["id"] if st else "",
                "fit": st["fit"] if st else "",
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
    """A big picture for an installed game: its own screenshot, else the TS Store's picture."""
    p = cached_download(gid, lambda: metainfo_screenshots(gid)[:2])
    if p:
        return p
    e = store_entry_for(gid)
    return store_art(e["id"], hd=True) if e else None


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
    w = int(res.split("x")[0]) if res else 0
    profile = ("Turbo" if w and w <= 800 else "Game" if w and w <= 960 else "Fast" if w and w <= 1120
               else "Balanced" if w and w <= 1280 else "Quality" if w else "")
    return {
        "profile": profile,
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


# ---- installs (one at a time, with progress) ----------------------------------------------------

JOB = {"id": "", "action": "", "percent": 0, "text": "", "state": "idle"}   # idle | running | done | failed
_job_lock = threading.Lock()


def _apt(args):
    """Runs apt-get with machine-readable progress; returns (exit code, last lines)."""
    env = dict(os.environ, DEBIAN_FRONTEND="noninteractive", LANG="C.UTF-8")
    cmd = ["apt-get", "-y", "--no-install-recommends", "-o", "APT::Status-Fd=1", "-o", "Dpkg::Use-Pty=0"] + args
    tail = []
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                            text=True, errors="ignore", env=env)
    for line in proc.stdout:
        line = line.rstrip()
        m = re.match(r"(dlstatus|pmstatus):[^:]*:([\d.]+):(.*)", line)
        if m:
            pct = float(m.group(2))
            JOB["percent"] = int(pct * 0.6) if m.group(1) == "dlstatus" else int(60 + pct * 0.4)
            JOB["text"] = ("Downloading… " if m.group(1) == "dlstatus" else "") + m.group(3)[:80]
        elif line:
            tail = (tail + [line])[-6:]
    return proc.wait(), tail


def _run_job(sid, action):
    e = STORE_BY_ID[sid]
    pkgs = e["pkg"].split()
    try:
        if action == "install":
            JOB["text"] = "Starting…"
            rc, tail = _apt(["install"] + pkgs)
            if rc != 0 and any("Unable to locate" in t or "has no installation candidate" in t for t in tail):
                JOB.update(text="Updating the app list…", percent=2)
                subprocess.run(["apt-get", "update"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                rc, tail = _apt(["install"] + pkgs)
        else:
            JOB["text"] = "Removing…"
            rc, tail = _apt(["remove"] + pkgs)
        if rc == 0:
            JOB.update(state="done", percent=100, text="Installed – find it in your games" if action == "install" else "Removed")
        else:
            why = " ".join(tail)
            if "Could not get lock" in why or "is held by process" in why:
                why = "Another install is running (App Store or terminal). Try again in a minute."
            elif "No space left" in why:
                why = "Storage is full. Remove something and try again."
            elif "Temporary failure resolving" in why or "Could not resolve" in why:
                why = "No internet connection."
            JOB.update(state="failed", text=why[-220:] or "Failed (code %d)" % rc)
    except FileNotFoundError:
        JOB.update(state="failed", text="apt-get is missing – this works only inside AirCursor PC.")
    except Exception as ex:
        JOB.update(state="failed", text=str(ex)[:200])


def start_job(sid, action):
    if sid not in STORE_BY_ID or action not in ("install", "remove"):
        return "unknown game"
    if os.geteuid() != 0:
        return "Installing needs the PC's main user (root)."
    with _job_lock:
        if JOB["state"] == "running":
            return "Wait until %s is finished." % STORE_BY_ID[JOB["id"]]["name"]
        JOB.update(id=sid, action=action, percent=0, text="", state="running")
    threading.Thread(target=_run_job, args=(sid, action), daemon=True).start()
    return ""


def store_list():
    have = installed_packages()
    out = []
    for e in STORE:
        d = {k: e[k] for k in ("id", "name", "genre", "about", "mb", "fit")}
        d["installed"] = e["id"] in have
        out.append(d)
    return out


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
        if u.path == "/api/store":
            return self.send(200, {"games": store_list(), "job": JOB})
        if u.path == "/api/job":
            return self.send(200, JOB)
        if u.path.startswith("/api/storeart/"):
            p = store_art(u.path[14:], hd=q.get("hd", [""])[0] == "1")
            return self.file(p) if p else self.send(404, {"error": "no art"})
        if u.path.startswith("/api/storeicon/"):
            p = store_icon(u.path[15:])
            return self.file(p) if p else self.send(404, {"error": "no icon"})
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
        if u.path in ("/api/install", "/api/uninstall"):
            err = start_job(str(body.get("id", "")), "install" if u.path == "/api/install" else "remove")
            return self.send(200, {"ok": not err, "error": err, "job": JOB})
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
