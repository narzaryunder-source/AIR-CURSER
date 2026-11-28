import os
import pytest

from aircursor_desktop import hotspot
from aircursor_desktop.settings import Settings

IFACE = """
There is 1 interface on the system:

    Name                   : Wi-Fi
    Description            : Intel(R) Wi-Fi 6 AX201 160MHz
    GUID                   : 1234
    Physical address       : aa:bb:cc:dd:ee:ff
    Interface type         : Primary
    State                  : connected
    SSID                   : Rahul's Phone
    AP BSSID               : 11:22:33:44:55:66
    Band                   : 2.4 GHz
    Authentication         : WPA2-Personal
    Signal                 : 92%
    Profile                : Rahul's Phone
"""
NETS = """
Interface name : Wi-Fi
There are 3 networks currently visible.

SSID 1 : HomeWifi
    Network type            : Infrastructure
    Authentication          : WPA2-Personal
    Encryption              : CCMP
    BSSID 1                 : aa:aa:aa:aa:aa:aa
         Signal             : 40%
         Radio type         : 802.11ac

SSID 2 : Rahul's Phone
    Network type            : Infrastructure
    Authentication          : WPA3-Personal
    Encryption              : CCMP
    BSSID 1                 : bb:bb:bb:bb:bb:bb
         Signal             : 88%

SSID 3 :
    Network type            : Infrastructure
    Authentication          : WPA2-Personal
"""


def test_reads_windows_wifi_state():
    w = hotspot.parse_interface(IFACE)
    assert w == {"state": "connected", "ssid": "Rahul's Phone", "signal": "92%", "name": "Wi-Fi"}
    assert hotspot.parse_interface("The Wireless AutoConfig Service (wlansvc) is not running.") is None


def test_lists_networks_strongest_first_without_hidden_ones():
    nets = hotspot.parse_networks(NETS)
    assert [n["ssid"] for n in nets] == ["Rahul's Phone", "HomeWifi"]
    assert nets[0]["auth"] == "WPA3-Personal" and nets[0]["signal"] == 88


def test_profile_is_safe_xml_and_picks_wpa3_only_when_needed():
    x = hotspot.profile_xml('A&B <Phone>', 'p"w<d>&1', "WPA2-Personal")
    assert "<name>A&amp;B &lt;Phone&gt;</name>" in x and "p\"w&lt;d&gt;&amp;1" in x
    assert "WPA2PSK" in x and "<connectionMode>manual</connectionMode>" in x
    assert "WPA3SAE" in hotspot.profile_xml("x", "12345678", "WPA3-Personal")
    assert "WPA2PSK" in hotspot.profile_xml("x", "12345678", "WPA2-Personal / WPA3-Personal")


class FakeNetsh:
    def __init__(self):
        self.calls, self.files = [], []
        self.iface, self.nets = IFACE.replace("Rahul's Phone", "HomeWifi"), NETS

    def __call__(self, args):
        self.calls.append(args)
        if args[:3] == ("wlan", "show", "interfaces"):
            return 0, self.iface
        if args[:3] == ("wlan", "show", "networks"):
            return 0, self.nets
        if args[:3] == ("wlan", "add", "profile"):
            path = args[3].split("=", 1)[1]
            self.files.append((path, open(path, encoding="utf-8").read()))
        if args[:2] == ("wlan", "connect"):
            self.iface = IFACE
        return 0, "ok"


