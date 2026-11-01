#!/usr/bin/env python3
"""AirCursor App Store: a Windows-Store-like app shop for AirCursor PC.

Cards with real logos, one-tap Install, "Installed" + Open / Remove, web apps (YouTube, Gmail,
WhatsApp, AI websites), games, and a search over 60,000+ Ubuntu apps. Installs run through
ubuntu-extras (the same installer the old App Store used), one after another.
"""
import os
import re
import shlex
import subprocess
import threading
import time
import urllib.request

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import Gdk, GdkPixbuf, Gio, GLib, Gtk, Pango  # noqa: E402

HOME = os.path.expanduser("~")
CACHE = os.path.join(HOME, ".cache", "aircursor-store")
ICON_DIR = "/usr/share/aircursor/webapps"
EXTRAS = "/usr/local/bin/ubuntu-extras"
ANSI = re.compile(r"\x1b\[[0-9;]*m")


class App:
    def __init__(self, id, name, desc, cat, size=0, domains=(), checks=(), opens=(), icon="application-x-executable",
                 url=None, removable=True, popular=False, check_all=False, builtin=False):
        self.id, self.name, self.desc, self.cat, self.size = id, name, desc, cat, size
        self.domains, self.checks, self.opens, self.icon = list(domains), list(checks), list(opens), icon
        self.url, self.removable, self.popular, self.check_all, self.builtin = url, removable, popular, check_all, builtin
        self.target = id   # what ubuntu-extras installs (an App Store id or an Ubuntu package name)

    @property
    def web(self):
        return self.url is not None


def web(id, name, desc, cat, url, domain, popular=False, builtin_desktop=None, categories="Network;"):
    a = App("web-" + id, name, desc, cat, 0, [domain], [], [], "web-browser", url=url, popular=popular)
    a.categories = categories
    if builtin_desktop:
        a.builtin = True
        a.removable = False
        a.checks = ["desk:" + builtin_desktop]
        a.opens = ["desk:" + builtin_desktop]
    return a


