"""Check a concrete app in the current runner session. Never close an existing window."""
import ctypes
import os
from pathlib import Path
import sys
import time

import psutil

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from aircursor_desktop.app_agent import plan_launch
from aircursor_desktop.applications import launch_registered, registered_apps
from aircursor_desktop.session import interactive_desktop


def session_id(pid):
    sid = ctypes.c_uint32()
    if not ctypes.windll.kernel32.ProcessIdToSessionId(pid, ctypes.byref(sid)):
        raise ctypes.WinError()
    return sid.value


def main():
    assert interactive_desktop()
    sid = session_id(os.getpid())
    catalog = dict(registered_apps())
    candidates = [('Notepad', 'notepad.exe'), ('Paint', 'mspaint.exe'), ('Character Map','charmap.exe')]
    name, executable = next(pair for pair in candidates if pair[0] in catalog)
    selected = plan_launch('Open ' + name)
    assert selected == [(name, catalog[name])]
    existing = set(psutil.pids())
    launch_registered(selected[0][1])
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        for proc in psutil.process_iter(['name','pid']):
            if proc.pid not in existing and (proc.info['name'] or '').lower() == executable and session_id(proc.pid) == sid:
                print('Verified Windows app launch', name, 'in interactive session', sid, flush=True)
                proc.terminate()
                return
        time.sleep(.15)
    raise RuntimeError('Windows did not start the expected app in the current session')


if __name__ == '__main__':
    main()
