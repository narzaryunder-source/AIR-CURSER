# Design, performance and compatibility

## Design decisions

Apple's Tahoe desktop, Safari, Phone, Control Center and lock-screen screenshots were inspected visually. Frames from Apple's Spotlight product demonstration were reviewed alongside the material, icon, Finder, Spaces and System Settings guidance. The reference desktop uses a thin edge-to-edge menu bar, a compact icon-only Dock, restrained status information, generous exposed wallpaper and visibly separate window and content layers.

The first AirCursor pass had a large desktop clock, persistent telemetry card and labeled toolbar. The rebuild moves the clock and telemetry into the menu bar and on-demand controls. The lock screen owns the large time display. The Dock has original layered icons, proximity magnification, spring interpolation, running indicators and a separate utility group. Glass is restricted to navigation and transient controls. The material samples a Gaussian-filtered copy of the current wallpaper in screen coordinates, refracts the edge region and adds a directional highlight. Windows 11 managed windows additionally request a DWM system backdrop and rounded corners. DWM rendering was not verified on this machine.

| Approach considered | Trade-off for AirCursor |
| --- | --- |
| Tkinter | Small footprint and compatible backend, limited scene composition and animation. |
| Qt Quick + native Qt windows | Qt scene graph for the desktop, consistent DPI geometry, direct Python integration, native file models and real WebEngine profiles. Costs include a larger installer and Chromium maintenance. Selected for this build. |
| WebView2 frontend | Evergreen web engine and smaller application payload. A second bridge and Windows-specific hosting layer would be needed for input, native windows and file operations. |
| WinUI / Windows Composition | Strong integration with system materials and windowing. More native interop and a second UI/runtime stack to maintain alongside the existing Python services. |

The second render review found excessive blank command and transcript areas in Store and AI. Store now opens on a browsable collection with an original 3D-art image and real package-detail actions. AI now opens with provider selection and switches to the transcript after a real response. The final render set includes a browser new tab, the integrated engine displaying Python.org, right-aligned desktop shortcuts, the two material palettes and the authentication screen. No Apple screenshot, icon or font was redistributed.

