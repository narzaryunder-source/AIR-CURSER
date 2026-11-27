# Windows 2.0.0

- Consolidates the five Windows previews into one release.
- Opens directly in the signed-in Windows session. The workspace and phone input pause when Windows locks or suspends.
- Adds an original illuminated cursor icon for the app, Start menu, taskbar, installer and workspace.
- Air Store includes 86 package entries and 18 curated PC-game links for Steam and Epic Games, including Marvel’s Spider-Man, Battlefield, Fortnite and open-world titles.
- Carries over saved settings and the existing Android download for the repository’s Latest release links.

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

## Windows authentication update

- New and changed master passwords require 15 characters and use 900,000 PBKDF2-SHA256 iterations. Existing hashes migrate after a successful sign-in.
- Google and Microsoft Authenticator enrollment uses a locally generated QR code and confirmation. The lock screen accepts an authenticator code as an alternative, and enrollments can also require both credentials. Eight one-time recovery codes can be rotated.
- Trusted Windows sign-in tickets expire after eight hours and use DPAPI. AirCursor locks and revokes trust on idle timeout, Windows session lock and sleep.

## Windows responsiveness update

- Dock icons animate over a stable glass background. Glass sampling runs asynchronously and reuses stable textures.
- System telemetry runs off the UI thread. Layout saves are coalesced, and changing a Windows window title no longer rebuilds the Dock when its entries stay the same.
- Phone discovery refreshes its address after Wi-Fi, hotspot and USB tethering changes. Pairing and remote input work over a reachable local network without internet access.
- The Windows receiver keeps fractional motion and skips input for unchanged cursor positions. Virtual-screen coordinates target the center of each pixel.

## Windows browser and catalog update

- Restored browser tabs load on selection. Air Browser starts Chromium on demand, pauses eligible idle pages, can unload them with the optional Memory Saver, and uses an opaque native browser window to reduce whole-window compositing.
- Saved tab selection and order persist. Background navigation preserves text entered in the address bar. Localhost addresses and explicit ports work in the address bar.
- Downloads are a toolbar action with recent completed downloads, progress, cancel and open controls. Installer launches use the Windows application association or signature review, and downloads preserve the Windows internet-zone marker.
- Air Store offers new game, launcher and creative entries, app artwork and Microsoft Store search through winget.
