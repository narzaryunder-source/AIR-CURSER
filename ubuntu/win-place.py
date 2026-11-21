#!/usr/bin/env python3
"""Keeps new PC windows usable under AirCursor's phone-drawn menu bar and dock (Mac style).

win-place.py WINDOW_ID...               (ids as xprop prints them, e.g. 0x1e00003)
win-place.py --toggle-max WINDOW_ID     full size on / off (the phone menu bar's button)
win-place.py --close WINDOW_ID          close, as the window's own close button does

Some apps (Electron and Tauri apps such as OpenCode Desktop) place or move their own window after
it opens, at the very top of the screen: its title bar - with the close / minimise / full size
buttons - then sits under the phone's menu bar, where no finger can reach it. For each window:
  - a big app window (most of the screen wide, or taller than the free space) opens full size;
  - a smaller one whose title bar is under the menu bar moves down below it;
  - a dialog taller than the free space (a file chooser) is made to fit, so its buttons show.
Dialogs never go full size. Full-screen, already full-size, hidden, minimised and special windows (desktop,
panels, menus, splash screens) are left alone. Only Xlib through ctypes: no extra packages.
"""
import ctypes
import ctypes.util
import os
import subprocess
import sys

BIG_WIDTH = 0.75   # at least this share of the screen wide: an app window that wants the screen


class XWindowAttributes(ctypes.Structure):
    _fields_ = [
        ("x", ctypes.c_int), ("y", ctypes.c_int), ("width", ctypes.c_int), ("height", ctypes.c_int),
        ("border_width", ctypes.c_int), ("depth", ctypes.c_int), ("visual", ctypes.c_void_p),
        ("root", ctypes.c_ulong), ("c_class", ctypes.c_int), ("bit_gravity", ctypes.c_int),
        ("win_gravity", ctypes.c_int), ("backing_store", ctypes.c_int), ("backing_planes", ctypes.c_ulong),
        ("backing_pixel", ctypes.c_ulong), ("save_under", ctypes.c_int), ("colormap", ctypes.c_ulong),
        ("map_installed", ctypes.c_int), ("map_state", ctypes.c_int), ("all_event_masks", ctypes.c_long),
        ("your_event_mask", ctypes.c_long), ("do_not_propagate_mask", ctypes.c_long),
        ("override_redirect", ctypes.c_int), ("screen", ctypes.c_void_p),
    ]


class XSizeHints(ctypes.Structure):
    _fields_ = [
        ("flags", ctypes.c_long), ("x", ctypes.c_int), ("y", ctypes.c_int),
        ("width", ctypes.c_int), ("height", ctypes.c_int),
        ("min_width", ctypes.c_int), ("min_height", ctypes.c_int),
        ("max_width", ctypes.c_int), ("max_height", ctypes.c_int),
        ("width_inc", ctypes.c_int), ("height_inc", ctypes.c_int),
        ("min_aspect_x", ctypes.c_int), ("min_aspect_y", ctypes.c_int),
        ("max_aspect_x", ctypes.c_int), ("max_aspect_y", ctypes.c_int),
        ("base_width", ctypes.c_int), ("base_height", ctypes.c_int), ("win_gravity", ctypes.c_int),
    ]


class XClientMessageEvent(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
        ("display", ctypes.c_void_p), ("window", ctypes.c_ulong), ("message_type", ctypes.c_ulong),
        ("format", ctypes.c_int), ("l", ctypes.c_long * 5),
    ]


class XEvent(ctypes.Union):
    _fields_ = [("xclient", XClientMessageEvent), ("pad", ctypes.c_long * 24)]


IS_VIEWABLE = 2
P_MIN_SIZE = 1 << 4
P_MAX_SIZE = 1 << 5
CLIENT_MESSAGE = 33
SUBSTRUCTURE = (1 << 19) | (1 << 20)   # SubstructureNotifyMask | SubstructureRedirectMask


def margins():
    """The space the phone's menu bar (top) and dock (bottom) take, in PC pixels."""
    try:
        with open("/tmp/cloudpc-margins") as f:
            top, bottom = (int(v) for v in f.read().split()[:2])
            return max(0, top), max(0, bottom)
    except (OSError, ValueError):
        pass
    out = []
    for p in ("/general/margin_top", "/general/margin_bottom"):
        try:
            out.append(int(subprocess.run(["xfconf-query", "-c", "xfwm4", "-p", p], capture_output=True,
                                          text=True, timeout=5).stdout.strip() or 0))
        except (OSError, ValueError, subprocess.SubprocessError):
            out.append(0)
    return out[0], out[1]


