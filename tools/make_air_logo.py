#!/usr/bin/env python3
"""Air browser logo: a round "browser" badge – a globe with a swirl of air around it.

Our own drawing (no other browser's marks). One geometry, two outputs:
  ubuntu/air-logo.svg                         (the PC: desktop, dock, apps grid)
  app/src/main/res/drawable/ic_air_logo.xml   (the app: Air's tabs, windows, menus)
Run: python3 tools/make_air_logo.py [variant]   (variants: a b c d; "sheet" renders all four)
"""
import math
import os
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..")
C = 54.0   # centre in a 108 × 108 box (the Android adaptive-icon grid)


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def ribbon(cx, cy, a0, sweep, r0, r1, w, tail=0.15, head=0.85, steps=72):
    """A swirl of air: a band along a spiral from angle a0 (degrees) over `sweep` degrees, radius
    r0 → r1, at most `w` wide, thin at the tail, round and full near the head."""
    outer, inner = [], []
    for i in range(steps + 1):
        t = i / steps
        a = math.radians(a0 + sweep * t)
        r = r0 + (r1 - r0) * t
        if t < head:
            k = (t / head) ** 0.9
        else:
            k = math.sqrt(max(0.0, 1 - ((t - head) / (1 - head)) ** 2))
        k = max(k, 0.0)
        hw = max(w * k / 2, 0.15 if t < 1 else 0)
        tw = tail
        if t < tw:
            hw *= t / tw
        outer.append((cx + (r + hw) * math.cos(a), cy + (r + hw) * math.sin(a)))
        inner.append((cx + (r - hw) * math.cos(a), cy + (r - hw) * math.sin(a)))
    pts = outer + inner[::-1]
    return "M" + " L".join("%s,%s" % (f(x), f(y)) for x, y in pts) + "Z"


def ellipse_path(cx, cy, rx, ry):
    return "M%s,%s A%s,%s 0 1,0 %s,%s A%s,%s 0 1,0 %s,%s Z" % (
        f(cx - rx), f(cy), f(rx), f(ry), f(cx + rx), f(cy), f(rx), f(ry), f(cx - rx), f(cy))


def ring_path(cx, cy, rx, ry, w):
    """An outline ellipse as a filled band (Android vectors draw fills best)."""
    return ellipse_path(cx, cy, rx + w / 2, ry + w / 2) + " " + ellipse_path(cx, cy, max(rx - w / 2, 0.1), max(ry - w / 2, 0.1))


# Each shape: (path, fill) where fill is a colour or ("lin", x1, y1, x2, y2, [(offset, colour)…]).
def variant(v):
    S = []
    if v == "a":   # gradient globe, soft meridians, one white air swirl around it
        S.append((ellipse_path(C, C, 40, 40), ("lin", 22, 16, 88, 94, [(0, "#38E1FF"), (0.5, "#2E6BFF"), (1, "#6B3CF2")])))
        for p in (ring_path(C, C, 15, 40, 2.2), ring_path(C, C, 40, 13, 2.2), ring_path(C, C, 31, 40, 1.6)):
            S.append((p, "#33FFFFFF"))
        S.append((ribbon(C, C, 150, 285, 30, 47, 11), ("lin", 10, 90, 98, 18, [(0, "#00FFFFFF"), (0.35, "#D9F6FF"), (1, "#FFFFFF")])))
    elif v == "b":  # air vortex: three swirls (cyan, blue, violet) turning into a bright core
        S.append((ellipse_path(C, C, 47, 47), ("lin", 16, 10, 92, 98, [(0, "#16204A"), (1, "#070A1C")])))
        cols = [("#3EE6FF", "#1FB7FF"), ("#3C7BFF", "#2A55F0"), ("#9B5CFF", "#6E3CF0")]
        for k, (c1, c2) in enumerate(cols):
            a0 = 90 + 120 * k
            S.append((ribbon(C, C, a0, 230, 42, 9, 17, tail=0.05, head=0.92),
                      ("lin", C + 40 * math.cos(math.radians(a0)), C + 40 * math.sin(math.radians(a0)), C, C, [(0, c1), (1, c2)])))
        S.append((ellipse_path(C, C, 8, 8), "#FFFFFF"))
    elif v == "c":  # globe with a tilted air ring (orbit) passing in front
        S.append((ribbon(C, C, 180, 180, 46, 46, 6, tail=0.2, head=0.8), "#7F5CFF"))   # back half of the orbit
        S.append((ellipse_path(C, C, 36, 36), ("lin", 26, 20, 84, 90, [(0, "#4BE3FF"), (0.55, "#2F78FF"), (1, "#3B33D6")])))
        for p in (ring_path(C, C, 13, 36, 2), ring_path(C, C, 36, 12, 2)):
            S.append((p, "#38FFFFFF"))
        S.append((ribbon(C, C, 0, 180, 46, 46, 8, tail=0.2, head=0.8),
                  ("lin", 8, 54, 100, 54, [(0, "#FFFFFF"), (1, "#BFEFFF")])))
    elif v == "e":  # deep globe wrapped by a bright cyan → white air swirl (in front at the bottom)
        S.append((ribbon(C, C, 200, 110, 44, 46, 8, tail=0.3, head=0.95), "#5A3BE8"))   # the part behind
        S.append((ellipse_path(C, C, 36, 36), ("lin", 26, 18, 84, 92, [(0, "#2F8CFF"), (0.55, "#2B4FE8"), (1, "#3A23B8")])))
        for p in (ring_path(C, C, 13, 36, 2.2), ring_path(C, C, 36, 12, 2.2), ring_path(C, C, 26, 36, 1.5)):
            S.append((p, "#40FFFFFF"))
        S.append((ribbon(C, C, 300, 260, 40, 47, 13, tail=0.25, head=0.85),
                  ("lin", 90, 30, 30, 100, [(0, "#2FD3FF"), (0.6, "#7FE9FF"), (1, "#FFFFFF")])))
    elif v == "f":  # the air vortex on a globe: three swirls over faint meridians
        S.append((ellipse_path(C, C, 47, 47), ("lin", 16, 10, 92, 98, [(0, "#1B2A63"), (1, "#0A0E26")])))
        for p in (ring_path(C, C, 17, 47, 1.6), ring_path(C, C, 47, 16, 1.6), ring_path(C, C, 34, 47, 1.2)):
            S.append((p, "#2EFFFFFF"))
        cols = [("#3EE6FF", "#1FB7FF"), ("#3C7BFF", "#2A55F0"), ("#9B5CFF", "#6E3CF0")]
        for k, (c1, c2) in enumerate(cols):
            a0 = 90 + 120 * k
            S.append((ribbon(C, C, a0, 230, 42, 9, 17, tail=0.05, head=0.92),
                      ("lin", C + 40 * math.cos(math.radians(a0)), C + 40 * math.sin(math.radians(a0)), C, C, [(0, c1), (1, c2)])))
        S.append((ellipse_path(C, C, 8, 8), "#FFFFFF"))
    else:           # d: gradient globe where the "meridians" are streams of wind
        S.append((ellipse_path(C, C, 44, 44), ("lin", 18, 14, 92, 96, [(0, "#2FD8FF"), (0.5, "#2D63FF"), (1, "#7A3CF2")])))
        S.append((ribbon(C, C, 200, 250, 34, 22, 9, tail=0.12, head=0.9), "#FFFFFF"))
        S.append((ribbon(C, C, 20, 250, 34, 22, 9, tail=0.12, head=0.9), "#CCFFFFFF"))
        S.append((ellipse_path(C, C, 9, 9), "#FFFFFF"))
    return S


