#!/usr/bin/env python3
"""Air Store backend (AirCursor PC).

The store's window is a web page shown in Air (the phone's own browser engine, smooth like a phone
app store); this small server inside the PC lists the apps, installs and removes them (through
ubuntu-extras, like the old App Store) and opens them. Only 127.0.0.1 and a random key: other apps
on the phone cannot use it. Web apps (Spotify, YouTube Music, Netflix…) open in Air.
"""
import importlib.util
import json
import os
import re
import secrets
import subprocess
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LIB = os.path.dirname(os.path.abspath(__file__))
UI = os.path.join(LIB, "airstore.html")
TOKEN = secrets.token_hex(16)
ANSI = re.compile(r"\x1b\[[0-9;]*m")

# The catalog and the install helpers of the App Store (one list for both stores).
_spec = importlib.util.spec_from_file_location("appstore", os.path.join(LIB, "app-store.py"))
store = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(store)

MUSIC, VIDEO, SOCIAL, LEARN = "Music", "Movies & TV", "Social", "Learn"
W = store.web
EXTRA = [
    # Music (streaming web players: they run in Air, the phone's engine, with sound and DRM)
    W("spotify", "Spotify", "Music and podcasts – millions of songs", MUSIC, "https://open.spotify.com", "open.spotify.com", True,
      categories="AudioVideo;Audio;"),
    W("ytmusic", "YouTube Music", "Songs, albums and music videos", MUSIC, "https://music.youtube.com", "music.youtube.com", True,
      categories="AudioVideo;Audio;"),
    W("jiosaavn", "JioSaavn", "Hindi, English and regional music", MUSIC, "https://www.jiosaavn.com", "jiosaavn.com",
      categories="AudioVideo;Audio;"),
    W("gaana", "Gaana", "Bollywood and Indian music", MUSIC, "https://gaana.com", "gaana.com", categories="AudioVideo;Audio;"),
    W("soundcloud", "SoundCloud", "New artists, mixes and remixes", MUSIC, "https://soundcloud.com", "soundcloud.com",
      categories="AudioVideo;Audio;"),
    W("applemusic", "Apple Music", "Apple's music service (subscription)", MUSIC, "https://music.apple.com", "music.apple.com",
      categories="AudioVideo;Audio;"),
    # Movies & TV
    W("netflix", "Netflix", "Films and series (subscription)", VIDEO, "https://www.netflix.com", "netflix.com", True,
      categories="AudioVideo;Video;"),
    W("primevideo", "Prime Video", "Amazon's films and series", VIDEO, "https://www.primevideo.com", "primevideo.com",
      categories="AudioVideo;Video;"),
    W("hotstar", "JioHotstar", "Cricket, films and series", VIDEO, "https://www.hotstar.com", "hotstar.com", True,
      categories="AudioVideo;Video;"),
    W("twitch", "Twitch", "Live game streams", VIDEO, "https://www.twitch.tv", "twitch.tv", categories="AudioVideo;Video;"),
    # Social
    W("instagram", "Instagram", "Photos, reels and messages", SOCIAL, "https://www.instagram.com", "instagram.com", True,
      categories="Network;Chat;"),
    W("x", "X", "Posts and news", SOCIAL, "https://x.com", "x.com", categories="Network;"),
    W("facebook", "Facebook", "Friends, groups and pages", SOCIAL, "https://www.facebook.com", "facebook.com",
      categories="Network;"),
    W("telegram", "Telegram", "Telegram Web: chats and channels", SOCIAL, "https://web.telegram.org", "telegram.org",
      categories="Network;Chat;"),
    W("discord", "Discord", "Servers, voice and chat", SOCIAL, "https://discord.com/app", "discord.com",
      categories="Network;Chat;"),
    W("linkedin", "LinkedIn", "Jobs and your work profile", SOCIAL, "https://www.linkedin.com", "linkedin.com",
      categories="Network;"),
    # Create
    W("photopea", "Photopea", "Photoshop-like photo editor in the browser", store.MEDIA, "https://www.photopea.com",
      "photopea.com", True, categories="Graphics;"),
    W("figma", "Figma", "Design apps and websites together", store.MEDIA, "https://www.figma.com", "figma.com",
      categories="Graphics;"),
    W("capcut", "CapCut", "Easy video editing online", store.MEDIA, "https://www.capcut.com/editor", "capcut.com",
      categories="AudioVideo;Video;"),
    W("clipchamp", "Clipchamp", "Microsoft's simple video editor", store.MEDIA, "https://app.clipchamp.com",
      "clipchamp.com", categories="AudioVideo;Video;"),
    # Work
    W("notion", "Notion", "Notes, docs and plans in one place", store.OFFICE, "https://www.notion.so", "notion.so", True,
      categories="Office;"),
    W("sheets", "Google Sheets", "Spreadsheets online", store.OFFICE, "https://docs.google.com/spreadsheets",
      "sheets.google.com", categories="Office;"),
    W("slides", "Google Slides", "Presentations online", store.OFFICE, "https://docs.google.com/presentation",
      "slides.google.com", categories="Office;"),
    W("office", "Microsoft 365", "Word, Excel and PowerPoint online", store.OFFICE, "https://www.office.com",
      "office.com", True, categories="Office;"),
    W("trello", "Trello", "Boards for projects and to-dos", store.OFFICE, "https://trello.com", "trello.com",
      categories="Office;"),
    # AI
    W("deepseek", "DeepSeek", "AI chat and reasoning", store.AI, "https://chat.deepseek.com", "deepseek.com"),
    W("grok", "Grok", "xAI's assistant", store.AI, "https://grok.com", "grok.com"),
    # Learn
    W("khan", "Khan Academy", "Free lessons: maths, science, coding", LEARN, "https://www.khanacademy.org",
      "khanacademy.org", True, categories="Education;"),
    W("duolingo", "Duolingo", "Learn languages a few minutes a day", LEARN, "https://www.duolingo.com", "duolingo.com",
      categories="Education;"),
    W("w3schools", "W3Schools", "Learn HTML, CSS, JavaScript and Python", LEARN, "https://www.w3schools.com",
      "w3schools.com", categories="Education;Development;"),
    W("leetcode", "LeetCode", "Coding practice for interviews", LEARN, "https://leetcode.com", "leetcode.com",
      categories="Education;Development;"),
]
_ids = {a.id for a in store.CATALOG}
CATALOG = list(store.CATALOG) + [a for a in EXTRA if a.id not in _ids]
BY_ID = {a.id: a for a in CATALOG}

