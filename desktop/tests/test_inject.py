from aircursor_desktop.inject import Controller


class Fake:
    def __init__(self):
        self.log = []
        self.pos = (500, 300)

    def screen(self):
        return 1880, 1000, 0, 0

    def cursor(self):
        return self.pos

    def move_to(self, x, y):
        self.pos = (x, y)
        self.log.append(("move", x, y))

    def button(self, b, down):
        self.log.append(("btn", b, down))

    def wheel(self, d):
        self.log.append(("wheel", d))

    def hwheel(self, d):
        self.log.append(("hwheel", d))

    def key(self, vk, down, ext):
        self.log.append(("key", vk, down))

    def unicode(self, u, down):
        self.log.append(("uni", u, down))

    def vk_for_char(self, ch):
        return None


def test_pointer_speed_and_edges():
    f = Fake()
    c = Controller(f)
    c.move(47, 0)                       # 1880 / 470 = 4 px per point
    assert f.pos == (688, 300)
    c.move(-10000 / 4, 10000)           # far past the edges: stays on the screen
    assert f.pos == (0, 999)


def test_a_real_mouse_move_in_between_is_respected():
    f = Fake()
    c = Controller(f)
    c.move(1, 0)
    f.pos = (100, 100)                  # the person moved the real mouse
    c.move(1, 0)
    assert f.pos == (104, 100)


def test_two_finger_scroll_follows_the_fingers():
    f = Fake()
    c = Controller(f)
    c.scroll(0, -50)                    # fingers up → page up → scroll down (negative wheel)
    assert f.log[-1][0] == "wheel" and f.log[-1][1] < 0
    c.wheel(1, 0)                       # one notch down
    assert f.log[-1] == ("wheel", -120)


def test_ctrl_c_is_the_c_key_and_plain_letters_are_typed():
    f = Fake()
    c = Controller(f)
    c.key_state(0xFFE3, True)           # Ctrl held on the phone keyboard
    c.key(ord("c"))
    c.key_state(0xFFE3, False)
    assert ("key", 0x43, True) in f.log and ("key", 0xA2, False) in f.log
    f.log.clear()
    c.key(ord("c"))
    assert f.log == [("uni", ord("c"), True), ("uni", ord("c"), False)]


def test_text_with_emoji_and_new_lines():
    f = Fake()
    Controller(f).text("a\n👋")
    assert ("key", 0x0D, True) in f.log
    units = [e[1] for e in f.log if e[0] == "uni" and e[2]]
    assert units == [ord("a"), 0xD83D, 0xDC4B]


def test_everything_held_is_let_go_when_the_phone_leaves():
    f = Fake()
    c = Controller(f)
    c.button("l", True)
    c.key_state(0xFFE9, True)           # Alt
    f.log.clear()
    c.release_all()
    assert ("btn", "l", False) in f.log and ("key", 0xA4, False) in f.log