def color_svg(c):
    if c.startswith("#") and len(c) == 9:   # #AARRGGBB → rgb + opacity
        return "#" + c[3:], int(c[1:3], 16) / 255
    return c, 1.0


def svg(shapes, size=108):
    defs, body = [], []
    for i, (p, fill) in enumerate(shapes):
        if isinstance(fill, tuple):
            _, x1, y1, x2, y2, stops = fill
            st = "".join('<stop offset="%s" stop-color="%s" stop-opacity="%s"/>' % ((o,) + tuple(map(str, color_svg(c))))
                         for o, c in stops)
            defs.append('<linearGradient id="g%d" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>'
                        % (i, f(x1), f(y1), f(x2), f(y2), st))
            body.append('<path d="%s" fill="url(#g%d)" fill-rule="evenodd"/>' % (p, i))
        else:
            col, op = color_svg(fill)
            body.append('<path d="%s" fill="%s" fill-opacity="%s" fill-rule="evenodd"/>' % (p, col, f(op)))
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 108 108">'
            '<defs>%s</defs>%s</svg>\n' % (size, size, "".join(defs), "".join(body)))


def android(shapes):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Air browser logo. Generated by tools/make_air_logo.py -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"',
           '    android:width="24dp" android:height="24dp" android:viewportWidth="108" android:viewportHeight="108">']
    for p, fill in shapes:
        p = p.replace(" ", " ")
        if isinstance(fill, tuple):
            _, x1, y1, x2, y2, stops = fill
            out.append('<path android:pathData="%s" android:fillType="evenOdd">' % p)
            out.append('  <aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="%s" android:startY="%s" '
                       'android:endX="%s" android:endY="%s">' % (f(x1), f(y1), f(x2), f(y2)))
            for o, c in stops:
                cc = c if len(c) == 9 else "#FF" + c[1:]
                out.append('    <item android:offset="%s" android:color="%s"/>' % (o, cc))
            out.append('  </gradient></aapt:attr>')
            out.append('</path>')
        else:
            cc = fill if len(fill) == 9 else "#FF" + fill[1:]
            out.append('<path android:pathData="%s" android:fillColor="%s" android:fillType="evenOdd"/>' % (p, cc))
    out.append('</vector>')
    return "\n".join(out) + "\n"


def main():
    v = sys.argv[1] if len(sys.argv) > 1 else "f"
    if v == "sheet":
        out = sys.argv[2]
        for k in "abcdef":
            with open(os.path.join(out, "air-%s.svg" % k), "w") as fh:
                fh.write(svg(variant(k), 512))
        return
    shapes = variant(v)
    with open(os.path.join(ROOT, "ubuntu", "air-logo.svg"), "w") as fh:
        fh.write(svg(shapes, 256))
    with open(os.path.join(ROOT, "app", "src", "main", "res", "drawable", "ic_air_logo.xml"), "w") as fh:
        fh.write(android(shapes))
    print("Air logo variant", v, "written")


if __name__ == "__main__":
    main()
