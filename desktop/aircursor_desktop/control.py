import os
import time
from functools import lru_cache

import psutil
from PySide6.QtCore import QObject, QSize, Qt
from PySide6.QtGui import QImageReader, QPixmap, QIcon
from .navigation import sidebar, item, heading
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QStackedWidget,
    QDoubleSpinBox,
    QPlainTextEdit,
    QVBoxLayout,
    QWidget,
)
from . import VERSION
from .widgets import asset, Workers, button, confirm, label, password, row, system_link

PROFILES = {
    "Performance": (8, False),
    "Balanced": (4, True),
    "Quality": (2, True),
    "Battery Saver": (15, False),
    "Mobile Hotspot": (12, False),
}
SYSTEM = {
    "Network & metering": "network-wifi",
    "Keyboard": "easeofaccess-keyboard",
    "Mouse": "mousetouchpad",
    "Display": "display",
    "Sound": "sound",
    "Notifications": "notifications",
    "Applications": "appsfeatures",
    "Storage": "storagesense",
    "Privacy and security": "privacy",
    "Windows Update": "windowsupdate",
}


def diagnostics():
    script = """
    Get-NetIPConfiguration | Where-Object NetAdapter | ForEach-Object {
      $_.InterfaceAlias
      $_.NetAdapter.InterfaceDescription
      $g = $_.IPv4DefaultGateway.NextHop
      if ($g) {
        $ping = New-Object System.Net.NetworkInformation.Ping
        try { "Gateway $g ICMP: " + $ping.Send($g,900).Status } catch { $_.Exception.Message }
        finally { $ping.Dispose() }
      }
      "DNS: " + ($_.DNSServer.ServerAddresses -join ', ')
      if ($_.NetAdapter.InterfaceDescription -match 'RNDIS|Remote NDIS|USB.*Ethernet') {
        "Possible USB tethering adapter"
      }
    }
    """
    from . import powershell

    completed = powershell.run(script, timeout=22)
    return (completed.stdout + completed.stderr).decode(
        "oem", errors="replace"
    ).strip() or "No adapters reported."


