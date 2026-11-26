"""Tabbed access to real folders. Bulk work runs off the UI thread."""
import ctypes
import os
from pathlib import Path, PureWindowsPath
import shutil
import stat
import tempfile
import zipfile

from PySide6.QtCore import QDir, QFile, QSize, Qt, QStandardPaths
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (QAbstractItemView, QComboBox, QFileDialog, QFileSystemModel, QInputDialog,
                               QLineEdit, QListView, QMenu, QTabWidget, QTreeView, QVBoxLayout, QWidget)
from .widgets import Workers, button, confirm, label, open_path, row


def extract_new(archive):
    archive = Path(archive)
    target = archive.with_suffix("")
    target.mkdir(exist_ok=False)
    try:
        with zipfile.ZipFile(archive) as z:
            for info in z.infolist():
                path = PureWindowsPath(info.filename)
                if (path.anchor or not path.parts or any(part in (".", "..") or ":" in part or
                        part.endswith((".", " ")) or PureWindowsPath(part).is_reserved() for part in path.parts)
                        or stat.S_ISLNK(info.external_attr >> 16)):
                    raise ValueError("Unsafe path in ZIP archive.")
                out = target.joinpath(*path.parts)
                if info.is_dir():
                    out.mkdir(parents=True, exist_ok=True)
                else:
                    out.parent.mkdir(parents=True, exist_ok=True)
                    with z.open(info) as src, out.open("xb") as dest:
                        shutil.copyfileobj(src, dest)
    except Exception:
        shutil.rmtree(target)
        raise
    return target


def zip_new(path):
    path = Path(path)
    target = path.with_name(path.name + ".zip")
    with target.open("xb") as dest:
        try:
            with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
                items = path.rglob("*") if path.is_dir() else [path]
                z.write(path, path.name) if path.is_dir() else None
                for item in items:
                    if item.is_symlink() or item.is_junction():
                        continue
                    z.write(item, item.relative_to(path.parent))
        except Exception:
            dest.close()
            target.unlink()
            raise
    return target


def shell_transfer(paths, destination, move, hwnd):
    """Let the Windows shell handle ACLs and collision naming for the whole operation."""
    from ctypes import wintypes as w
    class Op(ctypes.Structure):
        _fields_ = [("hwnd", w.HWND), ("op", w.UINT), ("source", w.LPCWSTR), ("dest", w.LPCWSTR),
                    ("flags", w.WORD), ("aborted", w.BOOL), ("maps", w.HANDLE), ("title", w.LPCWSTR)]
    params = Op(hwnd, 1 if move else 2, "\0".join(map(str, paths)) + "\0\0",
                str(destination) + "\0\0", 0x0008, False, None, None)
    result = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(params))
    if result or params.aborted:
        raise OSError(f"Windows file operation stopped (code {result}).")


