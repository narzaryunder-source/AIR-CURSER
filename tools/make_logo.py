#!/usr/bin/env python3
"""Builds the AirCursor arrow logo everywhere from tools/logo/arrow-blue.png (the arrow with its
silver edge and trails, cut out of the owner's own blue logo design by tools/logo/cut_arrow.py).

  AirCursor (the app)  – blue arrow on deep navy
  AirCursor PC         – the same arrow in violet on deep purple (two different home-screen icons)

Writes the Android adaptive icon layers (background, foreground, monochrome), the in-app logo
tiles, and the PC's logo / Start button (SVG with the picture inside). The Air browser keeps its
eagle (tools/make_eagle.py). Needs numpy and Pillow. Run from the repository root:
    python3 tools/make_logo.py [preview.png]
"""
import base64
import io
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
ARROW = Image.open(os.path.join(ROOT, "tools", "logo", "arrow-blue.png")).convert("RGBA")

# Background colours: (top-left, middle, bottom-right) and the glow behind the arrow.
THEMES = {
    "aircursor": {"bg": ((0x1D, 0x31, 0x63), (0x0F, 0x18, 0x38), (0x05, 0x08, 0x17)), "glow": (0x3C, 0xC8, 0xFF), "hue": 0.0,
                  "rim": (0x7F, 0xB8, 0xFF)},
    "pc": {"bg": ((0x3A, 0x22, 0x74), (0x1A, 0x10, 0x3E), (0x08, 0x05, 0x19)), "glow": (0xB0, 0x7C, 0xFF), "hue": 58.0,
           "rim": (0xC9, 0xA6, 0xFF)},
}


def arrow(theme):
    """The arrow, turned to the theme's colour (blue → violet for the PC). Silver stays silver."""
    a = np.asarray(ARROW).astype(float) / 255
    shift = THEMES[theme]["hue"]
    if shift:
        rgb = a[..., :3]
        mx, mn = rgb.max(-1), rgb.min(-1)
        sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
        # rotate the hue of coloured pixels around the grey axis (keeps brightness)
        k = np.full(3, 1 / np.sqrt(3))
        th = np.radians(shift) * np.clip((sat - 0.12) / 0.2, 0, 1)[..., None]
        cos, sin = np.cos(th), np.sin(th)
        cross = np.cross(np.broadcast_to(k, rgb.shape), rgb)
        dot = (rgb @ k)[..., None]
        rot = rgb * cos + cross * sin + k * dot * (1 - cos)
        a = np.dstack([np.clip(rot, 0, 1), a[..., 3]])
    return Image.fromarray((a * 255).round().astype(np.uint8), "RGBA")


def background(theme, size):
    c0, c1, c2 = (np.array(c, float) / 255 for c in THEMES[theme]["bg"])
    yy, xx = np.mgrid[0:size, 0:size] / (size - 1)
    t = (xx + yy) / 2   # diagonal: top-left → bottom-right
    col = np.where((t < 0.5)[..., None], c0 + (c1 - c0) * (t / 0.5)[..., None], c1 + (c2 - c1) * ((t - 0.5) / 0.5)[..., None])
    # soft glow behind the arrow head, and a gentle vignette
    g = np.array(THEMES[theme]["glow"], float) / 255
    r = np.hypot(xx - 0.58, yy - 0.42)
    glow = np.exp(-(r / 0.30) ** 2) * 0.30
    col = col * (1 - glow[..., None]) + g * glow[..., None]
    vig = np.clip(np.hypot(xx - 0.5, yy - 0.5) / 0.75, 0, 1) ** 2 * 0.25
    col = col * (1 - vig[..., None])
    return Image.fromarray((np.clip(col, 0, 1) * 255).round().astype(np.uint8), "RGB").convert("RGBA")


