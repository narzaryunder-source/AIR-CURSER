"""Explicit, bounded text context for a single Air AI request."""

from pathlib import Path
import platform

import psutil

MAX_CONTEXT_BYTES = 24 * 1024


def checked_context(text):
    if not text.strip() or "\0" in text:
        raise ValueError("Choose readable text.")
    if len(text.encode("utf-8")) > MAX_CONTEXT_BYTES:
        raise ValueError("Attach up to 24 KiB of text. Extract a smaller section and try again.")
    return text


def read_text_context(path):
    path = Path(path)
    with path.open("rb") as source:
        raw = source.read(MAX_CONTEXT_BYTES + 1)
    if len(raw) > MAX_CONTEXT_BYTES:
        raise ValueError("Attach a text file of 24 KiB or less.")
    try:
        return path.name, checked_context(raw.decode("utf-8-sig"))
    except UnicodeDecodeError as exc:
        raise ValueError("Choose a UTF-8 text file.") from exc


def computer_context():
    ram = psutil.virtual_memory()
    return (f"System: {platform.system()} {platform.release()} ({platform.machine()})\n"
            f"Logical CPUs: {psutil.cpu_count()}\n"
            f"Memory: {ram.available / 2**30:.1f} GiB available of {ram.total / 2**30:.1f} GiB total")