class Controls(QObject):
    def __init__(self, desk, parent):
        super().__init__(parent)
        self.desk, self.work = desk, Workers(self)
        self.pages = {}
        self.status = label("")
        net, nl = self.tab("Hotspot")
        self.net = label("")
        nl.addWidget(self.net)
        self.available = QComboBox()
        self.available.setEditable(True)
        self.available.setPlaceholderText("Phone hotspot name")
        self.available.setEditText(desk.hot.phone_ssid)
        row(nl, self.available, button("Find hotspots", self.scan))
        self.secret = password("Wi-Fi password · saved by Windows")
        nl.addWidget(self.secret)
        self.auth = QComboBox()
        self.auth.addItems(["From discovery / WPA2", "WPA3-Personal"])
        row(nl, self.auth, button("Save and connect", self.save), button("Connect saved phone", self.join))
        auto = QCheckBox("Switch to my phone after a sustained internet outage")
        auto.setChecked(desk.s.get("auto_switch", True))
        auto.toggled.connect(self.auto)
        nl.addWidget(auto)
        regular = QCheckBox("Return to previous Wi-Fi once stable and transfers are idle")
        regular.setChecked(desk.s.get("return_wifi", False))
        regular.toggled.connect(lambda value: desk.s.set(return_wifi=value))
        nl.addWidget(regular)
        nl.addWidget(
            label(
                "Enable the hotspot in the phone’s Quick Settings. For USB, connect a data cable and enable USB tethering on the phone."
            )
        )
        row(
            nl,
            button("Adapter diagnostics", self.inspect),
            button("Metered connection…", lambda: system_link(self.parent(), "ms-settings:network-wifi")),
            button("Forget phone…", self.forget),
        )
        self.details = QPlainTextEdit()
        self.details.setReadOnly(True)
        nl.addWidget(self.details)
        nl.addWidget(self.status)
        remote, rl = self.tab("Connected phone")
        self.remote = label("")
        self.remote.setTextInteractionFlags(
            self.remote.textInteractionFlags() | self.remote.textInteractionFlags().TextSelectableByMouse
        )
        rl.addWidget(self.remote)
        row(
            rl,
            button("Turn receiver on / off", self.toggle_remote),
            button("Renew pairing code", desk.host.renew_code),
        )
        rl.addWidget(
            label(
                "Open Use as keyboard & touchpad on your phone. Use the same Wi-Fi, your phone hotspot, or compatible USB tethering. Select this computer or enter its address and pairing code."
            )
        )
        speed = QDoubleSpinBox()
        speed.setRange(0.25, 2.5)
        speed.setSingleStep(0.1)
        speed.setValue(desk.host.ctl.speed)

        def set_speed(value):
            desk.host.ctl.speed = value
            desk.s.set(pointer_speed=value)

        speed.valueChanged.connect(set_speed)
        row(rl, label("Pointer sensitivity"), speed)
        scrolling = QCheckBox("Natural two-finger scrolling")
        scrolling.setChecked(desk.host.ctl.natural_scroll)

        def scroll(state):
            desk.host.ctl.natural_scroll = state
            desk.s.set(natural_scroll=state)

        scrolling.toggled.connect(scroll)
        rl.addWidget(scrolling)
        rl.addStretch()
        boost, bl = self.tab("Performance")
        self.profile = QComboBox()
        self.profile.addItems(PROFILES)
        self.profile.setCurrentText(desk.s.get("profile", "Balanced"))
        self.profile.currentTextChanged.connect(desk.profile)
        bl.addWidget(self.profile)
        self.metrics = label("")
        bl.addWidget(self.metrics)
        row(bl, button("Application resource use", self.processes), button("GPU information", self.gpu))
        self.process_list = QPlainTextEdit()
        self.process_list.setReadOnly(True)
        bl.addWidget(self.process_list)
        self.refresh()

    def tab(self, title):
        widget = QWidget()
        layout = QVBoxLayout(widget)
        layout.setContentsMargins(22, 20, 22, 20)
        heading(layout, title)
        self.pages[title] = widget
        return widget, layout

    def scan(self):
        self.status.setText("Reading Windows Wi-Fi discovery…")

        def done(nets):
            old = self.available.currentText()
            self.available.clear()
            for n in nets:
                self.available.addItem(n["ssid"], n)
            if old:
                self.available.setCurrentText(old)
            self.status.setText(
                f"{len(nets)} visible network entries. If none appear, check Wi-Fi and Windows location permissions."
            )

        self.work.run(self.desk.hot.networks, done, self.status.setText)

    def save(self):
        name, secret = self.available.currentText(), self.secret.text()
        selected = self.available.currentData() or {}
        if selected.get("ssid") != name:
            selected = {}
        auth = self.auth.currentText() if self.auth.currentIndex() else selected.get("auth", "")
        self.secret.clear()

        def done(err):
            if err:
                self.status.setText(err)
            else:
                self.join()

        self.work.run(
            lambda: self.desk.hot.save_phone(name, secret, auth, selected.get("id")),
            done,
            self.status.setText,
        )

    def join(self):
        self.status.setText("Waiting for Wi-Fi association and internet verification…")

        def done(err):
            self.desk.refresh_hotspot_policy()
            self.status.setText(err or "Phone hotspot connected. Internet verified.")

        self.work.run(
            lambda: self.desk.hot.connect(lambda: self.desk.locked),
            done,
            done,
        )

    def forget(self):
        if not self.desk.hot.phone_ssid or not confirm(
            self.parent(), "Forget hotspot", "Remove the saved phone Wi-Fi profile from Windows?"
        ):
            return
        def done(_):
            self.desk.refresh_hotspot_policy()
            self.status.setText("Phone hotspot forgotten.")

        self.work.run(self.desk.hot.forget_phone, done, self.status.setText)

    def inspect(self):
        self.details.setPlainText("Checking adapters and gateways…")
        self.work.run(diagnostics, self.details.setPlainText, self.details.setPlainText)

    def auto(self, enabled):
        self.desk.s.set(auto_switch=enabled)
        self.desk.refresh_hotspot_policy()

    def toggle_remote(self):
        host = self.desk.host
        if os.name != "nt":
            self.remote.setText("The input receiver requires Windows.")
            return
        if not host.running and (self.desk.locked or not self.desk.session_watching):
            self.remote.setText("Windows session notifications must be available to enable phone controls.")
            return
        try:
            (host.stop if host.running else host.start)()
            self.desk.s.set(remote_on=host.running)
            self.refresh()
        except OSError as exc:
            self.remote.setText(str(exc))

    def refresh(self):
        desk = self.desk
        self.net.setText(desk.network_text + "\nSaved phone · " + (desk.hot.phone_ssid or "None"))
        host = desk.host
        if host.running:
            age = (
                f" · last packet {int(time.monotonic() - host.last_seen)}s ago"
                if host.client and host.last_seen
                else ""
            )
            self.remote.setText(
                f"Pairing code   {host.code[:3]} {host.code[3:]}\n"
                + "   ".join(f"{addr}:{host.port}" for addr in host.addresses)
                + f"\n{host.client_addr or 'Waiting for phone'} · {host.events} input commands{age}"
                + (f"\nWindows blocked input for {host.rejected} commands." if host.rejected else "")
            )
        else:
            self.remote.setText("Phone receiver is off.")
        self.metrics.setText(desk.metrics_text)

    def gpu(self):
        def gather():
            script = """Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name
            $g = Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction SilentlyContinue
            if ($g) { 'Busiest engine sample: ' + (($g | Measure-Object UtilizationPercentage -Maximum).Maximum) + '%' }
            else { 'GPU utilization counter unavailable.' }"""
            from . import powershell

            result = powershell.run(script, timeout=20)
            return (result.stdout + result.stderr).decode("oem", errors="replace")

        self.work.run(gather, self.process_list.setPlainText, self.process_list.setPlainText)

    def processes(self):
        def gather():
            samples = list(psutil.process_iter(["name", "memory_info"]))
            for proc in samples:
                try:
                    proc.cpu_percent()
                except psutil.Error:
                    continue
            time.sleep(0.3)
            rows = []
            for proc in samples:
                try:
                    if proc.info["memory_info"]:
                        rows.append(
                            (
                                proc.info["memory_info"].rss,
                                proc.info["name"] or "Unknown",
                                proc.cpu_percent(),
                            )
                        )
                except psutil.Error:
                    continue
            return "\n".join(
                f"{n:25} {rss / 2**20:7.1f} MiB   {cpu:5.1f}% CPU"
                for rss, n, cpu in sorted(rows, reverse=True)[:24]
            )

        self.work.run(gather, self.process_list.setPlainText, self.process_list.setPlainText)


