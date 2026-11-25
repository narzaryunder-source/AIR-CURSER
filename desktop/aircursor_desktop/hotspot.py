"""The phone's internet on the computer: join the phone's hotspot through Windows' own Wi-Fi
(netsh), and switch to it by itself when the computer's internet stops.

The hotspot password goes once into a Windows Wi-Fi profile (Windows keeps it, like any Wi-Fi);
AirCursor keeps only the hotspot's name. The profile joins only when asked ("manual"), so Windows
never spends mobile data on its own.

Android does not let apps switch the hotspot on: it is switched on on the phone (Quick Settings).
"""
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
from xml.sax.saxutils import escape

NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0   # no black console window flashing up


def netsh(*args, runner=None, timeout=20):
    """Runs netsh (Windows' network tool) → (exit code, text)."""
    if runner:
        return runner(args)
    try:
        p = subprocess.run(["netsh", *args], capture_output=True, timeout=timeout, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError) as e:
        return 1, str(e)
    enc = "oem" if sys.platform == "win32" else "utf-8"
    return p.returncode, (p.stdout + p.stderr).decode(enc, errors="replace")


def _fields(block):
    out = {}
    for line in block.splitlines():
        m = re.match(r"^\s*([^:]+?)\s+:\s?(.*)$", line)
        if m:
            out.setdefault(m.group(1).strip(), m.group(2).strip())
    return out


def parse_interface(text):
    """`netsh wlan show interfaces` → {"state": "connected", "ssid": "...", "signal": "90%"} or None (no Wi-Fi)."""
    f = _fields(text)
    if "State" not in f:
        return None
    return {"state": f.get("State", "").lower(), "ssid": f.get("SSID", ""), "signal": f.get("Signal", ""),
            "name": f.get("Name", "")}


def parse_networks(text):
    """`netsh wlan show networks mode=bssid` → [{"ssid", "auth", "signal"}] (hidden networks left out)."""
    nets = []
    for m in re.finditer(r"^SSID \d+ : ?(.*)$((?:\n(?!SSID \d+ :).*)*)", text, re.M):
        ssid = m.group(1).strip()
        if not ssid:
            continue
        f = _fields(m.group(2))
        signals = [int(s) for s in re.findall(r"Signal\s*:\s*(\d+)%", m.group(2))]
        nets.append({"ssid": ssid, "auth": f.get("Authentication", ""), "signal": max(signals) if signals else 0})
    nets.sort(key=lambda n: -n["signal"])
    return nets


def profile_xml(ssid, password, auth=""):
    """A Windows Wi-Fi profile for a phone hotspot (WPA2, or WPA3 when the phone uses only WPA3)."""
    method = "WPA3SAE" if "WPA3" in auth.upper() and "WPA2" not in auth.upper() else "WPA2PSK"
    hexname = ssid.encode("utf-8").hex().upper()
    return (
        '<?xml version="1.0"?>\n'
        '<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">\n'
        "  <name>%s</name>\n"
        "  <SSIDConfig><SSID><hex>%s</hex><name>%s</name></SSID></SSIDConfig>\n"
        "  <connectionType>ESS</connectionType>\n"
        "  <connectionMode>manual</connectionMode>\n"
        "  <MSM><security>\n"
        "    <authEncryption><authentication>%s</authentication><encryption>AES</encryption>"
        "<useOneX>false</useOneX></authEncryption>\n"
        "    <sharedKey><keyType>passPhrase</keyType><protected>false</protected>"
        "<keyMaterial>%s</keyMaterial></sharedKey>\n"
        "  </security></MSM>\n"
        "</WLANProfile>\n"
    ) % (escape(ssid), hexname, escape(ssid), method, escape(password))


def internet_ok(timeout=3.0):
    """Can this computer reach the internet? (two well-known addresses, no name lookup needed)"""
    for host, port in (("1.1.1.1", 443), ("8.8.8.8", 53)):
        try:
            with socket.create_connection((host, port), timeout=timeout):
                return True
        except OSError:
            continue
    return False


