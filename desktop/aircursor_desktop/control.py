import os
import subprocess
import time

import psutil
from PySide6.QtCore import Qt
from PySide6.QtGui import QPixmap, QIcon
from .navigation import sidebar, item, heading
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QStackedWidget,
    QDoubleSpinBox,
    QInputDialog,
    QLineEdit,
    QPlainTextEdit,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)
from .hotspot import NO_WINDOW
from .widgets import asset, Workers, button, confirm, label, password, row, system_link

PROFILES = {
    "Performance": (2, False),
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
    completed = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True,
        timeout=22,
        creationflags=NO_WINDOW,
    )
    return (completed.stdout + completed.stderr).decode(
        "oem", errors="replace"
    ).strip() or "No adapters reported."


class Control(QTabWidget):
    def __init__(self, desk):
        super().__init__()
        self.desk, self.work = desk, Workers(self)
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
            button("Metered connection…", lambda: system_link(self, "ms-settings:network-wifi")),
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
                "In AirCursor on the phone, open Use as keyboard & touchpad, select this computer and enter the six-digit code. Both devices must be on the same local network."
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
        boost, bl = self.tab("AirBoost")
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
        self.addTab(widget, title)
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
        self.work.run(
            lambda: self.desk.hot.connect(lambda: self.desk.locked),
            lambda err: self.status.setText(err or "Phone hotspot connected. Internet verified."),
            self.status.setText,
        )

    def forget(self):
        if not self.desk.hot.phone_ssid or not confirm(
            self, "Forget hotspot", "Remove the saved phone Wi-Fi profile from Windows?"
        ):
            return
        self.work.run(
            self.desk.hot.forget_phone,
            lambda _: self.status.setText("Phone hotspot forgotten."),
            self.status.setText,
        )

    def inspect(self):
        self.details.setPlainText("Checking adapters and gateways…")
        self.work.run(diagnostics, self.details.setPlainText, self.details.setPlainText)

    def auto(self, enabled):
        self.desk.s.set(auto_switch=enabled)
        (self.desk.auto.start if enabled else self.desk.auto.stop)()

    def toggle_remote(self):
        host = self.desk.host
        if os.name != "nt":
            self.remote.setText("The input receiver requires Windows.")
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
                + "   ".join(f"{addr}:{host.port}" for addr in desk.addresses)
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
            result = subprocess.run(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                capture_output=True,
                timeout=20,
                creationflags=NO_WINDOW,
            )
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
                            (proc.info["memory_info"].rss, proc.info["name"] or "Unknown", proc.cpu_percent())
                        )
                except psutil.Error:
                    continue
            return "\n".join(
                f"{n:25} {rss / 2**20:7.1f} MiB   {cpu:5.1f}% CPU"
                for rss, n, cpu in sorted(rows, reverse=True)[:24]
            )

        self.work.run(gather, self.process_list.setPlainText, self.process_list.setPlainText)


