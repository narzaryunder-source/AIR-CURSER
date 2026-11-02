# TS Station (TechOS Station) – TS1 for AirCursor PC

A console-style game launcher (PS5-like feel) for the owner's own AirCursor PC.
It is **not** part of the AirCursor app – it is installed separately inside one PC.

Install inside AirCursor PC – no terminal: open this link in the PC's browser, then click the
downloaded file and choose Install:

    https://github.com/narzaryunder-source/AIR-CURSER/raw/main/tsstation/dist/ts-station.deb

Updates: TS Station → Settings → "Update to …" (it checks GitHub itself).
Terminal alternative: `wget -qO- https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/tsstation/install.sh | bash`

Maintainers: change the files, raise `VERSION`, run `./build-deb.sh`, commit `dist/ts-station.deb`.

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