def test_saving_the_phone_keeps_no_password_on_disk(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    n = FakeNetsh()
    h = hotspot.Hotspot(s, runner=n, online=lambda: True, sleep=lambda _: None)
    assert "8 to 63" in h.save_phone("Rahul's Phone", "short")
    assert h.save_phone("Rahul's Phone", "hotspot-pass-1", "WPA3-Personal") == ""
    path, xml = n.files[0]
    assert "hotspot-pass-1" in xml and not os.path.exists(path), "the file with the password is gone"
    assert "hotspot-pass-1" not in (tmp_path / "s.json").read_text()
    assert h.phone_ssid == "Rahul's Phone"
    assert h.connect() == "" and ("wlan", "connect", "name=Rahul's Phone", "ssid=Rahul's Phone") in n.calls


def test_auto_switch_joins_the_phone_after_the_internet_is_gone_for_a_while(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Rahul's Phone")
    n = FakeNetsh()
    state = {"online": True, "t": 0.0}
    h = hotspot.Hotspot(s, runner=n, online=lambda: state["online"] or n.iface == IFACE)
    said = []
    a = hotspot.AutoSwitch(h, report=said.append, after=20, clock=lambda: state["t"])
    assert a.step() == "online"
    state["online"] = False
    assert a.step() == "waiting"
    state["t"] = 25
    assert a.step() == "switched" and "Internet verified" in said[-1]
    assert ("wlan", "connect", "name=Rahul's Phone", "ssid=Rahul's Phone") in n.calls
    state["t"] = 40
    assert a.step() == "online"
    assert sum(c[:2] == ("wlan", "connect") for c in n.calls) == 1


def test_auto_switch_says_when_the_phone_is_not_in_range(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Other Phone")
    n = FakeNetsh()
    state = {"t": 0.0}
    a = hotspot.AutoSwitch(hotspot.Hotspot(s, runner=n, online=lambda: False), after=0, clock=lambda: state["t"])
    state["t"] = 100
    assert a.step() == "not-in-range"
    assert not any(c[:2] == ("wlan", "connect") for c in n.calls)


def test_request_accepted_but_wrong_ssid_is_failure(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Phone")
    net = FakeNetsh()
    h = hotspot.Hotspot(s, runner=net, online=lambda: True, sleep=lambda _: None)
    assert "not confirmed" in h.connect()


def test_association_without_internet_is_reported(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Rahul's Phone")
    h = hotspot.Hotspot(s, runner=FakeNetsh(), online=lambda: False, sleep=lambda _: None)
    assert "Internet access could not be verified" in h.connect()


@pytest.mark.parametrize(("associated_at", "route_at", "expected"), [
    (0.2, 0.2, 0.25),
    (2.2, 3.0, 3.0),
])
def test_native_association_and_late_route_are_separate(tmp_path, associated_at, route_at, expected):
    settings = Settings(str(tmp_path / "settings.json"))
    settings.set(phone_ssid="Phone", phone_adapter="61")
    stamp = 0
    requested = False

    def advance(delay):
        nonlocal stamp
        stamp += delay

    class Wlan:
        def interfaces(self):
            ssid = "Phone" if requested and stamp >= associated_at else "Home"
            return [{"id": b"a", "ssid": ssid, "state": "connected"}]

        def networks(self):
            return [{"id": b"a", "ssid": "Phone"}]

        def connect(self, ssid, adapter):
            nonlocal requested
            assert (ssid, adapter) == ("Phone", b"a")
            requested = True

    hot = hotspot.Hotspot(settings, runner=FakeNetsh(), sleep=advance)
    hot.native = Wlan()
    hot.verify_adapter = lambda _: stamp >= route_at
    assert hot.connect() == ""
    assert stamp == expected

    settings.set(phone_ssid=None)
    policy = hotspot.AutoSwitch(hot)
    policy.start()
    assert policy.thread is None


def test_backoff_and_recovery(tmp_path):
    t = [0]
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Missing")
    n = FakeNetsh()
    h = hotspot.Hotspot(s, runner=n, online=lambda: False)
    a = hotspot.AutoSwitch(h, after=6, clock=lambda: t[0])
    states = []
    for t[0] in range(122):
        if a.step() == "not-in-range":
            states.append(t[0])
    assert states == [6, 21, 51, 111]
    h.online = lambda: True
    assert a.step() == "online"
    assert a.retry_at == 0 and a.delay == 15
    assert not any(c[:2] == ("wlan", "connect") for c in n.calls)


def test_cancel_before_switch_and_no_profile_is_idle(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    n = FakeNetsh()
    h = hotspot.Hotspot(s, runner=n, online=lambda: False)
    a = hotspot.AutoSwitch(h, after=0)
    assert a.step() == "idle"
    s.set(phone_ssid="Rahul's Phone")
    assert a.step(lambda: True) == "idle"
    assert not n.calls


def test_native_save_keeps_password_entirely_in_windows(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    h = hotspot.Hotspot(s, runner=FakeNetsh())
    class Native:
        def save(self, xml, adapter):
            assert "hotspot-secret" in xml and "WPA3SAE" in xml
            return adapter.hex()
    h.native = Native()
    assert not h.save_phone("Phone", "hotspot-secret", "WPA3", b"ABC")
    assert s.get("phone_adapter") == "414243"
    assert "hotspot-secret" not in (tmp_path / "s.json").read_text()


def test_active_transfer_defers_failover(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Rahul's Phone")
    n = FakeNetsh()
    h = hotspot.Hotspot(s, runner=n, online=lambda: False)
    h.traffic_busy = lambda: True
    assert hotspot.AutoSwitch(h, after=0).step() == "in-use"
    assert not n.calls


def test_previous_wifi_requires_two_stable_sightings_and_idle_link(tmp_path):
    t = [100]
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Phone", return_wifi=True)
    h = hotspot.Hotspot(s,runner=FakeNetsh(),online=lambda:True)
    h.on_phone = lambda: True
    h.networks = lambda: [dict(ssid="Home",id=b"adapter",signal=80)]
    restored = []
    h.restore = lambda prior, cancelled: restored.append(prior["profile"]) or True
    policy = hotspot.AutoSwitch(h, clock=lambda:t[0])
    policy.previous = dict(ssid="Home",id=b"adapter",profile="Known home")
    policy.step()
    assert not restored
    t[0] += 60
    h.traffic_busy=lambda:True
    policy.step()
    assert not restored
    h.traffic_busy=lambda:False
    for _ in range(2):
        t[0] += 60
        policy.step()
    assert restored==["Known home"] and policy.previous is None
