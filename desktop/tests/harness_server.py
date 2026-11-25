"""For the phone-app interop test (app/src/test/.../WindowsHostInteropTest.kt): the Windows host
with a recording controller. Prints "READY <port> <code>", then every command it gets, one per line."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from aircursor_desktop.server import RemoteHost  # noqa: E402


class Rec:
    def apply(self, c):
        print("CMD " + repr(c), flush=True)

    def release_all(self):
        print("RELEASED", flush=True)


h = RemoteHost(Rec(), name="Windows Test", port=0)
h.start()
print("READY %d %s" % (h.port, h.code), flush=True)
end = time.time() + 30
while time.time() < end and sys.stdin.readline():
    pass
h.stop()
