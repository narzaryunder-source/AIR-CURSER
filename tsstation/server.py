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
import shlex
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
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
TOKEN = os.environ.get("TS_TOKEN") or secrets.token_hex(16)
RAW = "https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/tsstation/"
DEB_URL = "https://github.com/narzaryunder-source/AIR-CURSER/raw/main/tsstation/dist/ts-station.deb"


def _read_version():
    try:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "VERSION")) as f:
            return f.read().strip()
    except OSError:
        return "0.2.0"


VERSION = _read_version()
NOT_GAMES = re.compile(r"(stockfish|mangohud|cloudpc-|aircursor-)", re.I)

os.makedirs(DATA, exist_ok=True)
os.makedirs(os.path.join(CACHE, "art"), exist_ok=True)
os.makedirs(os.path.join(CACHE, "flathub"), exist_ok=True)
_lock = threading.Lock()
UA = {"User-Agent": "TS-Station/1.0 (AirCursor PC)"}

# ---- TS Store: free games that run on this PC ----------------------------------------------------
# pkg = Ubuntu packages to install, match = which installed menu entries belong to it,
# fh = Flathub id (only for pictures), mb = download size, fit = how it runs on a phone CPU/GPU.
STORE = [
    # (pkg, name, genre, about, match, flathub id, MB, fit, 2D/3D) – only well-known, finished games.
    # Racing
    ("supertuxkart", "SuperTuxKart", "Racing", "Kart racing with items, 20+ tracks, Grand Prix and battle mode.",
     r"supertuxkart", "net.supertuxkart.SuperTuxKart", 640, "Heavy", "3D"),
    ("extremetuxracer", "Extreme Tux Racer", "Racing", "Slide down snowy mountains, collect herrings, beat the clock.",
     r"tuxracer|^etr", "net.sourceforge.ExtremeTuxRacer", 41, "Good", "3D"),
    ("trigger-rally", "Trigger Rally", "Racing", "Rally driving on dirt, snow and tarmac against the clock.",
     r"trigger", "io.sourceforge.trigger_rally.TriggerRally", 120, "Heavy", "3D"),
    ("torcs", "TORCS", "Racing", "Realistic car racing simulator with many cars and tracks.",
     r"torcs", "net.sourceforge.torcs", 335, "Heavy", "3D"),
    ("xmoto", "X-Moto", "Racing", "Motocross with real physics – hundreds of tricky levels.",
     r"xmoto", "org.tuxfamily.XMoto", 33, "Smooth", "2D"),
    ("armagetronad", "Armagetron Advanced", "Racing", "Light-cycle duels: trap your rivals with your trail.",
     r"armagetron", "org.armagetronad.ArmagetronAdvanced", 2, "Smooth", "3D"),
    # Action, platform, sandbox
    ("minetest", "Luanti (Minetest)", "Sandbox", "Build, mine and explore an endless block world – alone or online.",
     r"minetest|luanti", "org.luanti.luanti", 30, "Good", "3D"),
    ("supertux", "SuperTux", "Platformer", "Classic side-scrolling jump-and-run adventure with Tux.",
     r"supertux2?$|supertux2", "org.supertuxproject.SuperTux", 165, "Smooth", "2D"),
    ("frogatto", "Frogatto & Friends", "Platformer", "A hand-drawn action-adventure platformer with a big story.",
     r"frogatto", "com.frogatto.Frogatto", 90, "Good", "2D"),
    ("openclonk", "OpenClonk", "Action", "Dig, build and fight in a living 2D world – missions and melees.",
     r"openclonk", "org.openclonk.OpenClonk", 120, "Good", "2D"),
    ("cdogs-sdl", "C-Dogs SDL", "Action", "Top-down run-and-gun with campaigns and co-op for four.",
     r"cdogs", "io.github.cxong.cdogs-sdl", 40, "Smooth", "2D"),
    ("teeworlds", "Teeworlds", "Shooter", "Fast online shooter with grappling hooks.",
     r"teeworlds", "com.teeworlds.Teeworlds", 8, "Smooth", "2D"),
    ("openarena", "OpenArena", "Shooter", "Fast arena first-person shooter.",
     r"openarena", "io.github.ec_.Quake3e.OpenArena", 420, "Heavy", "3D"),
    ("bzflag-client", "BZFlag", "Shooter", "Online tank battles – capture the flag.",
     r"bzflag", "org.bzflag.BZFlag", 30, "Good", "3D"),
    # Arcade
    ("neverball", "Neverball", "Arcade", "Tilt the floor to roll the ball to the goal – 3D puzzle racing.",
     r"neverball", "org.neverball.Neverball", 39, "Good", "3D"),
    ("neverputt", "Neverputt", "Sports", "Mini golf from the makers of Neverball.",
     r"neverputt", "", 39, "Good", "3D"),
    ("chromium-bsu", "Chromium B.S.U.", "Arcade", "Fast scrolling space shooter.",
     r"chromium-bsu", "net.sourceforge.chromium-bsu", 1, "Good", "2D"),
    ("kobodeluxe", "Kobo Deluxe", "Arcade", "Destroy space fortresses in this quick arcade shooter.",
     r"kobo", "net.olofson.KoboDeluxe", 1, "Smooth", "2D"),
    ("lbreakouthd", "LBreakoutHD", "Arcade", "Break the bricks – 100+ levels and bonuses.",
     r"lbreakout", "net.sourceforge.lgames.LBreakoutHD", 5, "Smooth", "2D"),
    ("blockout2", "BlockOut II", "Puzzle", "Falling blocks in a 3D pit.",
     r"blockout", "", 10, "Good", "3D"),
    # Strategy
    ("hedgewars", "Hedgewars", "Strategy", "Turn-based artillery battles with hedgehogs.",
     r"hedgewars", "org.hedgewars.Hedgewars", 150, "Good", "2D"),
    ("wesnoth", "Battle for Wesnoth", "Strategy", "Fantasy turn-based strategy with long campaigns.",
     r"wesnoth", "org.wesnoth.Wesnoth", 280, "Smooth", "2D"),
    ("widelands", "Widelands", "Strategy", "Build an economy and an empire, at your own pace.",
     r"widelands", "org.widelands.Widelands", 350, "Good", "2D"),
    ("freeciv-client-gtk3", "Freeciv", "Strategy", "Lead a civilization from the stone age to the space age.",
     r"freeciv", "", 60, "Smooth", "2D"),
    ("warzone2100", "Warzone 2100", "Strategy", "Real-time strategy: build a base, design units, win the war.",
     r"warzone", "net.wz2100.wz2100", 150, "Heavy", "3D"),
    ("megaglest", "MegaGlest", "Strategy", "3D real-time strategy with seven factions.",
     r"megaglest", "org.megaglest.MegaGlest", 400, "Heavy", "3D"),
    # Simulation
    ("openttd openttd-opengfx", "OpenTTD", "Simulation", "Build a transport empire with trains, ships and planes.",
     r"openttd", "org.openttd.OpenTTD", 16, "Smooth", "2D"),
    ("simutrans", "Simutrans", "Simulation", "Transport tycoon: connect cities and grow them.",
     r"simutrans", "com.simutrans.Simutrans", 60, "Smooth", "2D"),
    ("lincity-ng", "LinCity-NG", "Simulation", "Build and run your own city.",
     r"lincity", "io.github.lincity_ng.lincity-ng", 47, "Smooth", "2D"),
    ("endless-sky", "Endless Sky", "Simulation", "Space trading, exploring and combat.",
     r"endless", "io.github.endless_sky.endless_sky", 245, "Good", "2D"),
    # Role-playing and adventure
    ("flare-game", "Flare", "RPG", "Action role-playing game: dungeons, loot, a full campaign.",
     r"flare", "org.flarerpg.Flare", 130, "Good", "2D"),
    ("freedroidrpg", "FreedroidRPG", "RPG", "Sci-fi role-playing with a long story and humour.",
     r"freedroid", "", 280, "Good", "2D"),
    ("crawl-tiles", "Dungeon Crawl Stone Soup", "RPG", "The famous deep dungeon adventure – every game is new.",
     r"crawl", "org.develz.Crawl", 12, "Smooth", "2D"),
    ("beneath-a-steel-sky", "Beneath a Steel Sky", "Adventure", "Classic point-and-click sci-fi adventure (freeware).",
     r"steel.sky|bass", "org.scummvm.ScummVM", 75, "Smooth", "2D"),
    ("flight-of-the-amazon-queen", "Flight of the Amazon Queen", "Adventure",
     "Classic point-and-click adventure in the jungle (freeware).", r"amazon|fotaq", "org.scummvm.ScummVM", 55, "Smooth", "2D"),
    # Sports and puzzles
    ("blobby", "Blobby Volley", "Sports", "Funny two-player volleyball.",
     r"blobby", "de.blobbyvolley.BlobbyVolley2", 1, "Smooth", "2D"),
    ("foobillardplus", "FooBillard++", "Sports", "Pool and snooker in 3D.",
     r"foobillard", "", 30, "Good", "3D"),
    ("enigma", "Enigma", "Puzzle", "Roll a marble through 1000+ clever puzzle levels.",
     r"enigma", "org.nongnu.enigma", 30, "Smooth", "2D"),
    ("pingus", "Pingus", "Puzzle", "Guide a crowd of penguins safely to the exit.",
     r"pingus", "", 12, "Smooth", "2D"),
    ("frozen-bubble", "Frozen Bubble", "Puzzle", "Pop coloured bubbles before they reach the bottom.",
     r"frozen", "org.frozen_bubble.frozen-bubble", 22, "Smooth", "2D"),
    ("quadrapassel", "Quadrapassel", "Puzzle", "Falling-blocks classic.",
     r"quadrapassel", "org.gnome.Quadrapassel", 1, "Smooth", "2D"),
    ("gnome-2048", "2048", "Puzzle", "Slide tiles, join numbers, reach 2048.",
     r"2048", "org.gnome.TwentyFortyEight", 1, "Smooth", "2D"),
    ("gnome-sudoku", "Sudoku", "Puzzle", "Number puzzles from easy to very hard.",
     r"sudoku", "org.gnome.Sudoku", 2, "Smooth", "2D"),
    ("gnome-mines", "Mines", "Puzzle", "Minesweeper.",
     r"mines", "org.gnome.Mines", 1, "Smooth", "2D"),
    # Cards and board games
    ("aisleriot", "Solitaire", "Cards", "Klondike, Spider, FreeCell and 80 more card games.",
     r"sol$|aisleriot", "org.gnome.Aisleriot", 2, "Smooth", "2D"),
    ("pysolfc", "PySol", "Cards", "1000+ solitaire card games in one.",
     r"pysol", "io.sourceforge.pysolfc.PySolFC", 15, "Smooth", "2D"),
    ("gnome-chess stockfish", "Chess", "Board", "Chess against a strong computer (Stockfish) or a friend.",
     r"chess", "org.gnome.Chess", 3, "Smooth", "2D"),
    ("gnome-mahjongg", "Mahjongg", "Board", "Match the tiles and clear the board.",
     r"mahjongg", "org.gnome.Mahjongg", 5, "Smooth", "2D"),
    ("iagno", "Reversi", "Board", "The classic disc-flipping strategy game.",
     r"iagno|reversi", "org.gnome.Reversi", 13, "Smooth", "2D"),
    ("four-in-a-row", "Four in a Row", "Board", "Drop discs, connect four.",
     r"four", "org.gnome.Four-in-a-row", 2, "Smooth", "2D"),
    ("gnubg", "GNU Backgammon", "Board", "Backgammon against a world-class computer player.",
     r"gnubg|backgammon", "", 10, "Smooth", "2D"),
]
STORE = [dict(zip(("pkg", "name", "genre", "about", "match", "fh", "mb", "fit", "dim"), e)) for e in STORE]
for e in STORE:
    e["id"] = e["pkg"].split()[0]

