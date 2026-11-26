"""Run inbox Windows PowerShell commands with their matching, system-owned modules."""

import os
from pathlib import Path
import subprocess


def run(script, *, timeout, variables=None):
    home = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0"
    # pwsh 7 can export a PSModulePath containing modules incompatible with Windows PowerShell 5.1.
    env = dict(os.environ)
    env["PSModulePath"] = str(home / "Modules")
    if variables:
        env.update(variables)
    return subprocess.run(
        [str(home / "powershell.exe"), "-NoProfile", "-NonInteractive", "-Command", script],
        env=env, capture_output=True, timeout=timeout, creationflags=subprocess.CREATE_NO_WINDOW, check=False,
    )