AI, OFFICE, MEDIA, GAMES, WEB = "AI & Coding", "Office", "Photo & Video", "Games", "Web apps"
CATALOG = [
    # AI & coding
    App("vscode", "VS Code", "Code editor from Microsoft. On most phones it opens in Firefox (VS Code Server)", AI, 110,
        ["code.visualstudio.com"], ["pkg:code"], ["desk:code.desktop", "pkg:code"], "applications-development", popular=True),
    App("claude", "Claude Code", "AI coding assistant in the terminal (Claude account needed)", AI, 80,
        ["claude.ai"], ["cmd:claude"], ["desk:cloudpc-ai-claude-code.desktop", "term:cloudpc-claude"], "utilities-terminal"),
    App("coding", "Coding tools", "Git, Python, Node.js and the C/C++ compiler in one step", AI, 200,
        ["git-scm.com"], ["cmd:git", "cmd:gcc", "cmd:node"], ["term:bash"], "applications-development",
        removable=False, check_all=True),
    App("python", "Python", "Python 3 with pip and virtual environments", AI, 40,
        ["python.org"], ["cmd:pip3"], ["term:python3"], "applications-development", removable=False),
    App("ollama", "Offline AI (Ollama)", "AI chat without internet. Very heavy and slow on phones", AI, 1500,
        ["ollama.com"], ["cmd:ollama"], ["desk:cloudpc-ai-offline.desktop"], "utilities-terminal"),
    web("chatgpt", "ChatGPT", "OpenAI's AI assistant", AI, "https://chatgpt.com", "chatgpt.com", True,
        "cloudpc-ai-chatgpt.desktop"),
    web("claude-web", "Claude", "Anthropic's AI assistant", AI, "https://claude.ai", "claude.ai", False,
        "cloudpc-ai-claude.desktop"),
    web("gemini", "Gemini", "Google's AI assistant", AI, "https://gemini.google.com", "gemini.google.com", False,
        "cloudpc-ai-gemini.desktop"),
    web("copilot", "Copilot", "Microsoft's AI assistant", AI, "https://copilot.microsoft.com", "copilot.microsoft.com"),
    web("perplexity", "Perplexity", "AI search with sources", AI, "https://www.perplexity.ai", "perplexity.ai"),
    web("github", "GitHub", "Your code and projects online", AI, "https://github.com", "github.com",
        categories="Development;"),
    # Office
    App("libreoffice", "LibreOffice", "Word, Excel and PowerPoint files: documents, sheets, slides", OFFICE, 300,
        ["libreoffice.org"], ["pkg:libreoffice-writer"], ["desk:libreoffice-writer.desktop", "pkg:libreoffice-writer"],
        "applications-office", popular=True),
    App("gdrive", "Google Drive", "15 GB free cloud storage for your files", OFFICE, 25,
        ["drive.google.com"], ["cmd:google-drive"], ["hold:google-drive"], "folder-remote"),
    web("gmail", "Gmail", "Your Google mail", OFFICE, "https://mail.google.com", "mail.google.com", True,
        categories="Network;Email;"),
    web("docs", "Google Docs", "Documents, sheets and slides online", OFFICE, "https://docs.google.com",
        "docs.google.com", categories="Office;"),
    web("whatsapp", "WhatsApp", "WhatsApp Web: link it with your phone", OFFICE, "https://web.whatsapp.com",
        "www.whatsapp.com", categories="Network;Chat;"),
    # Photo & video
    App("gimp", "GIMP", "Photo editing, like Photoshop", MEDIA, 120,
        ["www.gimp.org", "gimp.org"], ["pkg:gimp"], ["desk:gimp.desktop", "pkg:gimp"], "applications-graphics", popular=True),
    App("vlc", "VLC", "Plays every video and music file", MEDIA, 60,
        ["www.videolan.org", "videolan.org"], ["pkg:vlc"], ["desk:vlc.desktop", "pkg:vlc"], "applications-multimedia",
        popular=True),
    App("inkscape", "Inkscape", "Logos, posters and vector art", MEDIA, 120,
        ["inkscape.org", "www.inkscape.org"], ["pkg:inkscape"], ["desk:org.inkscape.Inkscape.desktop", "pkg:inkscape"],
        "applications-graphics"),
    App("blender", "Blender", "3D modelling and animation (heavy)", MEDIA, 350,
        ["blender.org"], ["pkg:blender"], ["desk:blender.desktop", "pkg:blender"], "applications-graphics"),
    App("shotcut", "Shotcut", "Simple video editor", MEDIA, 150,
        ["shotcut.org"], ["pkg:shotcut"], ["desk:org.shotcut.Shotcut.desktop", "pkg:shotcut"], "applications-multimedia"),
    App("kdenlive", "Kdenlive", "Advanced video editor (heavy)", MEDIA, 450,
        ["kdenlive.org"], ["pkg:kdenlive"], ["desk:org.kde.kdenlive.desktop", "pkg:kdenlive"], "applications-multimedia"),
    web("youtube", "YouTube", "Videos, music and live streams", MEDIA, "https://www.youtube.com", "youtube.com", True,
        categories="AudioVideo;Video;"),
    web("canva", "Canva", "Easy designs, posters and social media posts", MEDIA, "https://www.canva.com", "canva.com",
        categories="Graphics;"),
    # Games
    App("luanti", "Minetest", "Minecraft-like 3D world: build, mine and explore", GAMES, 15,
        ["luanti.org"], ["pkg:minetest", "pkg:luanti"], ["desk:net.minetest.minetest.desktop", "pkg:minetest", "pkg:luanti"],
        "applications-games", popular=True),
    App("supertuxkart", "SuperTuxKart", "3D kart racing with items and tracks (big download)", GAMES, 630,
        ["supertuxkart.net"], ["pkg:supertuxkart"], ["desk:supertuxkart.desktop", "pkg:supertuxkart"],
        "applications-games", popular=True),
    App("trigger", "Trigger Rally", "Rally car racing in 3D", GAMES, 120,
        ["trigger-rally.sourceforge.io"], ["pkg:trigger-rally"], ["desk:trigger-rally.desktop", "pkg:trigger-rally"],
        "applications-games"),
    App("torcs", "TORCS", "Realistic car racing simulator (3D, big download)", GAMES, 340,
        ["torcs.sourceforge.net"], ["pkg:torcs"], ["desk:torcs.desktop", "pkg:torcs"], "applications-games"),
    App("neverball", "Neverball", "Tilt the floor and roll the ball through 3D levels", GAMES, 25,
        ["neverball.org"], ["pkg:neverball"], ["desk:neverball.desktop", "pkg:neverball"], "applications-games"),
    App("tuxracer", "Extreme Tux Racer", "Downhill penguin racing in 3D", GAMES, 45,
        ["extremetuxracer.sourceforge.io"], ["pkg:extremetuxracer"], ["pkg:extremetuxracer"], "applications-games"),
    App("openarena", "OpenArena", "Fast 3D arena shooter", GAMES, 50,
        ["openarena.ws"], ["pkg:openarena"], ["desk:openarena.desktop", "pkg:openarena"], "applications-games"),
    App("games", "Games pack", "SuperTux, X-Moto, Frozen Bubble, 2048, Chess, Solitaire, OpenTTD and more (2D, smooth)",
        GAMES, 280, ["supertux.org"], ["pkg:supertux"], ["folder:Desktop/Games"], "applications-games"),
]
CATEGORIES = [("Popular", "starred"), (AI, "applications-development"), (OFFICE, "x-office-document"),
              (MEDIA, "applications-multimedia"), (GAMES, "applications-games"), (WEB, "web-browser"),
              ("Installed", "emblem-ok-symbolic")]

