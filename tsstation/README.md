# TS Station (TechOS Station) – the game console of AirCursor PC

A console-style game launcher for everyone's AirCursor PC. It ships with the AirCursor app: the
app writes it to /opt/ts-station (with a MANAGED marker, so it does not update itself) and pins it
in the dock. Change the files here, raise `VERSION`; the next app version brings it to every PC.

The old one-click package (`dist/ts-station.deb`, `install.sh`) stays for PCs set up before.

## Version 0.2

- Home like a console: big key art on the right, logo-style title, source / installed / profile chips,
  Play Game + details button, shelf tabs with counts (All games, Favorites, Recent), arrows,
  "View collection", cover tiles with name and source, hint bar at the bottom.
- Store: 27 free games that run on this PC (racing, sandbox, shooters, strategy, puzzle…), with
  pictures from Flathub, download size and an honest "runs on this phone" label (Smooth / Good / Heavy).
  One button installs (apt, with progress) or uninstalls.
- Details page for every game: art, about, play count, last played, profile tips, uninstall.
- Controller: connect it to the phone (Bluetooth / USB). The AirCursor PC viewer turns it into keys
  (needs AirCursor v38+), so it works here and in games:
  D-pad / stick = arrows, A = Enter, B = Esc, X = Space, Y = F, L1 / R1 = Q / E, L2 / R2 = Z / X,
  Start = Menu, Select = F1. A controller the browser sees itself works too (Gamepad API).
- Keyboard: WASD / arrows move, Enter activate, Esc back, F1 or Space details, O options, F favourite,
  Q / E pages.
- Themes: TS Native, Glass, Classic, Minimal (fastest).

## Files

- `server.py` – local server on 127.0.0.1 with a random key: games (.desktop with Categories=Game),
  art (AppStream screenshots, else Flathub, else the icon), Store installs, starts games through the
  AirCursor launcher (game mode, FPS overlay), settings and play history in ~/.config/ts-station.
- `ui/` – the interface.
- `ts-station` – launcher (Brave app window, full screen; Firefox kiosk if Brave is missing).