def placed(theme, size, span):
    """The arrow scaled so its bounding box spans [span] of [size], centred, on a clear layer."""
    a = arrow(theme)
    box = a.getchannel("A").point(lambda v: 255 if v > 60 else 0).getbbox()
    a = a.crop(box)
    k = span * size / max(a.width, a.height)
    a = a.resize((max(1, round(a.width * k)), max(1, round(a.height * k))), Image.LANCZOS)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.alpha_composite(a, ((size - a.width) // 2, (size - a.height) // 2))
    return layer


def shadow(layer, blur, opacity, dy):
    al = layer.getchannel("A").filter(ImageFilter.GaussianBlur(blur))
    sh = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    sh.putalpha(al.point(lambda v: int(v * opacity)))
    out = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    out.alpha_composite(sh, (0, dy))
    return out


def adaptive(theme, name):
    size = 432   # 108 dp at 4x
    bg = background(theme, size)
    bg.convert("RGB").save(os.path.join(RES, name + "_bg.png"), optimize=True)
    # Arrow inside the 66 dp safe circle (tip and trails along the diagonal).
    fg = placed(theme, size, 0.52)
    fl = shadow(fg, 6, 0.55, 5)
    fl.alpha_composite(fg)
    fl.save(os.path.join(RES, name + "_fg.png"), optimize=True)
    mono = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    mono.putalpha(fg.getchannel("A").point(lambda v: 255 if v > 110 else int(v * 2.2)))
    mono.save(os.path.join(RES, name + "_mono.png"), optimize=True)
    return bg, fl


def tile(theme, size):
    """Rounded tile like the original design: deep background, arrow, a thin glowing rim."""
    s = size * 4
    bg = background(theme, s)
    fg = placed(theme, s, 0.74)
    bg.alpha_composite(shadow(fg, s * 0.012, 0.6, round(s * 0.012)))
    bg.alpha_composite(fg)
    mask = Image.new("L", (s, s), 0)
    rad = round(s * 0.225)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), rad, fill=255)
    rim = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    rc = THEMES[theme]["rim"]
    w = max(2, round(s * 0.012))
    ImageDraw.Draw(rim).rounded_rectangle((w // 2, w // 2, s - 1 - w // 2, s - 1 - w // 2), rad - w // 2,
                                          outline=rc + (150,), width=w)
    bg.alpha_composite(rim)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def svg_with(img, size=256):
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    data = base64.b64encode(buf.getvalue()).decode()
    return ('<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
            'width="%d" height="%d" viewBox="0 0 %d %d"><image width="%d" height="%d" '
            'xlink:href="data:image/png;base64,%s"/></svg>\n' % (size, size, size, size, size, size, data))


def main():
    os.makedirs(RES, exist_ok=True)
    previews = []
    for theme, name in (("aircursor", "brand_aircursor"), ("pc", "brand_pc")):
        bg, fg = adaptive(theme, name)
        logo = tile(theme, 256)
        logo.save(os.path.join(RES, "logo_" + ("aircursor" if theme == "aircursor" else "pc") + ".png"), optimize=True)
        previews.append((bg, fg, logo))
    pc_tile = tile("pc", 256)
    with open(os.path.join(ROOT, "ubuntu", "aircursor-logo.svg"), "w") as f:
        f.write(svg_with(pc_tile))
    with open(os.path.join(ROOT, "ubuntu", "aircursor-start.svg"), "w") as f:
        f.write(svg_with(tile("pc", 128), 128))
    print("arrow logos written")
    if len(sys.argv) > 1:
        preview(previews, sys.argv[1])


def preview(items, path):
    """Launcher masks (circle, squircle, rounded square) at two sizes, plus the in-app tiles."""
    sheet = Image.new("RGBA", (1290, 560), (236, 238, 243, 255))
    d = ImageDraw.Draw(sheet)
    for row, (bg, fg, logo) in enumerate(items):
        full = bg.copy()
        full.alpha_composite(fg)
        y = 30 + row * 270
        x = 30
        for shape in ("circle", "squircle", "rounded"):
            for px in (192, 96):
                m = Image.new("L", (432, 432), 0)
                md = ImageDraw.Draw(m)
                inset = 72   # the visible 72 dp of 108 dp
                if shape == "circle":
                    md.ellipse((inset, inset, 432 - inset, 432 - inset), fill=255)
                elif shape == "squircle":
                    md.rounded_rectangle((inset, inset, 432 - inset, 432 - inset), 110, fill=255)
                else:
                    md.rounded_rectangle((inset, inset, 432 - inset, 432 - inset), 60, fill=255)
                ic = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
                ic.paste(full, (0, 0), m)
                ic = ic.crop((inset, inset, 432 - inset, 432 - inset)).resize((px, px), Image.LANCZOS)
                sheet.alpha_composite(ic, (x, y + (192 - px) // 2))
                x += px + 22
        sheet.alpha_composite(logo.resize((192, 192), Image.LANCZOS), (x + 10, y))
        d.text((x + 10, y + 200), "in-app logo / PC", fill=(60, 60, 70, 255))
    sheet.convert("RGB").save(path)


if __name__ == "__main__":
    main()