CSS = b"""
window.store { background: #f3f5f9; }
.sidebar { background: #1b2030; }
.brand { color: #ffffff; font-weight: 800; font-size: 17px; }
.brand-sub { color: #8f9bb8; font-size: 11px; }
.sidebar button { color: #cdd5e6; background-image: none; background-color: transparent; border: none;
    border-radius: 9px; padding: 7px 10px; box-shadow: none; text-shadow: none; -gtk-icon-shadow: none; }
.sidebar button:hover { background-color: rgba(255,255,255,0.08); }
.sidebar button.active { background-color: #3b6cf6; color: #ffffff; }
.sidebar button label { color: inherit; }
.storage { color: #8f9bb8; font-size: 11px; }
.page-title { font-size: 21px; font-weight: 800; color: #141a28; }
.page-sub { color: #5b6476; font-size: 12px; }
.banner { background-image: linear-gradient(115deg, #2f5be8, #7b4cf0); border-radius: 14px; }
.banner-title { color: #ffffff; font-size: 17px; font-weight: 800; }
.banner-text { color: #e6e9ff; font-size: 12px; }
.card { background-color: #ffffff; border-radius: 13px; border: 1px solid #e1e6f0; }
.card:hover { border-color: #b7c5ee; }
.app-name { font-weight: 800; font-size: 13px; color: #141a28; }
.app-desc { color: #5b6476; font-size: 11px; }
.meta { color: #7a8397; font-size: 11px; }
.ok { color: #138a4e; font-weight: 800; font-size: 11px; }
.bad { color: #c62f2f; font-size: 11px; }
button.primary { background-image: none; background-color: #3b6cf6; color: #ffffff; border: none; border-radius: 8px;
    padding: 4px 16px; font-weight: 800; box-shadow: none; text-shadow: none; }
button.primary:hover { background-color: #2f5be8; }
button.primary:disabled { background-color: #c9d3ee; color: #ffffff; }
button.secondary { background-image: none; background-color: #e9eefc; color: #2448c4; border: none; border-radius: 8px;
    padding: 4px 16px; font-weight: 800; box-shadow: none; text-shadow: none; }
button.secondary:hover { background-color: #dbe3fb; }
button.link { background-image: none; background-color: transparent; color: #8a92a5; border: none; box-shadow: none;
    padding: 2px 6px; font-size: 11px; text-shadow: none; }
button.link:hover { color: #c62f2f; }
.statusbar { background-color: #ffffff; border-top: 1px solid #e1e6f0; }
.statusbar label { color: #263042; font-size: 12px; }
progressbar trough { min-height: 6px; border-radius: 3px; background-color: #e3e8f3; border: none; }
progressbar progress { min-height: 6px; border-radius: 3px; background-color: #3b6cf6; border: none; }
entry.search { border-radius: 18px; padding: 4px 12px; }
"""


def sh(cmd, timeout=20):
    try:
        return subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout).stdout
    except Exception:
        return ""


def installed_packages():
    out = sh(["dpkg-query", "-W", "-f=${db:Status-Abbrev} ${Package}\n"], 30)
    return {line.split()[1].split(":")[0] for line in out.splitlines() if line.startswith("ii ") and len(line.split()) > 1}


def which(cmd):
    for d in ("/usr/local/bin", "/usr/bin", "/bin", "/usr/games", "/usr/local/games", os.path.join(HOME, ".local/bin")):
        if os.access(os.path.join(d, cmd), os.X_OK):
            return True
    return False


def desktop_exists(did):
    return any(os.path.isfile(os.path.join(d, did)) for d in ("/usr/local/share/applications", "/usr/share/applications"))


def web_desktop_id(app):
    return "aircursor-%s.desktop" % app.id


def is_installed(app, pkgs):
    if app.web:
        return desktop_exists(web_desktop_id(app)) or any(
            desktop_exists(c[5:]) for c in app.checks if c.startswith("desk:"))
    results = []
    for c in app.checks:
        kind, _, val = c.partition(":")
        if kind == "pkg":
            results.append(val in pkgs)
        elif kind == "cmd":
            results.append(which(val))
        elif kind == "file":
            results.append(os.path.exists(val))
        elif kind == "desk":
            results.append(desktop_exists(val))
    if not results:
        return False
    return all(results) if app.check_all else any(results)


def free_space_text():
    try:
        st = os.statvfs("/")
        free = st.f_bavail * st.f_frsize / 1e9
        return "Free space: %.0f GB" % free if free >= 10 else "Free space: %.1f GB" % free
    except Exception:
        return ""


def launch_desktop(did):
    try:
        info = Gio.DesktopAppInfo.new(did)
    except Exception:
        info = None
    if info is None:
        return False
    try:
        return info.launch([], None)
    except Exception:
        return False