# ---- Play at once: web games (in Air, the phone's own browser engine) and cloud gaming ------------
# (id, name, genre, about, url, 2D/3D, kind) – well-known games only, nothing to install.
INSTANT = [
    ("web-chess", "Chess.com", "Board", "Play chess online against people or bots.", "https://www.chess.com/play", "2D", "Web"),
    ("web-lichess", "Lichess", "Board", "Free chess, no ads – puzzles, lessons and games.", "https://lichess.org", "2D", "Web"),
    ("web-krunker", "Krunker", "Shooter", "Fast first-person shooter in the browser.", "https://krunker.io", "3D", "Web"),
    ("web-shellshockers", "Shell Shockers", "Shooter", "Online egg shooter, quick matches.", "https://shellshock.io", "3D", "Web"),
    ("web-narrowone", "Narrow One", "Shooter", "Capture the flag with bows – medieval castles.", "https://narrow.one", "3D", "Web"),
    ("web-smashkarts", "Smash Karts", "Racing", "Online kart battles with weapons.", "https://smashkarts.io", "3D", "Web"),
    ("web-hexgl", "HexGL", "Racing", "Futuristic anti-gravity racing.", "https://hexgl.bkcore.com/play/", "3D", "Web"),
    ("web-geofs", "GeoFS", "Simulation", "Flight simulator over the whole real world.", "https://www.geo-fs.com/geofs.php", "3D", "Web"),
    ("web-minecraftclassic", "Minecraft Classic", "Sandbox", "The original Minecraft from 2009, free from Mojang.",
     "https://classic.minecraft.net", "3D", "Web"),
    ("web-bloxd", "Bloxd.io", "Sandbox", "Block building, parkour and survival online.", "https://bloxd.io", "3D", "Web"),
    ("web-zombsroyale", "ZombsRoyale", "Shooter", "100-player battle royale from above.", "https://zombsroyale.io", "2D", "Web"),
    ("web-slither", "Slither.io", "Arcade", "Grow the longest snake online.", "https://slither.io", "2D", "Web"),
    ("web-diep", "Diep.io", "Arcade", "Upgrade your tank and take over the arena.", "https://diep.io", "2D", "Web"),
    ("web-agar", "Agar.io", "Arcade", "Eat or be eaten – online.", "https://agar.io", "2D", "Web"),
    ("web-skribbl", "skribbl.io", "Party", "Draw and guess with friends.", "https://skribbl.io", "2D", "Web"),
    ("web-garticphone", "Gartic Phone", "Party", "The telephone game with drawings.", "https://garticphone.com", "2D", "Web"),
    ("web-wordle", "Wordle", "Puzzle", "Guess the word in six tries.", "https://www.nytimes.com/games/wordle/index.html", "2D", "Web"),
    ("web-tetris", "Tetris", "Puzzle", "The official Tetris online.", "https://tetris.com/play-tetris", "2D", "Web"),
    ("web-littlealchemy2", "Little Alchemy 2", "Puzzle", "Mix elements and discover the world.", "https://littlealchemy2.com", "2D", "Web"),
    ("web-cookieclicker", "Cookie Clicker", "Idle", "The famous cookie empire game.", "https://orteil.dashnet.org/cookieclicker/", "2D", "Web"),
    ("web-adarkroom", "A Dark Room", "Adventure", "A minimalist text adventure that grows into much more.",
     "https://adarkroom.doublespeakgames.com", "2D", "Web"),
    ("web-paperclips", "Universal Paperclips", "Idle", "Make paperclips. Then make more.",
     "https://www.decisionproblem.com/paperclips/index2.html", "2D", "Web"),
    # Cloud gaming: big PC and console games (GTA V, Fortnite…) run on a server and stream here.
    ("cloud-geforcenow", "GeForce NOW", "Cloud", "Your Steam, Epic and Ubisoft games streamed from NVIDIA – free tier "
     "with a queue. Needs an NVIDIA account and 15+ Mbps internet.", "https://play.geforcenow.com", "3D", "Cloud"),
    ("cloud-xbox", "Xbox Cloud Gaming", "Cloud", "Hundreds of Xbox games streamed – needs Game Pass Ultimate and fast internet.",
     "https://www.xbox.com/play", "3D", "Cloud"),
    ("cloud-boosteroid", "Boosteroid", "Cloud", "Cloud gaming for games you own on Steam, Epic and more – paid, fast internet.",
     "https://cloud.boosteroid.com", "3D", "Cloud"),
]
INSTANT = [dict(zip(("id", "name", "genre", "about", "url", "dim", "kind"), e)) for e in INSTANT]
INSTANT_BY_ID = {e["id"]: e for e in INSTANT}