def plan(win, screen, top, bottom):
    """What to do with one window: ("max",) / ("move", x, y) / None.

    win: dict with x, y, w, h (the client), frame (left, right, top, bottom), kind ("normal",
    "dialog", "other"), transient, states (set of names), fixed (cannot be resized).
    """
    sw, sh = screen
    if win["kind"] == "other":
        return None
    st = win["states"]
    if {"FULLSCREEN", "HIDDEN"} & st or {"MAXIMIZED_VERT", "MAXIMIZED_HORZ"} <= st:
        return None
    left, right, ftop, fbottom = win["frame"]
    fx, fy = win["x"] - left, win["y"] - ftop
    fw, fh = win["w"] + left + right, win["h"] + ftop + fbottom
    free_h = sh - top - bottom
    big = fw >= sw * BIG_WIDTH or fh > free_h
    if big and win["kind"] == "normal" and not win["transient"] and not win["fixed"]:
        return ("max",)
    # A dialog (or a window that should not be full size) taller or wider than the free space – a
    # file chooser on a phone's short screen: made to fit, or its Open / Cancel buttons are off-screen.
    if not win["fixed"] and (fh > free_h or fw > sw):
        w = min(fw, sw) - left - right
        h = min(fh, free_h) - ftop - fbottom
        return ("fit", (sw - min(fw, sw)) // 2, top, w, h)
    # Its title bar must be below the menu bar (and on the screen at all).
    if fy < top or fy > sh - max(ftop, 24):
        x = 0 if fw >= sw else min(max(fx, 0), sw - fw)
        return ("move", x, top)
    return None


def connect():
    """Xlib, the display, its root window, atom(name) and send(window, message, l0..l4)."""
    xlib = ctypes.cdll.LoadLibrary(ctypes.util.find_library("X11") or "libX11.so.6")
    xlib.XOpenDisplay.restype = ctypes.c_void_p
    xlib.XOpenDisplay.argtypes = [ctypes.c_char_p]
    xlib.XDefaultRootWindow.restype = ctypes.c_ulong
    xlib.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
    xlib.XInternAtom.restype = ctypes.c_ulong
    xlib.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
    xlib.XGetWindowProperty.argtypes = [
        ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_long, ctypes.c_long, ctypes.c_int,
        ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_int),
        ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_void_p)]
    xlib.XGetWindowAttributes.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(XWindowAttributes)]
    xlib.XTranslateCoordinates.argtypes = [
        ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_int, ctypes.c_int,
        ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_ulong)]
    xlib.XGetWMNormalHints.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(XSizeHints),
                                       ctypes.POINTER(ctypes.c_long)]
    xlib.XGetTransientForHint.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong)]
    xlib.XSendEvent.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_long,
                                ctypes.POINTER(XEvent)]
    xlib.XFree.argtypes = [ctypes.c_void_p]
    xlib.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
    xlib.XCloseDisplay.argtypes = [ctypes.c_void_p]
    xlib.XDisplayWidth.argtypes = [ctypes.c_void_p, ctypes.c_int]
    xlib.XDisplayHeight.argtypes = [ctypes.c_void_p, ctypes.c_int]
    # A window that closes meanwhile must not end this program (Xlib's default: exit).
    handler_type = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)
    xlib.on_error = handler_type(lambda d, e: 0)   # kept alive here: Xlib calls it later
    xlib.XSetErrorHandler.argtypes = [handler_type]
    xlib.XSetErrorHandler(xlib.on_error)

    d = xlib.XOpenDisplay(None)
    if not d:
        return None
    root = xlib.XDefaultRootWindow(d)
    atoms = {}

    def atom(name):
        if name not in atoms:
            atoms[name] = xlib.XInternAtom(d, name.encode(), 0)
        return atoms[name]

    def send(w, message, l):
        # A client message to the root window, "from a pager": the window manager obeys it.
        ev = XEvent()
        ev.xclient.type = CLIENT_MESSAGE
        ev.xclient.window = w
        ev.xclient.format = 32
        ev.xclient.message_type = atom(message)
        ev.xclient.l[:] = (list(l) + [0, 0, 0, 0, 0])[:5]
        xlib.XSendEvent(d, root, 0, SUBSTRUCTURE, ctypes.byref(ev))

    return xlib, d, root, atom, send


def window_id(raw):
    try:
        return int(raw, 0)
    except ValueError:
        return None


