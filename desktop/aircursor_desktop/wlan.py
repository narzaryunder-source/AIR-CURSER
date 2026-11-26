"""Small, synchronous Native Wi-Fi adapter. Windows owns profile keys and scan scheduling."""

import ctypes as c
from contextlib import contextmanager
from ctypes import wintypes as w

DWORD = w.DWORD
GUID = c.c_ubyte * 16
NAME = c.c_wchar * 256


class Interface(c.Structure):
    _fields_ = [("guid", GUID), ("description", NAME), ("state", DWORD)]


class SSID(c.Structure):
    _fields_ = [("length", DWORD), ("data", c.c_ubyte * 32)]

    def text(self):
        return bytes(self.data[: self.length]).decode("utf-8", errors="replace")


class Network(c.Structure):
    _fields_ = [
        ("profile", NAME),
        ("ssid", SSID),
        ("bss", DWORD),
        ("bssids", DWORD),
        ("connectable", w.BOOL),
        ("reason", DWORD),
        ("phy_count", DWORD),
        ("phy_types", DWORD * 8),
        ("more_phy", w.BOOL),
        ("signal", DWORD),
        ("security", w.BOOL),
        ("auth", DWORD),
        ("cipher", DWORD),
        ("flags", DWORD),
        ("reserved", DWORD),
    ]


class Association(c.Structure):
    _fields_ = [
        ("ssid", SSID),
        ("bss", DWORD),
        ("bssid", c.c_ubyte * 6),
        ("phy", DWORD),
        ("phy_index", DWORD),
        ("signal", DWORD),
        ("rx", DWORD),
        ("tx", DWORD),
    ]


class Connection(c.Structure):
    _fields_ = [("state", DWORD), ("mode", DWORD), ("profile", NAME), ("association", Association)]


class Parameters(c.Structure):
    _fields_ = [
        ("mode", DWORD),
        ("profile", w.LPCWSTR),
        ("ssid", c.c_void_p),
        ("bssid_list", c.c_void_p),
        ("bss", DWORD),
        ("flags", DWORD),
    ]


def check(code):
    if code:
        raise c.WinError(code)


class Wlan:
    def __init__(self):
        self.api = c.WinDLL("wlanapi")

    @contextmanager
    def handle(self):
        handle, version = w.HANDLE(), DWORD()
        check(self.api.WlanOpenHandle(2, None, c.byref(version), c.byref(handle)))
        try:
            yield handle
        finally:
            self.api.WlanCloseHandle(handle, None)

    def _list(self, func, structure, *args):
        ptr = c.c_void_p()
        check(func(*args, c.byref(ptr)))
        try:
            count = DWORD.from_address(ptr.value).value
            items = (structure * count).from_address(ptr.value + 2 * c.sizeof(DWORD))
            return [structure.from_buffer_copy(item) for item in items]
        finally:
            self.api.WlanFreeMemory(ptr)

    def adapters(self, handle):
        return self._list(self.api.WlanEnumInterfaces, Interface, handle, None)

    def interfaces(self):
        rows = []
        with self.handle() as handle:
            for adapter in self.adapters(handle):
                row = dict(
                    name=adapter.description, id=bytes(adapter.guid), state="disconnected", ssid="", signal=""
                )
                if adapter.state == 1:
                    row["state"] = "connected"
                    ptr, size = c.c_void_p(), DWORD()
                    code = self.api.WlanQueryInterface(
                        handle, c.byref(adapter.guid), 7, None, c.byref(size), c.byref(ptr), None
                    )
                    if not code:
                        try:
                            conn = Connection.from_address(ptr.value)
                            row.update(
                                profile=conn.profile,
                                ssid=conn.association.ssid.text(),
                                signal=f"{conn.association.signal}%",
                            )
                        finally:
                            self.api.WlanFreeMemory(ptr)
                rows.append(row)
        return rows

    def networks(self):
        rows = []
        with self.handle() as handle:
            for adapter in self.adapters(handle):
                try:
                    nets = self._list(
                        self.api.WlanGetAvailableNetworkList, Network, handle, c.byref(adapter.guid), 0, None
                    )
                except OSError:
                    if len(self.adapters(handle)) == 1:
                        raise
                    continue
                for net in nets:
                    if net.ssid.length and net.connectable:
                        rows.append(
                            dict(
                                ssid=net.ssid.text(),
                                signal=net.signal,
                                id=bytes(adapter.guid),
                                auth={7: "WPA2-Personal", 9: "WPA3-Personal"}.get(net.auth, ""),
                            )
                        )
        return sorted(rows, key=lambda net: -net["signal"])

    def save(self, xml, adapter_id):
        with self.handle() as handle:
            adapters = self.adapters(handle)
            selected = next((a for a in adapters if bytes(a.guid) == adapter_id), None)
            if not selected and adapters:
                selected = adapters[0]
            if not selected:
                raise OSError("No wireless adapter is available.")
            reason = DWORD()
            check(
                self.api.WlanSetProfile(
                    handle, c.byref(selected.guid), 2, c.c_wchar_p(xml), None, True, None, c.byref(reason)
                )
            )
            return bytes(selected.guid).hex()

    def connect(self, ssid, adapter_id):
        guid = GUID.from_buffer_copy(adapter_id)
        params = Parameters(0, ssid, None, None, 1, 0)
        with self.handle() as handle:
            check(self.api.WlanConnect(handle, c.byref(guid), c.byref(params), None))

    @contextmanager
    def notifications(self, event):
        # The callback only wakes the policy worker. No WLAN calls on the RPC callback thread.
        callback = c.WINFUNCTYPE(None, c.c_void_p, c.c_void_p)

        def changed(data, context):
            source, code = (DWORD * 2).from_address(data)
            if source == 8 and code in (10, 13, 14, 21):
                event.set()

        listener = callback(changed)
        with self.handle() as handle:
            check(self.api.WlanRegisterNotification(handle, 8, True, listener, None, None, None))
            try:
                yield
            finally:
                self.api.WlanRegisterNotification(handle, 0, True, None, None, None, None)
