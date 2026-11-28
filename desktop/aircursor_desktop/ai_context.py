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


def office_context(path):
    """Read bounded text from a selected Word document or PowerPoint presentation."""
    import re
    from xml.etree import ElementTree
    from zipfile import BadZipFile, ZipFile

    path = Path(path)
    if path.stat().st_size > 32 * 1024**2:
        raise ValueError("Choose an Office document under 32 MiB.")
    is_word = path.suffix.lower() == ".docx"
    try:
        with ZipFile(path) as archive:
            if is_word:
                names = ["word/document.xml"]
            else:
                slides = [(int(match[1]), name) for name in archive.namelist()
                          if (match := re.fullmatch(r"ppt/slides/slide(\d+)\.xml", name))]
                names = [name for _, name in sorted(slides)]
            if sum(archive.getinfo(n).file_size for n in names) > 2_000_000:
                raise ValueError("Choose a smaller Office document.")
            lines = []
            for name in names:
                raw = archive.read(name)
                if b"<!DOCTYPE" in raw.replace(b"\0", b"").upper():
                    raise ValueError("Unsupported Office XML.")
                root = ElementTree.fromstring(raw)
                for paragraph in root.findall(".//{*}p"):
                    lines.append("".join(t.text or "" for t in paragraph.findall(".//{*}t")))
            return path.name, checked_context("\n".join(lines))
    except (BadZipFile, KeyError, ElementTree.ParseError) as exc:
        raise ValueError("The Office document could not be read.") from exc


def project_context(folder):
    import os
    from itertools import islice

    path = Path(folder)
    with os.scandir(path) as entries:
        names = sorted(entry.name for entry in islice(entries, 100))
    return path.name, checked_context("Project folder entries (up to 100):\n" + "\n".join(names))
