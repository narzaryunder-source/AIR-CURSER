import os

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
        return 0, "ok"


def test_saving_the_phone_keeps_no_password_on_disk(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    n = FakeNetsh()
    h = hotspot.Hotspot(s, runner=n)
    assert "8 to 63" in h.save_phone("Rahul's Phone", "short")
    assert h.save_phone("Rahul's Phone", "hotspot-pass-1", "WPA3-Personal") == ""
    path, xml = n.files[0]
    assert "hotspot-pass-1" in xml and not os.path.exists(path), "the file with the password is gone"
    assert "hotspot-pass-1" not in (tmp_path / "s.json").read_text()
    assert h.phone_ssid == "Rahul's Phone"
    assert h.connect() == "" and n.calls[-1] == ("wlan", "connect", "name=Rahul's Phone", "ssid=Rahul's Phone")


def test_auto_switch_joins_the_phone_after_the_internet_is_gone_for_a_while(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Rahul's Phone")
    n = FakeNetsh()
    state = {"online": True, "t": 0.0}
    h = hotspot.Hotspot(s, runner=n, online=lambda: state["online"])
    said = []
    a = hotspot.AutoSwitch(h, report=said.append, after=20, clock=lambda: state["t"])
    assert a.step() == "online"
    state["online"] = False
    assert a.step() == "waiting"
    state["t"] = 25
    assert a.step() == "switched" and "switched to your phone" in said[-1]
    assert n.calls[-1][:2] == ("wlan", "connect")
    state["t"] = 40
    assert a.step() == "waiting", "no new try within a minute"


def test_auto_switch_says_when_the_phone_is_not_in_range(tmp_path):
    s = Settings(str(tmp_path / "s.json"))
    s.set(phone_ssid="Other Phone")
    n = FakeNetsh()
    state = {"t": 0.0}
    a = hotspot.AutoSwitch(hotspot.Hotspot(s, runner=n, online=lambda: False), after=0, clock=lambda: state["t"])
    state["t"] = 100
    assert a.step() == "not-in-range"
    assert not any(c[:2] == ("wlan", "connect") for c in n.calls)
