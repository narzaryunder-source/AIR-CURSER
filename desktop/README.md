# AirCursor for Windows

AirCursor opens a password-protected workspace alongside Windows Explorer. The Dock opens Files, Air Browser, Air Store, Air AI, Spotlight, the Windows command prompt, Settings, window overview and Downloads. Open **AirCursor → Enter desktop mode** or press **F11**. The menu bar offers **Exit desktop** in full screen. **Esc** dismisses a panel overlay or exits desktop mode when the workspace has focus.

## Install the development build

1. Download `AirCursor-Windows.exe` and its accompanying `SHA256SUMS.txt` from the development build. Open [Windows development builds](https://github.com/narzaryunder-source/AIR-CURSER/actions/workflows/windows-workspace.yml?query=branch%3Awindows%2Faircursor-workspace) and select a successful run. On its Summary screen, the installer is inside the **release-windows** artifact and the checksum is in **windows-verification**.
2. In PowerShell, run `Get-FileHash .\AirCursor-Windows.exe -Algorithm SHA256` and compare the digest. The preview installer is unsigned.
3. Close any running AirCursor instance. Run the installer in your normal Windows account. It installs the x64 application under `%LOCALAPPDATA%\Programs\AirCursor` and adds **AirCursor** to Start. It includes Python, Qt Quick, Qt WebEngine, the Chromium helper, mDNS discovery and the Windows receiver. Allow approximately 800 MiB of free space for the installed application, plus space for browser data and downloads.
4. Open AirCursor and enter your existing password, or create one on a new profile. Existing settings remain in `%APPDATA%\AirCursor\settings.json`. **Trust this Windows sign-in** allows re-entry for up to eight hours in the same Windows logon. Explicitly locking AirCursor revokes that ticket. Settings → Privacy also lets you revoke it.
5. Remove the application through Windows Settings → Apps. Uninstallation removes the installed program files. Your workspace data and saved Windows Wi-Fi profile remain available for reinstallation.

The project website’s Windows button resolves to the latest approved GitHub release asset. Development artifacts are separate from that production download.

## Pair the phone and test hotspot recovery

1. On the phone, enable the hotspot in Android Quick Settings. Open **Control Center → All controls → Hotspot → Find hotspots**, select the phone, enter its Wi-Fi password, and choose **Save and connect**. The Windows WLAN service stores the key in a current-user Wi-Fi profile. The SSID and adapter identifier are saved in AirCursor.
2. Wait for association with the specified SSID and an internet check. On native WLAN the content check sends traffic over the selected adapter. Control Center also reports whole-computer reachability; proxy-only Wi-Fi can remain unverified. Adapter diagnostics shows gateways, DNS servers and possible USB tether adapters. Newer Windows builds can require location access for Wi-Fi discovery.
3. Under **Connected phone**, turn the receiver on. On the existing Android app, select **Use as keyboard & touchpad**, choose the computer or enter its displayed IPv4 address and port, then enter the six-digit code displayed on Windows. Choose an appropriate local-network firewall permission if Windows prompts.
4. Test pointer movement, left/right clicks, two-finger scrolling and typing. Hold a modifier and lock AirCursor with **Ctrl+Shift+L**. The receiver should release held inputs immediately. Unlock and pair again if the connection timed out.
5. For failover, return to regular Wi-Fi, leave the configured phone hotspot available, and enable automatic switching. Disconnect regular internet and watch the status. Repeat with the phone out of range. Optional return to the previous Wi-Fi waits for two stable sightings and idle transfer activity. This applies to Wi-Fi that AirCursor itself displaced.
6. For USB tethering, use a data-capable USB cable and enable tethering in Android settings. Windows manages the tether interface. AirCursor's diagnostics identifies likely RNDIS/USB Ethernet adapters.

## Workspace controls

| Action | Control |
| --- | --- |
| Search apps, recent shortcuts, settings, optional browser history or a calculation | **Ctrl+Space** while AirCursor is active |
| Search Windows-indexed files | Type a query and choose **Search Windows for…** |
| Pin an installed shortcut | Right-click the shortcut result in Spotlight |
| Reorder desktop shortcuts | Drag vertically in the desktop shortcut column |
| Add or remove a shortcut | Desktop or shortcut context menu |
| Switch managed workspaces | **Ctrl+1** / **Ctrl+2** from the desktop, or the Window menu |
| Snap a managed window | **Ctrl+Alt+Left** / **Ctrl+Alt+Right** |
| Show open windows | Overview in the Dock. Native window activation follows Windows foreground rules. |
| View notifications and clipboard | Menu-bar clock or AirCursor menu. Clipboard history is opt-in and clears on lock. |
| Change theme, wallpaper or privacy | Settings in the Dock |
| Adjust effects, polling and telemetry cadence | Control Center → All controls → AirBoost |

### Browser, files, store and AI

- **Air Browser** opens locally even when disconnected. Type an address or search term to start Qt WebEngine. Its menu includes private tabs, downloads with progress and cancellation, bookmarks, history, developer tools and an external-browser fallback. Private tabs use an off-the-record profile. Browser history for search is opt-in. Website permission requests identify the origin. Site-specific authentication, DRM and codec support depend on the engine and the provider.
- **Files** browses real folders in tabs. Use the sidebar for known locations, double-click to open, and use **Actions** or the context menu for preview, copy, cut, paste, rename, ZIP and Recycle Bin operations. Copy/move use the Windows shell for permissions and collision handling. The filter searches the current folder. **Open in Explorer** opens Windows' full shell UI. Image and PDF thumbnails are generated on demand.
- **Air Store** uses Microsoft App Installer's `winget.exe` and the winget community source. Open a category, select a package and request **Package details**. Search accepts other names or package IDs. Install, update and uninstall require explicit approval. Source output supplies available metadata and exit status. Some publishers or architectures may have no matching manifest. The curated catalog does not guarantee availability. **Open downloaded installer** checks Windows Authenticode status and computes SHA-256 before offering to launch a local EXE/MSI.
- **Air AI** opens the official ChatGPT, Claude, Gemini and Perplexity sites in Air Browser. For API chat, start Ollama on port 11434 or LM Studio on 1234 and choose **Find models**. OpenRouter uses its documented HTTPS API and an optional key saved in Windows Credential Manager. Zero-token-price filtering uses returned prompt and completion prices. Saved conversations are ordinary user-profile data. Model-server memory and GPU requirements vary by model and context size. **Open terminal** starts the user's Windows command prompt for installed CLIs.

## Run, test or reproduce the installer

Use a supported x64 Windows 10/11 installation with the Windows ICU components available to Qt. Python 3.13 is used for the packaged runtime.

```powershell
cd desktop
py -3.13 -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements-build.txt
.\.venv\Scripts\python -m aircursor_desktop
$env:AIRCURSOR_TEST_WEBENGINE = "1"
.\.venv\Scripts\python -m pytest -q tests
.\.venv\Scripts\python run.py --self-test
Get-Content "$env:TEMP\aircursor-selftest.txt"
.\.venv\Scripts\python packaging/build.py --nsis "${env:ProgramFiles(x86)}\NSIS\makensis.exe"
```

Use NSIS 3.11 or newer with the amd64 Unicode target. The builder verifies the embeddable Python ZIP and every Windows wheel against recorded SHA-256 digests. It produces `dist/AirCursor-Windows.exe` and `dist/SHA256SUMS.txt`. To test the installed build, run `& "$env:LOCALAPPDATA\Programs\AirCursor\AirCursor.exe" --self-test` and check the log. The test uses temporary settings and a loopback web fixture.

The branch workflow builds on a Windows Server runner, installs the generated file silently, and runs the installed self-test before uploading the artifact. Screenshots can be reproduced on a dedicated graphical desktop with `python tools/capture.py --output report`. This captures the entire display. Close unrelated applications first.

[Release notes](RELEASE-NOTES.md) · [Design and verification report](report/VERIFICATION.md)

## Everyday workflows in the audited preview

- **Install and launch software.** Search Air Store, select a full package ID, review Package details, and choose Install. Accept publisher and source agreements only after reviewing them. Use Launch selected app to resolve its registered Start entry. Open a file in its default program or choose Files → Actions → Open with.
- **Open a downloaded installer.** Choose Air Browser → ⋯ → Downloads → Open file. Review its Windows signature and SHA-256. You can paste the publisher's digest before choosing Run installer.
- **Edit media.** Right-click a picture in Files and choose Edit photo to rotate or resize to a new PNG. For a video, install FFmpeg, then choose Trim video, specify start/end seconds, and choose a new MP4 name. Existing output files are protected.
- **Connect an AI service.** Start your local model server or enter an HTTPS provider URL under Configure endpoint. Choose Provider key if the endpoint requires credentials, Find models, select a model and Send. Windows Credential Manager stores API keys. Separate conversations can be renamed or deleted.
- **Get a six-digit sign-in code on your phone.** In Settings → Privacy → Set up phone authenticator, verify your password and enter the setup key into your phone's authenticator. Record the one-time recovery code and confirm a generated code. At the next password sign-in, enter a fresh six-digit code. This is separate from the remote-input pairing code shown in Control Center.
- **Restore your work.** Enable Restore open panels at sign-in. AirCursor remembers your active workspace, managed window geometry, file tabs and views, and regular browser URLs. The calendar widget can be enabled from Settings → Workspace or the desktop context menu.

See [the feature audit](report/FUNCTIONAL-AUDIT.md), [measurements and native checks](report/VERIFICATION.md) and [the local AI session](report/ai-local.jpg).
