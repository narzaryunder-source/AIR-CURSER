import pytest

from aircursor_desktop.lock import PasswordLock
from aircursor_desktop.settings import Settings


@pytest.fixture
def lock(tmp_path):
    t = {"now": 1000.0}
    lk = PasswordLock(Settings(str(tmp_path / "s.json")), clock=lambda: t["now"])
    lk.t = t
    return lk


def test_password_is_kept_only_as_a_hash(lock, tmp_path):
    lock.set_password("mango-basket-1234", "mango-basket-1234")
    text = (tmp_path / "s.json").read_text()
    assert "mango-basket-1234" not in text and lock.has_password
    assert lock.check("mango-basket-1234")


def test_rules_for_a_new_password(lock):
    assert lock.problem("short-but-weak")  # too short
    assert lock.problem(" leading-master-password")  # space at the start
    assert lock.problem("abcd", "abce")  # not the same twice
    assert lock.problem("long-master-password", "long-master-password") is None
    with pytest.raises(ValueError):
        lock.set_password("ab")


def test_wrong_passwords_make_you_wait_longer(lock):
    lock.set_password("secret-kiwi-lime-1")
    assert not lock.check("x") and lock.wait_left() == 0
    assert not lock.check("y") and lock.wait_left() == 0
    assert not lock.check("z") and lock.wait_left() == 1
    assert not lock.check("secret-kiwi-lime-1"), "even the right one waits"
    lock.t["now"] += 1
    assert not lock.check("w") and lock.wait_left() == 2
    lock.t["now"] += 100
    assert lock.check("secret-kiwi-lime-1") and lock.wait_left() == 0


def test_the_wait_survives_a_restart(lock, tmp_path):
    lock.set_password("secret-kiwi-lime-1")
    for p in "abcd":
        lock.check(p)
    again = PasswordLock(Settings(str(tmp_path / "s.json")), clock=lambda: lock.t["now"])
    assert again.wait_left() > 0


def test_change_needs_the_old_password(lock):
    lock.set_password("first-master-password")
    with pytest.raises(ValueError):
        lock.change("nope", "second-master-password", "second-master-password")
    lock.t["now"] += 100
    lock.change("first-master-password", "second-master-password", "second-master-password")
    assert lock.check("second-master-password")


def test_existing_hash_is_upgraded_only_after_successful_login(lock):
    import hashlib
    from aircursor_desktop.lock import ITERATIONS

    salt = b"previous-hash-salt"
    digest = hashlib.pbkdf2_hmac("sha256", b"migrate-me", salt, 200000).hex()
    lock.s.set(password={"salt": salt.hex(), "iterations": 200000, "hash": digest}, phone_ssid="Saved")
    assert not lock.check("incorrect")
    assert lock.s.get("password")["iterations"] == 200000
    assert lock.check("migrate-me")
    assert lock.s.get("password")["iterations"] == ITERATIONS
    assert lock.s.get("phone_ssid") == "Saved"


def test_legacy_short_password_remains_valid_and_migrates(lock):
    import hashlib
    from aircursor_desktop.lock import ITERATIONS

    salt = b"legacy-profile-salt"
    digest = hashlib.pbkdf2_hmac("sha256", b"abcd", salt, 200000).hex()
    lock.s.set(password={"salt": salt.hex(), "iterations": 200000, "hash": digest})
    assert lock.check("abcd")
    assert lock.s.get("password")["iterations"] == ITERATIONS
    with pytest.raises(ValueError):
        lock.set_password("abcd")


def test_password_delay_cannot_be_rounded_down(lock):
    lock.set_password("long-test-master")
    lock.s.set(wait_until=lock.t["now"] + 0.1)
    assert lock.wait_left() == 1
    assert not lock.check("long-test-master")
