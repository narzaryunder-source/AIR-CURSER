import os
import subprocess
import time
from pathlib import Path

import psutil
from PySide6.QtWidgets import (QCheckBox, QComboBox, QFormLayout, QInputDialog, QLineEdit, QListWidget,
                               QPlainTextEdit, QTabWidget, QVBoxLayout, QWidget)
from .hotspot import NO_WINDOW
from .server import local_addresses
from .widgets import Workers, button, confirm, label, password, row, system_link

PROFILES = {"Performance": (2, False), "Balanced": (4, True), "Quality": (2, True),
            "Battery Saver": (15, False), "Mobile Hotspot": (12, False)}
SYSTEM = {"Network & metering": "network-wifi", "Keyboard & mouse": "mousetouchpad",
          "Display": "display", "Sound": "sound", "Notifications": "notifications",
          "Applications": "appsfeatures", "Storage": "storagesense",
          "Privacy and security": "privacy", "Windows Update": "windowsupdate"}


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
    completed = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                               capture_output=True, timeout=22, creationflags=NO_WINDOW)
    return (completed.stdout + completed.stderr).decode("oem", errors="replace").strip() or "No adapters reported."


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
        nl.addWidget(label("Enable the hotspot in the phone’s Quick Settings. For USB, connect a data cable and enable USB tethering on the phone."))
        row(nl, button("Adapter diagnostics", self.inspect), button("Metered connection…",
            lambda: system_link(self, "ms-settings:network-wifi")), button("Forget phone…", self.forget))
        self.details = QPlainTextEdit()
        self.details.setReadOnly(True)
        nl.addWidget(self.details)
        nl.addWidget(self.status)
        remote, rl = self.tab("Connected phone")
        self.remote = label("")
        self.remote.setTextInteractionFlags(self.remote.textInteractionFlags() | self.remote.textInteractionFlags().TextSelectableByMouse)
        rl.addWidget(self.remote)
        row(rl, button("Turn receiver on / off", self.toggle_remote), button("Renew pairing code", desk.host.renew_code))
        rl.addWidget(label("In AirCursor on the phone, open Use as keyboard & touchpad, select this computer and enter the six-digit code. Both devices must be on the same local network."))
        rl.addStretch()
        boost, bl = self.tab("AirBoost")
        self.profile = QComboBox()
        self.profile.addItems(PROFILES)
        self.profile.setCurrentText(desk.s.get("profile", "Balanced"))
        self.profile.currentTextChanged.connect(desk.profile)
        bl.addWidget(self.profile)
        self.metrics = label("")
        bl.addWidget(self.metrics)
        bl.addWidget(button("Application resource use", self.processes))
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
            self.status.setText(f"{len(nets)} visible network entries. If none appear, check Wi-Fi and Windows location permissions.")
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
        self.work.run(lambda: self.desk.hot.save_phone(name, secret, auth, selected.get("id")), done, self.status.setText)

    def join(self):
        self.status.setText("Waiting for Wi-Fi association and internet verification…")
        self.work.run(lambda: self.desk.hot.connect(lambda: self.desk.locked),
                      lambda err: self.status.setText(err or "Phone hotspot connected. Internet verified."), self.status.setText)

    def forget(self):
        if not self.desk.hot.phone_ssid or not confirm(self, "Forget hotspot", "Remove the saved phone Wi-Fi profile from Windows?"):
            return
        self.work.run(self.desk.hot.forget_phone, lambda _: self.status.setText("Phone hotspot forgotten."), self.status.setText)

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
            age = f" · last packet {int(time.monotonic()-host.last_seen)}s ago" if host.client and host.last_seen else ""
            self.remote.setText(f"Pairing code   {host.code[:3]} {host.code[3:]}\n"
                + "   ".join(f"{addr}:{host.port}" for addr in desk.addresses)
                + f"\n{host.client_addr or 'Waiting for phone'} · {host.events} input events{age}")
        else:
            self.remote.setText("Phone receiver is off.")
        self.metrics.setText(desk.metrics_text)

    def processes(self):
        def gather():
            rows = []
            for p in psutil.process_iter(["name", "memory_info"]):
                try:
                    if p.info["memory_info"]:
                        rows.append((p.info["memory_info"].rss, p.info["name"] or "Unknown"))
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue
            return "\n".join(f"{n:32} {rss / 2**20:8.1f} MiB" for rss, n in sorted(rows, reverse=True)[:24])
        self.work.run(gather, self.process_list.setPlainText, self.process_list.setPlainText)


class Preferences(QTabWidget):
    def __init__(self, desk):
        super().__init__()
        self.desk = desk
        general = QWidget()
        form = QFormLayout(general)
        self.addTab(general, "Workspace")
        for title, key, choices in [("Appearance", "appearance", ["Automatic", "Dark", "Light"]),
                                    ("Wallpaper", "wallpaper", ["Fjord", "Violet", "Copper"])]:
            box = QComboBox()
            box.addItems(choices)
            box.setCurrentText(desk.s.get(key, choices[0]))
            box.currentTextChanged.connect(lambda value, k=key: desk.preference(k, value))
            form.addRow(title, box)
        for title, key, default in [("Remember visited pages for search", "browser_history_enabled", False),
                                    ("Keep recent clipboard text until lock", "clipboard", False),
                                    ("Restore open workspace panels", "restore", True),
                                    ("Show one panel at a time", "stage", False)]:
            check = QCheckBox(title)
            check.setChecked(desk.s.get(key, default))
            check.toggled.connect(lambda state, k=key: desk.preference(k, state))
            form.addRow(check)
        form.addRow(button("Change AirCursor password…", self.change_password))
        form.addRow(label("AirCursor for Windows · 2.0 development preview"))
        system = QWidget()
        form = QFormLayout(system)
        self.addTab(system, "Windows settings")
        for name, page in SYSTEM.items():
            form.addRow(button(name, lambda page=page: system_link(self, "ms-settings:" + page)))

    def change_password(self):
        values = []
        for name in ("Current password", "New password", "Confirm new password"):
            value, ok = QInputDialog.getText(self, "Change AirCursor password", name, QLineEdit.EchoMode.Password)
            if not ok:
                return
            values.append(value)
        try:
            self.desk.auth.change(*values)
            self.desk.notify("Password changed.")
        except ValueError as exc:
            self.desk.notify(str(exc))