def act(what, ids):
    """--toggle-max / --close: the buttons of AirCursor's menu bar for the window in front."""
    x = connect()
    if not x:
        return 1
    xlib, d, root, atom, send = x
    for w in filter(None, map(window_id, ids)):
        if what == "--toggle-max":
            send(w, "_NET_WM_STATE", [2, atom("_NET_WM_STATE_MAXIMIZED_VERT"), atom("_NET_WM_STATE_MAXIMIZED_HORZ"), 2])
        else:
            send(w, "_NET_CLOSE_WINDOW", [0, 2])   # the app may still ask to save first
    xlib.XSync(d, 0)
    xlib.XCloseDisplay(d)
    return 0


def main(args):
    if not args or not os.environ.get("DISPLAY"):
        return 0
    if args[0] in ("--toggle-max", "--close"):
        return act(args[0], args[1:])
    x = connect()
    if not x:
        return 1
    xlib, d, root, atom, send = x

    def prop32(w, name, kind=0):
        typ, fmt, n, after, data = ctypes.c_ulong(), ctypes.c_int(), ctypes.c_ulong(), ctypes.c_ulong(), ctypes.c_void_p()
        if xlib.XGetWindowProperty(d, w, atom(name), 0, 64, 0, kind, ctypes.byref(typ), ctypes.byref(fmt),
                                   ctypes.byref(n), ctypes.byref(after), ctypes.byref(data)) != 0 or not data:
            return []
        try:
            if fmt.value != 32:
                return []
            return list(ctypes.cast(data, ctypes.POINTER(ctypes.c_ulong))[:n.value])
        finally:
            xlib.XFree(data)

    names = {}
    for s in ("FULLSCREEN", "HIDDEN", "MAXIMIZED_VERT", "MAXIMIZED_HORZ"):
        names[atom("_NET_WM_STATE_" + s)] = s
    t_normal, t_dialog = atom("_NET_WM_WINDOW_TYPE_NORMAL"), atom("_NET_WM_WINDOW_TYPE_DIALOG")
    screen = (xlib.XDisplayWidth(d, 0), xlib.XDisplayHeight(d, 0))
    top, bottom = margins()

    for w in filter(None, map(window_id, args)):
        a = XWindowAttributes()
        if not xlib.XGetWindowAttributes(d, w, ctypes.byref(a)) or a.map_state != IS_VIEWABLE or a.override_redirect:
            continue
        ax, ay, child = ctypes.c_int(), ctypes.c_int(), ctypes.c_ulong()
        if not xlib.XTranslateCoordinates(d, w, root, 0, 0, ctypes.byref(ax), ctypes.byref(ay), ctypes.byref(child)):
            continue
        types = prop32(w, "_NET_WM_WINDOW_TYPE")
        kind = "normal" if not types or types[0] == t_normal else "dialog" if types[0] == t_dialog else "other"
        frame = (prop32(w, "_NET_FRAME_EXTENTS") + [0, 0, 0, 0])[:4]
        tf = ctypes.c_ulong()
        transient = bool(xlib.XGetTransientForHint(d, w, ctypes.byref(tf))) and tf.value not in (0, root)
        hints, supplied = XSizeHints(), ctypes.c_long()
        fixed = False
        if xlib.XGetWMNormalHints(d, w, ctypes.byref(hints), ctypes.byref(supplied)):
            fixed = (hints.flags & P_MIN_SIZE and hints.flags & P_MAX_SIZE and hints.min_width == hints.max_width
                     and hints.min_height == hints.max_height)
        win = {"x": ax.value, "y": ay.value, "w": a.width, "h": a.height, "frame": tuple(int(v) for v in frame),
               "kind": kind, "transient": transient, "fixed": bool(fixed),
               "states": {names[s] for s in prop32(w, "_NET_WM_STATE") if s in names}}
        todo = plan(win, screen, top, bottom)
        if not todo:
            continue
        if todo[0] == "max":
            send(w, "_NET_WM_STATE", [1, atom("_NET_WM_STATE_MAXIMIZED_VERT"), atom("_NET_WM_STATE_MAXIMIZED_HORZ"), 2])
        elif todo[0] == "fit":
            # x and y of the frame (north-west gravity), width and height of the window.
            send(w, "_NET_MOVERESIZE_WINDOW", [1 | (0xF << 8) | (2 << 12), todo[1], todo[2], todo[3], todo[4]])
        else:
            # x and y of the frame (north-west gravity).
            send(w, "_NET_MOVERESIZE_WINDOW", [1 | (1 << 8) | (1 << 9) | (2 << 12), todo[1], todo[2]])
        print(hex(w), *todo)
    xlib.XSync(d, 0)
    xlib.XCloseDisplay(d)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