# ---- Phone games: the AirCursor app lists the games installed on the phone here ------------------
PHONE_DIR = os.path.join(HOME, ".cache", "ts-station", "phone")
PHONE_LIST = os.path.join(PHONE_DIR, "games.json")


def phone_games():
    try:
        with open(PHONE_LIST) as f:
            items = json.load(f)
    except Exception:
        return []
    out = []
    for it in items if isinstance(items, list) else []:
        pkg = str(it.get("pkg", ""))
        if not re.fullmatch(r"[A-Za-z0-9_.]{3,120}", pkg):
            continue
        out.append({"id": "phone-" + pkg, "pkg": pkg, "name": str(it.get("name", pkg))[:60],
                    "icon": os.path.join(PHONE_DIR, pkg + ".png")})
    return out


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
    with urllib.request.urlopen(req, timeout=12) as r:
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
    except urllib.error.HTTPError:
        d = {}          # Flathub does not know it: remember that
    except Exception:
        return None     # no internet right now: ask again next time
    with open(p, "w") as f:
        json.dump(d, f)
    return d or None


_dl_locks = {}
_dl_guard = threading.Lock()


def cached_download(name, url_fn):
    """Cached file CACHE/art/<name>.(jpg|png), downloaded once from url_fn() (None if impossible)."""
    with _dl_guard:
        lock = _dl_locks.setdefault(name, threading.Lock())
    with lock:   # the background prefetch and the window may ask at the same time: download once
        return _cached_download(name, url_fn)


