"""Inspect a user-selected Windows installer before handing it to the Windows shell."""

import json
import os
from pathlib import Path
import subprocess

from PySide6.QtCore import QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QMessageBox
from .widgets import Workers, confirm

SCRIPT = """
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$ErrorActionPreference = 'Stop'
$s = Get-AuthenticodeSignature -LiteralPath $env:AIRCURSOR_INSPECT_FILE
@{ status = [string]$s.Status; publisher = $s.SignerCertificate.Subject;
   sha256 = (Get-FileHash -LiteralPath $env:AIRCURSOR_INSPECT_FILE -Algorithm SHA256).Hash } | ConvertTo-Json -Compress
"""


def inspect_file(path):
    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT],
        env={**os.environ, "AIRCURSOR_INSPECT_FILE": str(path)},
        capture_output=True,
        timeout=120,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    if result.returncode:
        raise OSError(
            result.stderr.decode("utf-8", "replace").strip() or "Windows could not inspect this file."
        )
    return json.loads(result.stdout.decode("utf-8-sig"))


def approve(parent, path):
    path = Path(path).resolve()
    if not hasattr(parent, "installer_workers"):
        parent.installer_workers = Workers(parent)

    def inspected(data):
        if not parent.isVisible():
            return
        if data["status"] not in ("Valid", "NotSigned"):
            QMessageBox.warning(
                parent,
                "Installer signature",
                f"Windows signature status: {data['status']}. Choose another copy from the publisher.",
            )
            return
        publisher = data["publisher"] or "Unsigned file"
        if confirm(
            parent,
            "Run installer?",
            f"{path.name}\n\nPublisher: {publisher}\nSignature: {data['status']}\nSHA-256: {data['sha256']}\n\nLaunch with Windows?",
        ):
            if not QDesktopServices.openUrl(QUrl.fromLocalFile(str(path))):
                QMessageBox.warning(parent, "Installer", "Windows could not open the installer.")

    parent.installer_workers.run(
        lambda: inspect_file(path),
        inspected,
        lambda error: (
            QMessageBox.warning(parent, "Installer inspection", error) if parent.isVisible() else None
        ),
    )
