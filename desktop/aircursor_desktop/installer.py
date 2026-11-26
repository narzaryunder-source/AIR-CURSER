"""Inspect a user-selected Windows installer before handing it to the Windows shell."""

import ctypes
from ctypes import wintypes
import hmac
import json
import os
from pathlib import Path
import subprocess

from PySide6.QtCore import QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QDialog, QDialogButtonBox, QLineEdit, QMessageBox, QVBoxLayout
from .widgets import Workers, label

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


def locked_inspection(path):
    import msvcrt
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    open_file = kernel.CreateFileW
    open_file.restype = wintypes.HANDLE
    open_file.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
                          wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    handle = open_file(str(path), 0x80000000, 1, None, 3, 0x80, None)
    if handle == wintypes.HANDLE(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    except OSError:
        kernel.CloseHandle(wintypes.HANDLE(handle))
        raise
    lease = os.fdopen(fd, "rb")
    try:
        return lease, inspect_file(path)
    except BaseException:
        lease.close()
        raise


def checksum_matches(expected, actual):
    value = expected.strip().casefold()
    return not value or (value.isascii() and len(value) == 64 and hmac.compare_digest(value, actual.lower()))


def approve(parent, path):
    path = Path(path).resolve()
    if not hasattr(parent, "installer_workers"):
        parent.installer_workers = Workers(parent)

    def inspected(result):
        lease, data = result
        with lease:
            if not parent.isVisible():
                return
            if data["status"] not in ("Valid", "NotSigned"):
                QMessageBox.warning(parent, "Installer signature",
                    f"Windows signature status: {data['status']}. Choose another copy from the publisher.")
                return
            dialog = QDialog(parent)
            dialog.setWindowTitle("Review Windows installer")
            layout = QVBoxLayout(dialog)
            publisher = data.get("publisher") or "Unsigned file"
            layout.addWidget(label(f"{path.name}\n\nPublisher: {publisher}\nSignature: {data['status']}\nSHA-256: {data['sha256']}"))
            checksum = QLineEdit()
            checksum.setPlaceholderText("Optional SHA-256 from the publisher")
            layout.addWidget(checksum)
            actions = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel)
            launch = actions.addButton("Run installer", QDialogButtonBox.ButtonRole.AcceptRole)
            actions.button(QDialogButtonBox.StandardButton.Cancel).setDefault(True)
            launch.setAutoDefault(False)
            checksum.textChanged.connect(lambda text: launch.setEnabled(checksum_matches(text, data["sha256"])))
            actions.accepted.connect(dialog.accept)
            actions.rejected.connect(dialog.reject)
            layout.addWidget(actions)
            if dialog.exec() and parent.isVisible() and checksum_matches(checksum.text(), data["sha256"]):
                if not QDesktopServices.openUrl(QUrl.fromLocalFile(str(path))):
                    QMessageBox.warning(parent, "Installer", "Windows could not open the installer.")

    parent.installer_workers.run(
        lambda: locked_inspection(path),
        inspected,
        lambda error: (
            QMessageBox.warning(parent, "Installer inspection", error) if parent.isVisible() else None
        ),
    )