def _cached_download(name, url_fn):
    for ext in ("jpg", "png"):
        p = os.path.join(CACHE, "art", "%s.%s" % (name, ext))
        if os.path.exists(p) and os.path.getsize(p) > 0:
            return p
    miss = os.path.join(CACHE, "art", name + ".none")
    if os.path.exists(miss) and time.time() - os.path.getmtime(miss) < 3 * 86400:
        return None
    urls = list(url_fn())
    if not urls:
        return None     # nothing known yet (or no internet to ask): try again next time
    offline = False
    for url in urls:
        try:
            data = fetch(url)
            if not (data[:4] == b"\x89PNG" or data[:3] == b"\xff\xd8\xff" or data[8:12] == b"WEBP"):
                continue    # an error page, not a picture
            ext = "png" if data[:4] == b"\x89PNG" else "jpg"
            p = os.path.join(CACHE, "art", "%s.%s" % (name, ext))
            with open(p + ".tmp", "wb") as f:
                f.write(data)
            os.replace(p + ".tmp", p)
            return p
        except urllib.error.HTTPError:
            continue
        except Exception:
            offline = True  # no internet / too slow: try again later instead of giving up for days
    if not offline:
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
                "exec": e.get("Exec", ""),
                "path": e.get("Path", ""),
                "mine": d == MY_GAMES,
                "genre": st["genre"] if st else genre(cats),
                "source": "Added by you" if d == MY_GAMES else ("TS Store" if st else "Ubuntu"),
                "store": st["id"] if st else "",
                "fh": st["fh"] if st else "",
                "fit": st["fit"] if st else "",
                "dim": st["dim"] if st else "",
                "kind": "PC",
            })
    # Play at once (web and cloud) – always there, nothing to install.
    for e in INSTANT:
        games.append({
            "id": e["id"], "name": e["name"], "about": e["about"], "icon": "", "file": "", "exec": "", "path": "",
            "mine": False, "genre": e["genre"], "store": "", "fh": "", "dim": e["dim"], "kind": e["kind"],
            "fit": "Cloud" if e["kind"] == "Cloud" else "Smooth",
            "source": "Cloud gaming · opens in your phone browser" if e["kind"] == "Cloud" else "Web · plays at once",
        })
    # The phone's own games (opened on the phone: its GPU, its controls).
    for e in phone_games():
        games.append({
            "id": e["id"], "name": e["name"], "about": "Installed on your phone – opens there, smooth on the phone's own chip.",
            "icon": e["icon"], "file": "", "exec": "", "path": "", "mine": False, "genre": "Phone", "store": "",
            "fh": "", "dim": "", "kind": "Phone", "fit": "Smooth", "source": "On your phone", "pkg": e["pkg"],
        })
    return games