# Flathub has the real logos and screenshots of many of these apps (shown by the store page).
FLATHUB = {
    "vscode": "com.visualstudio.code", "libreoffice": "org.libreoffice.LibreOffice", "gimp": "org.gimp.GIMP",
    "vlc": "org.videolan.VLC", "inkscape": "org.inkscape.Inkscape", "blender": "org.blender.Blender",
    "shotcut": "org.shotcut.Shotcut", "kdenlive": "org.kde.kdenlive", "luanti": "org.luanti.luanti",
    "supertuxkart": "net.supertuxkart.SuperTuxKart", "trigger": "io.sourceforge.trigger_rally.TriggerRally",
    "torcs": "net.sourceforge.torcs", "neverball": "org.neverball.Neverball",
    "tuxracer": "net.sourceforge.ExtremeTuxRacer", "openarena": "io.github.ec_.Quake3e.OpenArena",
    "games": "org.supertuxproject.SuperTux", "geany": "org.geany.Geany", "thonny": "org.thonny.Thonny",
    "codeblocks": "org.codeblocks.codeblocks", "godot3": "org.godotengine.Godot3", "abiword": "com.abisource.AbiWord",
    "gnumeric": "org.gnome.Gnumeric", "xournalpp": "com.github.xournalpp.xournalpp",
    "cherrytree": "com.giuspen.cherrytree", "foliate": "com.github.johnfactotum.Foliate",
    "drawing": "com.github.maoschanz.drawing", "krita": "org.kde.krita", "audacity": "org.audacityteam.Audacity",
    "mpv": "io.mpv.Mpv", "audacious": "org.atheme.audacious", "shotwell": "org.gnome.Shotwell",
    "simplescreenrecorder": "com.maartenbaert.SimpleScreenRecorder", "filezilla": "org.filezillaproject.Filezilla",
    "qbittorrent": "org.qbittorrent.qBittorrent", "remmina": "org.remmina.Remmina", "baobab": "org.gnome.baobab",
    "brave": "com.brave.Browser", "bluefish": "nl.openoffice.bluefish",
}

# ---- jobs: one install / remove at a time ------------------------------------------------------

JOB = {"id": "", "action": "", "percent": 0, "text": "", "state": "idle"}
QUEUE = []
_lock = threading.Lock()
_pkgs = {"t": 0, "set": set()}


def installed_set():
    if time.time() - _pkgs["t"] > 3:
        _pkgs["set"] = store.installed_packages()
        _pkgs["t"] = time.time()
    return _pkgs["set"]


def app_json(a, pkgs):
    return {
        "id": a.id, "name": a.name, "desc": a.desc, "cat": a.cat, "size": a.size, "web": a.web, "url": a.url,
        "domain": (a.domains or [""])[0], "popular": a.popular, "removable": a.removable,
        "installed": store.is_installed(a, pkgs), "fh": FLATHUB.get(a.id, ""),
    }


