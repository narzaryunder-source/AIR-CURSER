# AirCursor for Windows

A small Windows app that works with the AirCursor phone app:

- **Phone internet** – join your phone's hotspot in one click, and switch to it by itself when the
  computer's internet stops (the hotspot is switched on on the phone; Android does not let apps do it).
- **Phone as mouse & keyboard** – the phone app's "Use as keyboard & touchpad" drives this computer
  (same protocol as AirCursor PC; 6-digit pairing code).
- **Password** – the app opens only with your password (kept as a salted PBKDF2 hash).

Built by GitHub Actions (`.github/workflows/build.yml`, job `windows`) into `AirCursor-Windows.exe`
and attached to each release next to `AirCursor.apk`.

Run from source: `python -m aircursor_desktop` (Windows, Python 3.10+). Tests: `python -m pytest tests`.
