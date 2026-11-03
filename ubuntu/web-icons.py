#!/usr/bin/env python3
"""Real logos for AirCursor PC's web shortcuts (YouTube, GitHub, Google Drive, AI apps, web apps).

Each site's logo is downloaded once (128 px, Google's favicon service) into
/usr/share/pixmaps/aircursor-web/<site>.png and the shortcut's Icon= points at it, in the
Start menu and on the desktop. No internet: nothing changes, it tries again next start.
"""
import glob
import os
import re
import sys
import urllib.parse
import urllib.request

DIR = "/usr/share/pixmaps/aircursor-web"
HOME = os.path.expanduser("~")
PLACES = ["/usr/share/applications", "/usr/local/share/applications", os.path.join(HOME, ".local/share/applications"),
          os.path.join(HOME, "Desktop"), os.path.join(HOME, "Desktop", "AI Apps"), os.path.join(HOME, "Desktop", "Web Apps"),
          os.path.join(HOME, "Desktop", "Tools")]
# Shortcuts whose command has no web address in it.
KNOWN = {
    "cloudpc-github.desktop": "github.com",
    "GitHub.desktop": "github.com",
    "cloudpc-gdrive.desktop": "drive.google.com",
    "Google-Drive.desktop": "drive.google.com",
}
URL = re.compile(r"(https?://[^\s\"']+)")


def site_of(path, text):
    name = os.path.basename(path)
    if name in KNOWN:
        return KNOWN[name]
    m = re.search(r"^Exec=(.*)$", text, re.M)
    if not m or ("cloudpc-web" not in m.group(1) and "cloudpc-app" not in m.group(1)):
        return None
    u = URL.search(m.group(1))
    if not u:
        return None
    host = urllib.parse.urlsplit(u.group(1)).hostname or ""
    if host in ("127.0.0.1", "localhost") or not host:
        return None
    return host[4:] if host.startswith("www.") else host


def logo(host):
    os.makedirs(DIR, exist_ok=True)
    out = os.path.join(DIR, host + ".png")
    if os.path.isfile(out) and os.path.getsize(out) > 200:
        return out
    url = ("https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://%s&size=128"
           % urllib.parse.quote(host))
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "AirCursorPC/1.0"})
        with urllib.request.urlopen(req, timeout=15) as r:
            data = r.read(400000)
    except Exception:
        return None
    if len(data) < 200 or not data.startswith(b"\x89PNG"):
        return None
    tmp = out + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, out)
    return out


def main():
    done = 0
    for place in PLACES:
        for path in glob.glob(os.path.join(place, "*.desktop")):
            try:
                with open(path, encoding="utf-8", errors="replace") as f:
                    text = f.read()
            except OSError:
                continue
            host = site_of(path, text)
            if not host:
                continue
            m = re.search(r"^Icon=(.*)$", text, re.M)
            if m and m.group(1).startswith(DIR):
                continue
            icon = logo(host)
            if not icon:
                continue
            new = re.sub(r"^Icon=.*$", "Icon=" + icon, text, count=1, flags=re.M) if m else text.rstrip("\n") + "\nIcon=" + icon + "\n"
            try:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(new)
                done += 1
            except OSError:
                pass
    print("web icons updated:", done)


if __name__ == "__main__":
    sys.exit(main())
