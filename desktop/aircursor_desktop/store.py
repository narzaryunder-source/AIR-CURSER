"""Air Store runs winget directly and displays its verbatim manifest, progress and exit status."""
from pathlib import Path
import shutil

from PySide6.QtCore import QProcess, Qt, QSize
from PySide6.QtGui import QColor, QFont, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import (QFrame, QGridLayout, QStackedWidget, QFileDialog, QLineEdit, QListWidget, QPlainTextEdit, QVBoxLayout, QWidget)
from .navigation import sidebar, item, heading
from .widgets import asset, button, confirm, label, open_path, row

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


class Studio(QFrame):
    def __init__(self, open_blender):
        super().__init__()
        self.pic=QPixmap(asset("studio.jpg"))
        self.setMinimumHeight(128)
        self.setMaximumHeight(190)
        layout=QVBoxLayout(self)
        layout.setContentsMargins(22,12,22,13)
        layout.addStretch()
        title=label("Blender")
        title.setStyleSheet("font-size:32px;font-weight:650;color:white")
        layout.addWidget(title)
        description=label("3D modeling, sculpting and animation")
        description.setStyleSheet("color:#f1eff9")
        layout.addWidget(description)
        link=button("View package  ›",open_blender)
        link.setStyleSheet("background:#e8e6f3;color:#29233c;border-radius:12px;padding:5px 12px")
        layout.addWidget(link,alignment=Qt.AlignmentFlag.AlignLeft)
        layout.addStretch()

    def paintEvent(self,event):
        from PySide6.QtGui import QPainterPath
        from PySide6.QtCore import QRectF
        p=QPainter(self)
        p.setRenderHints(QPainter.RenderHint.SmoothPixmapTransform|QPainter.RenderHint.Antialiasing)
        path=QPainterPath()
        path.addRoundedRect(QRectF(self.rect()),13,13)
        p.setClipPath(path)
        pic=self.pic.scaled(self.size(),Qt.AspectRatioMode.KeepAspectRatioByExpanding,Qt.TransformationMode.SmoothTransformation)
        p.drawPixmap((self.width()-pic.width())//2,(self.height()-pic.height())//2,pic)


def package_icon(name):
    colors={"Blender":"#b96d39","Krita":"#6957ac","OBS Studio":"#35434e","GIMP":"#677768",
            "Visual Studio Code":"#3388b1","VLC":"#ba7536","Ollama":"#476575","LM Studio":"#85628d"}
    pix=QPixmap(72,72)
    pix.fill(Qt.GlobalColor.transparent)
    p=QPainter(pix)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QColor(colors.get(name,"#58758d")))
    p.drawRoundedRect(2,2,68,68,18,18)
    p.setPen(QColor("white"))
    font=QFont("Segoe UI",17)
    font.setWeight(QFont.Weight.DemiBold)
    p.setFont(font)
    p.drawText(pix.rect(),Qt.AlignmentFlag.AlignCenter,name[:2])
    p.end()
    return QIcon(pix)


class Store(QWidget):
    def __init__(self, launcher):
        super().__init__()
        self.launcher = launcher
        left,self.nav,layout = sidebar(self,"Browse",190)
        for category in ["Discover",*CATALOG]:
            item(self.nav,category,category)
        self.nav.currentTextChanged.connect(self.populate)
        self.query = QLineEdit()
        self.query.setPlaceholderText("Search winget by application name or package ID")
        self.query.returnPressed.connect(lambda:self.run("search"))
        row(layout,self.query,button("Search",lambda:self.run("search")))
        self.stack=QStackedWidget()
        layout.addWidget(self.stack,1)
        explore=QWidget()
        front=QVBoxLayout(explore)
        front.setContentsMargins(0,0,0,0)
        front.setSpacing(9)
        heading(front,"Discover")
        front.addWidget(Studio(lambda:self.details("Graphic Design","Blender")),2)
        section=label("Creative and development tools")
        section.setObjectName("section")
        front.addWidget(section)
        grid=QGridLayout()
        grid.setSpacing(7)
        self.featured=[]
        for i,(name,category) in enumerate([("Krita","Graphic Design"),("OBS Studio","Video Editing"),
                ("GIMP","Photo Editing"),("Visual Studio Code","Programming"),("VLC","Entertainment"),("Ollama","Artificial Intelligence")]):
            card=button(name+"  ›", lambda n=name,c=category:self.details(c,n))
            card.setIcon(package_icon(name))
            card.setIconSize(QSize(32,32))
            card.setStyleSheet("text-align:left;padding:8px 10px")
            grid.addWidget(card,i//2,i%2)
        front.addLayout(grid)
        front.addStretch()
        self.stack.addWidget(explore)
        detail=QWidget()
        self.stack.addWidget(detail)
        layout=QVBoxLayout(detail)
        layout.setContentsMargins(0,0,0,0)
        self.title=heading(layout,"Packages")
        self.apps = QListWidget()
        self.apps.setIconSize(QSize(43,43))
        self.apps.currentTextChanged.connect(self.pick)
        self.apps.setMaximumHeight(174)
        layout.addWidget(self.apps)
        self.selected=label("")
        self.selected.setObjectName("secondary")
        layout.addWidget(self.selected)
        self.active_category=next(iter(CATALOG))
        self.install=button("Install…",lambda:self.run("install"))
        self.install.setObjectName("primary")
        row(layout,self.install,button("Package details",lambda:self.run("show")),
            button("Installed?",lambda:self.run("list")),button("Update…",lambda:self.run("upgrade")),
            button("Uninstall…",lambda:self.run("uninstall")))
        self.status=label("Choose Package details to request the current manifest from winget.")
        self.status.setObjectName("secondary")
        layout.addWidget(self.status)
        self.output=QPlainTextEdit()
        self.output.setReadOnly(True)
        self.output.setMaximumBlockCount(1500)
        self.output.setPlaceholderText("Package information and installation progress appear here.")
        layout.addWidget(self.output,1)
        row(layout,button("Launch installed app…",launcher),button("Open downloaded installer…",self.installer))
        left.addWidget(label("Source  ·  winget"))
        self.process = QProcess(self)
        self.process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.process.readyReadStandardOutput.connect(self.read)
        self.process.finished.connect(lambda code, _: self.status.setText(f"winget exited with code {code}."))
        self.process.errorOccurred.connect(lambda _: self.status.setText(self.process.errorString()))
        self.nav.setCurrentRow(0)

    def details(self,category,name):
        self.nav.setCurrentRow(list(CATALOG).index(category)+1)
        matches=self.apps.findItems(name,Qt.MatchFlag.MatchExactly)
        if matches:
            self.apps.setCurrentItem(matches[0])
        self.run("show")

    def populate(self, category):
        if category=="Discover":
            self.stack.setCurrentIndex(0)
            self.query.clear()
            return
        self.stack.setCurrentIndex(1)
        self.active_category=category
        self.title.setText(category)
        self.apps.clear()
        for name,package in CATALOG[category].items():
            art = {"Artificial Intelligence":"Air AI","Browsers":"Air Browser","Development Tools":"Terminal",
                   "Programming":"Terminal"}.get(category,"Air Store")
            item(self.apps,name,package,art).setIcon(package_icon(name))
        self.apps.setCurrentRow(0)

    def pick(self, name):
        if name:
            self.query.setText(CATALOG[self.active_category][name])
            self.selected.setText(CATALOG[self.active_category][name])

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
        self.stack.setCurrentIndex(1)
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
