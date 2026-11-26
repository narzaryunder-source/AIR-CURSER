"""The phone's internet on the computer: join the phone's hotspot through Windows' own Wi-Fi
(netsh), and switch to it by itself when the computer's internet stops.

The hotspot password goes once into a Windows Wi-Fi profile (Windows keeps it, like any Wi-Fi);
AirCursor keeps only the hotspot's name. The profile joins only when asked ("manual"), so Windows
never spends mobile data on its own.

Android does not let apps switch the hotspot on: it is switched on on the phone (Quick Settings).
"""

import os
import re
from contextlib import ExitStack

from .connectivity import probe, probe_interface
import subprocess
import sys
import tempfile
import threading
import time
from xml.sax.saxutils import escape

NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0  # no black console window flashing up


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
    return {
        "state": f.get("State", "").lower(),
        "ssid": f.get("SSID", ""),
        "signal": f.get("Signal", ""),
        "name": f.get("Name", ""),
    }


def parse_networks(text):
    """`netsh wlan show networks mode=bssid` → [{"ssid", "auth", "signal"}] (hidden networks left out)."""
    nets = []
    for m in re.finditer(r"^SSID \d+ : ?(.*)$((?:\n(?!SSID \d+ :).*)*)", text, re.M):
        ssid = m.group(1).strip()
        if not ssid:
            continue
        f = _fields(m.group(2))
        signals = [int(s) for s in re.findall(r"Signal\s*:\s*(\d+)%", m.group(2))]
        nets.append(
            {"ssid": ssid, "auth": f.get("Authentication", ""), "signal": max(signals) if signals else 0}
        )
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


def internet_ok(timeout=1.5):
    return probe(timeout).usable