class Hotspot:
    def __init__(self, settings, runner=None, online=internet_ok):
        self.s = settings
        self.runner = runner
        self.online = online

    @property
    def phone_ssid(self):
        return self.s.get("phone_ssid", "")

    def wifi(self):
        code, text = netsh("wlan", "show", "interfaces", runner=self.runner)
        return parse_interface(text) if code == 0 else None

    def networks(self):
        code, text = netsh("wlan", "show", "networks", "mode=bssid", runner=self.runner)
        return parse_networks(text) if code == 0 else []

    def save_phone(self, ssid, password, auth=""):
        """Saves the phone's hotspot as a Windows Wi-Fi profile. → "" or the problem."""
        ssid = ssid.strip()
        if not ssid:
            return "Write the hotspot's name (as on the phone)."
        if not 8 <= len(password) <= 63:
            return "A hotspot password has 8 to 63 characters."
        fd, path = tempfile.mkstemp(suffix=".xml", prefix="aircursor-")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(profile_xml(ssid, password, auth))
            code, text = netsh("wlan", "add", "profile", "filename=" + path, "user=current", runner=self.runner)
        finally:
            try:
                os.remove(path)   # the password leaves the disk at once; Windows has its own copy now
            except OSError:
                pass
        if code != 0:
            return "Windows did not save it: " + text.strip()[:200]
        self.s.set(phone_ssid=ssid)
        return ""

    def forget_phone(self):
        if self.phone_ssid:
            netsh("wlan", "delete", "profile", "name=" + self.phone_ssid, runner=self.runner)
        self.s.set(phone_ssid=None)

    def connect(self):
        """Joins the phone's hotspot. → "" or the problem."""
        ssid = self.phone_ssid
        if not ssid:
            return "Set up your phone's hotspot first."
        if self.wifi() is None:
            return "This computer has no Wi-Fi (or it is off). Use a USB cable: phone Settings → Hotspot → USB tethering."
        code, text = netsh("wlan", "connect", "name=" + ssid, "ssid=" + ssid, runner=self.runner)
        if code != 0:
            return "Could not connect: " + text.strip()[:200]
        return ""

    def on_phone(self):
        w = self.wifi()
        return bool(w and self.phone_ssid and w["ssid"] == self.phone_ssid and w["state"] == "connected")


class AutoSwitch:
    """Watches the internet; after it has been gone for [after] seconds, joins the phone's hotspot
    (when the phone's hotspot is in range). Calls [report](message) for what it did."""

    def __init__(self, hotspot, report=lambda m: None, every=10.0, after=20.0, clock=time.monotonic):
        self.h = hotspot
        self.report = report
        self.every = every
        self.after = after
        self.clock = clock
        self.down_since = None
        self.last_try = -1e9
        self._stop = threading.Event()
        self.thread = None

    def step(self):
        """One look (the thread calls this every few seconds; tests call it directly)."""
        if self.h.online():
            self.down_since = None
            return "online"
        now = self.clock()
        if self.down_since is None:
            self.down_since = now
        if now - self.down_since < self.after or now - self.last_try < 60 or not self.h.phone_ssid:
            return "waiting"
        if self.h.on_phone():
            return "on-phone"
        self.last_try = now
        if not any(n["ssid"] == self.h.phone_ssid for n in self.h.networks()):
            self.report("No internet, and your phone's hotspot is not in range – switch it on on the phone.")
            return "not-in-range"
        err = self.h.connect()
        self.report(err or "No internet – switched to your phone's hotspot (%s)." % self.h.phone_ssid)
        return "switched" if not err else "failed"

    def start(self):
        if self.thread and self.thread.is_alive():
            return
        self._stop.clear()

        def loop():
            while not self._stop.wait(self.every):
                try:
                    self.step()
                except Exception:
                    pass
        self.thread = threading.Thread(target=loop, name="auto-switch", daemon=True)
        self.thread.start()

    def stop(self):
        self._stop.set()
