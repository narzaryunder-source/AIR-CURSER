"""Explicitly approved disposable-Windows-runner package lifecycle check for the development workflow."""

import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from aircursor_desktop.applications import find_tool
from aircursor_desktop.store import arguments, search_rows

PACKAGE = "sharkdp.fd"


def call(argv):
    result = subprocess.run(
        argv, check=False, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=240
    )
    print(result.stdout[-4096:], result.stderr[-1024:], flush=True)
    return result


def main():
    if (
        os.name != "nt"
        or os.getenv("GITHUB_ACTIONS") != "true"
        or os.getenv("AIRCURSOR_APPROVE_LIFECYCLE") != "1"
    ):
        raise SystemExit("Requires the explicitly enabled test on a disposable Windows Actions runner.")
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    winget = find_tool("winget")
    if not winget:
        raise SystemExit("Microsoft App Installer (winget) is unavailable on this runner.")

    def command(action, *options):
        return call([winget, *arguments(action, PACKAGE), "--accept-source-agreements", *options])

    discovery = command("search")
    discovery.check_returncode()
    if not any(package == PACKAGE for _, package in search_rows(discovery.stdout)):
        raise RuntimeError("The real winget search result was not selectable.")
    existing = command("list")
    if (existing.returncode & 0xFFFFFFFF) != 0x8A150014:
        raise SystemExit("The package is already installed or its initial state could not be established.")
    versions = command("show", "--versions")
    versions.check_returncode()
    available = re.findall(r"(?m)^\s*(\d+\.\d+\.\d+)\s*$", versions.stdout)
    if len(available) < 2:
        raise SystemExit("Two available versions are required for a real upgrade.")
    try:
        command(
            "install", "--version", available[1], "--scope", "user", "--accept-package-agreements"
        ).check_returncode()
        fd = find_tool("fd")
        if not fd or available[1] not in call([fd, "--version"]).stdout:
            raise RuntimeError("Installed application version did not match.")
        command("upgrade", "--accept-package-agreements").check_returncode()
        upgraded = call([fd, "--version"])
        upgraded.check_returncode()
        if available[0] not in upgraded.stdout:
            raise RuntimeError("Update did not produce the latest available version.")
    finally:
        command("uninstall").check_returncode()
    if (command("list").returncode & 0xFFFFFFFF) != 0x8A150014:
        raise RuntimeError("Uninstallation was not confirmed.")


if __name__ == "__main__":
    main()
