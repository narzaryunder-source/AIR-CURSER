# Design, performance and compatibility

## Design decisions

Apple's Tahoe desktop, Safari, Phone, Control Center and lock-screen screenshots were inspected visually. Frames from Apple's Spotlight product demonstration were reviewed alongside the material, icon, Finder, Spaces and System Settings guidance. The reference desktop uses a thin edge-to-edge menu bar, a compact icon-only Dock, restrained status information, generous exposed wallpaper and visibly separate window and content layers.

The first AirCursor pass had a large desktop clock, persistent telemetry card and labeled toolbar. The rebuild moves the clock and telemetry into the menu bar and on-demand controls. The lock screen owns the large time display. The Dock has original layered icons, proximity magnification, spring interpolation, running indicators and a separate utility group. Glass is restricted to navigation and transient controls. The material samples a Gaussian-filtered copy of the current wallpaper in screen coordinates, refracts the edge region and adds a directional highlight. Windows 11 managed windows additionally request a DWM system backdrop and rounded corners. The Windows Server 2025 render review found a duplicate child-control shadow. Corrected Windows captures are included below.

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

## Audit follow-up measurements

The starting revision for the functional audit was 647f35e. Its 56 tests ran in 5.10 seconds on this host. The audited revision executed **79 tests successfully in 6.34 seconds**, with four platform-specific checks skipped on Linux. These cover native registered applications and credential storage, a Winsock interface-bound HTTP request Windows Authenticode/file locking, and DPAPI trust plus Shell file operations. The real Chromium integration ran with its normal sandbox. The source self-test rendered a private page, wrote OK, and reported 279,195,648 bytes of main-process RSS.

| Measurement on this host | Audit run |
| --- | ---: |
| Policy decision after simulated outage, timer only | 20.99 s |
| Policy decision after simulated outage with WLAN notification | 6.35 s |
| Loopback receiver, 300 samples, median / P95 | 0.0058 / 0.0119 ms |
| Startup to rendered lock frame, median of three fresh processes with warm filesystem caches | 0.311 s |
| Main-process lock RSS / unlocked RSS, median | 114.9 / 117.5 MiB |
| GUI timer interval, P95 range across those runs | 16.22–16.33 ms |

These Linux startup figures use the current environment and cache state. Differences from the earlier host timings are not evidence of a Windows startup speedup. The policy times exclude adapter association, DHCP, DNS and HTTP duration. Interface-bound verification improves the meaning of a successful connection on a multi-adapter machine. Physical recovery time still requires a Windows radio.

The local FFmpeg export test encoded a 0.80 s MP4 from a two-second source and verified the output with FFprobe. Live AI model discovery retrieved 474 OpenRouter entries. A separate local inference run sent a prompt from the actual Air AI window to llama.cpp, displayed the answer and persisted the conversation. See [the running local model](ai-local.jpg) and the [feature-by-feature audit](FUNCTIONAL-AUDIT.md).

## First Qt Quick build and test results

The first Qt Quick build executed 56 tests successfully in 4.20 seconds on Linux, including the live browser integration test. The x64 Windows installer was cross-built with NSIS using the pinned embeddable Python runtime and Windows wheels. The installer ran under Wine and created the per-user program, uninstaller and Start menu shortcut. The Wine 10 environment lacks the Windows ICU DLL required by Qt, so an installed Windows GUI pass remains outstanding. The local source self-test rendered a private Chromium page. The public website loaded in the integrated browser was Python.org.

The dedicated branch was subsequently pushed after GitHub confirmed write access. Its native Windows Server 2025 test pass ran 81 tests with two skips. It exercised Credential Manager, Authenticode, locking the inspected file, same-logon DPAPI tickets, Windows Shell file moves, interface-bound Winsock HTTP, Qt and Chromium. Live winget returned a selectable sharkdp.fd result. In the explicitly approved disposable-runner check, winget verified the publisher manifest hash, installed fd 10.4.2, the binary printed its version, winget upgraded it to 10.5.0, the binary printed the new version, and removal was confirmed. The older run that supplied this lifecycle evidence later encountered a packaging failure. See [the lifecycle run](https://github.com/narzaryunder-source/AIR-CURSER/actions/runs/45866603580) and [current branch build results](https://github.com/narzaryunder-source/AIR-CURSER/actions/workflows/windows-workspace.yml?query=branch%3Awindows%2Faircursor-workspace). No production release has been published.

## Platform coverage and remaining checks

The original gallery was captured on a dedicated Linux display. The [Windows gallery](windows-gallery.jpg) contains real Windows Server 2025 captures. Its complete PNG/JPEG set is attached to native runs as the windows-rendering artifact. The desktop and named feature views are captured at 1920×1080. The second desktop capture is 1366×768. Linux used software rendering for Qt Quick. Chromium rendered both a loopback fixture and the live official Python website. Physical phone radio, retail Windows 10/11 display hardware, provider billing, DRM, commercial editor installers, microphones and cameras still need device testing.

Before approving a production release, use the branch Actions workflow and two physical Windows PCs to check:

1. Clean installation, upgrade over an existing profile, normal launch, Windows ICU availability, private storage isolation, TLS, downloads/cancellation and uninstall. Check Windows SmartScreen and UAC in their normal configurations.
2. Pair the unchanged Android app. Hold and release modifiers, test multi-monitor negative coordinates and mixed DPI, disconnect/reconnect, and run a long input session.
3. Test WPA2/WPA3, two adapters, an absent phone, wrong key, captive portal, DNS outage, USB tethering, sleep/wake, an adapter reset and an active download. Measure detection, association, DHCP and internet verification separately. Verify the new WLAN-bound probe while Ethernet or a VPN stays online.
4. Inspect glass and resize/shadow behavior on Windows 10 and Windows 11 under light/dark, high DPI and reduced-resource profiles. Benchmark with a GPU and check screen-reader/keyboard access.
5. Query winget, review the manifest, install/launch/update/remove an approved test package, and inspect a signed installer. Availability, size, dependencies and disk requirements depend on the publisher manifest.
6. Confirm Credential Manager, Windows logon-ticket expiry, device permission prompts and actual Ollama, LM Studio and OpenRouter responses using the tester's own credentials.

AirCursor workspaces organize its own windows. Native windows can be discovered and activated, and Windows retains its system-wide virtual desktops and shortcuts. App history, clipboard history and AI prompts can contain private data. Browser and chat histories are local user-profile files. Private browser tabs clear on close or explicit AirCursor lock. The optional trust ticket uses Windows DPAPI and the logon authentication ID. The existing mobile pairing protocol is plaintext TCP on the local link.

## Screenshots

[Desktop](workspace.png) · [1366×768 desktop](workspace-1366.png) · [Dock and menu bar](dock-and-menu.jpg) · [Dock detail](dock.png) · [Menu detail](menu-bar.png) · [Files](files.jpg) · [Browser new tab](browser.jpg) · [Live website](browser-loaded.jpg) · [Store](store.jpg) · [AI](ai.jpg) · [Control Center](control-center.jpg) · [Dark Control Center](control-center-dark.jpg) · [Spotlight](spotlight.jpg) · [Settings](settings.jpg) · [Authentication](authentication.jpg)
