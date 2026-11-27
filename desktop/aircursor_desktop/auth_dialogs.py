"""Local authenticator enrollment and recovery-code display."""

import qrcode
from PySide6.QtGui import QColor, QImage, QPainter, QPixmap
from PySide6.QtWidgets import (
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QLabel,
    QMessageBox,
    QVBoxLayout,
)

from .widgets import label, password
from pathlib import Path


def qr_image(uri):
    qr = qrcode.QRCode(border=4)
    qr.add_data(uri)
    matrix = qr.get_matrix()
    scale = 5
    width = len(matrix) * scale
    image = QImage(width, width, QImage.Format.Format_RGB32)
    image.fill(0xFFFFFFFF)
    with QPainter(image) as painter:
        ink = QColor("black")
        for y, line in enumerate(matrix):
            for x, module in enumerate(line):
                if module:
                    painter.fillRect(x * scale, y * scale, scale, scale, ink)
    return image


def enroll(parent, uri, require_both=False):
    dialog = QDialog(parent)
    dialog.setWindowTitle("Set up phone authenticator")
    layout = QVBoxLayout(dialog)
    layout.addWidget(
        label(
            "Scan this QR code in Google Authenticator or Microsoft Authenticator. Then enter the six-digit code shown on your phone."
        )
    )
    qr = QLabel()
    qr.setAccessibleName("Authenticator enrollment QR code")
    qr.setPixmap(QPixmap.fromImage(qr_image(uri)))
    layout.addWidget(qr)
    entry = password("Six-digit code")
    entry.setMaxLength(6)
    layout.addWidget(entry)
    both = QCheckBox("Require both the master password and a phone code")
    both.setChecked(require_both)
    layout.addWidget(both)
    actions = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
    actions.accepted.connect(dialog.accept)
    actions.rejected.connect(dialog.reject)
    layout.addWidget(actions)
    entry.setFocus()
    result = (entry.text(), not both.isChecked()) if dialog.exec() else None
    qr.clear()
    entry.clear()
    dialog.deleteLater()
    return result


def show_recovery(parent, codes):
    dialog = QDialog(parent)
    dialog.setWindowTitle("One-time recovery codes")
    layout = QVBoxLayout(dialog)
    layout.addWidget(
        label("Save these codes somewhere you can reach without your phone. Each code works once.")
    )
    listing = label("\n".join(codes))
    listing.setStyleSheet("font-family: Consolas, monospace;")
    layout.addWidget(listing)
    actions = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)

    def save():
        name, _ = QFileDialog.getSaveFileName(
            dialog, "Save recovery codes", "AirCursor-recovery.txt", "Text files (*.txt)"
        )
        if name and dialog.isVisible():
            try:
                with Path(name).open("x", encoding="utf-8") as output:
                    output.write("\n".join(codes) + "\n")
            except OSError as exc:
                QMessageBox.warning(dialog, "Could not save recovery codes", str(exc))

    actions.addButton("Save codes…", QDialogButtonBox.ButtonRole.ActionRole).clicked.connect(save)
    actions.rejected.connect(dialog.reject)
    layout.addWidget(actions)
    dialog.exec()
    listing.clear()
    dialog.deleteLater()
