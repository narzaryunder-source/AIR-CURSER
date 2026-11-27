"""Build a self-contained Windows x64 per-user installer on Windows or Linux with NSIS 3."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import runpy
import shutil
import subprocess
import sys
from urllib.request import urlopen
from zipfile import ZipFile
from qt_runtime import ship_qt_file

HERE = Path(__file__).resolve().parent
DESKTOP = HERE.parent
PYTHON = "3.13.17"
PYTHON_SHA256 = "9e35319bffedab8b36ad44d8f377cb4f1733bcec257a0b6d04d94f01486ddb38"


def digest(path):
    with path.open("rb") as src:
        return hashlib.file_digest(src, "sha256").hexdigest()


def compile_nsis(nsis, script, **definitions):
    # POSIX makensis uses '-' while the Windows command line accepts '/'.
    prefix = "/" if os.name == "nt" else "-"
    args = [nsis, prefix + "V2"] + [f"{prefix}D{key}={val}" for key, val in definitions.items()]
    subprocess.run(args + [str(HERE / script)], check=True)


def nsis_path(path):
    return '"$INSTDIR\\' + str(path).replace("/", "\\").replace("$", "$$") + '"'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=DESKTOP / "build/cache")
    parser.add_argument("--nsis", default="makensis")
    args = parser.parse_args()
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    embedded = cache / f"python-{PYTHON}-embed-amd64.zip"
    if not embedded.exists():
        with urlopen(f"https://www.python.org/ftp/python/{PYTHON}/{embedded.name}", timeout=90) as src:
            with embedded.open("wb") as dest:
                shutil.copyfileobj(src, dest)
    if digest(embedded) != PYTHON_SHA256:
        raise ValueError(f"Unexpected Python runtime checksum. Remove {embedded} and retry.")
    wheels = cache / "wheels"
    lock = json.loads((HERE / "wheels.json").read_text())
    if any(not (wheels / name).is_file() for name in lock):
        subprocess.run(
            [
                sys.executable,
                "-m",
                "pip",
                "download",
                "--only-binary=:all:",
                "--platform",
                "win_amd64",
                "--python-version",
                "313",
                "--implementation",
                "cp",
                "-d",
                str(wheels),
                "-r",
                str(DESKTOP / "requirements.txt"),
            ],
            check=True,
        )
    stage = DESKTOP / "build/package"
    if stage.exists():
        shutil.rmtree(stage)
    rt = stage / "runtime"
    rt.mkdir(parents=True)
    with ZipFile(embedded) as archive:
        archive.extractall(rt)
    omitted = set()
    omitted_bytes = 0
    for name, checksum in lock.items():
        file = wheels / name
        if digest(file) != checksum:
            raise ValueError(f"Unexpected wheel checksum: {name}")
        with ZipFile(file) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                if ship_qt_file(name, entry.filename):
                    archive.extract(entry, rt)
                else:
                    omitted.add(PurePosixPath("runtime", entry.filename))
                    omitted_bytes += entry.file_size
    # The embeddable interpreter ignores global site packages and PYTHONPATH.
    (rt / "python313._pth").write_text("python313.zip\n.\n", encoding="ascii")
    shutil.copytree(
        DESKTOP / "aircursor_desktop",
        rt / "aircursor_desktop",
        ignore=shutil.ignore_patterns("__pycache__"),
    )
    shutil.copytree(DESKTOP / "assets", rt / "assets")
    shutil.copy2(DESKTOP / "run.py", rt)
    # Keep the shared libraries, Chromium helper, locales, resources and license files together.
    for name in ("README.md", "RELEASE-NOTES.md"):
        if (DESKTOP / name).exists():
            shutil.copy2(DESKTOP / name, stage)
    shutil.copytree(DESKTOP / "report", stage / "report")
    output = DESKTOP / "dist"
    output.mkdir(exist_ok=True)
    ico = DESKTOP / "assets/aircursor.ico"
    compile_nsis(args.nsis, "launcher.nsi", OUTPUT=stage / "AirCursor.exe", BRANDICON=ico)
    files = sorted(stage.rglob("*"), key=lambda p: len(p.parts), reverse=True)
    uninstall = stage.parent / "uninstall-files.nsh"
    lines = [f'{"RMDir" if file.is_dir() else "Delete"} {nsis_path(file.relative_to(stage))}' for file in files]
    uninstall.write_text("\n".join(lines) + "\n", encoding="utf-8-sig")
    retired = stage.parent / "retired-runtime.nsh"
    old_only = sorted(path for path in omitted if not (stage / path).exists())
    old_dirs = {parent for path in old_only for parent in path.parents if parent.parts}
    retire_lines = [f"Delete {nsis_path(path)}" for path in old_only]
    retire_lines += [
        f"RMDir {nsis_path(path)}"
        for path in sorted(old_dirs, key=lambda p: (-len(p.parts), str(p)))
        if not (stage / path).exists()
    ]
    retired.write_text("\n".join(retire_lines) + "\n", encoding="utf-8-sig")
    exe = output / "AirCursor-Windows.exe"
    version = runpy.run_path(str(DESKTOP / "aircursor_desktop/__init__.py"))["VERSION"]
    compile_nsis(
        args.nsis,
        "installer.nsi",
        PAYLOAD_GLOB=stage / "*",
        OUTPUT=exe,
        BRANDICON=ico,
        UNINSTALL_FILES=uninstall,
        RETIRED_RUNTIME=retired,
        AIRCURSOR_VERSION=version,
    )
    (output / "SHA256SUMS.txt").write_text(f"{digest(exe)}  {exe.name}\n", encoding="ascii")
    sizes = {
        "installer_bytes": exe.stat().st_size,
        "payload_bytes": sum(file.stat().st_size for file in files if file.is_file()),
        "retired_files": len(old_only),
        "skipped_wheel_bytes": omitted_bytes,
    }
    (output / "package-size.json").write_text(json.dumps(sizes, indent=2) + "\n", encoding="ascii")
    print(exe, exe.stat().st_size, flush=True)


if __name__ == "__main__":
    main()