@lru_cache(maxsize=3)
def wallpaper_preview(scene):
    reader = QImageReader(asset(scene + ".jpg"))
    reader.setScaledSize(reader.size().scaled(QSize(440, 200), Qt.AspectRatioMode.KeepAspectRatio))
    return QPixmap.fromImage(reader.read())


class Preferences(QWidget):
    def __init__(self, desk):
        super().__init__()
        self.desk = desk
        rail, self.nav, layout = sidebar(self, "Settings", 182)
        stack = QStackedWidget()
        layout.addWidget(stack)
        self.sections = {}

        def add_section(name, art, pane):
            from PySide6.QtWidgets import QScrollArea

            area = QScrollArea()
            area.setFrameShape(area.Shape.NoFrame)
            area.setWidgetResizable(True)
            area.setWidget(pane)
            index = stack.addWidget(area)
            self.sections[name] = index
            item(self.nav, name, index, art)

        def section(name, title, art):
            pane = QWidget()
            content = QVBoxLayout(pane)
            content.setContentsMargins(0, 0, 0, 0)
            content.setSpacing(13)
            heading(content, title)
            add_section(name, art, pane)
            return content

        appearance = section("Appearance", "Appearance", "Settings")
        appearance.addWidget(label("Color mode"))
        self.mode = QComboBox()
        self.mode.addItems(["Automatic", "Light", "Dark"])
        self.mode.setCurrentText(desk.s.get("appearance", "Automatic"))
        self.mode.currentTextChanged.connect(lambda v: desk.preference("appearance", v))
        appearance.addWidget(self.mode)
        appearance.addWidget(label("Desktop picture"))
        pics = []
        for scene in ("Fjord", "Violet", "Copper"):
            choose = button(scene, lambda scene=scene: desk.preference("wallpaper", scene))
            choose.setIcon(QIcon(wallpaper_preview(scene.lower())))
            choose.setIconSize(QSize(78, 56))
            pics.append(choose)
        row(appearance, *pics)
        preview = label("")
        preview.setAlignment(Qt.AlignmentFlag.AlignLeft)

        def wallpaper():
            preview.setPixmap(wallpaper_preview(desk.shell.scene))

        desk.shell.sceneChanged.connect(wallpaper)
        wallpaper()
        appearance.addWidget(preview)
        appearance.addStretch()
        workspace = section("Workspace", "Workspace", "Overview")
        self.controls = Controls(desk, self)
        for name, art in (("Hotspot", "Control Center"), ("Connected phone", "Control Center"), ("Performance", "Settings")):
            add_section(name, art, self.controls.pages[name])
        privacy = section("Privacy", "Privacy and security", "Control Center")
        for pane, title, key, default in [
            (privacy, "Remember visited pages for search", "browser_history_enabled", False),
            (privacy, "Keep recent clipboard text until lock", "clipboard", False),
            (workspace, "Restore open panels at startup", "restore", True),
            (workspace, "Open AirCursor full screen", "start_fullscreen", True),
            (workspace, "Show desktop calendar widget", "calendar_widget", False),
            (workspace, "Show one panel at a time", "stage", False),
        ]:
            check = QCheckBox(title)
            check.setChecked(desk.s.get(key, default))
            check.toggled.connect(lambda state, k=key: desk.preference(k, state))
            pane.addWidget(check)
        privacy.addWidget(
            label(
                "AirCursor opens in your signed-in Windows session. Use Windows + L to pause the workspace and phone controls."
            )
        )
        privacy.addStretch()
        workspace.addWidget(label("Press F11 to switch between full screen and a window."))
        workspace.addWidget(label(f"AirCursor for Windows · {VERSION}"))
        workspace.addStretch()
        windows = section("Windows", "Windows settings", "Launcher")
        for name, page in SYSTEM.items():
            windows.addWidget(button(name, lambda page=page: system_link(self, "ms-settings:" + page)))
        from PySide6.QtCore import QUrl
        from PySide6.QtGui import QDesktopServices

        windows.addWidget(
            button(
                "AirCursor releases",
                lambda: QDesktopServices.openUrl(
                    QUrl("https://github.com/narzaryunder-source/AIR-CURSER/releases")
                ),
            )
        )
        windows.addStretch()
        self.nav.currentRowChanged.connect(stack.setCurrentIndex)
        self.nav.setCurrentRow(0)

    def select_page(self, name):
        self.nav.setCurrentRow(self.sections[name])
        self.controls.refresh()
