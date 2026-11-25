import base64

from aircursor_desktop import protocol


def test_hello_and_codes():
    assert protocol.parse_hello(protocol.hello("123456")) == "123456"
    for bad in ("HELLO 1 12345", "HELLO 2 123456", "HELLO 1 12a456", "HI 1 123456", ""):
        assert protocol.parse_hello(bad) is None
    c = protocol.new_code()
    assert len(c) == 6 and c.isdigit()


def test_commands_like_the_phone_sends_them():
    t = base64.b64encode("नमस्ते 👋".encode()).decode()
    assert protocol.parse("M 12.5 -3") == ("move", 12.5, -3.0)
    assert protocol.parse("S 0 -40") == ("scroll", 0.0, -40.0)
    assert protocol.parse("SE") == ("scroll_end",)
    assert protocol.parse("B l d") == ("button", "l", True)
    assert protocol.parse("C r") == ("click", "r")
    assert protocol.parse("W 2 -1") == ("wheel", 2, -1)
    assert protocol.parse("K 65293") == ("key", 0xFF0D)
    assert protocol.parse("KD 65507") == ("key_state", 0xFFE3, True)
    assert protocol.parse("T " + t) == ("text", "नमस्ते 👋")
    assert protocol.parse("P") == ("ping",)


def test_rejects_junk_and_out_of_range():
    for bad in ("M 1", "M nan 0", "M 99999 0", "B x d", "W 99 0", "K 5", "T !!", "Z", "C l x", "T " + "A" * 9000):
        assert protocol.parse(bad) is None, bad


def test_pairing_gate_pauses_and_renews():
    code = {"v": "111111"}
    renewed, slept = [], []
    g = protocol.PairingGate(lambda: code["v"], lambda: renewed.append(1), max_wrong=3, pause=1, sleep=slept.append)
    assert not g.check("000000") and not g.check(None)
    assert g.check("111111") and slept == [1, 1]
    for _ in range(3):
        g.check("000000")
    assert renewed == [1]
