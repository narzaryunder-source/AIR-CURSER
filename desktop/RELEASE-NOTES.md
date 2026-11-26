# AirCursor Windows 2.0 development preview

The Windows frontend now uses Qt Quick for the desktop and authentication, native Qt windows for productivity apps, and Qt WebEngine for web content. AirCursor's fjord wallpaper, app artwork and glass surfaces are original project assets. Android, Ubuntu and protocol v1 remain compatible.

- The phone hotspot manager verifies association and internet access through the selected native WLAN adapter, listens for WLAN changes, retries with backoff, reports captive-portal and DNS conditions, and defers automatic switching during active transfers.
- Lock, disconnect and receiver takeover release held inputs. A pending handshake belongs to its original listener. The receiver accepts on-link IPv4 peers, uses a six-digit pairing code and retains protocol rate limiting.
- A compact desktop menu bar and magnifying Dock provide access to Files, Browser, Store, AI, Spotlight, window overview and Windows integration. AirCursor-managed windows have traffic-light controls, resize handles, keyboard snapping and two workspaces.
- AirBoost supplies five application-level profiles, network traffic rates, process sampling and on-demand GPU information. Appearance, clipboard history, browser-history indexing and regular-Wi-Fi return are configurable.
- The installer bundles the Windows x64 runtime under the current user. Existing password hashes migrate on successful sign-in. Uninstallation preserves workspace data.

See the verification report for measurements, platform coverage and outstanding hardware checks. This preview has no production release tag.

## Productivity and security audit update

- Connected Store search results to package actions and registered Windows launches. Added an explicitly approved disposable-runner lifecycle check.
- Connected completed browser downloads to file opening and installer review. Added publisher hash comparison and protected the reviewed file from concurrent writes.
- Added system file clipboard formats, Open With, folder creation, photo export and FFmpeg video trimming.
- Added custom compatible AI endpoints with endpoint-specific credentials, conversation management and local memory information. Tested a real local inference round trip.
- Added indexed Spotlight file results, a navigable calendar widget, and restoration of window geometry, active workspace and folder tabs.
- Added an optional phone authenticator with one-time recovery and retained the original phone-input protocol. Control Center now counts Windows input rejections separately.