class Folder(QWidget):
    def __init__(self, files, path):
        super().__init__()
        self.files = files
        self.grid = False
        layout = QVBoxLayout(self)
        self.address = QLineEdit()
        self.address.returnPressed.connect(lambda: self.navigate(Path(self.address.text())))
        self.filter = QLineEdit()
        self.filter.setPlaceholderText("Filter this folder")
        self.filter.setMaximumWidth(190)
        row(layout, button("↑", lambda: self.navigate(self.path.parent)), self.address, self.filter,
            button("List / grid", self.toggle))
        self.model = QFileSystemModel(self)
        self.model.setFilter(QDir.Filter.AllEntries | QDir.Filter.NoDotAndDotDot | QDir.Filter.AllDirs)
        self.model.setNameFilterDisables(False)
        self.filter.textChanged.connect(lambda text: self.model.setNameFilters(["*" + text + "*"] if text else []))
        self.list = QTreeView()
        self.list.setRootIsDecorated(False)
        self.list.setItemsExpandable(False)
        self.list.setColumnWidth(0, 300)
        self.icons = QListView()
        self.icons.setViewMode(QListView.ViewMode.IconMode)
        self.icons.setIconSize(QSize(56, 56))
        self.icons.setGridSize(QSize(128, 100))
        self.icons.setResizeMode(QListView.ResizeMode.Adjust)
        self.icons.setMovement(QListView.Movement.Static)
        for view in (self.list, self.icons):
            view.setModel(self.model)
            view.setDragEnabled(True)
            view.setDragDropMode(QAbstractItemView.DragDropMode.DragOnly)
            view.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
            view.customContextMenuRequested.connect(lambda pos, v=view: self.menu(v, pos))
            view.doubleClicked.connect(lambda idx: self.open(Path(self.model.filePath(idx))))
            layout.addWidget(view)
        self.icons.hide()
        self.setAcceptDrops(True)
        self.navigate(path)

    def navigate(self, path):
        if not path.is_dir():
            self.files.status.setText("Choose an accessible folder.")
            return
        self.path = path.resolve()
        root = self.model.setRootPath(str(self.path))
        self.list.setRootIndex(root)
        self.icons.setRootIndex(root)
        self.list.setColumnWidth(0, 280)
        self.address.setText(str(self.path))
        index = self.files.tabs.indexOf(self)
        if index >= 0:
            self.files.tabs.setTabText(index, self.path.name or str(self.path))

    def selected(self):
        view = self.icons if self.grid else self.list
        idx = view.currentIndex()
        return Path(self.model.filePath(idx)) if idx.isValid() else None

    def open(self, path):
        if path.is_dir():
            self.navigate(path)
        else:
            open_path(self, path)

    def toggle(self):
        self.grid = not self.grid
        self.icons.setVisible(self.grid)
        self.list.setVisible(not self.grid)

    def menu(self, view, pos):
        idx = view.indexAt(pos)
        if idx.isValid():
            view.setCurrentIndex(idx)
        self.files.actions(view.viewport().mapToGlobal(pos))

    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls() and all(u.isLocalFile() for u in event.mimeData().urls()):
            event.acceptProposedAction()

    def dropEvent(self, event):
        paths = [Path(u.toLocalFile()) for u in event.mimeData().urls() if u.isLocalFile()]
        if paths and confirm(self, "Copy into folder", f"Copy {len(paths)} item(s) to {self.path}?"):
            self.files.transfer(paths, False)
            event.acceptProposedAction()