def favicon(gid):
    """A web or cloud game's logo (Google's favicon service, 128 px), downloaded once."""
    e = INSTANT_BY_ID.get(gid)
    if not e:
        return None
    host = urllib.parse.urlsplit(e["url"]).hostname or ""
    return cached_download("icon-" + gid, lambda: [
        "https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://%s&size=128"
        % urllib.parse.quote(host)])


def genre(cats):
    for key, label in (("ActionGame", "Action"), ("ArcadeGame", "Arcade"), ("BoardGame", "Board"),
                       ("CardGame", "Cards"), ("LogicGame", "Puzzle"), ("Simulation", "Simulation"),
                       ("SportsGame", "Racing & sports"), ("StrategyGame", "Strategy"),
                       ("AdventureGame", "Adventure"), ("BlocksGame", "Blocks"), ("KidsGame", "Kids")):
        if key in cats:
            return label
    return "Game"


# ---- art ---------------------------------------------------------------------------------------

_meta = {"index": None}


def metainfo_screenshots(gid):
    """Screenshot links from the games' AppStream files (read once, then remembered)."""
    if _meta["index"] is None:
        index = {}
        for path in glob.glob("/usr/share/metainfo/*.xml") + glob.glob("/usr/share/appdata/*.xml"):
            try:
                root = ET.parse(path).getroot()
            except Exception:
                continue
            urls = []
            for shot in root.iter("screenshot"):
                imgs = shot.findall("image")
                src = [i for i in imgs if i.get("type", "source") == "source"] or imgs
                if src and src[0].text:
                    urls.append(src[0].text.strip())
            for t in list(root.iter("launchable")) + root.findall("id"):
                key = (t.text or "").strip().removesuffix(".desktop")
                if key and urls:
                    index.setdefault(key, urls)
        _meta["index"] = index
    return _meta["index"].get(gid, [])


