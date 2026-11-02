# TS Station (TechOS Station) – TS1 for AirCursor PC

A console-style game launcher (PS5-like feel) for the owner's own AirCursor PC.
It is **not** part of the AirCursor app – it is installed separately inside one PC.

Install / update inside AirCursor PC (Terminal):

    wget -qO- https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/tsstation/install.sh | bash

- `server.py` – local server on 127.0.0.1 with a random key: finds games (.desktop with Categories=Game),
  art (the game's own AppStream screenshots, else its icon), starts games through the AirCursor launcher
  (game mode, FPS overlay), settings and play history in ~/.config/ts-station.
- `ui/` – the interface: Home (hero + shelf), Library, Search, Performance, Themes (TS Native, Glass,
  Classic, Minimal), Settings. Keyboard: ← → move, Enter play, F favourite, Q/E pages, Esc back.
- `ts-station` – launcher (Brave app window, full screen; Firefox kiosk if Brave is missing).