def package_desktop_files(pkg):
    out = sh(["dpkg", "-L", pkg])
    return [os.path.basename(p) for p in out.splitlines()
            if p.startswith("/usr/share/applications/") and p.endswith(".desktop")]


def open_app(app):
    for o in app.opens:
        kind, _, val = o.partition(":")
        if kind == "desk" and desktop_exists(val) and launch_desktop(val):
            return True
        if kind == "pkg":
            for did in package_desktop_files(val):
                if launch_desktop(did):
                    return True
        if kind == "term":
            subprocess.Popen(["xfce4-terminal", "--working-directory=" + HOME, "-x", "bash", "-lc",
                              "%s; exec bash" % val], start_new_session=True)
            return True
        if kind == "hold":
            subprocess.Popen(["xfce4-terminal", "--hold", "-e", val], start_new_session=True)
            return True
        if kind == "folder":
            subprocess.Popen(["thunar", os.path.join(HOME, val)], start_new_session=True)
            return True
    if app.web and app.url:
        subprocess.Popen(["firefox", "--new-window", app.url], start_new_session=True)
        return True
    return False


# ---- Logos ------------------------------------------------------------------------------------

def favicon_path(app):
    return os.path.join(CACHE, "icons", app.id + ".png")


def fetch_favicon(app):
    """Downloads the app's real logo (its website icon) once. Returns True when a good one exists."""
    path = favicon_path(app)
    if os.path.exists(path):
        return True
    fail = path + ".fail"
    if os.path.exists(fail) and time.time() - os.path.getmtime(fail) < 7 * 86400:
        return False
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for domain in app.domains:
        url = ("https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL"
               "&url=https://%s&size=128" % domain)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 AirCursorStore"})
            with urllib.request.urlopen(req, timeout=12) as r:
                data = r.read(400000)
            loader = GdkPixbuf.PixbufLoader()
            loader.write(data)
            loader.close()
            pb = loader.get_pixbuf()
            if pb is None or pb.get_width() < 32:
                continue
            if pb.get_width() != 128:
                pb = pb.scale_simple(128, 128, GdkPixbuf.InterpType.BILINEAR)
            pb.savev(path, "png", [], [])
            return True
        except Exception:
            continue
    try:
        open(fail, "w").close()
    except OSError:
        pass
    return False


def desktop_icon_name(app):
    """The icon of the installed app itself (its real logo from the package)."""
    for o in app.opens:
        kind, _, val = o.partition(":")
        dids = [val] if kind == "desk" else package_desktop_files(val) if kind == "pkg" else []
        for did in dids:
            for d in ("/usr/local/share/applications", "/usr/share/applications"):
                p = os.path.join(d, did)
                if os.path.isfile(p):
                    try:
                        for line in open(p, encoding="utf-8", errors="ignore"):
                            if line.startswith("Icon="):
                                return line[5:].strip()
                    except OSError:
                        pass
    return None


def load_icon(app, installed, size=56):
    theme = Gtk.IconTheme.get_default()
    names = []
    if installed and not app.web:
        n = desktop_icon_name(app)
        if n:
            names.append(n)
    fav = favicon_path(app)
    for n in names:
        try:
            if n.startswith("/") and os.path.exists(n):
                return GdkPixbuf.Pixbuf.new_from_file_at_scale(n, size, size, True)
            if theme.has_icon(n):
                return theme.load_icon(n, size, Gtk.IconLookupFlags.FORCE_SIZE)
        except Exception:
            pass
    if os.path.exists(fav):
        try:
            return GdkPixbuf.Pixbuf.new_from_file_at_scale(fav, size, size, True)
        except Exception:
            pass
    for n in (app.icon, "application-x-executable", "image-missing"):
        try:
            if theme.has_icon(n):
                return theme.load_icon(n, size, Gtk.IconLookupFlags.FORCE_SIZE)
        except Exception:
            pass
    return None


# ---- Web apps (instant, no download) -----------------------------------------------------------

def add_web_app(app):
    os.makedirs(ICON_DIR, exist_ok=True)
    icon = "web-browser"
    if fetch_favicon(app):
        dst = os.path.join(ICON_DIR, app.id + ".png")
        try:
            with open(favicon_path(app), "rb") as s, open(dst, "wb") as d:
                d.write(s.read())
            icon = dst
        except OSError:
            pass
    text = ("[Desktop Entry]\nType=Application\nName=%s\nComment=%s\nExec=firefox --new-window %s\nIcon=%s\n"
            "Terminal=false\nCategories=%s\nStartupWMClass=firefox\nX-AirCursor-WebApp=true\n"
            % (app.name, app.desc, app.url, icon, getattr(app, "categories", "Network;")))
    path = os.path.join("/usr/share/applications", web_desktop_id(app))
    with open(path, "w") as f:
        f.write(text)
    desk = os.path.join(HOME, "Desktop", "Web Apps")
    os.makedirs(desk, exist_ok=True)
    shortcut = os.path.join(desk, app.id[4:] + ".desktop")
    with open(shortcut, "w") as f:
        f.write(text)
    os.chmod(shortcut, 0o755)


