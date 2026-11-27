# AirCursor Windows bug report

This report covers the Windows installer, sign-in, workspace, browser, Store, Files, remote input, hotspot, and AI workflows reviewed for 2.0 Preview 5. It records issues found in the Windows work to date and distinguishes fixes from device-dependent behavior. Windows upgrades and the packaged application are tested on GitHub Actions. The public download and verification logs are linked from the release page.

## Preview 5 findings

| ID | Severity | Reproduction in the prior build | Root cause and fix | Verification |
| --- | --- | --- | --- | --- |
| WIN-001 | High | Start Setup or Uninstall while AirCursor is still running. | Setup and the launcher did not coordinate writes to installed files. The launcher, installer and uninstaller now use named Windows mutexes to avoid replacing files during an active session. Setup also recognizes the previous version's workspace mutex. | Windows lifecycle test checks blocked operations, an actual Preview 4 upgrade, a clean start, and preserved user settings after uninstall. |
| WIN-002 | High | Start AirCursor with an invalid saved layout, or reopen a session that causes the problem immediately on sign-in. | Restore assumed the saved window layout had the expected type and reopened panels without a recovery path. Invalid layout records are ignored. The Start menu now has **AirCursor (Safe Mode)**, which uses software rendering, skips the previous windows and tabs, and does not overwrite the saved layout when it closes. | Real Qt tests exercise malformed layout data, Safe Mode with an existing password, and preserved tabs and windows. Windows checks the installed shortcut and arguments. |
| WIN-003 | Medium | Let a Chromium renderer terminate while its tab is open. | The browser did not handle the renderer termination event. Affected tabs now show **Reload tab** and **Open in default browser** while retaining their address. | Live Chromium test terminates only its own renderer and verifies recovery by reloading the page. |
| WIN-004 | Medium | Quit while the folder storing settings is temporarily unwritable. | A session write could interrupt shutdown before the phone receiver and timers were stopped. Shutdown now attempts to save state and proceeds with cleanup on a file-write error. | Error path reviewed and regression-tested with a simulated write failure. A failed save can still leave the last valid saved layout. |

## Earlier Windows fixes

| ID | Area | Symptom and cause | Resolution or evidence |
| --- | --- | --- | --- |
| WIN-005 | Browser startup | Restoring 15 saved tabs initialized all Chromium pages at once. | Preview 4 restored the selected tab first. A live test opened all 15 tabs. The median time to show the window was 244 ms before and 57 ms after in three local loopback comparisons. |
| WIN-006 | Browser interaction | Background navigations overwrote the address being typed. New-tab painting repeatedly scaled the wallpaper. Browser translucency added whole-window compositing. | Preview 4 isolated the current address field, cached wallpaper sizing, and used an opaque browser window. |
| WIN-007 | Browser downloads | Completed downloads lacked a reliable path to an approved Windows launch, and recent downloads disappeared after restart. | Added the Downloads toolbar, recent history for normal windows, file launch and folder access, Windows internet-zone handling, and installer verification. A live download test checks exact bytes, cancellation, and opening a completed file. |
| WIN-008 | Store | Search returned CLI text without selecting an install target. Launch could open generic search instead of the selected package. The old DaVinci Resolve package ID was gone. | Search rows select exact package IDs. Launch matches Windows Start identities or supported CLI tools. The editing catalog uses the maintained OpenShot package. The Windows lifecycle fixture installed, launched, upgraded and removed the real `fd` package. |
| WIN-009 | Files and editing | Cut/copy used a private clipboard. A simulated copy drop could report Move to the drag source. FFmpeg could return success for an empty clip. | Files interoperates with Windows clipboard formats, advertises Copy for copy drops, and rejects empty exports. Tests exercise ZIP path validation, file copy and move, output collisions, and real FFmpeg output. |
| WIN-010 | Wi-Fi and hotspot | Internet verification could accidentally use Ethernet or VPN after joining the phone Wi-Fi. Fixed retry timing slowed switching. | Internet verification binds to the selected WLAN interface. WLAN notifications, exponential retry, and transfer-aware switching are covered by policy tests and a native Winsock test. Physical DHCP and radio recovery timing still needs device coverage. |
| WIN-011 | Phone input | Discovery could keep an obsolete IP after switching networks. Subpixel motion caused redundant Windows input calls. Blocked input appeared as delivery. | Address changes trigger discovery refresh. Cursor movement retains fractions and skips redundant positions. Receiver tests cover pairing, re-advertisement, input rejection, and lock behavior. |
| WIN-012 | Dock and desktop | Hover changed Dock width during animation and regenerated the glass image every frame. System telemetry ran on the UI thread. Windows acrylic could cast duplicate shadows. | Stable Dock geometry, asynchronous glass sampling, background telemetry and corrected composition. The controlled hover test went from a median of 49 glass redraws to zero. |
| WIN-013 | Authentication | Password setup allowed short passwords. Phone codes required the existing password flow. Trust and idle locking needed stronger controls. | New passwords require at least 15 characters. The Windows build supports QR enrollment, recovery codes, alternative or two-step sign-in, Windows DPAPI tickets, and automatic locking. Tests cover code replay, expiry behavior, and Windows session lock. |
| WIN-014 | Packaging and native tools | The default Windows NSIS installation could lack an amd64 stub. The compiler failed on a mixed path. PowerShell could inherit an incompatible module path. | Packaging supports the available NSIS stub, uses a host-native glob and sets the matching Windows PowerShell module path. Installed-runtime tests execute on Windows Server 2025 and 2022. |
| WIN-015 | Air AI | The first model list sometimes selected no model even when it contained exactly one. Provider configuration and conversation management were limited. | A single discovered model is selected automatically. Compatible endpoints, scoped credentials, conversation management, and an actual local inference round trip were checked. |