def flathub_find(name):
    """Flathub app id for a game name (their search; remembered for a week)."""
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    p = os.path.join(CACHE, "flathub", "search-%s.json" % slug)
    if os.path.exists(p) and time.time() - os.path.getmtime(p) < 7 * 86400:
        try:
            with open(p) as f:
                return json.load(f).get("id", "")
        except Exception:
            pass
    fid = ""
    try:
        req = urllib.request.Request("https://flathub.org/api/v2/search", data=json.dumps({"query": name}).encode(),
                                     headers=dict(UA, **{"Content-Type": "application/json"}))
        with urllib.request.urlopen(req, timeout=12) as r:
            hits = json.loads(r.read(2_000_000)).get("hits") or []
        want = re.sub(r"[^a-z0-9]", "", name.lower())
        for h in hits[:6]:
            have = re.sub(r"[^a-z0-9]", "", (h.get("name") or "").lower())
            if have and (have == want or (len(want) > 4 and (want in have or have in want))):
                fid = h.get("app_id", "")
                break
    except Exception:
        return None   # no internet: try again next time
    with open(p, "w") as f:
        json.dump({"id": fid}, f)
    return fid


def hero_art(gid, name=""):
    """A big picture for an installed game: TS Store / Flathub art first (big, reliable),
    then the game's own AppStream screenshot, then a Flathub search by its name."""
    e = store_entry_for(gid)
    if e:
        p = store_art(e["id"], hd=True)
        if p:
            return p
    p = cached_download(gid, lambda: metainfo_screenshots(gid)[:2])
    if p:
        return p
    if not name:
        g = next((g for g in find_games() if g["id"] == gid), None)
        name = g["name"] if g else ""
    if name:
        art = os.path.join(CACHE, "art", "fh-" + gid)
        if os.path.exists(art + ".jpg") or os.path.exists(art + ".png"):
            return cached_download("fh-" + gid, lambda: [])
        fid = flathub_find(name)
        if fid is None:
            return None
        return cached_download("fh-" + gid, lambda: flathub_shots(fid)[:2])
    return None


def prefetch_art():
    """Download every game's picture in the background right after start, one at a time."""
    time.sleep(1)
    for g in find_games():
        try:
            if g["kind"] == "PC":
                hero_art(g["id"], g["name"])
            elif g["id"] in INSTANT_BY_ID:
                favicon(g["id"])
        except Exception:
            pass


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


FIELD_CODES = re.compile(r"%[fFuUdDnNickvm]")
GAME_LOG = "/tmp/ts-station-games.log"


