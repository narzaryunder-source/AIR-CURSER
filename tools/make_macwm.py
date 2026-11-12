#!/usr/bin/env python3
"""AirMac: AirCursor PC's own window frame for xfwm4 (a Mac-like look, our own drawing).

Dark title bar with rounded top corners, three coloured round buttons on the left (close, minimise,
maximise) and the title in the middle. No compositing is needed (cheap on a phone's CPU).
Writes ubuntu/airmac/xfwm4/*.png + themerc.  Run: python3 tools/make_macwm.py
"""
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "ubuntu", "airmac", "xfwm4")
H = 30          # title bar height
R = 9           # corner radius
B = 2           # side / bottom border
BTN = 28        # button image width
DOT = 13        # coloured circle diameter
SS = 4          # supersampling

BAR = {"active": (44, 44, 48), "inactive": (34, 34, 37)}
EDGE = {"active": (20, 20, 22), "inactive": (18, 18, 20)}
COLORS = {"close": (255, 95, 87), "hide": (254, 188, 46), "maximize": (40, 200, 64)}
GREY = (78, 78, 82)


def bar(state, w, h=H):
    return Image.new("RGBA", (w, h), BAR[state] + (255,))


def corner(state, right):
    big = Image.new("RGBA", (R * SS, H * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    d.rounded_rectangle([0 if right else 0, 0, (R * 2) * SS, H * SS * 2],
                        radius=R * SS, fill=BAR[state] + (255,))
    im = big.resize((R, H), Image.LANCZOS)
    if right:
        im = im.transpose(Image.FLIP_LEFT_RIGHT)
    # Make the mask hard (xfwm4 without a compositor uses a 1-bit shape): anti-aliased edge blended
    # onto the bar colour, outside fully transparent.
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            px[x, y] = BAR[state] + (255,) if a >= 110 else (0, 0, 0, 0)
    return im


def button(kind, state, look):
    big = Image.new("RGBA", (BTN * SS, H * SS), BAR[state] + (255,))
    d = ImageDraw.Draw(big)
    cx, cy, r = BTN * SS / 2, H * SS / 2, DOT * SS / 2
    col = COLORS[kind] if state == "active" or look in ("prelight", "pressed") else GREY
    if look == "pressed":
        col = tuple(int(c * 0.78) for c in col)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col + (255,))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=tuple(int(c * 0.8) for c in col) + (255,), width=SS)
    if look in ("prelight", "pressed"):
        g = (60, 20, 10, 230) if kind == "close" else (90, 60, 0, 230) if kind == "hide" else (0, 70, 10, 230)
        s, w = r * 0.45, int(SS * 1.6)
        if kind == "close":
            d.line([cx - s, cy - s, cx + s, cy + s], fill=g, width=w)
            d.line([cx - s, cy + s, cx + s, cy - s], fill=g, width=w)
        elif kind == "hide":
            d.line([cx - s * 1.1, cy, cx + s * 1.1, cy], fill=g, width=w)
        else:
            d.line([cx - s * 1.1, cy, cx + s * 1.1, cy], fill=g, width=w)
            d.line([cx, cy - s * 1.1, cx, cy + s * 1.1], fill=g, width=w)
    return big.resize((BTN, H), Image.LANCZOS)


def save(im, name):
    im.save(os.path.join(OUT, name + ".png"), optimize=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    for st in ("active", "inactive"):
        save(corner(st, False), "top-left-" + st)
        save(corner(st, True), "top-right-" + st)
        for i in range(1, 6):
            save(bar(st, 4), "title-%d-%s" % (i, st))
        edge = EDGE[st] + (255,)
        save(Image.new("RGBA", (B, 16), edge), "left-" + st)
        save(Image.new("RGBA", (B, 16), edge), "right-" + st)
        save(Image.new("RGBA", (16, B), edge), "bottom-" + st)
        save(Image.new("RGBA", (B, B), edge), "bottom-left-" + st)
        save(Image.new("RGBA", (B, B), edge), "bottom-right-" + st)
        for kind in COLORS:
            save(button(kind, st, "normal"), "%s-%s" % (kind, st))
            if kind == "maximize":
                save(button(kind, st, "normal"), "maximize-toggled-" + st)
        # Unused buttons: plain bar (menu, shade, stick are not in the layout).
        for kind in ("menu", "shade", "stick"):
            save(bar(st, 4), "%s-%s" % (kind, st))
    for kind in COLORS:
        for look in ("prelight", "pressed"):
            save(button(kind, "active", look), "%s-%s" % (kind, look))
            if kind == "maximize":
                save(button(kind, "active", look), "maximize-toggled-" + look)
    for kind in ("menu", "shade", "stick"):
        save(bar("active", 4), kind + "-pressed")
    with open(os.path.join(OUT, "themerc"), "w") as f:
        f.write("""# AirMac – AirCursor PC window frame
button_offset=6
button_spacing=0
show_app_icon=false
full_width_title=true
title_alignment=center
title_shadow_active=false
title_shadow_inactive=false
active_text_color=#e8e8ec
inactive_text_color=#8e8e93
title_vertical_offset_active=0
title_vertical_offset_inactive=0
shadow_delta_height=0
shadow_delta_width=0
shadow_delta_x=0
shadow_delta_y=-4
shadow_opacity=45
frame_border_top=0
""")
    print("written", len(os.listdir(OUT)), "files to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
