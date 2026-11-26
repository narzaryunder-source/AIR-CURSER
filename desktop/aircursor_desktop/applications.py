"""Discover registered Windows applications and launch an explicit Start menu identity."""

import json
import os
import shutil
import subprocess
from pathlib import Path


def registered_apps():
    if os.name != "nt":
        return []
    system = Path(os.environ["SystemRoot"]) / "System32"
    script = (
        "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); "
        "$ErrorActionPreference='Stop'; ConvertTo-Json -Compress -InputObject @(Get-StartApps)"
    )
    result = subprocess.run(
        [
            str(system / "WindowsPowerShell/v1.0/powershell.exe"),
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            script,
        ],
        capture_output=True,
        timeout=15,
        creationflags=subprocess.CREATE_NO_WINDOW,
        check=True,
    )
    rows = json.loads(result.stdout.decode("utf-8-sig"))
    return [
        (row["Name"], "startapp:" + row["AppID"]) for row in rows if row.get("AppID") and row.get("Name")
    ]


def launch_registered(identity):
    if os.name != "nt" or not identity.startswith("startapp:") or not identity[9:]:
        raise ValueError("Choose an installed Windows application.")
    explorer = Path(os.environ["SystemRoot"]) / "explorer.exe"
    subprocess.Popen([str(explorer), "shell:AppsFolder\\" + identity[9:]])


def match_apps(name, apps):
    """Match a display name by whole words. Leave the final choice to the user."""
    import re

    def words(value):
        return " ".join(re.findall(r"[^\W_]+", value.casefold()))

    needle = words(name)
    return [
        (title, target)
        for title, target in apps
        if needle and (" " + needle + " ") in (" " + words(title) + " ")
    ]


CLI = {
    "Git.Git": "git",
    "Gyan.FFmpeg": "ffmpeg",
    "OpenJS.NodeJS.LTS": "node",
    "Ollama.Ollama": "ollama",
    "sharkdp.fd": "fd",
}


def find_tool(name):
    if os.name != "nt":
        return shutil.which(name)
    import winreg

    directories = [
        os.environ.get("PATH", ""),
        str(Path(os.environ["LOCALAPPDATA"]) / "Microsoft/WinGet/Links"),
    ]
    for hive, key in (
        (winreg.HKEY_CURRENT_USER, "Environment"),
        (winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment"),
    ):
        try:
            with winreg.OpenKey(hive, key) as handle:
                directories.append(winreg.ExpandEnvironmentStrings(winreg.QueryValueEx(handle, "Path")[0]))
        except OSError:
            continue
    for value in os.pathsep.join(directories).split(os.pathsep):
        folder = Path(value.strip('"'))
        if folder.is_absolute():
            candidate = folder / (name + ".exe")
            if candidate.is_file():
                return str(candidate)
    return None


def launch_cli(package):
    name = CLI.get(package)
    if not name:
        return False
    executable = find_tool(name)
    if not executable:
        return False
    # Start an interactive console in the verified executable's directory. Only fixed command
    # names enter cmd's command string. /D prevents cmd AutoRun from changing the command.
    command = {
        "ffmpeg": "ffmpeg.exe -version",
        "git": "git.exe --version",
        "ollama": "ollama.exe --help",
        "node": "node.exe",
        "fd": "fd.exe --version",
    }[name]
    subprocess.Popen(
        [str(Path(os.environ["SystemRoot"]) / "System32/cmd.exe"), "/d", "/k", command],
        cwd=str(Path(executable).parent),
        creationflags=subprocess.CREATE_NEW_CONSOLE,
    )
    return True