def exec_argv(g):
    """The game's command from its menu entry (desktop entry rules), through the AirCursor launcher."""
    cmd = FIELD_CODES.sub("", g["exec"].replace("%%", "\0")).replace("\0", "%")
    try:
        argv = shlex.split(cmd)
    except ValueError:
        argv = cmd.split()
    if argv and argv[0] != "cloudpc-launch" and shutil.which("cloudpc-launch"):
        # Game mode, FPS overlay and the "safe" second try – like starting it from the Start menu.
        argv = ["cloudpc-launch", g["id"] + ".desktop"] + argv
    return argv


def xdo(*args):
    try:
        return subprocess.run(["xdotool"] + list(args), capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return ""


IN_AIR = os.environ.get("TS_IN_AIR") == "1"


def tell_air(what):
    """TS Station shown in Air (the phone's browser over the PC): ask it to step aside / come back."""
    try:
        p = subprocess.Popen(["xclip", "-quiet", "-selection", "clipboard"], stdin=subprocess.PIPE,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        p.stdin.write(("aircursor-web:air:" + what).encode())
        p.stdin.close()
        threading.Timer(15, lambda: p.poll() is None and p.kill()).start()
    except Exception:
        pass


def _watch_game(proc, name):
    """While the game runs, TS Station steps aside; when it closes, TS Station comes back."""
    time.sleep(2.5)
    if proc.poll() is None:
        if IN_AIR:
            tell_air("minimize")
        for wid in xdo("search", "--name", "^TS Station$").split():
            xdo("windowminimize", wid)
    proc.wait()
    if IN_AIR:
        tell_air("show")
    for wid in xdo("search", "--name", "^TS Station$").split()[:1]:
        xdo("windowactivate", wid)


def played(gid):
    s = defaults(load_settings())
    p = s["played"].setdefault(gid, {"count": 0})
    p["count"] = p.get("count", 0) + 1
    p["last"] = int(time.time())
    save_settings(s)


def to_phone(marker):
    """A message for the AirCursor app on the phone (through the PC's clipboard, like the other tools)."""
    try:
        p = subprocess.Popen(["xclip", "-quiet", "-selection", "clipboard"], stdin=subprocess.PIPE,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        p.stdin.write(marker.encode())
        p.stdin.close()
        threading.Timer(15, lambda: p.poll() is None and p.kill()).start()
        return True
    except Exception:
        return False


def launch(gid):
    """Starts a game. Returns "" when it is starting, else a short reason."""
    e = INSTANT_BY_ID.get(gid)
    if e and e["kind"] == "Cloud":
        # Cloud gaming sites support Chrome (not every browser engine): open them in the phone's own
        # browser – full screen, hardware video, already signed in.
        if not to_phone("aircursor-open:" + e["url"]):
            return "Could not reach the phone."
        played(gid)
        return ""
    if e:
        # Its own full-screen window in Air (the phone's browser engine: smooth, GPU).
        if shutil.which("cloudpc-app"):
            spawn(["cloudpc-app", "ts-" + gid, "max", e["name"], e["url"]])
        elif shutil.which("cloudpc-air"):
            spawn(["cloudpc-air", e["url"]])
        else:
            return "Air is missing – update the AirCursor app."
        played(gid)
        return ""
    if gid.startswith("phone-"):
        g = next((g for g in phone_games() if g["id"] == gid), None)
        if not g:
            return "This game is not on the phone any more."
        if not to_phone("aircursor-launch:" + g["pkg"]):
            return "Could not reach the phone."
        played(gid)
        return ""
    g = next((g for g in find_games() if g["id"] == gid), None)
    if not g:
        return "This game is not installed any more."
    argv = exec_argv(g)
    if not argv:
        return "This game has no start command."
    if not shutil.which(argv[0]) and not os.path.exists(argv[0]):
        return "Program not found: %s" % argv[0]
    played(gid)
    log = open(GAME_LOG, "ab")
    log.write(("\n==> %s  %s\n" % (time.strftime("%H:%M:%S"), " ".join(argv))).encode())
    log.flush()
    cwd = g["path"] if g["path"] and os.path.isdir(g["path"]) else HOME
    try:
        proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=log, stderr=log, cwd=cwd, start_new_session=True)
    except OSError as ex:
        return "Could not start: %s" % ex
    finally:
        log.close()
    threading.Thread(target=_watch_game, args=(proc, g["name"]), daemon=True).start()
    return ""


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


def _vt(v):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3]) or (0,)


_latest = {"t": 0, "v": ""}


