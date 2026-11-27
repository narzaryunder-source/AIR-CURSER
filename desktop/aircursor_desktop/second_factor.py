"""Optional RFC 6238 phone-authenticator enrollment for the Windows workspace password."""

import base64
import hashlib
import hmac
import secrets
import math
import time

from urllib.parse import quote, urlencode

from . import trust


def recovery_codes():
    codes = [secrets.token_hex(16).upper() for _ in range(8)]
    return ["-".join(value[i : i + 8] for i in range(0, len(value), 8)) for value in codes]


def enrollment():
    return base64.b32encode(secrets.token_bytes(20)).decode("ascii"), recovery_codes()


def enrollment_uri(secret, account):
    label = "AirCursor:" + quote(account, safe="")
    query = urlencode(dict(secret=secret, issuer="AirCursor", algorithm="SHA1", digits=6, period=30))
    return f"otpauth://totp/{label}?{query}"


def alternative(settings):
    return bool(settings.get("phone_factor", {}).get("alternative"))


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


def enable(settings, secret, recovery, entered, *, allow_alternative=True):
    counter = match(secret, entered.strip(), time.time())
    if counter is None:
        raise ValueError("The code did not match. Check the phone’s time and authenticator settings.")
    sealed = base64.b64encode(trust.protect(secret.encode("ascii"))).decode("ascii")
    settings.set(
        phone_factor={
            "secret": sealed,
            "recovery_codes": [recovery_hash(value) for value in recovery],
            "last": counter,
            "alternative": allow_alternative,
        },
        trusted_logon=None,
        factor_failures=None,
        factor_retry=None,
    )


def renew_recovery(settings, codes):
    factor = dict(settings.get("phone_factor"))
    factor.pop("recovery", None)
    factor["recovery_codes"] = [recovery_hash(value) for value in codes]
    settings.set(phone_factor=factor, trusted_logon=None)


def verify(settings, entered):
    factor = settings.get("phone_factor")
    if not factor:
        raise ValueError("Phone authentication is not enabled.")
    now = time.time()
    wait = math.ceil(settings.get("factor_retry", 0) - now)
    if wait > 0:
        raise ValueError(f"Try the authenticator again in {wait} seconds.")
    value = entered.strip()
    if len(value) <= 64:
        digest = recovery_hash(value)
        remaining = factor.get("recovery_codes", [])
        matched = [item for item in remaining if hmac.compare_digest(digest, item)]
        if matched:
            settings.set(
                phone_factor={
                    **factor,
                    "recovery_codes": [item for item in remaining if item not in matched],
                },
                trusted_logon=None,
                factor_failures=None,
                factor_retry=None,
            )
            return "recovered"
        if "recovery" in factor and hmac.compare_digest(digest, factor["recovery"]):
            disable(settings)
            return "legacy_recovery"
        secret = trust.protect(base64.b64decode(factor["secret"], validate=True), decrypt=True).decode(
            "ascii"
        )
        counter = match(secret, value, now, factor["last"])
        if counter is not None:
            settings.set(phone_factor={**factor, "last": counter}, factor_failures=None, factor_retry=None)
            return "verified"
    failed = min(10, settings.get("factor_failures", 0) + 1)
    settings.set(factor_failures=failed, factor_retry=now + min(300, 2 ** (failed - 1)))
    raise ValueError("Use a fresh six-digit authenticator code or an unused recovery code.")


def disable(settings):
    settings.set(phone_factor=None, factor_failures=None, factor_retry=None, trusted_logon=None)
