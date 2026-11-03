# AirCursor eagle: low-poly bald-eagle head, beak to the left (original design).
SILHOUETTE = [(42,42),(47,33),(58,27),(72,28),(82,37),(86,50),(84,64),(80,75),(78,84),(72,79),(66,88),(60,82),
              (55,86),(52,78),(47,63),(46,56)]
FACETS = [
    ("#FFFFFF", [(42,42),(47,33),(56,40)]),
    ("#F1F3FF", [(47,33),(58,27),(56,40)]),
    ("#E2E7FF", [(58,27),(72,28),(56,40)]),
    ("#EAEDFF", [(72,28),(68,41),(56,40)]),
    ("#CFD7FB", [(72,28),(82,37),(68,41)]),
    ("#BAC5F4", [(82,37),(86,50),(68,41)]),
    ("#C7D0F8", [(68,41),(86,50),(73,55)]),
    ("#F6F7FF", [(56,40),(68,41),(60,52)]),
    ("#DDE3FE", [(68,41),(73,55),(60,52)]),
    ("#FFFFFF", [(42,42),(56,40),(46,56)]),
    ("#F2F4FF", [(46,56),(56,40),(60,52)]),
    ("#FFFFFF", [(46,56),(60,52),(47,63)]),
    ("#AEB9F0", [(86,50),(84,64),(73,55)]),
    ("#C1CAF6", [(73,55),(84,64),(70,66)]),
    ("#D4DBFC", [(60,52),(73,55),(70,66)]),
    ("#E7EBFF", [(47,63),(60,52),(57,68)]),
    ("#CCD4FA", [(57,68),(60,52),(70,66)]),
    ("#A3AFEC", [(84,64),(80,75),(70,66)]),
    ("#B6C0F3", [(70,66),(80,75),(68,77)]),
    ("#C4CCF7", [(57,68),(70,66),(68,77)]),
    ("#D2D9FB", [(47,63),(57,68),(52,78)]),
    ("#9EAAE9", [(80,75),(78,84),(72,79)]),
    ("#A9B4EE", [(72,79),(80,75),(68,77)]),
    ("#B0BBF1", [(68,77),(72,79),(66,88)]),
    ("#BAC4F4", [(57,68),(68,77),(60,82)]),
    ("#C3CCF7", [(60,82),(68,77),(66,88)]),
    ("#C9D1F8", [(52,78),(57,68),(60,82)]),
    ("#B7C1F3", [(52,78),(60,82),(55,86)]),
    # beak (gold), hooked tip
    ("#FFD873", [(42,42),(44,50),(30,45)]),
    ("#F7BE45", [(30,45),(44,50),(23,49)]),
    ("#EBA035", [(23,49),(44,50),(27,53)]),
    ("#D9852A", [(23,49),(27,53),(18,58)]),
    ("#B86A17", [(18,58),(27,53),(22,57)]),
    ("#CF7D22", [(27,53),(44,50),(46,56)]),
    ("#8E4E12", [(27,53),(46,56),(28,55)]),
    ("#E3962E", [(28,55),(46,56),(36,61)]),
    ("#B5661A", [(36,61),(46,56),(47,62)]),
    # brow and eye
    ("#2B2770", [(41,41.5),(59,38.5),(51,44.5)]),
    ("#F5B83D", [(47.8,45.3),(55.8,43.6),(52.3,49.2)]),
    ("#0E0D2E", [(50.4,45.3),(53.8,44.6),(52.2,47.9)]),
    ("#FFFFFF", [(50.9,45.4),(52.0,45.15),(51.4,46.0)]),
]

def shapes(scale=1.0, dx=2.0, dy=-3.5):
    def t(p):
        x, y = p[0] + dx, p[1] + dy
        return (54 + (x - 54) * scale, 54 + (y - 54) * scale)
    out = [("#D6DCFD", [t(p) for p in SILHOUETTE])]
    out += [(c, [t(p) for p in pts]) for c, pts in FACETS]
    return out

def svg(size=512, scale=1.0, bg=True, rounded=True, mono=None):
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">']
    if bg:
        parts.append('<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">'
                     '<stop offset="0" stop-color="#23287F"/><stop offset="0.55" stop-color="#4A33C4"/>'
                     '<stop offset="1" stop-color="#8A3DE4"/></linearGradient>'
                     '<radialGradient id="glow" cx="0.3" cy="0.28" r="0.62"><stop offset="0" stop-color="#9DB3FF" stop-opacity="0.38"/>'
                     '<stop offset="1" stop-color="#9DB3FF" stop-opacity="0"/></radialGradient></defs>')
        r = ' rx="24"' if rounded else ""
        parts.append(f'<rect width="108" height="108"{r} fill="url(#bg)"/><rect width="108" height="108"{r} fill="url(#glow)"/>')
    for color, pts in shapes(scale):
        c = mono or color
        d = " ".join(f"{x:.2f},{y:.2f}" for x, y in pts)
        parts.append(f'<polygon points="{d}" fill="{c}" stroke="{c}" stroke-width="0.35" stroke-linejoin="round"/>')
    parts.append("</svg>")
    return "".join(parts)

if __name__ == "__main__":
    open("eagle.svg", "w").write(svg())
    open("eagle-small.svg", "w").write(svg(size=96))