def run_job(action, a):
    JOB.update(id=a.id, action=action, percent=2, text="Starting…", state="running")
    total = gets = unpacks = setups = 0
    frac = 0.02
    try:
        p = subprocess.Popen([store.EXTRAS, action, a.target], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, errors="replace", stdin=subprocess.DEVNULL,
                             env=dict(os.environ, DEBIAN_FRONTEND="noninteractive"))
        for raw in p.stdout:
            line = ANSI.sub("", raw).strip()
            if not line:
                continue
            m = re.search(r"(\d+) newly installed", line)
            text = None
            if m:
                total = max(total, int(m.group(1)))
            elif line.startswith("Get:"):
                gets += 1
                parts = line.split()
                text = "Downloading %s…" % (parts[4] if len(parts) > 4 else "")
            elif line.startswith("Unpacking "):
                unpacks += 1
                text = "Installing %s…" % line.split()[1]
            elif line.startswith("Setting up "):
                setups += 1
                text = "Setting up…"
            elif line.startswith("Removing "):
                text = line
            if total:
                frac = max(frac, min(0.97, 0.05 + 0.55 * gets / total + 0.2 * unpacks / total + 0.17 * setups / total))
            elif text:
                frac = min(0.9, frac + 0.01)
            JOB["percent"] = int(frac * 100)
            if text:
                JOB["text"] = text[:80]
        p.wait()
        if action == "install":
            store.sh(["/usr/local/bin/cloudpc-apps"], 120)
        _pkgs["t"] = 0
        ok = store.is_installed(a, installed_set())
        ok = ok if action == "install" else not ok
        JOB.update(state="done" if ok else "failed", percent=100 if ok else JOB["percent"],
                   text=("Installed" if action == "install" else "Removed") if ok else
                   "Did not work – check the internet connection and try again")
    except Exception as e:
        JOB.update(state="failed", text=str(e)[:160])


def worker():
    while True:
        with _lock:
            item = QUEUE.pop(0) if QUEUE else None
        if item is None:
            time.sleep(0.4)
            continue
        run_job(*item)


def request(action, aid):
    a = BY_ID.get(aid)
    if a is None and aid.startswith("pkg:"):    # an Ubuntu app from "Search all apps"
        name = aid[4:]
        if not re.fullmatch(r"[a-z0-9][a-z0-9+.-]+", name):
            return "unknown app"
        a = store.App(aid, name, "", "Search", 0, [], ["pkg:" + name], ["pkg:" + name], "package-x-generic")
        a.target = name
        BY_ID[aid] = a
    if a is None:
        return "unknown app"
    if a.web:
        try:
            if action == "install":
                store.add_web_app(a)
            else:
                store.remove_web_app(a)
            JOB.update(id=a.id, action=action, percent=100, state="done",
                       text="Added – it is on the desktop and in the apps menu" if action == "install" else "Removed")
        except Exception as e:
            return str(e)[:160]
        return ""
    with _lock:
        if any(q[1].id == a.id for q in QUEUE) or (JOB["state"] == "running" and JOB["id"] == a.id):
            return ""
        QUEUE.append((action, a))
    return ""


def search_ubuntu(q):
    q = re.sub(r"[^\w .+-]", " ", q)[:40].strip()
    if not q:
        return []
    out = store.sh(["apt-cache", "search", "--names-only"] + q.split()[:4], 30)
    pkgs = installed_set()
    rows = []
    for line in out.splitlines()[:60]:
        name, _, desc = line.partition(" - ")
        name = name.strip()
        if not name or name.endswith(("-dev", "-doc", "-dbg", "-data", "-common")) or name.startswith("lib"):
            continue
        rows.append({"id": "pkg:" + name, "name": name, "desc": desc.strip(), "cat": "Ubuntu app", "size": 0,
                     "web": False, "url": None, "domain": "", "popular": False, "removable": True,
                     "installed": name in pkgs, "fh": ""})
    return rows[:40]


def storage():
    try:
        st = os.statvfs("/")
        return round(st.f_bavail * st.f_frsize / 1e9, 1)
    except Exception:
        return 0


# ---- HTTP ---------------------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def ok(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        return q.get("k", [""])[0] == TOKEN or self.headers.get("X-Key") == TOKEN

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path in ("/", "/index.html"):
            with open(UI, "rb") as f:
                return self.send(200, f.read(), "text/html; charset=utf-8")
        if not self.ok():
            return self.send(403, {"error": "key"})
        if u.path == "/api/apps":
            pkgs = installed_set()
            return self.send(200, {"apps": [app_json(a, pkgs) for a in CATALOG], "freeGB": storage(), "job": JOB,
                                   "queue": [q[1].id for q in QUEUE]})
        if u.path == "/api/job":
            return self.send(200, dict(JOB, queue=[q[1].id for q in QUEUE]))
        if u.path == "/api/search":
            q = urllib.parse.parse_qs(u.query).get("q", [""])[0]
            return self.send(200, {"apps": search_ubuntu(q)})
        return self.send(404, {"error": "unknown"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        if not self.ok():
            return self.send(403, {"error": "key"})
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(min(n, 8192)) or b"{}")
        except Exception:
            body = {}
        aid = str(body.get("id", ""))
        if u.path in ("/api/install", "/api/remove"):
            err = request("install" if u.path == "/api/install" else "remove", aid)
            return self.send(200, {"ok": not err, "error": err})
        if u.path == "/api/open":
            a = BY_ID.get(aid)
            ok = bool(a) and store.open_app(a)
            return self.send(200, {"ok": ok})
        return self.send(404, {"error": "unknown"})


def main():
    threading.Thread(target=worker, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    print("http://127.0.0.1:%d/#k=%s" % (srv.server_address[1], TOKEN), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
