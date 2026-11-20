#!/usr/bin/python3 -I
"""cloudpc-as-user <command> [args...] – runs an app as the PC's own user instead of root.

The PC runs everything as root, but Electron apps (Claude Desktop, Cursor, Antigravity…) refuse
to draw their windows as root: their sandboxed page programs stop at once ("Running as root
without --no-sandbox is not supported") and the window stays empty. They run as the user
"aircursor" (uid 1000) instead, with the same home folder (/root), desktop, sound and keyring:
  - the user exists (created once),
  - the desktop's D-Bus lets this one user in (keyring, so sign-ins stay saved),
  - the display (X) lets this one user in.
Installed inside Ubuntu as /usr/local/bin/cloudpc-as-user
"""
import os
import pwd
import subprocess
import sys

UID = 1000
DBUS_RULE = "/etc/dbus-1/session-local.conf"


def quiet(cmd):
    try:
        return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20).returncode == 0
    except Exception:
        return False


def user():
    try:
        return pwd.getpwuid(UID)
    except KeyError:
        pass
    # A user of its own with root's home folder (all files there stay usable).
    if not quiet(["useradd", "-u", str(UID), "-U", "-M", "-d", "/root", "-s", "/bin/bash", "-c", "AirCursor", "aircursor"]):
        quiet(["useradd", "-u", str(UID), "-g", str(UID), "-M", "-d", "/root", "-s", "/bin/bash", "-c", "AirCursor", "aircursor"])
    try:
        return pwd.getpwuid(UID)
    except KeyError:
        return None


def dbus_rule(name):
    want = (
        '<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"\n'
        ' "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">\n'
        "<!-- AirCursor PC: apps that cannot run as root run as %s; the desktop's bus lets this user in too. -->\n"
        "<busconfig>\n  <policy context=\"default\">\n    <allow user=\"%s\"/>\n  </policy>\n</busconfig>\n"
    ) % (name, name)
    try:
        with open(DBUS_RULE) as f:
            if f.read() == want:
                return
    except OSError:
        pass
    try:
        os.makedirs(os.path.dirname(DBUS_RULE), exist_ok=True)
        with open(DBUS_RULE, "w") as f:
            f.write(want)
    except OSError:
        return
    if os.environ.get("DBUS_SESSION_BUS_ADDRESS"):
        quiet(["dbus-send", "--session", "--type=method_call", "--dest=org.freedesktop.DBus",
               "/org/freedesktop/DBus", "org.freedesktop.DBus.ReloadConfig"])


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    cmd = sys.argv[1:]
    if os.getuid() == 0:
        u = user()
        if u is not None:
            dbus_rule(u.pw_name)
            if os.environ.get("DISPLAY"):
                quiet(["xhost", "+si:localuser:" + u.pw_name])
            try:
                os.setgroups([])
            except OSError:
                pass
            os.setgid(u.pw_gid)
            os.setuid(u.pw_uid)
            os.environ["USER"] = os.environ["LOGNAME"] = u.pw_name
            # HOME stays /root: the apps' settings and sign-ins live there for every way they start.
    os.execvp(cmd[0], cmd)


if __name__ == "__main__":
    sys.exit(main())
