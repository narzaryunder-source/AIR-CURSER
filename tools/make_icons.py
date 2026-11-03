#!/usr/bin/env python3
"""Generates AirCursor's line icons (24dp, 2px round strokes) as Android vector drawables
and an HTML preview. Run: python3 tools/make_icons.py [preview.html]"""
import os, sys

ICONS = {
    "menu": ["M4,6H20", "M4,12H20", "M4,18H20"],
    "keyboard": ["M4,6h16a2,2 0 0 1 2,2v8a2,2 0 0 1 -2,2h-16a2,2 0 0 1 -2,-2v-8a2,2 0 0 1 2,-2z",
                 "M6.5,10h0.01", "M10,10h0.01", "M13.5,10h0.01", "M17.5,10h0.01", "M8,14h8"],
    "hand": ["M8,13V5.5a1.5,1.5 0 0 1 3,0V11", "M11,10.5V4a1.5,1.5 0 0 1 3,0V11",
             "M14,10.5V5.5a1.5,1.5 0 0 1 3,0V12",
             "M17,8.5a1.5,1.5 0 0 1 3,0V14a7,7 0 0 1 -7,7h-1.5a6,6 0 0 1 -4.6,-2.2L3.4,14.6a1.6,1.6 0 0 1 2.4,-2.1L8,15"],
    "paste": ["M9,3h6v4h-6z", "M8,5H6a2,2 0 0 0 -2,2v12a2,2 0 0 0 2,2h12a2,2 0 0 0 2,-2V7a2,2 0 0 0 -2,-2h-2",
              "M8,12h8", "M8,16h5"],
    "image": ["M5,4h14a2,2 0 0 1 2,2v12a2,2 0 0 1 -2,2h-14a2,2 0 0 1 -2,-2v-12a2,2 0 0 1 2,-2z",
              "M10,9.5a1.5,1.5 0 1 1 -3,0a1.5,1.5 0 1 1 3,0z", "M21,15l-5,-5L5,20"],
    "folder": ["M4,6.5a2,2 0 0 1 2,-2h3.5l2,2.5H18a2,2 0 0 1 2,2V17a2,2 0 0 1 -2,2H6a2,2 0 0 1 -2,-2z"],
    "bolt": ["M13,2L4,14h7l-1,8l9,-12h-7z"],
    "speed": ["M4,17a8,8 0 1 1 16,0", "M12,17l4,-5", "M12,17h0.01"],
    "close": ["M6,6L18,18", "M18,6L6,18"],
    "desktop": ["M4,4h16a1,1 0 0 1 1,1v10a1,1 0 0 1 -1,1h-16a1,1 0 0 1 -1,-1v-10a1,1 0 0 1 1,-1z", "M9,20h6", "M12,16v4"],
    "download": ["M12,4v11", "M7,10l5,5l5,-5", "M5,20h14"],
    "stop": ["M7,6h10a1,1 0 0 1 1,1v10a1,1 0 0 1 -1,1h-10a1,1 0 0 1 -1,-1v-10a1,1 0 0 1 1,-1z"],
    "play": ["M7,4.5v15l12,-7.5z"],
    "help": ["M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z", "M9.2,9a3,3 0 0 1 5.8,1c0,2 -3,2.5 -3,4", "M12,17.5h0.01"],
    "camera": ["M5,7h2.5l1.5,-2h6l1.5,2H19a2,2 0 0 1 2,2v9a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2V9a2,2 0 0 1 2,-2z",
               "M15.5,13a3.5,3.5 0 1 1 -7,0a3.5,3.5 0 1 1 7,0z"],
    "sparkles": ["M12,3l1.8,4.7l4.7,1.8l-4.7,1.8L12,16l-1.8,-4.7L5.5,9.5l4.7,-1.8z",
                 "M19,15l0.8,1.7l1.7,0.8l-1.7,0.8l-0.8,1.7l-0.8,-1.7l-1.7,-0.8l1.7,-0.8z"],
    "refresh": ["M20,11a8,8 0 0 0 -14.5,-4.5L4,8", "M4,4v4h4", "M4,13a8,8 0 0 0 14.5,4.5L20,16", "M20,20v-4h-4"],
    "logout": ["M10,4H6a2,2 0 0 0 -2,2v12a2,2 0 0 0 2,2h4", "M16,16l4,-4l-4,-4", "M20,12H9"],
    "back": ["M19,12H5", "M11,6l-6,6l6,6"],
    "chevron": ["M9,6l6,6l-6,6"],
    "user": ["M15.5,8a3.5,3.5 0 1 1 -7,0a3.5,3.5 0 1 1 7,0z", "M5,20a7,7 0 0 1 14,0"],
    "plus": ["M12,5v14", "M5,12h14"],
    "lock": ["M6,11h12v9H6z", "M8.5,11V8a3.5,3.5 0 0 1 7,0v3"],
    "shield": ["M12,3l7,3v5c0,4.5 -3,8.3 -7,9.7c-4,-1.4 -7,-5.2 -7,-9.7V6z", "M9,12l2,2l4,-4"],
    "sliders": ["M4,7h10", "M18,7h2", "M4,17h4", "M12,17h8",
                "M18,7a2,2 0 1 1 -4,0a2,2 0 1 1 4,0z", "M12,17a2,2 0 1 1 -4,0a2,2 0 1 1 4,0z"],
    "info": ["M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z", "M12,11v6", "M12,7.5h0.01"],
    "check": ["M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z", "M8,12.5l2.5,2.5l5.5,-5.5"],
    "alert": ["M12,3l9.5,17h-19z", "M12,10v4.5", "M12,17.5h0.01"],
    "trash": ["M4,7h16", "M10,11v6", "M14,11v6", "M6,7l1,12a2,2 0 0 0 2,2h6a2,2 0 0 0 2,-2l1,-12", "M9,7V4h6v3"],
    "phone": ["M7,2h10a2,2 0 0 1 2,2v16a2,2 0 0 1 -2,2h-10a2,2 0 0 1 -2,-2v-16a2,2 0 0 1 2,-2z", "M11,18h2"],
    "mail": ["M4,5h16a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2h-16a2,2 0 0 1 -2,-2v-10a2,2 0 0 1 2,-2z", "M3,7l9,6l9,-6"],
    "store": ["M4,8l1.5,-4h13L20,8", "M4,8h16v2a2.7,2.7 0 0 1 -5.3,0a2.7,2.7 0 0 1 -5.4,0a2.7,2.7 0 0 1 -5.3,0z",
              "M5,12v8h14v-8", "M10,20v-5h4v5"],
    "terminal": ["M4,4h16a2,2 0 0 1 2,2v12a2,2 0 0 1 -2,2h-16a2,2 0 0 1 -2,-2v-12a2,2 0 0 1 2,-2z", "M7,9l3,3l-3,3", "M12,15h5"],
    "globe": ["M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z", "M2,12h20", "M12,2c2.8,2.8 4,6.2 4,10s-1.2,7.2 -4,10c-2.8,-2.8 -4,-6.2 -4,-10s1.2,-7.2 4,-10z"],
    "pause": ["M8,5v14", "M16,5v14"],
    "target": ["M12,2a10,10 0 1 1 0,20a10,10 0 1 1 0,-20z", "M12,7a5,5 0 1 1 0,10a5,5 0 1 1 0,-10z", "M12,11.5v1"],
    "crosshair": ["M12,3v4", "M12,17v4", "M3,12h4", "M17,12h4", "M12,7a5,5 0 1 1 0,10a5,5 0 1 1 0,-10z"],
    "up": ["M6,15l6,-6l6,6"],
    "down": ["M6,9l6,6l6,-6"],
    "left": ["M15,6l-6,6l6,6"],
    "right": ["M9,6l6,6l-6,6"],
    "upload": ["M12,20v-11", "M7,14l5,-5l5,5", "M5,4h14"],
    "cursor": ["M5,3l13,7l-6,1.5l-2.5,6z"],
    "moon": ["M20,14.5A8,8 0 1 1 9.5,4a6.5,6.5 0 0 0 10.5,10.5z"],
    "sun": ["M12,8a4,4 0 1 1 0,8a4,4 0 1 1 0,-8z", "M12,2v2", "M12,20v2", "M4.9,4.9l1.4,1.4", "M17.7,17.7l1.4,1.4",
            "M2,12h2", "M20,12h2", "M4.9,19.1l1.4,-1.4", "M17.7,6.3l1.4,-1.4"],
    "home": ["M4,10.5L12,4l8,6.5V19a1,1 0 0 1 -1,1h-4.5v-6h-5v6H5a1,1 0 0 1 -1,-1z"],
    "crown": ["M4,8l4,4l4,-6l4,6l4,-4l-1.5,10h-13z", "M6,21h12"],
    "book": ["M4,5a2,2 0 0 1 2,-2h13v16H6a2,2 0 0 0 -2,2z", "M4,19V5", "M8,7h7", "M8,11h5"],
    "star": ["M12,3.5l2.6,5.3l5.9,0.9l-4.3,4.1l1,5.8L12,16.9l-5.2,2.7l1,-5.8L3.5,9.7l5.9,-0.9z"],
    "history": ["M3.5,12a8.5,8.5 0 1 0 2.5,-6", "M3,4v4h4", "M12,8v4l3,2"],
    "mic": ["M12,3a3,3 0 0 1 3,3v6a3,3 0 0 1 -6,0v-6a3,3 0 0 1 3,-3z", "M5,11a7,7 0 0 0 14,0", "M12,18v3"],
    "maximize": ["M5,5h14v14h-14z"],
    "restore": ["M8,8h11v11h-11z", "M5,16V5h11"],
    "minimize": ["M5,12h14"],
    "windows": ["M4,5h7v6h-7z", "M13,5h7v6h-7z", "M4,13h7v6h-7z", "M13,13h7v6h-7z"],
    "bookmark": ["M6,3h12v18l-6,-4l-6,4z"],
    "search": ["M11,4a7,7 0 1 1 0,14a7,7 0 1 1 0,-14z", "M20,20l-4,-4"],
    "external": ["M14,4h6v6", "M20,4l-9,9", "M18,14v5a1,1 0 0 1 -1,1h-12a1,1 0 0 1 -1,-1v-12a1,1 0 0 1 1,-1h5"],
    "file": ["M14,3H7a2,2 0 0 0 -2,2v14a2,2 0 0 0 2,2h10a2,2 0 0 0 2,-2V8z", "M14,3v5h5"],
    "copy": ["M9,9h11v11h-11z", "M5,15V5a1,1 0 0 1 1,-1h9"],
    "eye": ["M2,12s3.5,-7 10,-7s10,7 10,7s-3.5,7 -10,7s-10,-7 -10,-7z", "M12,9a3,3 0 1 1 0,6a3,3 0 1 1 0,-6z"],
    "tabs": ["M3,8h18v12h-18z", "M3,8l2,-4h6l2,4"],
    "gamepad": ["M6,9h12a4,4 0 0 1 4,4v1a4,4 0 0 1 -7,2.6l-0.6,-0.6h-4.8l-0.6,0.6a4,4 0 0 1 -7,-2.6v-1a4,4 0 0 1 4,-4z",
                "M8,11v4", "M6,13h4", "M15.5,12.5h0.01", "M17.5,14.5h0.01"],
    "music": ["M9,18V5l11,-2v13", "M9,18a3,3 0 1 1 -6,0a3,3 0 1 1 6,0z", "M20,16a3,3 0 1 1 -6,0a3,3 0 1 1 6,0z"],
}

def xml(paths):
    body = "".join(
        f'\n    <path\n        android:pathData="{p}"\n        android:strokeColor="#FFFFFFFF"\n'
        f'        android:strokeWidth="2"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round" />'
        for p in paths)
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/make_icons.py -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="24"\n    android:viewportHeight="24">' + body + '\n</vector>\n')

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")
for name, paths in ICONS.items():
    open(os.path.join(root, f"ic_ui_{name}.xml"), "w").write(xml(paths))
if len(sys.argv) > 1:
    cells = "".join(
        f'<div class="c"><svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#1e293b" stroke-width="2" '
        f'stroke-linecap="round" stroke-linejoin="round">' + "".join(f'<path d="{p}"/>' for p in paths) +
        f'</svg><span>{name}</span></div>' for name, paths in ICONS.items())
    open(sys.argv[1], "w").write('<html><body style="margin:0;font:12px sans-serif;background:#fff">'
        '<style>.g{display:flex;flex-wrap:wrap;gap:10px;padding:12px}.c{width:86px;display:flex;flex-direction:column;'
        'align-items:center;gap:4px;border:1px solid #e2e8f0;border-radius:10px;padding:8px}</style>'
        f'<div class="g">{cells}</div></body></html>')
print(len(ICONS), "icons")