class Files(QWidget):
    def __init__(self, settings):
        super().__init__()
        self.s = settings
        self.work = Workers(self)
        self.clip = None
        layout = QVBoxLayout(self)
        self.tabs = QTabWidget()
        self.tabs.setTabsClosable(True)
        self.status = label("")
        self.places = QComboBox()
        self.places.activated.connect(lambda i: self.tabs.currentWidget().navigate(Path(self.places.itemData(i))))
        self.update_places()
        self.action = button("Actions", self.actions)
        row(layout, self.places, button("+ Tab", lambda: self.add(self.tabs.currentWidget().path)),
            button("Choose folder…", self.choose), button("Favorite folder", self.favorite),
            self.action, button("Open in Explorer", lambda: open_path(self, self.tabs.currentWidget().path)))
        layout.addWidget(self.tabs)
        layout.addWidget(self.status)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.add(Path.home())

    def update_places(self):
        self.places.clear()
        for role in ("Home", "Desktop", "Documents", "Download"):
            path = QStandardPaths.writableLocation(getattr(QStandardPaths.StandardLocation, role + "Location"))
            self.places.addItem("Downloads" if role == "Download" else role, path)
        if os.environ.get("APPDATA"):
            self.places.addItem("Recent", str(Path(os.environ["APPDATA"]) / "Microsoft/Windows/Recent"))
        for name in self.s.get("favorite_folders", []):
            self.places.addItem(Path(name).name or name, name)

    def favorite(self):
        value = str(self.tabs.currentWidget().path)
        self.s.set(favorite_folders=list(dict.fromkeys([value] + self.s.get("favorite_folders", [])))[:40])
        self.update_places()

    def choose(self):
        value = QFileDialog.getExistingDirectory(self)
        if value:
            self.tabs.currentWidget().navigate(Path(value))

    def add(self, path):
        tab = Folder(self, path)
        index = self.tabs.addTab(tab, path.name or str(path))
        self.tabs.setCurrentIndex(index)

    def close_tab(self, i):
        if self.tabs.count() > 1:
            view = self.tabs.widget(i)
            self.tabs.removeTab(i)
            view.deleteLater()

    def task(self, fn):
        self.status.setText("Working…")
        self.work.run(fn, lambda _: self.status.setText("Completed."), self.status.setText)

    def transfer(self, paths, move):
        dest = self.tabs.currentWidget().path
        hwnd = int(self.window().winId())
        self.task(lambda: shell_transfer(paths, dest, move, hwnd))

    def actions(self, pos=None):
        tab = self.tabs.currentWidget()
        path = tab.selected()
        menu = QMenu(self)
        if path:
            menu.addAction("Open", lambda: tab.open(path))
            menu.addAction("Preview", lambda: self.preview(path))
            menu.addAction("Copy", lambda: setattr(self, "clip", (path, False)))
            menu.addAction("Cut", lambda: setattr(self, "clip", (path, True)))
            menu.addAction("Rename…", lambda: self.rename(path))
            menu.addAction("Compress to ZIP", lambda: self.task(lambda: zip_new(path)))
            if path.suffix.lower() == ".zip":
                menu.addAction("Extract to new folder", lambda: self.task(lambda: extract_new(path)))
            menu.addAction("Move to Recycle Bin…", lambda: self.trash(path))
        if self.clip:
            menu.addAction("Paste here", lambda: self.transfer([self.clip[0]], self.clip[1]))
        menu.addAction("Storage information", lambda: self.storage(tab.path))
        menu.exec(pos or self.action.mapToGlobal(self.action.rect().bottomLeft()))

    def storage(self, path):
        disk = shutil.disk_usage(path)
        self.status.setText(f"{disk.free / 2**30:.1f} GiB available of {disk.total / 2**30:.1f} GiB.")

    def rename(self, path):
        name, ok = QInputDialog.getText(self, "Rename", "New name", text=path.name)
        if ok and name and name == Path(name).name and name not in (".", "..") and ":" not in name:
            def perform():
                dest = path.with_name(name)
                if dest.exists():
                    raise FileExistsError(dest)
                path.rename(dest)
            self.task(perform)

    def trash(self, path):
        if confirm(self, "Move to Recycle Bin", f"Move {path.name} to the Recycle Bin?"):
            def move():
                ok, _ = QFile.moveToTrash(str(path))
                if not ok:
                    raise OSError("Windows could not recycle this item.")
            self.task(move)

    def preview(self, path):
        from PySide6.QtWidgets import QDialog, QLabel
        from PySide6.QtGui import QImageReader
        dialog = QDialog(self)
        dialog.setWindowTitle(path.name)
        layout = QVBoxLayout(dialog)
        reader = QImageReader(str(path))
        if reader.canRead():
            reader.setScaledSize(reader.size().scaled(720, 480, Qt.AspectRatioMode.KeepAspectRatio))
            view = QLabel()
            view.setPixmap(QPixmap.fromImage(reader.read()))
            layout.addWidget(view)
        elif path.suffix.lower() in (".txt", ".md", ".py", ".json", ".csv", ".log"):
            try:
                with path.open("r", encoding="utf-8", errors="replace") as src:
                    from PySide6.QtWidgets import QPlainTextEdit
                    view = QPlainTextEdit(src.read(20000))
                    view.setReadOnly(True)
                    layout.addWidget(view)
            except OSError as exc:
                layout.addWidget(label(str(exc)))
        else:
            layout.addWidget(label("Use Open to view this item in its associated application."))
        dialog.exec()
