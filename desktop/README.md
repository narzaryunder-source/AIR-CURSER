# AirCursor for Windows

AirCursor opens full screen in your signed-in Windows session. Choose **Exit desktop** in the menu bar or press **F11** to switch to a window. **Alt+Tab** switches to Windows apps. To start in a window on future launches, clear **Settings → Workspace → Open AirCursor full screen**. The Dock opens Files, Air Browser, Air Store, Air AI, Spotlight, Terminal, Settings, Overview and Downloads.

## Install AirCursor for Windows

1. Download `AirCursor-Windows.exe` and `SHA256SUMS.txt` from the [latest stable release](https://github.com/narzaryunder-source/AIR-CURSER/releases/latest).
2. In PowerShell, run `Get-FileHash .\AirCursor-Windows.exe -Algorithm SHA256` and compare the digest. The installer is unsigned.
3. Close any running AirCursor instance. Run the installer in your normal Windows account. It installs the x64 application under `%LOCALAPPDATA%\Programs\AirCursor` and adds **AirCursor** to Start. It includes Python, Qt Quick, Qt WebEngine, the Chromium helper, mDNS discovery and the Windows receiver. Allow approximately 600 MiB of free space for the installed application, plus space for browser data, downloads and any optional AI runtime and model.
4. Open **AirCursor** from Start to go directly to the workspace. Upgrades keep the settings in `%APPDATA%\AirCursor\settings.json`.
5. Remove the application through Windows Settings → Apps. Uninstallation removes the installed program files. Your workspace data and saved Windows Wi-Fi profile remain available for reinstallation.

## Connect with or without a hotspot

The Windows workspace runs on its own. For phone keyboard and touchpad controls, turn on the receiver in **Settings → Connected phone** and open **Use as keyboard & touchpad** in the Android app. The phone needs a local network path to the PC, and pairing works without internet access.

- **Regular Wi-Fi.** Connect the phone and PC to the same network. A PC connected by Ethernet to the same router can also work. Guest networks sometimes block device-to-device traffic.
- **Phone hotspot.** Connect the PC to the hotspot. You can connect from Windows Wi-Fi settings or save it in AirCursor for automatic switching. The phone can also share its mobile internet over this connection.
- **USB tethering.** Connect a data-capable USB cable and enable USB tethering on the phone. Use the tether adapter’s address from **Connected phone** if automatic discovery is unavailable.

Select the PC in the phone app or enter the IPv4 address and port shown in **Connected phone**, then enter the displayed pairing code. Give discovery a few seconds after switching networks. Without a phone connection, use your Windows keyboard and mouse. Browsing and online services use the PC’s current internet connection.

## Pair the phone and test hotspot recovery

1. On the phone, enable the hotspot in Android Quick Settings. Open **Settings → Hotspot → Find hotspots**, select the phone, enter its Wi-Fi password, and choose **Save and connect**. The Windows WLAN service stores the key in a current-user Wi-Fi profile. The SSID and adapter identifier are saved in AirCursor.
2. Wait for association with the specified SSID and an internet check. On native WLAN the content check sends traffic over the selected adapter. Settings reports whole-computer reachability. Proxy-only Wi-Fi can remain unverified. Adapter diagnostics shows gateways, DNS servers and possible USB tether adapters. Newer Windows builds can require location access for Wi-Fi discovery.
3. Under **Connected phone**, turn the receiver on. On the existing Android app, select **Use as keyboard & touchpad**, choose the computer or enter its displayed IPv4 address and port, then enter the six-digit code displayed on Windows. Choose an appropriate local-network firewall permission if Windows prompts.
4. Test pointer movement, left/right clicks, two-finger scrolling and typing. Hold a modifier and lock Windows with **Windows + L**. The receiver should release held inputs immediately. Unlock Windows to resume AirCursor.
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
| Adjust effects, polling and telemetry cadence | Settings → Performance |

### Browser, files, store and AI

- **Air Browser** opens locally even when disconnected. Type an address or search term to start Qt WebEngine. Its menu includes private tabs, downloads with progress and cancellation, bookmarks, history, developer tools and an external-browser fallback. Private tabs use an off-the-record profile. Browser history for search is opt-in. Website permission requests identify the origin. Site-specific authentication, DRM and codec support depend on the engine and the provider.
- **Files** browses real folders in tabs. Use the sidebar for known locations, double-click to open, and use **Actions** or the context menu for preview, copy, cut, paste, rename, ZIP and Recycle Bin operations. Copy/move use the Windows shell for permissions and collision handling. The filter searches the current folder. **Open in Explorer** opens Windows' full shell UI. Image and PDF thumbnails are generated on demand.
- **Air Store** uses Microsoft App Installer's `winget.exe` and the winget community source. Open a category, select a package and request **Package details**. Search accepts other names or package IDs. Install, update and uninstall require explicit approval. Source output supplies available metadata and exit status. Some publishers or architectures may have no matching manifest. The curated catalog does not guarantee availability. **Open downloaded installer** checks Windows Authenticode status and computes SHA-256 before offering to launch a local EXE/MSI.
- **Air AI** opens the official ChatGPT, Claude, Gemini and Perplexity sites in Air Browser. For API chat, start Ollama on port 11434 or LM Studio on 1234 and choose **Find models**. OpenRouter uses its documented HTTPS API and an optional key saved in Windows Credential Manager. Zero-token-price filtering uses returned prompt and completion prices. Saved conversations are ordinary user-profile data. Model-server memory and GPU requirements vary by model and context size. **Open terminal** starts the user's Windows command prompt for installed CLIs.

### Run a small AI task offline

1. Install and start **Ollama** from Air Store or from the official Windows download. [Ollama for Windows](https://ollama.com/download/windows)
2. Open **Air AI**, select **Ollama**, and enable **Lightweight local chat**. Choose **Get Qwen3 0.6B…** and approve the one-time download of about 523 MB, or use **Find models** if it is installed. [Qwen3 0.6B](https://ollama.com/library/qwen3:0.6b)
3. Ask it to turn a short note into a checklist, for example. After setup, the Ollama app handles these requests locally and can run offline.

The lightweight mode sends up to four previous turns with the new message, with a combined 8 KiB ceiling for message text. It requests a 2,048-token context, up to 256 output tokens and up to four CPU inference threads. Ollama handles final context fitting and unloads the model after replying. That frees model memory between tasks and adds a reload delay to the next message. Full conversations remain available in Air AI, and other models and providers are available with the setting off. The installer and model ship separately. [Ollama chat parameters](https://docs.ollama.com/api/chat)

This design uses the small-model and bounded-memory ideas from the supplied guide to Alexey Fateev’s `llm.asm`. AirCursor uses native Windows Ollama for inference and retains its regular Windows workspace. [llm.asm](https://github.com/alesha-pro/llm.asm)

### Organize and schedule Air AI work

- **Projects** groups chats in the Air AI sidebar. Create a project, select it before starting a conversation, or use **Manage conversation → Move to project** to organize an existing chat.
- **Connectors** attaches a chosen UTF-8 file, clipboard text or basic computer information to the next message. Air AI asks before including attachment text in a request to a remote endpoint. The attachment itself is separate from the saved chat transcript. A model may quote it in a reply. The same menu configures compatible API providers.
- **Scheduled** saves a one-time or daily prompt for local Qwen3 in Ollama. Results appear as chats in the chosen project. AirCursor and Ollama need to be running and Windows needs to be unlocked. An overdue prompt can run when the workspace resumes. The scheduler skips duplicate catch-up runs for a daily prompt.

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

Use NSIS 3.11 or newer with the amd64 Unicode target. The builder verifies the embeddable Python ZIP and every Windows wheel against recorded SHA-256 digests. It produces `dist/AirCursor-Windows.exe`, `dist/SHA256SUMS.txt` and `dist/package-size.json`. To test the installed build, run `& "$env:LOCALAPPDATA\Programs\AirCursor\AirCursor.exe" --self-test` and check the log. The test uses temporary settings, a generated PDF and a loopback web fixture.

The branch workflow builds on a Windows Server runner, installs the generated file silently, and runs the installed self-test before uploading the artifact. Screenshots can be reproduced on a dedicated graphical desktop with `python tools/capture.py --output report`. This captures the entire display. Close unrelated applications first.

[Release notes](RELEASE-NOTES.md) · [Design and verification report](report/VERIFICATION.md)

## Everyday workflows in the audited preview

- **Install and launch software.** Search Air Store, select a full package ID, review Package details, and choose Install. Accept publisher and source agreements only after reviewing them. Use Launch selected app to resolve its registered Start entry. Open a file in its default program or choose Files → Actions → Open with.
- **Open a downloaded installer.** Choose Air Browser → ⋯ → Downloads → Open file. Review its Windows signature and SHA-256. You can paste the publisher's digest before choosing Run installer.
- **Edit media.** Right-click a picture in Files and choose Edit photo to rotate or resize to a new PNG. For a video, install FFmpeg, then choose Trim video, specify start/end seconds, and choose a new MP4 name. Existing output files are protected.
- **Connect an AI service.** Start your local model server or enter an HTTPS provider URL under Configure endpoint. Choose Provider key if the endpoint requires credentials, Find models, select a model and Send. Windows Credential Manager stores API keys. Separate conversations can be renamed or deleted.
- **Lock the workspace.** Use **Windows + L**. AirCursor follows the Windows session and pauses phone controls when the session closes, locks or suspends. The six-digit pairing code in **Settings → Connected phone** is for connecting a phone.
- **Find a PC game.** Open **Air Store → PC Games** for supported game listings on Steam or Epic Games. Select **Get launcher** to find the Windows launcher package, and use the publisher listing to check requirements, purchase and install the game.
- **Restore your work.** Enable Restore open panels at startup. AirCursor remembers your active workspace, managed window geometry, file tabs and views, and regular browser URLs. The calendar widget can be enabled from Settings → Workspace or the desktop context menu.

See [the feature audit](report/FUNCTIONAL-AUDIT.md), [measurements and native checks](report/VERIFICATION.md) and [the local AI session](report/ai-local.jpg).


## Browser, downloads and Windows applications

Air Browser restores saved tab entries when the workspace opens. Open the browser from the Dock, click a tab, or choose **Load saved page** to start Chromium. Minimizing the browser pauses eligible pages. Closing it releases eligible page renderers after a short idle period. Chrome and other Windows apps can continue using the computer while AirCursor pauses its desktop statistics and native window scans in the background. **Settings → Performance → Performance** reduces sampling and uses simpler glass. **Air Browser → ⋯ → Memory Saver** also lets Chromium unload eligible long-idle tabs while the browser remains open. Pages reload when selected.

With Memory Saver on, Air Browser can unload eligible pages after 45 seconds of inactivity when at least ten pages are loaded. Chromium decides which pages are safe to unload. Use this option when you keep many tabs open alongside memory-intensive Windows apps. Air AI offers **Qwen3 0.6B** on its start screen, using the existing Ollama setup for lightweight local chat.

Click **Downloads** in Air Browser or press **Ctrl+Shift+J**. After a download completes, use **Open file** to launch or install it through Windows, or **Open folder** to access archives and companion files. EXE, MSI and MSP installers get an Authenticode and SHA-256 review. Windows App Installer handles APPX and MSIX packages, bundles and App Installer files where supported. A download needs to match your Windows version and architecture. Browser downloads retain Windows internet-zone handling.

Air Store offers separate **Game Launchers** and **Games** categories, plus video editing, AI, browsers and other tools. Use the source selector to search winget Community or Microsoft Store. Publisher artwork is bundled where available. Hardware requirements, accounts and licensing come from the selected publisher, installer or launcher.


## Recover from a slow or stuck startup

Close AirCursor before running an update or uninstall. If the workspace stalls while restoring a session, open **AirCursor (Safe Mode)** from Start. It skips automatic restoration and uses software graphics for the workspace. Start AirCursor normally to return to the saved layout. If an individual browser tab stops after its Chromium renderer fails, use **Reload tab** or **Open in default browser**.

[Windows bug report](report/WINDOWS-BUG-REPORT.md)

### Air Browser and local AI in 2.3

Open **Air Browser** from Windows Start for a browser-only process. It can run alongside AirCursor. Its own per-user cookies, bookmarks and tab recovery live under the AirCursor data folder in StandaloneBrowser. The desktop browser retains its existing profile. The standalone window exits when it detects a locked Windows desktop.

In **Air AI**, choose **Qwen3 0.6B** with Ollama running. **Keep model ready for 2 minutes** retains its weights between follow-ups at the cost of resident memory. Click **Plan app launches** and describe a task to have the local model choose up to three installed Start apps. Review the names and registered identities before opening them. Connectors can attach text from a selected Word or PowerPoint file, or up to 100 top-level names from a coding project.

For MP4-compatible footage, **Files → Trim video → Fast copy** reuses the original audio and video streams. Its start point depends on source keyframes. Choose frame-accurate H.264 export when a precise cut matters.