def version_info():
    """Installed version and the newest one on GitHub (asked at most every 10 minutes)."""
    # Part of the AirCursor app now: the app brings the new versions, no separate update.
    if os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), "MANAGED")):
        return {"installed": VERSION, "latest": VERSION, "update": False}
    if time.time() - _latest["t"] > 600:
        try:
            _latest["v"] = fetch(RAW + "VERSION", 100).decode().strip()
        except Exception:
            pass
        _latest["t"] = time.time()
    latest = _latest["v"]
    return {"installed": VERSION, "latest": latest, "update": bool(latest) and _vt(latest) > _vt(VERSION)}


def _run_update():
    deb = "/tmp/ts-station-update.deb"
    try:
        JOB["text"] = "Downloading the update…"
        data = fetch(DEB_URL, 20_000_000)
        if data[:8] != b"!<arch>\n":
            raise ValueError("the download is not a package")
        with open(deb, "wb") as f:
            f.write(data)
        JOB.update(percent=50, text="Installing…")
        rc, tail = _apt(["install", deb])
        if rc == 0:
            JOB.update(state="done", percent=100, text="Updated – restarting TS Station")
        else:
            JOB.update(state="failed", text=" ".join(tail)[-220:] or "Failed (code %d)" % rc)
    except Exception as ex:
        JOB.update(state="failed", text="Update failed: %s" % str(ex)[:160])


def start_update():
    if os.geteuid() != 0:
        return "Updating needs the PC's main user (root)."
    with _job_lock:
        if JOB["state"] == "running":
            return "Wait until the current install is finished."
        JOB.update(id="ts-station", action="update", percent=0, text="", state="running")
    threading.Thread(target=_run_update, daemon=True).start()
    return ""


def restart():
    """Start the (updated) server again on the same address, so the open window just reloads."""
    time.sleep(0.4)
    env = dict(os.environ, TS_TOKEN=TOKEN, TS_PORT=str(PORT[0]))
    os.execve(sys.executable, ["python3", os.path.abspath(__file__)], env)   # same name: the launcher finds it


PORT = [0]


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
        d = {k: e[k] for k in ("id", "name", "genre", "about", "mb", "fit", "fh", "dim")}
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
                for k in ("file", "exec", "path", "pkg"):
                    g.pop(k, None)
            return self.send(200, {"games": games})
        if u.path == "/api/settings":
            return self.send(200, defaults(load_settings()))
        if u.path == "/api/system":
            return self.send(200, system_info())
        if u.path.startswith("/api/icon/"):
            gid = u.path[10:]
            if gid in INSTANT_BY_ID:
                p = favicon(gid)
                return self.file(p) if p else self.send(404, {"error": "no icon"})
            g = next((g for g in find_games() if g["id"] == gid), None)
            p = icon_path(g["icon"]) if g else None
            return self.file(p) if p else self.send(404, {"error": "no icon"})
        if u.path.startswith("/api/art/"):
            if u.path[9:] in INSTANT_BY_ID or u.path[9:].startswith("phone-"):
                return self.send(404, {"error": "no art"})   # the page paints these from their logo
            p = hero_art(u.path[9:])
            return self.file(p) if p else self.send(404, {"error": "no art"})
        if u.path == "/api/store":
            return self.send(200, {"games": store_list(), "job": JOB})
        if u.path == "/api/version":
            return self.send(200, version_info())
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
            err = launch(str(body.get("id", "")))
            return self.send(200, {"ok": not err, "error": err})
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
        if u.path == "/api/update":
            err = start_update()
            return self.send(200, {"ok": not err, "error": err, "job": JOB})
        if u.path == "/api/restart":
            self.send(200, {"ok": True})
            threading.Thread(target=restart, daemon=True).start()
            return None
        if u.path == "/api/quit":
            self.send(200, {"ok": True})
            threading.Thread(target=lambda: (time.sleep(0.3), os._exit(0)), daemon=True).start()
            return None
        return self.send(404, {"error": "unknown"})


def main():
    port = int(os.environ.get("TS_PORT") or 0)
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    except OSError:
        srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    PORT[0] = srv.server_address[1]
    threading.Thread(target=prefetch_art, daemon=True).start()
    url = "http://127.0.0.1:%d/#k=%s" % (srv.server_address[1], TOKEN)
    print(url, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
