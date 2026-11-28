"""Default to the smaller workspace on Windows computers with up to 2 GiB of RAM."""

import sys

import psutil


def low_memory(settings):
    choice = settings.get("low_memory")
    if isinstance(choice, bool):
        return choice
    return sys.platform == "win32" and psutil.virtual_memory().total <= 2 * 1024**3