References: [Tahoe screenshots and demonstrations](https://www.apple.com/newsroom/2025/06/macos-tahoe-26-makes-the-mac-more-capable-productive-and-intelligent-than-ever/), [Liquid Glass demonstration](https://developer.apple.com/videos/play/wwdc2025/219/), [Apple materials](https://developer.apple.com/design/human-interface-guidelines/materials), [app icons](https://developer.apple.com/design/human-interface-guidelines/app-icons), [Finder](https://support.apple.com/guide/mac-help/get-to-know-the-finder-mchlp2605/mac), [Dock](https://support.apple.com/guide/mac-help/use-the-dock-mh35859/mac), [Mission Control](https://support.apple.com/guide/mac-help/view-open-windows-spaces-mission-control-mh35798/mac), [Spotlight](https://support.apple.com/guide/mac-help/use-spotlight-mchlp1008/mac), [System Settings](https://support.apple.com/guide/mac-help/customize-your-mac-with-system-settings-mh15217/mac), [Qt scene graph](https://doc.qt.io/qt-6/qtquick-visualcanvas-scenegraph.html), [Windows backdrops](https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/system-backdrop-controller), [WebView2 processes](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/process-model), [WLAN notifications](https://learn.microsoft.com/en-us/windows/win32/api/wlanapi/nf-wlanapi-wlanregisternotification).

## What was measured

Baseline was repository commit `0c067b6`. Its 22 existing Windows-component tests passed on Linux with a real loopback listener, in 0.87 seconds. The initial network-denied sandbox run had 21 passes and one socket-permission failure. The expanded suite exercises loopback pairing, failed verification, missing hotspot, adaptive retry, active transfer deferral, idle return to regular Wi-Fi, archive paths, settings persistence, pointer sensitivity, GUI authentication and app entry points. The live Chromium test navigates via loopback HTTP, writes cookies and local storage, checks a fresh private profile, excludes private URLs from history and recovery, and verifies the bytes of a downloaded file.

| Measurement | Baseline | New build |
| --- | ---: | ---: |
| Simulated outage immediately after a healthy check, timer only | 29.99 s | 20.99 s |
| Same simulation with a WLAN notification 0.35 s after loss | unavailable | 6.35 s |
| Missing phone discovery attempts in simulated sustained outage | fixed cooldown | at 6, 21, 51, 111 s; retry delay caps at 240 s |
| Loopback movement delivery, 300 samples, median | 0.0105 ms | 0.0108 ms |
| Same run, 95th percentile | 0.0205 ms | 0.0185 ms |

Policy measurements execute the actual decision code with an injected clock and deterministic visibility/reachability. They exclude OS scan, association, DHCP, DNS and probe durations. Loopback timing ends at the recording controller. These results do not establish a speedup on a physical phone or measure Windows SendInput latency.

Five fresh Python processes on a 1366×768 Linux Qt software-rendered window gave a median 3.31 s from pre-import to first captured lock frame, 111.2 MiB resident at lock and 116.8 MiB after unlock. P95 intervals for a requested 16 ms GUI timer ranged from 16.17 to 16.28 ms. This is a timer responsiveness measurement. Display FPS, battery draw and Windows startup can differ. A separate browser smoke run loaded a private HTTP page with standard Chromium sandboxing and reported 285,446,144 resident bytes in the main process. Chromium child processes are excluded. Reproduce with `tools/benchmark.py startup`, `receiver` or `policy [--notification]`.

## Build and test results

The final feature suite executed 56 tests successfully in 4.20 seconds on Linux, including the live browser integration test. The x64 Windows installer was cross-built with NSIS using the pinned embeddable Python runtime and Windows wheels. The installer ran under Wine and created the per-user program, uninstaller and Start menu shortcut. The Wine 10 environment lacks the Windows ICU DLL required by Qt, so an installed Windows GUI pass remains outstanding. The local source self-test rendered a private Chromium page. The public website loaded in the integrated browser was Python.org.

The dedicated development branch is local. GitHub returned HTTP 403 on the branch push, so no native Windows Actions result or public preview release is recorded here. The existing website already links to the expected asset name, `AirCursor-Windows.exe`.

## Platform coverage and remaining checks

All gallery images are real application captures from Qt on a dedicated Linux display. The desktop and named feature views are captured at 1920×1080. The second desktop capture is 1366×768. Linux used software rendering for Qt Quick. Chromium rendered both a loopback fixture and the live official Python website. Windows hardware, phone radio, Windows 10/11 desktop composition, provider billing, DRM, Store publisher installers, microphones and cameras were not exercised here.

Before approving a production release, use the branch Actions workflow and two physical Windows PCs to check:

1. Clean installation, upgrade over an existing profile, normal launch, Windows ICU availability, private storage isolation, TLS, downloads/cancellation and uninstall. Check Windows SmartScreen and UAC in their normal configurations.
2. Pair the unchanged Android app. Hold and release modifiers, test multi-monitor negative coordinates and mixed DPI, disconnect/reconnect, and run a long input session.
3. Test WPA2/WPA3, two adapters, an absent phone, wrong key, captive portal, DNS outage, USB tethering, sleep/wake, an adapter reset and an active download. Measure detection, association, DHCP and internet verification separately. Host reachability is not adapter-bound.
4. Inspect glass and resize/shadow behavior on Windows 10 and Windows 11 under light/dark, high DPI and reduced-resource profiles. Benchmark with a GPU and check screen-reader/keyboard access.
5. Query winget, review the manifest, install/launch/update/remove an approved test package, and inspect a signed installer. Availability, size, dependencies and disk requirements depend on the publisher manifest.
6. Confirm Credential Manager, Windows logon-ticket expiry, device permission prompts and actual Ollama, LM Studio and OpenRouter responses using the tester's own credentials.

AirCursor workspaces organize its own windows. Native windows can be discovered and activated, and Windows retains its system-wide virtual desktops and shortcuts. App history, clipboard history and AI prompts can contain private data. Browser and chat histories are local user-profile files. Private browser tabs clear on close or explicit AirCursor lock. The optional trust ticket uses Windows DPAPI and the logon authentication ID. The existing mobile pairing protocol is plaintext TCP on the local link.

## Screenshots

[Desktop](workspace.png) · [1366×768 desktop](workspace-1366.png) · [Dock and menu bar](dock-and-menu.jpg) · [Dock detail](dock.png) · [Menu detail](menu-bar.png) · [Files](files.jpg) · [Browser new tab](browser.jpg) · [Live website](browser-loaded.jpg) · [Store](store.jpg) · [AI](ai.jpg) · [Control Center](control-center.jpg) · [Dark Control Center](control-center-dark.jpg) · [Spotlight](spotlight.jpg) · [Settings](settings.jpg) · [Authentication](authentication.jpg)
