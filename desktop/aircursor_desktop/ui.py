"""AirCursor for Windows: the window (Tkinter, part of Python on Windows – nothing to install)."""
import os
import queue
import sys
import threading
import tkinter as tk
import webbrowser

from . import VERSION
from .hotspot import AutoSwitch, Hotspot, internet_ok
from .inject import Controller, backend
from .lock import PasswordLock
from .server import RemoteHost, local_addresses
from .settings import Settings

BG, CARD, LINE, FIELD = "#0E1016", "#171A22", "#262B38", "#0A0C11"
TEXT, MUTED, FAINT = "#F2F4F8", "#A4A9B6", "#6E7383"
BLUE, BLUE_HI, GREEN, RED, AMBER = "#4F5BD5", "#6672F0", "#3DD68C", "#FF5F57", "#F5B83D"
PHONE_APP = "https://narzaryunder-source.github.io/AIR-CURSER/"
FONT = "Segoe UI" if sys.platform == "win32" else "DejaVu Sans"


def asset(name):
    base = getattr(sys, "_MEIPASS", os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    return os.path.join(base, "assets", name)


class NullBackend:
    """Off Windows (screenshots, tests): nothing is sent to a real mouse."""

    def screen(self):
        return 1920, 1080, 0, 0

    def cursor(self):
        return 0, 0

    def __getattr__(self, name):
        return lambda *a, **k: None


def font(size, bold=False):
    return (FONT, size, "bold") if bold else (FONT, size)


class Button(tk.Label):
    """A flat, rounded-looking button that looks the same everywhere."""

    def __init__(self, master, text, command, kind="primary", **kw):
        colors = {"primary": (BLUE, BLUE_HI, "white"), "ghost": ("#232838", "#2E3447", TEXT),
                  "danger": ("#3A1E24", "#4A252C", "#FF8A84")}[kind]
        self.base, self.hover, fg = colors
        self.fg, self.on_base = fg, self.base
        super().__init__(master, text=text, bg=self.base, fg=fg, font=font(10, True), padx=16, pady=8,
                         cursor="hand2", **kw)
        self.command = command
        self.enabled = True
        self.bind("<Enter>", lambda e: self.enabled and self.config(bg=self.hover))
        self.bind("<Leave>", lambda e: self.config(bg=self.base))
        self.bind("<Button-1>", lambda e: self.enabled and self.command())

    def set_enabled(self, on):
        self.enabled = on
        self.base = self.on_base if on else "#232838"
        self.config(fg=self.fg if on else FAINT, bg=self.base, cursor="hand2" if on else "arrow")


def entry(master, show="•"):
    return tk.Entry(master, show=show, bg=FIELD, fg=TEXT, insertbackground=TEXT, relief="flat", font=font(12),
                    highlightthickness=1, highlightbackground=LINE, highlightcolor=BLUE)


def label(master, text, size=10, color=TEXT, bold=False, bg=None, **kw):
    return tk.Label(master, text=text, fg=color, bg=bg or master.cget("bg"), font=font(size, bold), justify="left",
                    anchor="w", **kw)


class App:
    def __init__(self, root, settings=None, hotspot=None, ctl=None, demo=False):
        self.root = root
        self.s = settings or Settings()
        self.lock = PasswordLock(self.s)
        self.hot = hotspot or Hotspot(self.s)
        self.ctl = ctl or Controller(backend() or NullBackend())
        self.host = RemoteHost(self.ctl, on_change=lambda: self.post(self.refresh_remote))
        self.host.paused = True
        self.auto = AutoSwitch(self.hot, report=lambda m: self.post(lambda: self.toast(m)))
        self.demo = demo
        self.q = queue.Queue()
        root.title("AirCursor")
        root.configure(bg=BG)
        root.geometry("920x640")
        root.minsize(820, 600)
        try:
            root.iconbitmap(asset("aircursor.ico"))
        except tk.TclError:
            pass
        try:
            self.logo = tk.PhotoImage(file=asset("logo96.png")).subsample(2, 2)
        except tk.TclError:
            self.logo = None
        self.body = None
        root.after(50, self._pump)
        root.protocol("WM_DELETE_WINDOW", self.quit)
        self.show_lock()

    # ---- plumbing ----

    def post(self, fn):
        """Runs [fn] on the window's thread (network work happens on other threads)."""
        self.q.put(fn)

    def _pump(self):
        try:
            while True:
                self.q.get_nowait()()
        except queue.Empty:
            pass
        self.root.after(50, self._pump)

    def work(self, job, done):
        """[job] on a background thread, then [done](result) on the window's thread."""
        threading.Thread(target=lambda: self.post(lambda r=job(): done(r)), daemon=True).start()

    def screen(self):
        if self.body:
            self.body.destroy()
        self.body = tk.Frame(self.root, bg=BG)
        self.body.pack(fill="both", expand=True)
        return self.body

    def toast(self, msg, color=None):
        t = tk.Label(self.root, text=msg, bg="#1F2330", fg=color or TEXT, font=font(10), padx=16, pady=10,
                     wraplength=600)
        t.place(relx=0.5, rely=0.96, anchor="s")
        self.root.after(4500, t.destroy)

    def quit(self):
        self.host.stop()
        self.auto.stop()
        self.root.destroy()

    # ---- password ----

    def show_lock(self):
        self.host.paused = True
        b = self.screen()
        box = tk.Frame(b, bg=BG)
        box.place(relx=0.5, rely=0.45, anchor="center")
        if self.logo:
            tk.Label(box, image=self.logo, bg=BG).pack(pady=(0, 14))
        first = not self.lock.has_password
        label(box, "Set a password for AirCursor" if first else "AirCursor is locked", 20, bold=True).pack()
        label(box, "It keeps your phone hotspot and phone control safe on this computer." if first
              else "Enter your password to open it.", 10, MUTED).pack(pady=(4, 18))
        pw = entry(box)
        pw.pack(fill="x", ipady=7)
        again = None
        if first:
            label(box, "Same password again", 9, MUTED).pack(fill="x", pady=(10, 2))
            again = entry(box)
            again.pack(fill="x", ipady=7)
        msg = label(box, "", 9, RED)
        msg.pack(fill="x", pady=(8, 0))

        def go(*_):
            if first:
                err = self.lock.problem(pw.get(), again.get())
                if err:
                    msg.config(text=err)
                    return
                self.lock.set_password(pw.get())
                self.show_main()
                self.toast("Password set. Keep it safe – without it AirCursor can only be reset.", GREEN)
                return
            if self.lock.check(pw.get()):
                self.show_main()
                return
            pw.delete(0, "end")
            wait = self.lock.wait_left()
            msg.config(text="Wrong password." + (" Try again in %d s." % wait if wait else ""))

        Button(box, "Set password" if first else "Unlock", go).pack(fill="x", pady=(12, 0))
        (again or pw).bind("<Return>", go)
        pw.bind("<Return>", go if not first else lambda e: again.focus_set())
        pw.focus_set()
        if not first:
            r = label(box, "Forgot it? Reset AirCursor", 9, FAINT, cursor="hand2")
            r.pack(pady=(16, 0))
            r.bind("<Button-1>", lambda e: self.ask_reset())

    def ask_reset(self):
        d = self.dialog("Reset AirCursor?")
        label(d, "This clears AirCursor's password and settings on this computer and forgets the saved phone "
                 "hotspot. Nothing else on the computer is touched.", 10, MUTED, wraplength=380).pack(fill="x")
        row = tk.Frame(d, bg=CARD)
        row.pack(fill="x", pady=(16, 0))

        def reset():
            self.hot.forget_phone()
            self.s.clear()
            d.destroy()
            self.show_lock()
        Button(row, "Reset", reset, "danger").pack(side="right")
        Button(row, "Cancel", d.destroy, "ghost").pack(side="right", padx=8)

    def dialog(self, title):
        d = tk.Toplevel(self.root, bg=CARD, padx=22, pady=18)
        d.title(title)
        d.transient(self.root)
        d.resizable(False, False)
        label(d, title, 14, bold=True).pack(fill="x", pady=(0, 8))
        d.grab_set()
        d.after(30, lambda: self.center(d))
        return d

    def center(self, d):
        """Over the middle of the AirCursor window."""
        d.update_idletasks()
        r = self.root
        x = r.winfo_rootx() + (r.winfo_width() - d.winfo_width()) // 2
        y = r.winfo_rooty() + (r.winfo_height() - d.winfo_height()) // 3
        d.geometry("+%d+%d" % (max(0, x), max(0, y)))

    # ---- the main screen ----

    def show_main(self):
        self.gen = getattr(self, "gen", 0) + 1   # one status loop per main screen, never two
        b = self.screen()
        top = tk.Frame(b, bg=BG, padx=26, pady=18)
        top.pack(fill="x")
        if self.logo:
            tk.Label(top, image=self.logo, bg=BG).pack(side="left")
        t = tk.Frame(top, bg=BG)
        t.pack(side="left", padx=12)
        label(t, "AirCursor", 18, bold=True).pack(anchor="w")
        label(t, "for Windows · " + VERSION, 9, MUTED).pack(anchor="w")
        Button(top, "Lock", self.lock_now, "ghost").pack(side="right")

        grid = tk.Frame(b, bg=BG, padx=26)
        grid.pack(fill="both", expand=True)
        grid.columnconfigure(0, weight=1, uniform="c")
        grid.columnconfigure(1, weight=1, uniform="c")
        self.build_internet(self.card(grid, 0, 0, "Phone internet", "Your phone's mobile data on this computer"))
        self.build_remote(self.card(grid, 0, 1, "Phone as mouse & keyboard", "Use your phone as a touchpad"))
        foot = tk.Frame(b, bg=BG, padx=26, pady=14)
        foot.pack(fill="x")
        Button(foot, "Change password", self.change_password, "ghost").pack(side="left")
        get = label(foot, "Get AirCursor for your phone  ↗", 10, BLUE_HI, cursor="hand2")
        get.pack(side="right")
        get.bind("<Button-1>", lambda e: webbrowser.open(PHONE_APP))
        self.host.paused = False
        if self.s.get("auto_switch", True):
            self.auto.start()
        if self.s.get("remote_on") and not self.host.running:
            self.start_remote()
        self.refresh_internet(self.gen)
        self.refresh_remote()

    def card(self, grid, row, col, title, sub):
        c = tk.Frame(grid, bg=CARD, padx=20, pady=18, highlightthickness=1, highlightbackground=LINE)
        c.grid(row=row, column=col, sticky="nsew", padx=(0 if col == 0 else 8, 8 if col == 0 else 0), pady=4)
        grid.rowconfigure(row, weight=1)
        label(c, title, 14, bold=True).pack(fill="x")
        label(c, sub, 9, MUTED).pack(fill="x", pady=(2, 14))
        return c

    def lock_now(self):
        self.show_lock()

    # ---- phone internet ----

    def build_internet(self, c):
        row = tk.Frame(c, bg=CARD)
        row.pack(fill="x")
        self.net_dot = tk.Label(row, text="●", fg=FAINT, bg=CARD, font=font(12))
        self.net_dot.pack(side="left")
        self.net_text = label(row, "Checking the internet…", 11, bold=True)
        self.net_text.pack(side="left", padx=6)
        self.wifi_text = label(c, "", 9, MUTED)
        self.wifi_text.pack(fill="x", pady=(4, 12))
        self.phone_text = label(c, "", 10)
        self.phone_text.pack(fill="x")
        btns = tk.Frame(c, bg=CARD)
        btns.pack(fill="x", pady=(10, 12))
        self.connect_btn = Button(btns, "Connect to phone", self.connect_phone)
        self.connect_btn.pack(side="left")
        Button(btns, "Set up hotspot", self.setup_hotspot, "ghost").pack(side="left", padx=8)
        self.auto_var = tk.BooleanVar(value=self.s.get("auto_switch", True))
        tk.Checkbutton(c, text="If the internet stops, switch to the phone by itself", variable=self.auto_var,
                       command=self.toggle_auto, bg=CARD, fg=TEXT, selectcolor=FIELD, activebackground=CARD,
                       activeforeground=TEXT, font=font(10), anchor="w", highlightthickness=0).pack(fill="x")
        label(c, "Switch the hotspot on on the phone first (Quick Settings → Hotspot) – Android does not let "
                 "apps switch it on. With a USB cable: phone Settings → Hotspot → USB tethering; Windows uses it "
                 "by itself.", 9, FAINT, wraplength=360).pack(fill="x", pady=(12, 0))

    def refresh_internet(self, gen=None):
        if gen is None:   # a one-off refresh after a change
            gen = getattr(self, "gen", 0)
            repeat = False
        else:
            repeat = True
        if gen != getattr(self, "gen", 0) or not hasattr(self, "net_text") or not self.net_text.winfo_exists():
            return

        def look():
            return internet_ok(), self.hot.wifi()

        def show(r):
            online, wifi = r
            if gen != self.gen or not self.net_text.winfo_exists():
                return
            self.net_dot.config(fg=GREEN if online else RED)
            self.net_text.config(text="Internet is working" if online else "No internet")
            if wifi is None:
                self.wifi_text.config(text="Wi-Fi: off or not on this computer")
            elif wifi["state"] == "connected":
                via = " (your phone)" if wifi["ssid"] == self.hot.phone_ssid else ""
                self.wifi_text.config(text="Wi-Fi: %s%s · signal %s" % (wifi["ssid"], via, wifi["signal"]))
            else:
                self.wifi_text.config(text="Wi-Fi: not connected")
            ssid = self.hot.phone_ssid
            self.phone_text.config(text="Phone hotspot: " + ssid if ssid else "Phone hotspot: not set up yet")
            self.connect_btn.set_enabled(bool(ssid))
            if repeat:
                self.root.after(5000, self.refresh_internet, gen)
        self.work(look, show)

    def toggle_auto(self):
        on = self.auto_var.get()
        self.s.set(auto_switch=on)
        (self.auto.start if on else self.auto.stop)()

    def connect_phone(self):
        if not self.hot.phone_ssid:
            self.setup_hotspot()
            return
        self.toast("Connecting to %s…" % self.hot.phone_ssid)
        self.work(self.hot.connect, lambda err: self.toast(err or "Connected to your phone's hotspot.",
                                                           RED if err else GREEN))

    def setup_hotspot(self):
        d = self.dialog("Your phone's hotspot")
        label(d, "On the phone: Settings → Hotspot – switch it on, and look at its name and password. "
                 "Pick it below (or write its name).", 10, MUTED, wraplength=400).pack(fill="x")
        lst = tk.Listbox(d, bg=FIELD, fg=TEXT, selectbackground=BLUE, relief="flat", height=6, font=font(10),
                         highlightthickness=1, highlightbackground=LINE, activestyle="none")
        lst.pack(fill="x", pady=(10, 4))
        nets = []
        status = label(d, "Looking for Wi-Fi networks…", 9, FAINT)
        status.pack(fill="x")
        label(d, "Hotspot name", 9, MUTED).pack(fill="x", pady=(10, 2))
        name = entry(d, show="")
        name.insert(0, self.hot.phone_ssid)
        name.pack(fill="x", ipady=6)
        label(d, "Hotspot password", 9, MUTED).pack(fill="x", pady=(10, 2))
        pw = entry(d)
        pw.pack(fill="x", ipady=6)
        label(d, "Windows keeps the password in its own Wi-Fi settings; AirCursor does not store it.", 8, FAINT,
              wraplength=400).pack(fill="x", pady=(4, 0))
        msg = label(d, "", 9, RED, wraplength=400)
        msg.pack(fill="x", pady=(6, 0))

        def scan():
            def got(found):
                nets[:] = found
                lst.delete(0, "end")
                for n in found:
                    lst.insert("end", "  %s    %d%%" % (n["ssid"], n["signal"]))
                status.config(text="Found %d networks." % len(found) if found else
                              "No networks found (is Wi-Fi on?). You can write the name yourself.")
            self.work(self.hot.networks, got)

        def picked(_):
            sel = lst.curselection()
            if sel:
                name.delete(0, "end")
                name.insert(0, nets[sel[0]]["ssid"])
                pw.focus_set()
        lst.bind("<<ListboxSelect>>", picked)

        def save(*_):
            auth = next((n["auth"] for n in nets if n["ssid"] == name.get().strip()), "")
            msg.config(text="Saving…", fg=MUTED)

            def done(err):
                if err:
                    msg.config(text=err, fg=RED)
                    return
                d.destroy()
                self.refresh_internet()
                self.connect_phone()
            self.work(lambda: self.hot.save_phone(name.get(), pw.get(), auth), done)
        pw.bind("<Return>", save)
        row = tk.Frame(d, bg=CARD)
        row.pack(fill="x", pady=(14, 0))
        Button(row, "Save and connect", save).pack(side="right")
        Button(row, "Search again", scan, "ghost").pack(side="right", padx=8)
        if self.hot.phone_ssid:
            def forget():
                self.hot.forget_phone()
                d.destroy()
                self.refresh_internet()
            Button(row, "Forget phone", forget, "danger").pack(side="left")
        scan()

    # ---- phone as mouse & keyboard ----

    def build_remote(self, c):
        row = tk.Frame(c, bg=CARD)
        row.pack(fill="x")
        self.rem_dot = tk.Label(row, text="●", fg=FAINT, bg=CARD, font=font(12))
        self.rem_dot.pack(side="left")
        self.rem_text = label(row, "", 11, bold=True)
        self.rem_text.pack(side="left", padx=6)
        self.code_box = tk.Frame(c, bg=FIELD, padx=16, pady=10, highlightthickness=1, highlightbackground=LINE)
        self.code_box.pack(fill="x", pady=(12, 4))
        label(self.code_box, "Pairing code", 9, MUTED, bg=FIELD).pack(anchor="w")
        self.code_text = label(self.code_box, "", 26, TEXT, True, bg=FIELD)
        self.code_text.pack(anchor="w")
        self.addr_text = label(self.code_box, "", 9, MUTED, bg=FIELD)
        self.addr_text.pack(anchor="w")
        btns = tk.Frame(c, bg=CARD)
        btns.pack(fill="x", pady=(10, 10))
        self.rem_btn = Button(btns, "Turn on", self.toggle_remote)
        self.rem_btn.pack(side="left")
        self.code_btn = Button(btns, "New code", self.host.renew_code, "ghost")
        self.code_btn.pack(side="left", padx=8)
        label(c, "On the phone: AirCursor → Use as keyboard & touchpad → pick this computer (or \"Connect by "
                 "address\" with the address above) → type the code. If Windows asks about the firewall, "
                 "allow it – the hotspot is a Public network, so tick Public too.", 9, FAINT,
              wraplength=360).pack(fill="x")

    def refresh_remote(self):
        if not hasattr(self, "rem_text") or not self.rem_text.winfo_exists():
            return
        h = self.host
        if not h.running:
            self.rem_dot.config(fg=FAINT)
            self.rem_text.config(text="Off")
            self.code_box.pack_forget()
            self.rem_btn.config(text="Turn on")
            self.code_btn.pack_forget()
            return
        self.code_box.pack(fill="x", pady=(12, 4), after=self.rem_text.master)
        self.code_btn.pack(side="left", padx=8)
        self.rem_btn.config(text="Turn off")
        self.code_text.config(text="%s %s" % (h.code[:3], h.code[3:]))
        addrs = local_addresses() or ["(no network)"]
        self.addr_text.config(text="This computer: " + "  ·  ".join("%s:%d" % (a, h.port) for a in addrs[:3]))
        if h.client:
            self.rem_dot.config(fg=GREEN)
            self.rem_text.config(text="Phone connected (%s)" % h.client_addr)
        else:
            self.rem_dot.config(fg=AMBER)
            self.rem_text.config(text="Waiting for the phone…")

    def start_remote(self):
        try:
            self.host.start()
            self.s.set(remote_on=True)
        except OSError as e:
            self.toast("Could not start: %s" % e, RED)
        self.refresh_remote()

    def toggle_remote(self):
        if self.host.running:
            self.host.stop()
            self.s.set(remote_on=False)
            self.refresh_remote()
        else:
            self.start_remote()

    # ---- security ----

    def change_password(self):
        d = self.dialog("Change password")
        fields = []
        for t in ("Current password", "New password", "New password again"):
            label(d, t, 9, MUTED).pack(fill="x", pady=(8, 2))
            e = entry(d)
            e.pack(fill="x", ipady=6)
            fields.append(e)
        msg = label(d, "", 9, RED, wraplength=360)
        msg.pack(fill="x", pady=(8, 0))

        def go(*_):
            try:
                self.lock.change(*(f.get() for f in fields))
            except ValueError as e:
                wait = self.lock.wait_left()
                msg.config(text=str(e) + (" Try again in %d s." % wait if wait else ""))
                return
            d.destroy()
            self.toast("Password changed.", GREEN)
        fields[-1].bind("<Return>", go)
        Button(d, "Change", go).pack(anchor="e", pady=(12, 0))
        fields[0].focus_set()


def main():
    if sys.platform == "win32":
        try:   # sharp text on high-resolution screens
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    root = tk.Tk()
    App(root)
    root.mainloop()