def remove_web_app(app):
    for p in (os.path.join("/usr/share/applications", web_desktop_id(app)),
              os.path.join(HOME, "Desktop", "Web Apps", app.id[4:] + ".desktop"),
              os.path.join(ICON_DIR, app.id + ".png")):
        try:
            os.remove(p)
        except OSError:
            pass


# ---- UI ---------------------------------------------------------------------------------------

class Card(Gtk.Box):
    def __init__(self, store, app):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.store, self.app = store, app
        self.get_style_context().add_class("card")
        self.set_size_request(250, -1)
        inner = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        for m in ("top", "bottom", "start", "end"):
            getattr(inner, "set_margin_" + m)(12)
        self.add(inner)
        self.image = Gtk.Image()
        self.image.set_valign(Gtk.Align.START)
        inner.pack_start(self.image, False, False, 0)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
        inner.pack_start(col, True, True, 0)
        name = Gtk.Label(label=app.name, xalign=0)
        name.get_style_context().add_class("app-name")
        name.set_ellipsize(Pango.EllipsizeMode.END)
        col.pack_start(name, False, False, 0)
        desc = Gtk.Label(label=app.desc, xalign=0)
        desc.get_style_context().add_class("app-desc")
        desc.set_line_wrap(True)
        desc.set_lines(2)
        desc.set_ellipsize(Pango.EllipsizeMode.END)
        desc.set_max_width_chars(28)
        col.pack_start(desc, False, False, 0)
        self.progress = Gtk.ProgressBar()
        self.progress.set_no_show_all(True)
        self.progress.set_margin_top(4)
        col.pack_start(self.progress, False, False, 0)
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        row.set_margin_top(6)
        col.pack_end(row, False, False, 0)
        self.meta = Gtk.Label(xalign=0)
        self.meta.get_style_context().add_class("meta")
        row.pack_start(self.meta, True, True, 0)
        self.remove_btn = Gtk.Button(label="Remove")
        self.remove_btn.set_no_show_all(True)
        self.remove_btn.get_style_context().add_class("link")
        self.remove_btn.connect("clicked", lambda *_: store.remove(app))
        row.pack_end(self.remove_btn, False, False, 0)
        self.button = Gtk.Button()
        self.button.connect("clicked", self.on_button)
        row.pack_end(self.button, False, False, 0)
        self.refresh()

    def refresh(self):
        app, st = self.app, self.store
        installed = app.id in st.installed
        key = (installed, os.path.exists(favicon_path(app)))
        if key != getattr(self, "_icon_key", None):
            self._icon_key = key
            pb = load_icon(app, installed)
            if pb is not None:
                self.image.set_from_pixbuf(pb)
        job = st.job_state(app.id)
        ctx = self.button.get_style_context()
        for c in ("primary", "secondary"):
            ctx.remove_class(c)
        self.meta.get_style_context().remove_class("ok")
        self.meta.get_style_context().remove_class("bad")
        self.remove_btn.hide()
        if job == "running":
            self.button.set_label("Working…")
            ctx.add_class("primary")
            self.button.set_sensitive(False)
            self.meta.set_text("")
            self.progress.show()
        elif job == "queued":
            self.button.set_label("Waiting")
            ctx.add_class("primary")
            self.button.set_sensitive(False)
            self.meta.set_text("Starts after the current app")
            self.progress.hide()
        elif installed:
            self.progress.hide()
            self.button.set_label("Open")
            ctx.add_class("secondary")
            self.button.set_sensitive(True)
            self.meta.set_text("✓ Installed")
            self.meta.get_style_context().add_class("ok")
            if app.removable:
                self.remove_btn.show()
        else:
            self.progress.hide()
            self.button.set_label("Add" if app.web else "Install")
            ctx.add_class("primary")
            self.button.set_sensitive(True)
            if app.id in st.failed:
                self.meta.set_text("Failed – try again")
                self.meta.get_style_context().add_class("bad")
            else:
                self.meta.set_text("Website · instant" if app.web else "%d MB" % app.size if app.size else "")

    def on_button(self, *_):
        if self.app.id in self.store.installed:
            if not open_app(self.app):
                self.store.toast("Could not open %s. Find it in the Start menu." % self.app.name)
            else:
                self.store.toast("Opening %s…" % self.app.name)
        else:
            self.store.install(self.app)


