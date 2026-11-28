import json
import sys
import subprocess
from bisect import bisect_right
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT, FPS = 1280, 800, 30
FONTS = Path("/usr/share/fonts/truetype/dejavu")
SITE = Path("desktop/assets")
INK, MUTED, MINT = "#eef8fd", "#b0c4d0", "#a4edda"


@lru_cache
def font(size, bold=False):
    return ImageFont.truetype(str(FONTS / (
        "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    )), size)


def text(draw, x, y, label, size, color=INK, bold=False, anchor="lt"):
    draw.text((x, y), label, font=font(size, bold), fill=color, anchor=anchor)


def template():
    canvas = Image.new("RGB", (WIDTH, HEIGHT))
    draw = ImageDraw.Draw(canvas)
    for y in range(HEIGHT):
        draw.line((0, y, WIDTH, y), fill=(12 - y // 230, 28 - y // 130, 42 - y // 98))
    logo = Image.open(SITE / "logo96.png").convert("RGBA")
    logo.thumbnail((45, 45), Image.Resampling.LANCZOS)
    canvas.paste(logo, (32, 16), logo)
    text(draw, 91, 15, "Air Browser", 29, bold=True)
    text(draw, 91, 48, "Live website test · pauses between sites trimmed", 17, MUTED)
    draw.rounded_rectangle((1010, 21, 1249, 53), radius=16, fill="#223d4c", outline="#395263")
    text(draw, 1129, 28, "2.3.0 RC 1   |   Windows", 16, MINT, anchor="mt")
    draw.rounded_rectangle((25, 72, 1254, 735), radius=11, fill="#091723", outline="#395263")
    return canvas


def footer(draw, at, click, pages):
    current = next((page for page in reversed(pages) if page["navigation_at"] <= at), None)
    if current is None:
        title = "AirCursor Dock"
        state = "Opening Air Browser" if at >= click else "Click to open Air Browser"
        time_label = ""
    else:
        completed = current["reported_at"]
        if current["status"] in ("Loaded", "Security check"):
            completed = min(completed, current["navigation_at"] + current["seconds"])
        wait = at < completed
        title = current["name"]
        if wait:
            state = "Loading"
            time_label = f"{at-current['navigation_at']:.1f} s"
        else:
            state = {"Loaded": "Page load finished", "Security check": "Site security check",
                     "Load failed": "Navigation failed", "Still loading": "Page still loading"
                     }[current["status"]]
            time_label = f"{current['seconds']:.1f} s" if current["status"] == "Loaded" else ""
    text(draw, 35, 756, title, 25, bold=True)
    title_end = int(draw.textlength(title, font(25, True))) + 57
    text(draw, title_end, 760, state, 21, MUTED)
    if time_label:
        text(draw, 1241, 753, time_label, 28, MINT, bold=True, anchor="rt")


def main():
    path, destination = map(Path, sys.argv[1:3])
    run = json.loads((path / "capture.json").read_text(encoding="utf-8"))
    contexts = run["frames"]
    labels = run["sites"]
    for page in labels:
        if "just a moment" in page["title"].lower():
            page["status"] = "Security check"
    stamps = [it["at"] for it in contexts]
    click = next(e["at"] for e in run["events"] if e["type"] == "dock_click")
    opened = next(e["at"] for e in run["events"] if e["type"] == "browser_visible")
    board = template()
    command = [
        "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pixel_format", "rgb24",
        "-video_size", f"{WIDTH}x{HEIGHT}", "-framerate", str(FPS), "-i", "-",
        "-an", "-c:v", "libx264", "-profile:v", "main", "-crf", "20", "-preset", "fast",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        "-metadata", "title=Air Browser 2.3.0 RC 1 - Real website performance on Windows",
        str(destination),
    ]
    with subprocess.Popen(command, stdin=subprocess.PIPE) as encoder:
        skips = []
        for previous, following in zip(labels,labels[1:]):
            completion = previous["navigation_at"] + previous["seconds"]
            skips.append((completion + 0.55, following["navigation_at"] - 1.1))
        last, source = None, None
        for index in range(round(run["duration"] * FPS)):
            at = index / FPS
            if any(lo < at < hi for lo, hi in skips):
                continue
            chosen = contexts[max(0, bisect_right(stamps, at) - 1)]["file"]
            if chosen != last:
                with Image.open(path / chosen) as captured:
                    source = captured.convert("RGB")
                last = chosen

            # Grow the recording around the browser window once it opens.
            # Frame selection follows original Windows capture timestamps.
            fraction = max(0, min(1, (at - opened - .3) / .5))
            zoom = 1 + .34 * fraction * fraction * (3 - 2 * fraction)
            w, h = source.size
            trim_x, trim_y = int(w * (1 - 1 / zoom) / 2), int(h * (1 - 1 / zoom) / 2)
            visual = source.crop((trim_x, trim_y, w - trim_x, h - trim_y))
            visual.thumbnail((1216, 648), Image.Resampling.LANCZOS)
            if visual.width < 1100:
                factor = min(1216 / visual.width, 648 / visual.height)
                visual = visual.resize(
                    (round(visual.width * factor), round(visual.height * factor)), Image.Resampling.LANCZOS
                )
            canvas = board.copy()
            canvas.paste(visual, ((WIDTH - visual.width) // 2, 78 + (648 - visual.height) // 2))
            footer(ImageDraw.Draw(canvas), at, click, labels)
            encoder.stdin.write(canvas.tobytes())
        encoder.stdin.close()
        if encoder.wait():
            raise RuntimeError("Video encoding failed")

    print(f"{run['duration']:.3f}s source, {len(contexts)} captured frames")
    for page in labels:
        print(page["name"], page["status"], page["seconds"], page["title"], "scrolled", page.get("scroll_y"))


if __name__ == "__main__":
    main()
