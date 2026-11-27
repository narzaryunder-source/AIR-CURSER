"""Optional eight-hour re-entry ticket bound to this Windows logon, boot and password revision."""

import base64
import ctypes as c
from ctypes import wintypes as w
import json
import os
import time

import psutil

DURATION = 8 * 3600


class Blob(c.Structure):
    _fields_ = [("size", w.DWORD), ("data", c.c_void_p)]


def protect(data, decrypt=False):
    buf = c.create_string_buffer(data)
    src, dst = Blob(len(data), c.addressof(buf)), Blob()
    api = c.WinDLL("crypt32", use_last_error=True)
    method = api.CryptUnprotectData if decrypt else api.CryptProtectData
    if not method(c.byref(src), None, None, None, None, 1, c.byref(dst)):
        raise c.WinError(c.get_last_error())
    try:
        return c.string_at(dst.data, dst.size)
    finally:
        c.windll.kernel32.LocalFree(c.c_void_p(dst.data))


def logon():
    kernel = c.windll.kernel32
    api = c.WinDLL("advapi32", use_last_error=True)
    handle = w.HANDLE()
    if not api.OpenProcessToken(w.HANDLE(-1), 8, c.byref(handle)):
        raise c.WinError(c.get_last_error())
    try:
        # TOKEN_STATISTICS starts with TokenId (LUID) followed by AuthenticationId (LUID).
        raw = c.create_string_buffer(128)
        used = w.DWORD()
        if not api.GetTokenInformation(handle, 10, raw, len(raw), c.byref(used)):
            raise c.WinError(c.get_last_error())
        return raw.raw[8:16].hex()
    finally:
        kernel.CloseHandle(handle)


def remember(settings):
    ticket = [
        logon(),
        int(psutil.boot_time()),
        settings.get("password")["hash"],
        settings.get("phone_factor", {}).get("secret"),
        time.time(),
    ]
    settings.set(trusted_logon=base64.b64encode(protect(json.dumps(ticket).encode())).decode("ascii"))


def active(settings):
    token = settings.get("trusted_logon")
    if os.name != "nt" or not token or not settings.get("password"):
        return False
    try:
        session, boot, revision, factor_revision, issued = json.loads(
            protect(base64.b64decode(token, validate=True), True)
        )
        return (
            session == logon()
            and abs(boot - psutil.boot_time()) < 2
            and revision == settings.get("password")["hash"]
            and factor_revision == settings.get("phone_factor", {}).get("secret")
            and 0 <= time.time() - issued < DURATION
        )
    except (OSError, ValueError, KeyError, TypeError):
        return False
