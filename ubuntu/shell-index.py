#!/usr/bin/env python3
"""The PC's apps for AirCursor's phone-drawn dock and apps grid.

Writes /root/.cache/cloudpc-shell/apps.tsv (one app per line: id, name, desktop file, icon png,
window class, categories, keywords) and a 96 px PNG icon per app. The phone reads these files
directly (the PC's disk is on the phone) and draws the dock and grid with the phone's GPU.
Run inside the desktop session (it needs GTK's icon theme): python3 shell-index.py
"""
import configparser
import glob
import hashlib
import os
import sys

OUT = os.environ.get("CLOUDPC_SHELL_DIR", "/root/.cache/cloudpc-shell")
ICONS = os.path.join(OUT, "icons")
SIZE = 96
PLACES = ["/usr/share/applications", "/usr/local/share/applications",
          os.path.expanduser("~/.local/share/applications"), "/var/lib/flatpak/exports/share/applications"]
# Settings dialogs and helpers that are not apps people look for.
SKIP_CATS = {"Settings", "DesktopSettings", "HardwareSettings", "X-XFCE-SettingsDialog", "X-XFCE-HardwareSettings"}
KEEP_SETTINGS = {"xfce-settings-manager.desktop", "xfce4-settings-manager.desktop"}


WRAPPERS = {"env", "cloudpc-launch", "nice", "setsid", "nohup", "bash", "sh", "flatpak", "run", "exec", "true"}


def program(cmd):
    """The real program of an Exec= line (past cloudpc-launch, env, VAR=x, flags, .desktop ids)."""
    for tok in cmd.split():
        t = os.path.basename(tok.strip('"\''))
        if not t or t in WRAPPERS or t.startswith(("-", "%")) or "=" in t or t.endswith(".desktop"):
            continue
        return t
    return ""


def entries():
    seen = {}
    for place in PLACES:
        for path in sorted(glob.glob(os.path.join(place, "*.desktop"))):
            seen[os.path.basename(path)] = path   # later places win (user copies)
    for did, path in sorted(seen.items()):
        cp = configparser.RawConfigParser(strict=False, interpolation=None)
        cp.optionxform = str
        try:
            cp.read(path, encoding="utf-8")
            e = cp["Desktop Entry"]
        except Exception:
            continue
        if e.get("Type", "Application") != "Application":
            continue
        if e.get("NoDisplay", "").lower() == "true" or e.get("Hidden", "").lower() == "true":
            continue
        if e.get("OnlyShowIn") and "XFCE" not in e.get("OnlyShowIn"):
            continue
        cats = [c for c in e.get("Categories", "").split(";") if c]
        if did not in KEEP_SETTINGS and SKIP_CATS.intersection(cats):
            continue
        name = e.get("Name", "").strip()
        if not name:
            continue
        wmclass = e.get("StartupWMClass", "").strip() or program(e.get("Exec", ""))
        yield did, name, path, e.get("Icon", "").strip(), wmclass, ";".join(cats), e.get("Keywords", "").strip()


def render(theme, icon, did):
    if not icon:
        icon = "application-x-executable"
    key = hashlib.sha1((icon + "@" + did).encode()).hexdigest()[:16]
    out = os.path.join(ICONS, key + ".png")
    src = icon if os.path.isabs(icon) else None
    if os.path.isfile(out) and (src is None or os.path.getmtime(out) >= os.path.getmtime(src)):
        return out
    from gi.repository import GdkPixbuf, Gtk
    pb = None
    try:
        if src and os.path.isfile(src):
            pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(src, SIZE, SIZE, True)
        else:
            name = icon[:-4] if icon.endswith((".png", ".svg", ".xpm")) else icon
            for n in (name, "application-x-executable"):
                info = theme.lookup_icon(n, SIZE, Gtk.IconLookupFlags.FORCE_SIZE)
                if info:
                    pb = info.load_icon()
                    break
    except Exception:
        pb = None
    if pb is None:
        return ""
    tmp = out + ".tmp"
    pb.savev(tmp, "png", [], [])
    os.replace(tmp, out)
    return out


def clean(s):
    return s.replace("\t", " ").replace("\n", " ")


def main():
    import gi
    gi.require_version("Gtk", "3.0")
    gi.require_version("GdkPixbuf", "2.0")
    from gi.repository import Gtk
    os.makedirs(ICONS, exist_ok=True)
    theme = Gtk.IconTheme.get_default()
    rows = []
    for did, name, path, icon, wmclass, cats, kw in entries():
        png = render(theme, icon, did)
        rows.append("\t".join(clean(x) for x in (did, name, path, png, wmclass, cats, kw)))
    tmp = os.path.join(OUT, "apps.tsv.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")
    os.replace(tmp, os.path.join(OUT, "apps.tsv"))
    print("apps:", len(rows))


if __name__ == "__main__":
    sys.exit(main())