class Hotspot:
    def __init__(self, settings, runner=None, online=None, sleep=time.sleep):
        self.s = settings
        self.runner = runner
        self.health = None
        self.online = online or self.check_internet
        self.sleep = sleep
        self._joining = threading.Lock()
        self.traffic_busy = lambda: False
        self.native = None
        if sys.platform == "win32" and runner is None:
            from .wlan import Wlan

            try:
                self.native = Wlan()
            except OSError:
                # Wireless LAN is optional on Windows Server and some wired-only installations.
                pass

    def check_internet(self):
        self.health = probe()
        return self.health.usable

    @property
    def phone_ssid(self):
        return self.s.get("phone_ssid", "") or ""

    def wifi(self):
        if self.native:
            rows = self.native.interfaces()
            return next(
                (w for w in rows if self.phone_ssid and w["ssid"] == self.phone_ssid),
                next((w for w in rows if w["state"] == "connected"), rows[0] if rows else None),
            )
        code, text = netsh("wlan", "show", "interfaces", runner=self.runner)
        return parse_interface(text) if code == 0 else None

    def networks(self):
        if self.native:
            return self.native.networks()
        code, text = netsh("wlan", "show", "networks", "mode=bssid", runner=self.runner)
        return parse_networks(text) if code == 0 else []

    def save_phone(self, ssid, password, auth="", adapter_id=None):
        """Saves the phone's hotspot as a Windows Wi-Fi profile. → "" or the problem."""
        ssid = ssid.strip()
        if not ssid:
            return "Write the hotspot's name (as on the phone)."
        if not 8 <= len(password) <= 63:
            return "A hotspot password has 8 to 63 characters."
        if len(ssid.encode("utf-8")) > 32:
            return "The Wi-Fi name must fit in 32 UTF-8 bytes."
        if self.native:
            try:
                adapter = self.native.save(profile_xml(ssid, password, auth), adapter_id)
            except OSError as exc:
                return f"Windows could not save the Wi-Fi profile: {exc}"
            self.s.set(phone_ssid=ssid, phone_adapter=adapter)
            return ""
        if self.runner is None:
            return "The Windows Wireless LAN service is required to save this profile."
        fd, path = tempfile.mkstemp(suffix=".xml", prefix="aircursor-")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(profile_xml(ssid, password, auth))
            code, text = netsh(
                "wlan", "add", "profile", "filename=" + path, "user=current", runner=self.runner
            )
        finally:
            try:
                os.remove(path)  # the password leaves the disk at once; Windows has its own copy now
            except OSError:
                pass
        if code != 0:
            return "Windows did not save it: " + text.strip()[:200]
        self.s.set(phone_ssid=ssid)
        return ""

    def forget_phone(self):
        if self.native and self.phone_ssid and self.s.get("phone_adapter"):
            self.native.forget(self.phone_ssid, bytes.fromhex(self.s.get("phone_adapter")))
        elif self.phone_ssid:
            code, message = netsh("wlan", "delete", "profile", "name=" + self.phone_ssid, runner=self.runner)
            if code:
                raise OSError(message.strip() or "Windows could not forget the Wi-Fi profile.")
        self.s.set(phone_ssid=None, phone_adapter=None)

    def connect(self, cancelled=lambda: False, visible=None):
        """Association is asynchronous. Verify the target SSID and test its Windows IPv4 route."""
        if not self._joining.acquire(blocking=False):
            return "A connection attempt is already in progress."
        try:
            ssid = self.phone_ssid
            if not ssid:
                return "Set up your phone's hotspot first."
            wifi = self.wifi()
            if wifi is None:
                return "No Wi-Fi adapter. Enable Wi-Fi or connect the phone using USB tethering."
            adapter = None
            if self.native and (wifi["ssid"] != ssid or wifi["state"] != "connected"):
                saved = self.s.get("phone_adapter")
                visible = [n for n in (self.networks() if visible is None else visible) if n["ssid"] == ssid]
                adapter = next(
                    (n["id"] for n in visible if n["id"].hex() == saved),
                    visible[0]["id"] if visible else None,
                )
                if adapter is None:
                    return "The phone hotspot is not visible. Check Wi-Fi and Windows location access."
            if cancelled():
                return "Connection cancelled."
            if wifi["ssid"] != ssid or wifi["state"] != "connected":
                if self.native:
                    self.native.connect(ssid, adapter)
                else:
                    code, message = netsh(
                        "wlan", "connect", "name=" + ssid, "ssid=" + ssid, runner=self.runner
                    )
                    if code:
                        return "Windows could not request the connection: " + message.strip()[:200]
            for attempt in range(13):
                if cancelled():
                    return "Connection cancelled."
                joined = (next((r for r in self.native.interfaces() if r["id"] == adapter), None)
                          if self.native and adapter is not None else self.wifi())
                if (joined and joined["state"] == "connected" and joined["ssid"] == ssid
                        and (adapter is None or joined.get("id") == adapter)):
                    if self.verify_adapter(joined):
                        return ""
                    if attempt >= 4:
                        detail = f" {self.health.state}." if self.health else ""
                        return "Joined the phone hotspot. Internet access could not be verified." + detail
                if attempt < 12:
                    self.sleep(0.5)
            return "Windows accepted the request, but association with the phone was not confirmed."
        except OSError as exc:
            return f"Windows Wi-Fi: {exc}"
        finally:
            self._joining.release()

    def verify_adapter(self, row):
        if self.native:
            self.health = probe_interface(self.native.interface_index(row["id"]))
            return self.health.usable
        return self.online()

    def restore(self, prior, cancelled):
        """Use the last auto-displaced profile on its original adapter. Windows keeps its key."""
        if not self.native or cancelled() or not self._joining.acquire(blocking=False):
            return False
        try:
            self.native.connect(prior["profile"], prior["id"])
            for _ in range(12):
                if cancelled():
                    return False
                rows = self.native.interfaces()
                if any(row["id"] == prior["id"] and row["ssid"] == prior["ssid"] for row in rows):
                    if self.verify_adapter(prior):
                        return True
                self.sleep(0.5)
            return False
        finally:
            self._joining.release()

    def on_phone(self):
        w = self.wifi()
        return bool(w and self.phone_ssid and w["ssid"] == self.phone_ssid and w["state"] == "connected")