class Preferences(QWidget):
    def __init__(self, desk):
        super().__init__()
        self.desk = desk
        rail, nav, layout = sidebar(self, "Settings", 169)
        stack = QStackedWidget()
        layout.addWidget(stack)
        for idx, (name, art) in enumerate(
            [
                ("Appearance", "Settings"),
                ("Workspace", "Overview"),
                ("Privacy", "Control Center"),
                ("Windows", "Launcher"),
            ]
        ):
            item(nav, name, idx, art)
        nav.currentRowChanged.connect(stack.setCurrentIndex)
        nav.setCurrentRow(0)

        def section(name):
            pane = QWidget()
            content = QVBoxLayout(pane)
            content.setContentsMargins(0, 0, 0, 0)
            content.setSpacing(13)
            heading(content, name)
            from PySide6.QtWidgets import QScrollArea
            area = QScrollArea()
            area.setFrameShape(area.Shape.NoFrame)
            area.setWidgetResizable(True)
            area.setWidget(pane)
            stack.addWidget(area)
            return content

        appearance = section("Appearance")
        appearance.addWidget(label("Color mode"))
        self.mode = QComboBox()
        self.mode.addItems(["Automatic", "Light", "Dark"])
        self.mode.setCurrentText(desk.s.get("appearance", "Automatic"))
        self.mode.currentTextChanged.connect(lambda v: desk.preference("appearance", v))
        appearance.addWidget(self.mode)
        appearance.addWidget(label("Desktop picture"))
        pics = []
        from PySide6.QtCore import QSize

        for scene in ("Fjord", "Violet", "Copper"):
            choose = button(scene, lambda scene=scene: desk.preference("wallpaper", scene))
            choose.setIcon(QIcon(asset(scene.lower() + ".jpg")))
            choose.setIconSize(QSize(78, 56))
            pics.append(choose)
        row(appearance, *pics)
        preview = label("")
        preview.setAlignment(Qt.AlignmentFlag.AlignLeft)

        def wallpaper():
            photo = QPixmap(asset(desk.shell.scene + ".jpg"))
            preview.setPixmap(
                photo.scaled(
                    440, 200, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation
                )
            )

        desk.shell.appsChanged.connect(wallpaper)
        wallpaper()
        appearance.addWidget(preview)
        appearance.addStretch()
        workspace = section("Workspace")
        privacy = section("Privacy and security")
        for pane, title, key, default in [
            (privacy, "Remember visited pages for search", "browser_history_enabled", False),
            (privacy, "Keep recent clipboard text until lock", "clipboard", False),
            (workspace, "Restore open panels at sign-in", "restore", True),
            (workspace, "Show desktop calendar widget", "calendar_widget", False),
            (workspace, "Show one panel at a time", "stage", False),
        ]:
            check = QCheckBox(title)
            check.setChecked(desk.s.get(key, default))
            check.toggled.connect(lambda state, k=key: desk.preference(k, state))
            pane.addWidget(check)
        privacy.addWidget(button("Change AirCursor password…", self.change_password))
        self.factor = button("", self.configure_factor)
        self.update_factor()
        privacy.addWidget(self.factor)
        privacy.addWidget(button("Forget trusted Windows sign-in", lambda: desk.s.set(trusted_logon=None)))
        privacy.addStretch()
        workspace.addWidget(
            button("Open AirBoost profiles", lambda: desk.open("Control Center").setCurrentIndex(2))
        )
        workspace.addWidget(label("AirCursor for Windows · 2.0 preview"))
        workspace.addStretch()
        windows = section("Windows settings")
        for name, page in SYSTEM.items():
            windows.addWidget(button(name, lambda page=page: system_link(self, "ms-settings:" + page)))
        from PySide6.QtCore import QUrl
        from PySide6.QtGui import QDesktopServices
        windows.addWidget(button("AirCursor releases", lambda: QDesktopServices.openUrl(
            QUrl("https://github.com/narzaryunder-source/AIR-CURSER/releases"))))
        windows.addStretch()

    def update_factor(self):
        enabled = bool(self.desk.s.get("phone_factor"))
        self.factor.setText("Disable phone authenticator…" if enabled else "Set up phone authenticator…")
        self.factor.setEnabled(os.name == "nt")

    def configure_factor(self):
        from . import second_factor
        current, ok = QInputDialog.getText(self, "Phone authenticator", "Current workspace password", QLineEdit.EchoMode.Password)
        if not ok:
            return
        if not self.desk.auth.check(current):
            self.desk.notify("Incorrect password or a retry delay is active.")
            return
        enabled = self.desk.s.get("phone_factor")
        try:
            if enabled:
                entered, ok = QInputDialog.getText(self, "Disable phone authenticator", "Current authenticator code or recovery code", QLineEdit.EchoMode.Password)
                if not ok:
                    return
                second_factor.verify(self.desk.s, entered)
                second_factor.disable(self.desk.s)
            else:
                secret, recovery = second_factor.enrollment()
                message = ("In your phone’s authenticator choose manual entry. Use account AirCursor, "
                    "time-based SHA-1, 6 digits, 30 seconds.\n\nSetup key: " + secret +
                    "\n\nSave this single-use recovery code in a safe place: " + recovery +
                    "\n\nEnter the code displayed by your phone to finish setup.")
                entered, ok = QInputDialog.getText(self, "Set up phone authenticator", message, QLineEdit.EchoMode.Password)
                if not ok:
                    return
                second_factor.enable(self.desk.s, secret, recovery, entered)
            self.update_factor()
            self.desk.notify("Phone authentication settings updated.")
        except (ValueError, OSError) as exc:
            self.desk.notify(str(exc))

    def change_password(self):
        values = []
        for name in ("Current password", "New password", "Confirm new password"):
            value, ok = QInputDialog.getText(
                self, "Change AirCursor password", name, QLineEdit.EchoMode.Password
            )
            if not ok:
                return
            values.append(value)
        try:
            self.desk.auth.change(*values)
            self.desk.notify("Password changed.")
        except ValueError as exc:
            self.desk.notify(str(exc))
