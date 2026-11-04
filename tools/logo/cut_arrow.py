"""Cuts the arrow (with its silver edge and trails) out of the owner's logo sheet: the blue
variant, whose lower-right corner is covered by another tile there (only plain background is
hidden). The background is fitted and removed, edges are un-mixed, dust and swirl lines dropped.

Usage: python3 tools/logo/cut_arrow.py DIR   (DIR/src.png = the 2048 px logo sheet)
Writes DIR/arrow-rgba.png – copy it to tools/logo/arrow-blue.png, then run tools/make_logo.py.
"""
import sys
from collections import deque
import numpy as np
from PIL import Image, ImageFilter

S = sys.argv[1]
src = np.asarray(Image.open(S + "/src.png").convert("RGB")).astype(float) / 255
X0, Y0, X1, Y1 = 168, 140, 838, 782
tile = src[Y0:Y1, X0:X1]
H, W, _ = tile.shape
yy, xx = np.mgrid[0:H, 0:W]
gx, gy = xx + X0, yy + Y0

def mask_img(m):
    return Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8))

def arr(img):
    return np.asarray(img).astype(float) / 255

# The maroon tile covering the corner: rounded rectangle (left 510, top 506, corner radius 200), plus a margin.
m = 14
L, T, Rr = 510 - m, 506 - m, 200 + m
cx, cy = L + Rr, T + Rr
hidden = (gx >= L) & (gy >= T) & ~((gx < cx) & (gy < cy) & ((gx - cx) ** 2 + (gy - cy) ** 2 > Rr ** 2))

inset, R = 16, 64
inside = (xx >= inset) & (xx < W - inset) & (yy >= inset) & (yy < H - inset)
for ccx, ccy in ((inset + R, inset + R), (W - inset - R, inset + R), (inset + R, H - inset - R), (W - inset - R, H - inset - R)):
    cornerx = (xx < ccx) if ccx < W / 2 else (xx > ccx)
    cornery = (yy < ccy) if ccy < H / 2 else (yy > ccy)
    inside &= ~(cornerx & cornery & ((xx - ccx) ** 2 + (yy - ccy) ** 2 > R ** 2))

u = (xx / W) * 2 - 1
v = (yy / H) * 2 - 1
A = np.stack([np.ones_like(u), u, v, u * u, u * v, v * v], -1).reshape(-1, 6)
flat = tile.reshape(-1, 3)
use = (inside & ~hidden).reshape(-1)
for it in range(6):
    coef, *_ = np.linalg.lstsq(A[use], flat[use], rcond=None)
    bg = A @ coef
    resid = np.linalg.norm(flat - bg, axis=1)
    use = use & (resid < (0.07 if it < 2 else 0.04))
bg = np.clip(bg.reshape(H, W, 3), 0, 1)
d = np.linalg.norm(tile - bg, axis=2)

def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)

alpha = smooth(0.06, 0.20, d)
alpha[hidden | ~inside] = 0

# Big shapes only: label connected parts of the solid mask and keep the large ones.
solid = arr(mask_img(alpha > 0.4).filter(ImageFilter.MinFilter(3)).filter(ImageFilter.MaxFilter(3))) > 0.5
lab = np.zeros((H, W), np.int32)
sizes = [0]
n = 0
for y0, x0 in zip(*np.nonzero(solid)):
    if lab[y0, x0]:
        continue
    n += 1
    q = deque([(y0, x0)])
    lab[y0, x0] = n
    cnt = 0
    while q:
        y, x = q.popleft()
        cnt += 1
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if 0 <= ny < H and 0 <= nx < W and solid[ny, nx] and not lab[ny, nx]:
                lab[ny, nx] = n
                q.append((ny, nx))
    sizes.append(cnt)
sizes = np.array(sizes)
big = sizes >= 900
for k in np.nonzero(big)[0]:
    ys_, xs_ = np.nonzero(lab == k)
    print("part", k, sizes[k], "bbox", xs_.min(), ys_.min(), xs_.max(), ys_.max())
big = sizes >= 3000
big[0] = False
kept = big[lab]
print("parts:", n, "kept sizes:", sorted(sizes[big].tolist(), reverse=True))
keep = arr(mask_img(kept).filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(2.5)))
alpha = alpha * np.clip(keep * 1.3, 0, 1)

a3 = np.maximum(alpha, 1e-3)[..., None]
fg = np.clip(bg + (tile - bg) / a3, 0, 1)
fg[alpha < 0.01] = 0
Image.fromarray((np.dstack([fg, alpha]) * 255).round().astype(np.uint8), "RGBA").save(S + "/arrow-rgba.png")
navy = np.array([0.07, 0.10, 0.20])
prev = fg * alpha[..., None] + navy * (1 - alpha[..., None])
Image.fromarray((prev * 255).astype(np.uint8)).save(S + "/arrow-preview.png")
ys, xs = np.nonzero(alpha > 0.3)
print("arrow bbox:", xs.min(), ys.min(), xs.max(), ys.max())
