"""The app's password: kept only as a salted PBKDF2-SHA256 hash, never as the password itself.

Wrong passwords cost a growing wait (1, 2, 4 … 30 seconds), counted on disk, so closing and opening
the app does not reset it.
"""

import hashlib
import hmac
import math
import os
import time

ITERATIONS = 900_000
MIN_LENGTH = 15
MAX_LENGTH = 1024
MAX_WAIT = 300


def _hash(password, salt, iterations):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations).hex()


class PasswordLock:
    def __init__(self, settings, clock=time.time):
        self.s = settings
        self.clock = clock

    @property
    def has_password(self):
        return bool(self.s.get("password"))

    @staticmethod
    def problem(password, again=None):
        """Why a new password cannot be used (a message), or None when it is fine."""
        if len(password) < MIN_LENGTH:
            return "Use at least %d characters." % MIN_LENGTH
        if len(password) > MAX_LENGTH:
            return f"Use at most {MAX_LENGTH} characters."
        if password.strip() != password:
            return "No spaces at the start or the end."
        if again is not None and again != password:
            return "The two passwords are not the same."
        return None

    def set_password(self, password, again=None):
        err = self.problem(password, again)
        if err:
            raise ValueError(err)
        self._store(password)

    def _store(self, password):
        salt = os.urandom(16)
        self.s.set(
            password={
                "salt": salt.hex(),
                "iterations": ITERATIONS,
                "hash": _hash(password, salt, ITERATIONS),
            },
            wrong=0,
            wait_until=0,
            trusted_logon=None,
        )

    def wait_left(self):
        """Seconds until the next try is allowed (0 = now)."""
        return max(0, math.ceil(float(self.s.get("wait_until", 0)) - self.clock()))

    def check(self, password):
        """True for the right password. A wrong one (or a try while waiting) returns False."""
        p = self.s.get("password")
        if not p or self.wait_left() > 0:
            return False
        good = len(password) <= MAX_LENGTH and hmac.compare_digest(
            _hash(password, bytes.fromhex(p["salt"]), int(p["iterations"])), p["hash"]
        )
        if good:
            if int(p["iterations"]) < ITERATIONS:
                self._store(password)
            else:
                self.s.set(wrong=0, wait_until=0)
            return True
        wrong = int(self.s.get("wrong", 0)) + 1
        wait = 0 if wrong < 3 else min(MAX_WAIT, 2 ** min(9, wrong - 3))
        self.s.set(wrong=wrong, wait_until=self.clock() + wait)
        return False

    def change(self, old, new, again=None):
        if not self.check(old):
            raise ValueError("The current password is wrong.")
        self.set_password(new, again)
