"""Optional RFC 6238 phone-authenticator enrollment for the Windows workspace password."""

import base64
import hashlib
import hmac
import secrets
import time

from . import trust


def enrollment():
    secret = base64.b32encode(secrets.token_bytes(20)).decode("ascii")
    recovery = secrets.token_hex(16).upper()
    return secret, "-".join(recovery[i : i + 8] for i in range(0, len(recovery), 8))


def code(secret, counter):
    key = base64.b32decode(secret)
    digest = hmac.digest(key, counter.to_bytes(8, "big"), "sha1")
    offset = digest[-1] & 15
    truncated = int.from_bytes(digest[offset : offset + 4], "big") & 0x7FFFFFFF
    return f"{truncated % 1000000:06d}"


def match(secret, text, now, last=-1):
    if len(text) == 6 and text.isascii() and text.isdigit():
        for counter in range(int(now) // 30 - 1, int(now) // 30 + 2):
            if counter >= 0 and counter > last and hmac.compare_digest(code(secret, counter), text):
                return counter
    return None


def recovery_hash(text):
    return hashlib.sha256(text.replace("-", "").strip().upper().encode("utf-8")).hexdigest()


def enable(settings, secret, recovery, entered):
    counter = match(secret, entered.strip(), time.time())
    if counter is None:
        raise ValueError("The code did not match. Check the phone’s time and authenticator settings.")
    sealed = base64.b64encode(trust.protect(secret.encode("ascii"))).decode("ascii")
    settings.set(
        phone_factor={"secret": sealed, "recovery": recovery_hash(recovery), "last": counter},
        trusted_logon=None,
        factor_failures=None,
        factor_retry=None,
    )


def verify(settings, entered):
    factor = settings.get("phone_factor")
    if not factor:
        raise ValueError("Phone authentication is not enabled.")
    now = time.time()
    if now < settings.get("factor_retry", 0):
        raise ValueError("Wait before trying another authenticator code.")
    value = entered.strip()
    if hmac.compare_digest(recovery_hash(value), factor["recovery"]):
        disable(settings)
        return "recovered"
    secret = trust.protect(base64.b64decode(factor["secret"], validate=True), decrypt=True).decode("ascii")
    counter = match(secret, value, now, factor["last"])
    if counter is None:
        failed = min(6, settings.get("factor_failures", 0) + 1)
        settings.set(factor_failures=failed, factor_retry=now + min(30, 2 ** (failed - 1)))
        raise ValueError("Use a new six-digit authenticator code or your saved recovery code.")
    settings.set(phone_factor={**factor, "last": counter}, factor_failures=None, factor_retry=None)
    return "verified"


def disable(settings):
    settings.set(phone_factor=None, factor_failures=None, factor_retry=None, trusted_logon=None)