class AutoSwitch:
    """Wake on WLAN changes, confirm sustained failures and back off when the phone is absent."""

    def __init__(self, hotspot, report=lambda m: None, every=15.0, after=6.0, clock=time.monotonic):
        self.h, self.report, self.every, self.after, self.clock = hotspot, report, every, after, clock
        self.down_since = None
        self.retry_at = 0
        self.delay = 15
        self.thread = None
        self._wake = threading.Event()
        self._generation = 0
        self._step_lock = threading.Lock()
        self.previous = None
        self.next_return = 0
        self.sightings = 0

    def consider_return(self, cancelled):
        now = self.clock()
        if not self.previous or now < self.next_return or not self.h.s.get("return_wifi", False):
            return
        self.next_return = now + 60
        if not self.h.on_phone():
            self.previous = None
            return
        if self.h.traffic_busy():
            self.sightings = 0
            return
        strong = any(
            n["ssid"] == self.previous["ssid"] and n.get("id") == self.previous["id"] and n["signal"] >= 50
            for n in self.h.networks()
        )
        self.sightings = self.sightings + 1 if strong else 0
        if self.sightings < 2 or cancelled():
            return
        self.sightings = 0
        self.next_return = now + 300
        if self.h.restore(self.previous, cancelled):
            self.report(f"Returned to {self.previous['ssid']}. Computer internet access verified.")
            self.previous = None
        elif not cancelled():
            err = self.h.connect(cancelled)
            self.report(err or "Regular Wi-Fi could not be verified. Phone hotspot restored.")

    def step(self, cancelled=lambda: False):
        with self._step_lock:
            if cancelled() or not self.h.phone_ssid:
                return "idle"
            if self.h.online():
                self.down_since = None
                self.retry_at, self.delay = 0, 15
                self.consider_return(cancelled)
                return "online"
            now = self.clock()
            if self.down_since is None:
                self.down_since = now
            if now - self.down_since < self.after or now < self.retry_at or cancelled():
                return "waiting"
            self.retry_at = now + self.delay
            self.delay = min(self.delay * 2, 240)
            if self.h.traffic_busy():
                return "in-use"
            if self.h.on_phone():
                return "on-phone"
            visible = self.h.networks()
            if not any(n["ssid"] == self.h.phone_ssid for n in visible):
                self.report("Phone hotspot is out of range. Waiting before retrying.")
                return "not-in-range"
            prior = self.h.wifi() if self.h.native and self.h.s.get("return_wifi", False) else None
            err = self.h.connect(cancelled, visible)
            if not err and prior and prior.get("profile") and prior["ssid"] != self.h.phone_ssid:
                self.previous = prior
                self.sightings = 0
                self.next_return = self.clock() + 90
            if cancelled():
                return "idle"
            self.report(err or f"Switched to your phone hotspot ({self.h.phone_ssid}). Internet verified.")
            return "failed" if err else "switched"

    def start(self):
        if self.thread and self.thread.is_alive():
            return
        self._generation += 1
        generation = self._generation
        wake = self._wake = threading.Event()

        def cancelled():
            return generation != self._generation

        def loop():
            with ExitStack() as stack:
                if self.h.native:
                    try:
                        stack.enter_context(self.h.native.notifications(wake))
                    except OSError as exc:
                        self.report(f"Wi-Fi notifications unavailable. Using timed checks. {exc}")
                while not cancelled():
                    wake.clear()
                    try:
                        state = self.step(cancelled)
                        interval = (
                            self.every
                            if state != "waiting"
                            else max(
                                1,
                                min(
                                    self.every,
                                    max(self.down_since + self.after, self.retry_at) - self.clock(),
                                ),
                            )
                        )
                    except OSError as exc:
                        self.report(f"Network check failed: {exc}")
                        interval = self.every
                    if not cancelled():
                        start = self.clock()
                        wake.wait(interval)
                        if self.clock() - start > interval + 30:
                            self.down_since = None
                            self.retry_at, self.delay = 0, 15
                        if wake.is_set() and not cancelled():
                            time.sleep(0.35)

        self.thread = threading.Thread(target=loop, name="hotspot-policy", daemon=True)
        self.thread.start()

    def stop(self):
        self._generation += 1
        self._wake.set()
        self.thread = None
