"""Air Store runs winget directly and displays its verbatim manifest, progress and exit status."""
import os
from pathlib import Path
import shutil

from PySide6.QtCore import QProcess
from PySide6.QtWidgets import (QComboBox, QFileDialog, QLineEdit, QListWidget, QPlainTextEdit, QVBoxLayout, QWidget)
from .widgets import button, confirm, label, open_path, row

CATALOG = {
    "Artificial Intelligence": {"Ollama": "Ollama.Ollama", "LM Studio": "ElementLabs.LMStudio"},
    "Video Editing": {"DaVinci Resolve": "BlackmagicDesign.DaVinciResolve", "CapCut": "ByteDance.CapCut",
                      "OBS Studio": "OBSProject.OBSStudio"},
    "Photo Editing": {"GIMP": "GIMP.GIMP.3"},
    "Graphic Design": {"Blender": "BlenderFoundation.Blender", "Krita": "KDE.Krita"},
    "Programming": {"Visual Studio Code": "Microsoft.VisualStudioCode", "Python 3.13": "Python.Python.3.13"},
    "Productivity": {"LibreOffice": "TheDocumentFoundation.LibreOffice"},
    "Entertainment": {"VLC": "VideoLAN.VLC", "Audacity": "Audacity.Audacity"},
    "Browsers": {"Firefox": "Mozilla.Firefox", "Google Chrome": "Google.Chrome"},
    "Utilities": {"FFmpeg": "Gyan.FFmpeg"},
    "Development Tools": {"Git": "Git.Git", "Node.js LTS": "OpenJS.NodeJS.LTS"},
}


def arguments(action, value):
    if not value.strip() or value.startswith("-") or any(c in value for c in "\r\n\x00"):
        raise ValueError("Enter a package name or ID.")
    selector = ["--query", value] if action == "search" else ["--id", value, "--exact"]
    return [action, *selector, "--source", "winget", "--disable-interactivity"]


class Store(QWidget):
    def __init__(self, launcher):
        super().__init__()
        self.launcher = launcher
        layout = QVBoxLayout(self)
        self.category = QComboBox()
        self.category.addItems(CATALOG)
        self.apps = QListWidget()
        self.category.currentTextChanged.connect(self.populate)
        self.apps.currentTextChanged.connect(self.pick)
        self.query = QLineEdit()
        self.query.setPlaceholderText("Package name or exact winget ID")
        row(layout, self.category, self.query, button("Search source", lambda: self.run("search")))
        layout.addWidget(self.apps, 1)
        self.output = QPlainTextEdit()
        self.output.setReadOnly(True)
        self.output.setMaximumBlockCount(1500)
        self.status = label("Select a package and check its manifest for availability and requirements.")
        layout.addWidget(self.status)
        layout.addWidget(self.output, 2)
        for group in [[("Details", "show"), ("Installed?", "list"), ("Install…", "install"),
                       ("Update…", "upgrade"), ("Uninstall…", "uninstall")]]:
            row(layout, *[button(name, lambda op=op: self.run(op)) for name, op in group])
        row(layout, button("Launch installed app…", launcher), button("Open downloaded installer…", self.installer))
        self.process = QProcess(self)
        self.process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.process.readyReadStandardOutput.connect(self.read)
        self.process.finished.connect(lambda code, _: self.status.setText(f"winget exited with code {code}."))
        self.process.errorOccurred.connect(lambda _: self.status.setText(self.process.errorString()))
        self.populate(self.category.currentText())

    def populate(self, category):
        self.apps.clear()
        self.apps.addItems(CATALOG[category])
        self.apps.setCurrentRow(0)

    def pick(self, name):
        if name:
            self.query.setText(CATALOG[self.category.currentText()][name])

    def read(self):
        data = bytes(self.process.readAllStandardOutput()).decode("utf-8", "replace")
        cursor = self.output.textCursor()
        cursor.movePosition(cursor.MoveOperation.End)
        cursor.insertText(data)
        self.output.setTextCursor(cursor)

    def run(self, action):
        if self.process.state() != QProcess.ProcessState.NotRunning:
            self.status.setText("Wait for the current package operation to finish.")
            return
        exe = shutil.which("winget.exe")
        if not exe:
            self.status.setText("Install or update Microsoft App Installer to use winget.")
            return
        value = self.query.text().strip()
        try:
            args = arguments(action, value)
        except ValueError as exc:
            self.status.setText(str(exc))
            return
        if action in ("install", "upgrade", "uninstall"):
            if not confirm(self, f"{action.capitalize()} package", f"{action.capitalize()} {value} using the winget community source? Review Details for publisher and license. Windows may ask for approval."):
                return
        self.output.clear()
        self.status.setText("Running winget " + action + "…")
        self.process.start(exe, args)

    def installer(self):
        path, _ = QFileDialog.getOpenFileName(self, "Choose installer", "", "Windows installers (*.exe *.msi)")
        if path:
            open_path(self, Path(path))