## Remaining release risks

| Area | What still needs testing |
| --- | --- |
| Retail Windows 10/11 hardware | Run a fresh install and an upgrade on consumer hardware with SmartScreen and Defender enabled. The preview setup is unsigned. Check mixed-DPI monitors, sleep, GPU drivers, and Windows language differences. |
| Browser, games, editors and AI together | The 15-tab workload uses local test pages. Full games, video projects, multitasking with AI and protected media depend on hardware, drivers and publisher requirements. Optional Memory Saver can reclaim resources from eligible background tabs. |
| Real phone and network | Pair a physical Android phone over home Wi-Fi, hotspot, and USB tethering. Check firewall prompts, shared-network restrictions, mobile-radio dropouts, Wi-Fi adapter recovery and reconnects. |
| Vendor integrations | Check the user's games, online sign-in, codecs, camera and microphone, commercial editor installers, model servers and third-party licenses. |
| Profile corruption | Safe Mode handles the session restore loop when the settings file can still be read. A damaged settings document still needs manual inspection to preserve authentication and user data. |

## Reproduce and report a problem

- Open **AirCursor (Safe Mode)** from Start if a normal launch freezes during session restore. Return to **AirCursor** in Start for the normal workspace.
- Run `& "$env:LOCALAPPDATA\Programs\AirCursor\AirCursor.exe" --self-test` in PowerShell. Use `Get-Content "$env:TEMP\aircursor-selftest.txt"` to read the packaged smoke-test result.
- Include the release version, Windows build, GPU model, steps to reproduce, expected behavior, and what happened. A screenshot or a self-test result helps isolate the issue. Keep passwords, pairing codes, recovery codes and private URLs out of the report.

[Windows setup guide](../README.md) · [Functional audit](FUNCTIONAL-AUDIT.md) · [Performance and compatibility report](VERIFICATION.md)


## Windows 2.0 update

Windows 2.0 uses the signed-in Windows session for workspace access. The PIN and password controls described in the preview history above applied to the preview builds. Current session tests verify opening directly, pausing on Windows lock and suspend, and resuming after Windows becomes interactive.

## Windows 2.0.1 performance audit

| ID | Symptom | Root cause and fix |
| --- | --- | --- |
| WIN-016 | The desktop takes longer to appear with multiple saved apps. | The first Windows show event built each saved window inline, including hidden Stage Manager windows. Restore now shows the desktop first and creates windows one at a time. An open Spotlight or Overview pauses the queue. A workspace change replaces it, and a Windows lock stops it. |
| WIN-017 | Air Store's Discover view spends extra CPU on redraws. | The Blender banner performed a smooth image resize on each paint. It now reuses the fitted image until the banner size or display scaling changes. |
| WIN-018 | Rapid navigation generates repeated disk writes with browsing history enabled. | Each successful page load rewrote the settings document. Regular tab history and session state now share a debounced write. The browser and Spotlight can read pending entries immediately. Private tabs stay out of history, and disabling history clears pending and saved entries. |
| WIN-019 | Settings performs extra image work when running apps change. | App and Dock notifications regenerated the wallpaper thumbnail, and unrelated preferences reapplied the global Qt stylesheet. Cached thumbnails update when the wallpaper changes, and the global stylesheet changes with appearance. |
| WIN-020 | Reduced-effects profiles still render fallback window shadows in software. | Qt's fallback shadow effect stayed enabled regardless of profile. Performance, Battery Saver, Mobile Hotspot, and Safe Mode now disable the fallback effect. Platform-provided window chrome remains available. |

The Windows verification artifact includes `benchmarks-v2.0.0.jsonl` and `benchmarks.jsonl` for direct comparison on the same hosted runner. The browser workload restores 15 saved HTTP pages, loads each on selection, records settings writes, and measures switching among loaded tabs. Browser timings use a local server. Page complexity, hardware, drivers and internet conditions affect real-world workloads.
