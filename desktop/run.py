"""Start file for the Windows build (PyInstaller): AirCursor-Windows.exe."""
import os
import sys
import tempfile


def self_test():
    """The build runs the finished .exe once with --self-test: every part loads on real Windows."""
    lines, ok = [], True
    try:
        from aircursor_desktop import VERSION, hotspot, inject, lock, protocol, server, ui  # noqa: F401
        lines.append("AirCursor for Windows %s" % VERSION)
        b = inject.backend()
        lines.append("input: %s, screen %s, cursor %s" % (type(b).__name__, b.screen(), b.cursor()))
        w = hotspot.Hotspot(object.__new__(type("S", (), {"get": lambda self, k, d=None: d}))).wifi()
        lines.append("wifi: %s" % (w or "none on this machine"))
        try:
            import zeroconf
            lines.append("discovery: zeroconf %s" % zeroconf.__version__)
        except ImportError:
            lines.append("discovery: missing")
            ok = False
        lines.append("addresses: %s" % server.local_addresses())
    except Exception as e:   # noqa: BLE001
        lines.append("FAILED: %r" % e)
        ok = False
    with open(os.path.join(tempfile.gettempdir(), "aircursor-selftest.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + ("\nOK\n" if ok else "\nNOT OK\n"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
    from aircursor_desktop.ui import main
    main()