class Store(Gtk.Window):
    def __init__(self):
        super().__init__(title="App Store")
        self.get_style_context().add_class("store")
        self.set_icon_name("system-software-install")
        self.set_default_size(980, 620)
        scr = Gdk.Screen.get_default()
        if scr is not None and scr.get_width() <= 1366:
            self.maximize()
        prov = Gtk.CssProvider()
        prov.load_from_data(CSS)
        Gtk.StyleContext.add_provider_for_screen(scr, prov, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self.connect("destroy", Gtk.main_quit)

        self.pkgs = set()
        self.installed = set()
        self.failed = set()
        self.queue = []
        self.current = None
        self.cards = {}
        self.category = "Popular"
        self.search_text = ""

        root = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        self.add(root)
        # Sidebar
        side = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        side.get_style_context().add_class("sidebar")
        side.set_size_request(186, -1)
        root.pack_start(side, False, False, 0)
        brand = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        for m, v in (("top", 18), ("bottom", 14), ("start", 16), ("end", 12)):
            getattr(brand, "set_margin_" + m)(v)
        t = Gtk.Label(label="App Store", xalign=0)
        t.get_style_context().add_class("brand")
        s = Gtk.Label(label="AirCursor PC", xalign=0)
        s.get_style_context().add_class("brand-sub")
        brand.pack_start(t, False, False, 0)
        brand.pack_start(s, False, False, 0)
        side.pack_start(brand, False, False, 0)
        self.cat_buttons = {}
        for name, icon in CATEGORIES:
            b = Gtk.Button()
            box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
            img = Gtk.Image.new_from_icon_name(icon, Gtk.IconSize.MENU)
            box.pack_start(img, False, False, 0)
            box.pack_start(Gtk.Label(label=name, xalign=0), True, True, 0)
            b.add(box)
            b.set_margin_start(8)
            b.set_margin_end(8)
            b.connect("clicked", lambda _b, n=name: self.show_category(n))
            side.pack_start(b, False, False, 0)
            self.cat_buttons[name] = b
        self.storage = Gtk.Label(xalign=0)
        self.storage.get_style_context().add_class("storage")
        self.storage.set_margin_start(16)
        self.storage.set_margin_bottom(14)
        side.pack_end(self.storage, False, False, 0)

        # Main column
        main = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        root.pack_start(main, True, True, 0)
        head = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        for m, v in (("top", 16), ("bottom", 8), ("start", 22), ("end", 18)):
            getattr(head, "set_margin_" + m)(v)
        titles = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.title = Gtk.Label(xalign=0)
        self.title.get_style_context().add_class("page-title")
        self.subtitle = Gtk.Label(xalign=0)
        self.subtitle.get_style_context().add_class("page-sub")
        self.subtitle.set_ellipsize(Pango.EllipsizeMode.END)
        titles.pack_start(self.title, False, False, 0)
        titles.pack_start(self.subtitle, False, False, 0)
        head.pack_start(titles, True, True, 0)
        self.search = Gtk.SearchEntry()
        self.search.get_style_context().add_class("search")
        self.search.set_placeholder_text("Search 60,000+ apps")
        self.search.set_width_chars(22)
        self.search.set_valign(Gtk.Align.CENTER)
        self.search.connect("activate", self.on_search)
        head.pack_end(self.search, False, False, 0)
        main.pack_start(head, False, False, 0)

        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        main.pack_start(scroll, True, True, 0)
        self.content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14)
        for m, v in (("top", 6), ("bottom", 18), ("start", 22), ("end", 22)):
            getattr(self.content, "set_margin_" + m)(v)
        scroll.add(self.content)

        # Status bar for installs
        self.status_rev = Gtk.Revealer()
        bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        bar.get_style_context().add_class("statusbar")
        inner = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        for m, v in (("top", 9), ("bottom", 9), ("start", 18), ("end", 18)):
            getattr(inner, "set_margin_" + m)(v)
        bar.pack_start(inner, True, True, 0)
        self.spinner = Gtk.Spinner()
        inner.pack_start(self.spinner, False, False, 0)
        self.status = Gtk.Label(xalign=0)
        self.status.set_ellipsize(Pango.EllipsizeMode.END)
        inner.pack_start(self.status, True, True, 0)
        self.status_progress = Gtk.ProgressBar()
        self.status_progress.set_size_request(180, -1)
        self.status_progress.set_valign(Gtk.Align.CENTER)
        inner.pack_end(self.status_progress, False, False, 0)
        self.status_rev.add(bar)
        main.pack_end(self.status_rev, False, False, 0)

        self.storage.set_text(free_space_text())
        self.refresh_installed()
        self.show_category("Popular")
        threading.Thread(target=self.fetch_logos, daemon=True).start()

    # ---- data
    def refresh_installed(self):
        self.pkgs = installed_packages()
        self.installed = {a.id for a in CATALOG if is_installed(a, self.pkgs)}

    def job_state(self, app_id):
        if self.current is not None and self.current.id == app_id:
            return "running"
        if any(a.id == app_id for _, a in self.queue):
            return "queued"
        return None

    def fetch_logos(self):
        for a in CATALOG:
            if not os.path.exists(favicon_path(a)) and fetch_favicon(a):
                GLib.idle_add(self.refresh_card, a.id)

    def refresh_card(self, app_id):
        c = self.cards.get(app_id)
        if c is not None:
            c.refresh()
        return False

    def refresh_all(self):
        for c in self.cards.values():
            c.refresh()

    # ---- pages
    def clear(self):
        for ch in self.content.get_children():
            self.content.remove(ch)
        self.cards = {}

    def show_category(self, name):
        self.category = name
        for n, b in self.cat_buttons.items():
            ctx = b.get_style_context()
            (ctx.add_class if n == name else ctx.remove_class)("active")
        self.clear()
        subs = {
            "Popular": "Tap Install – new apps appear on the desktop and in the Start menu.",
            AI: "AI assistants and everything for programming.",
            OFFICE: "Documents, mail and cloud storage.",
            MEDIA: "Photos, videos, music and design.",
            GAMES: "Choose AirBoost → Game for the best FPS. Set graphics to Low inside 3D games.",
            WEB: "Websites as apps: open in their own window, no download.",
            "Installed": "Everything you added. Open or remove it here.",
        }
        self.title.set_text(name)
        self.subtitle.set_text(subs.get(name, ""))
        if name == "Popular":
            self.content.pack_start(self.banner(), False, False, 0)
            apps = [a for a in CATALOG if a.popular]
        elif name == WEB:
            apps = [a for a in CATALOG if a.web]
        elif name == "Installed":
            apps = [a for a in CATALOG if a.id in self.installed]
        else:
            apps = [a for a in CATALOG if a.cat == name]
        if not apps:
            empty = Gtk.Label(label="Nothing here yet. Pick apps under Popular or the other groups.", xalign=0)
            empty.get_style_context().add_class("page-sub")
            self.content.pack_start(empty, False, False, 0)
        else:
            self.content.pack_start(self.grid(apps), False, False, 0)
        self.content.show_all()
        self.refresh_all()

    def banner(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        box.get_style_context().add_class("banner")
        inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        for m, v in (("top", 16), ("bottom", 16), ("start", 18), ("end", 18)):
            getattr(inner, "set_margin_" + m)(v)
        t = Gtk.Label(label="Apps for your AirCursor PC", xalign=0)
        t.get_style_context().add_class("banner-title")
        s = Gtk.Label(label="Office, coding, AI, videos and games. Big apps take a few minutes on a phone – "
                            "keep AirCursor PC open while they install.", xalign=0)
        s.get_style_context().add_class("banner-text")
        s.set_line_wrap(True)
        inner.pack_start(t, False, False, 0)
        inner.pack_start(s, False, False, 0)
        box.pack_start(inner, True, True, 0)
        return box

    def grid(self, apps):
        flow = Gtk.FlowBox()
        flow.set_selection_mode(Gtk.SelectionMode.NONE)
        flow.set_homogeneous(True)
        flow.set_min_children_per_line(1)
        flow.set_max_children_per_line(4)
        flow.set_row_spacing(12)
        flow.set_column_spacing(12)
        for a in apps:
            c = Card(self, a)
            self.cards[a.id] = c
            flow.add(c)
        return flow

    # ---- search
    def on_search(self, entry):
        q = entry.get_text().strip()
        if not q:
            self.show_category(self.category)
            return
        for b in self.cat_buttons.values():
            b.get_style_context().remove_class("active")
        self.clear()
        self.title.set_text("Search: " + q)
        self.subtitle.set_text("Searching…")
        ql = q.lower()
        mine = [a for a in CATALOG if ql in a.name.lower() or ql in a.desc.lower()]
        if mine:
            self.content.pack_start(self.grid(mine), False, False, 0)
        self.results_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.content.pack_start(self.results_box, False, False, 0)
        self.content.show_all()
        self.refresh_all()
        threading.Thread(target=self.search_apt, args=(q,), daemon=True).start()

    def search_apt(self, q):
        out = sh(["apt-cache", "search", "--", q], 60)
        rows = []
        for line in out.splitlines()[:60]:
            if " - " in line:
                name, desc = line.split(" - ", 1)
                rows.append((name.strip(), desc.strip()))
        GLib.idle_add(self.show_results, q, rows)

    def show_results(self, q, rows):
        self.subtitle.set_text("%d Ubuntu apps found. Tap Install on the one you want." % len(rows) if rows else
                               "No Ubuntu apps found for \"%s\". Try another word in English." % q)
        for name, desc in rows:
            app = App("pkg:" + name, name, desc, "Search", 0, [], ["pkg:" + name], ["pkg:" + name], "package-x-generic")
            app.target = name
            if is_installed(app, self.pkgs):
                self.installed.add(app.id)
            card = Card(self, app)
            self.cards[app.id] = card
            self.results_box.pack_start(card, False, False, 0)
        self.results_box.show_all()
        self.refresh_all()
        return False

    # ---- install / remove
    def toast(self, text):
        self.status.set_text(text)
        self.status_progress.hide()
        self.spinner.stop()
        self.status_rev.set_reveal_child(True)
        if self.current is None:
            GLib.timeout_add_seconds(5, self.hide_status)

    def hide_status(self):
        if self.current is None:
            self.status_rev.set_reveal_child(False)
        return False

    def install(self, app):
        if app.web:
            try:
                add_web_app(app)
                self.installed.add(app.id)
                self.toast("%s added – it is on the desktop (Web Apps) and in the Start menu." % app.name)
            except Exception as e:
                self.toast("Could not add %s: %s" % (app.name, e))
            self.refresh_card(app.id)
            return
        if self.job_state(app.id):
            return
        if app.size >= 500 and not self.confirm(
                "%s is a big download (about %d MB).\n\nOn a phone this can take 10–30 minutes. Keep the phone "
                "charging and AirCursor PC open." % (app.name, app.size), "Install"):
            return
        self.failed.discard(app.id)
        self.queue.append(("install", app))
        self.next_job()

    def remove(self, app):
        if not self.confirm("Remove %s?\n\nIts files in your home folder stay." % app.name, "Remove"):
            return
        if app.web:
            remove_web_app(app)
            self.installed.discard(app.id)
            self.refresh_card(app.id)
            self.toast("%s removed." % app.name)
            return
        self.queue.append(("remove", app))
        self.next_job()

    def confirm(self, text, ok):
        d = Gtk.MessageDialog(transient_for=self, modal=True, message_type=Gtk.MessageType.QUESTION,
                              buttons=Gtk.ButtonsType.NONE, text=text)
        d.add_button("Cancel", Gtk.ResponseType.CANCEL)
        d.add_button(ok, Gtk.ResponseType.OK)
        r = d.run()
        d.destroy()
        return r == Gtk.ResponseType.OK

    def next_job(self):
        self.refresh_all()
        if self.current is not None or not self.queue:
            return
        action, app = self.queue.pop(0)
        self.current = app
        self.current_action = action
        self.refresh_all()
        self.status_rev.set_reveal_child(True)
        self.spinner.start()
        self.status_progress.show()
        self.status_progress.set_fraction(0.02)
        verb = "Installing" if action == "install" else "Removing"
        self.status.set_text("%s %s…" % (verb, app.name))
        threading.Thread(target=self.run_job, args=(action, app), daemon=True).start()

    def run_job(self, action, app):
        cmd = [EXTRAS, action, app.target]
        total, gets, unpacks, setups, frac = 0, 0, 0, 0, 0.02
        try:
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
                                 errors="replace", env=dict(os.environ, DEBIAN_FRONTEND="noninteractive"))
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
                    text = "Setting up %s…" % line.split()[2] if len(line.split()) > 2 else None
                elif line.startswith("Removing "):
                    text = line
                elif line.startswith(("+ ", "  + ")) or line.startswith("[") or line.startswith("Installing"):
                    text = line.lstrip("+ ")
                if total:
                    frac = max(frac, min(0.98, 0.05 + 0.55 * gets / total + 0.2 * unpacks / total + 0.18 * setups / total))
                elif text:
                    frac = min(0.9, frac + 0.01)
                if text:
                    GLib.idle_add(self.job_progress, app, text, frac)
            p.wait()
        except Exception as e:
            GLib.idle_add(self.job_progress, app, "Error: %s" % e, frac)
        if action == "install":
            sh(["/usr/local/bin/cloudpc-apps"], 120)
        GLib.idle_add(self.job_done, action, app)

    def job_progress(self, app, text, frac):
        self.status.set_text("%s: %s" % (app.name, text))
        self.status_progress.set_fraction(frac)
        c = self.cards.get(app.id)
        if c is not None:
            c.progress.set_fraction(frac)
        return False

    def job_done(self, action, app):
        self.current = None
        self.refresh_installed()
        for c in self.cards.values():   # search results are not in the catalog
            if c.app not in CATALOG and is_installed(c.app, self.pkgs):
                self.installed.add(c.app.id)
        ok = (app.id in self.installed) if action == "install" else (app.id not in self.installed)
        if action == "remove" and ok:
            self.installed.discard(app.id)
        self.storage.set_text(free_space_text())
        if action == "install":
            if ok:
                self.toast("%s is installed – open it here, from the desktop or from the Start menu." % app.name)
            else:
                self.failed.add(app.id)
                self.toast("%s could not be installed. Check the internet connection and tap Install again." % app.name)
        else:
            self.toast("%s removed." % app.name if ok else "%s could not be removed." % app.name)
        if self.category == "Installed":
            self.show_category("Installed")
        self.next_job()
        self.refresh_all()
        return False


def main():
    os.makedirs(CACHE, exist_ok=True)
    Store().show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
