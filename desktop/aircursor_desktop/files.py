"""Tabbed access to real folders. Bulk work runs off the UI thread."""

import ctypes
import os
from pathlib import Path, PureWindowsPath
import shutil
import stat
import zipfile

from PySide6.QtCore import QDir, QFile, QSize, Qt, QStandardPaths
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QFileDialog,
    QInputDialog,
    QLineEdit,
    QListView,
    QMenu,
    QTabWidget,
    QTreeView,
    QVBoxLayout,
    QWidget,
)
from .file_actions import file_mime, file_selection, open_with, valid_name
from .navigation import sidebar, item, heading
from .thumbnails import FileModel
from .widgets import WINDOWS_INSTALLERS, Workers, button, confirm, label, open_path, row


def extract_new(archive):
    archive = Path(archive)
    target = archive.with_suffix("")
    target.mkdir(exist_ok=False)
    try:
        with zipfile.ZipFile(archive) as z:
            for info in z.infolist():
                path = PureWindowsPath(info.filename)
                if (
                    path.anchor
                    or not path.parts
                    or any(
                        part in (".", "..")
                        or ":" in part
                        or part.endswith((".", " "))
                        or PureWindowsPath(part).is_reserved()
                        for part in path.parts
                    )
                    or stat.S_ISLNK(info.external_attr >> 16)
                ):
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
    if path.is_symlink() or path.is_junction():
        raise ValueError("Select the source folder or file directly.")
    target = path.with_name(path.name + ".zip")
    with target.open("xb") as dest:
        try:
            with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
                items = path.rglob("*") if path.is_dir() else [path]
                if path.is_dir():
                    z.write(path, path.name)
                for item in items:
                    if item.is_symlink() or item.is_junction():
                        continue
                    z.write(item, item.relative_to(path.parent))
        except Exception:
            dest.close()
            target.unlink()
            raise
    return target


def read_preview(path):
    from PySide6.QtGui import QImageReader

    reader = QImageReader(str(path))
    reader.setAutoTransform(True)
    if reader.canRead():
        size = reader.size()
        bounds = QSize(720, 480)
        if reader.transformation().value & 4:  # Rotate90 and combined 90-degree transforms.
            bounds.transpose()
        if size.isValid() and (size.width() > bounds.width() or size.height() > bounds.height()):
            reader.setScaledSize(size.scaled(bounds, Qt.AspectRatioMode.KeepAspectRatio))
        image = reader.read()
        if image.isNull():
            raise ValueError(reader.errorString())
        return image
    if path.suffix.lower() in (".txt", ".md", ".py", ".json", ".csv", ".log"):
        with path.open("r", encoding="utf-8", errors="replace") as src:
            return src.read(20000)
    return None


def shell_transfer(paths, destination, move, hwnd):
    """Let the Windows shell handle ACLs and collision naming for the whole operation."""
    from ctypes import wintypes as w

    class Op(ctypes.Structure):
        _fields_ = [
            ("hwnd", w.HWND),
            ("op", w.UINT),
            ("source", w.LPCWSTR),
            ("dest", w.LPCWSTR),
            ("flags", w.WORD),
            ("aborted", w.BOOL),
            ("maps", w.HANDLE),
            ("title", w.LPCWSTR),
        ]

    params = Op(
        hwnd,
        1 if move else 2,
        "\0".join(map(str, paths)) + "\0\0",
        str(destination) + "\0\0",
        0x0008,
        False,
        None,
        None,
    )
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
        row(
            layout,
            button("↑", lambda: self.navigate(self.path.parent)),
            self.address,
            self.filter,
            button("List / grid", self.toggle),
        )
        self.model = FileModel(self)
        self.model.setFilter(QDir.Filter.AllEntries | QDir.Filter.NoDotAndDotDot | QDir.Filter.AllDirs)
        self.model.setNameFilterDisables(False)
        self.filter.textChanged.connect(
            lambda text: self.model.setNameFilters(["*" + text + "*"] if text else [])
        )
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
        self.toggle()

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
        self.files.title.setText(self.path.name or str(self.path))
        self.files.status.setText(str(self.path))
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
        else:
            view.clearSelection()
            view.setCurrentIndex(self.model.index(-1, 0))
        self.files.actions(view.viewport().mapToGlobal(pos))

    def dragEnterEvent(self, event):
        if event.possibleActions() & Qt.DropAction.CopyAction and file_selection(event.mimeData())[0]:
            event.setDropAction(Qt.DropAction.CopyAction)
            event.accept()

    def dropEvent(self, event):
        if not event.possibleActions() & Qt.DropAction.CopyAction:
            event.ignore()
            return
        paths, _ = file_selection(event.mimeData())
        if paths and confirm(self, "Copy into folder", f"Copy {len(paths)} item(s) to {self.path}?"):
            self.files.transfer(paths, False)
            event.setDropAction(Qt.DropAction.CopyAction)
            event.accept()


class Files(QWidget):
    def __init__(self, settings):
        super().__init__()
        self.s = settings
        self.work = Workers(self)
        left, self.places, layout = sidebar(self, "Favorites", 176)
        self.title = heading(layout, "Files")
        self.tabs = QTabWidget()
        self.tabs.setTabsClosable(True)
        self.tabs.setTabBarAutoHide(True)
        self.status = label("")
        self.status.setObjectName("secondary")
        self.update_places()
        self.places.itemClicked.connect(lambda entry: self.go(Path(entry.data(Qt.ItemDataRole.UserRole))))
        self.action = button("Actions  ▾", self.actions)
        left.addWidget(button("Add favorite", self.favorite))
        row(
            layout,
            button("New tab", lambda: self.add(self.tabs.currentWidget().path)),
            button("Open folder…", self.choose),
            self.action,
            button("Open in Explorer", lambda: open_path(self, self.tabs.currentWidget().path)),
        )
        layout.addWidget(self.tabs, 1)
        layout.addWidget(self.status)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.tabs.currentChanged.connect(self.tab_changed)
        self.add(Path.home())

    def go(self, path):
        if path.is_dir():
            self.tabs.currentWidget().navigate(path)
        else:
            self.status.setText(f"Folder unavailable: {path}")

    def session(self):
        return {"selected": self.tabs.currentIndex(), "tabs": [
            {"path": str(self.tabs.widget(i).path), "grid": self.tabs.widget(i).grid}
            for i in range(self.tabs.count())][:32]}

    def restore_session(self, saved):
        if not isinstance(saved, dict):
            return
        original = self.tabs.widget(0)
        active = None
        for i, entry in enumerate(saved.get("tabs", [])[:32]):
            if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
                continue
            path = Path(entry["path"])
            if not path.is_absolute() or not path.is_dir():
                continue
            self.add(path)
            folder = self.tabs.currentWidget()
            if folder.grid != entry.get("grid", True):
                folder.toggle()
            if i == saved.get("selected"):
                active = folder
        if self.tabs.count() > 1:
            self.close_tab(self.tabs.indexOf(original))
            self.tabs.setCurrentWidget(active or self.tabs.widget(0))

    def downloads(self):
        self.go(Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.DownloadLocation)))

    def tab_changed(self, i):
        if i >= 0:
            path = self.tabs.widget(i).path
            self.status.setText(str(path))
            self.title.setText(path.name or str(path))

    def update_places(self):
        self.places.clear()
        for role in ("Home", "Desktop", "Documents", "Download", "Pictures", "Music", "Movies"):
            path = QStandardPaths.writableLocation(
                getattr(QStandardPaths.StandardLocation, role + "Location")
            )
            item(
                self.places,
                {"Download": "Downloads", "Movies": "Videos"}.get(role, role),
                path,
                "Downloads" if role == "Download" else "Files",
            )
        if os.environ.get("APPDATA"):
            item(
                self.places,
                "Recents",
                str(Path(os.environ["APPDATA"]) / "Microsoft/Windows/Recent"),
                "Overview",
            )
        for name in self.s.get("favorite_folders", []):
            item(self.places, Path(name).name or name, name, "Files")

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

    def task(self, fn, done=None):
        self.status.setText("Working…")
        self.work.run(fn, done or (lambda _: self.status.setText("Completed.")), self.status.setText)

    def transfer(self, paths, move, done=None):
        dest = self.tabs.currentWidget().path
        hwnd = int(self.window().winId())
        self.task(lambda: shell_transfer(paths, dest, move, hwnd), done)

    def actions(self, pos=None):
        tab = self.tabs.currentWidget()
        path = tab.selected()
        menu = QMenu(self)
        if path:
            menu.addAction("Open", lambda: tab.open(path))
            menu.addAction("Preview", lambda: self.preview(path))
            if path.is_file():
                from .media import edit_photo, trim_video
                if path.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff", ".tif"):
                    menu.addAction("Edit photo…", lambda: edit_photo(self, path))
                elif path.suffix.lower() in (".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v"):
                    menu.addAction("Trim video…", lambda: trim_video(self, path))
            if path.is_file() and path.suffix.lower() not in WINDOWS_INSTALLERS:
                menu.addAction("Open with…", lambda: self.choose_app(path))
            menu.addAction("Copy", lambda: QApplication.clipboard().setMimeData(file_mime([path])))
            menu.addAction("Cut", lambda: QApplication.clipboard().setMimeData(file_mime([path], True)))
            menu.addAction("Rename…", lambda: self.rename(path))
            menu.addAction("Compress to ZIP", lambda: self.task(lambda: zip_new(path)))
            if path.suffix.lower() == ".zip":
                menu.addAction("Extract to new folder", lambda: self.task(lambda: extract_new(path)))
            menu.addAction("Move to Recycle Bin…", lambda: self.trash(path))
        if file_selection(QApplication.clipboard().mimeData())[0]:
            menu.addAction("Paste here", self.paste)
        menu.addAction("New folder…", lambda: self.new_folder(tab.path))
        menu.addAction("Storage information", lambda: self.storage(tab.path))
        menu.exec(pos or self.action.mapToGlobal(self.action.rect().bottomLeft()))

    def choose_app(self, path):
        try:
            open_with(self, path)
        except OSError as exc:
            self.status.setText(str(exc))

    def new_folder(self, parent):
        name, ok = QInputDialog.getText(self, "New folder", "Folder name")
        if ok and valid_name(name):
            self.task(lambda: (parent / name).mkdir())
        elif ok:
            self.status.setText("Choose a valid Windows folder name.")

    def paste(self):
        clip = QApplication.clipboard()
        paths, move = file_selection(clip.mimeData())
        if not paths:
            return

        def done(_):
            if move and file_selection(clip.mimeData()) == (paths, move):
                clip.clear()
            self.status.setText("Completed.")

        self.transfer(paths, move, done)

    def storage(self, path):
        disk = shutil.disk_usage(path)
        self.status.setText(f"{disk.free / 2**30:.1f} GiB available of {disk.total / 2**30:.1f} GiB.")

    def rename(self, path):
        name, ok = QInputDialog.getText(self, "Rename", "New name", text=path.name)
        if ok and not valid_name(name):
            self.status.setText("Choose a valid Windows file name.")
            return
        if ok:

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
        from PySide6.QtWidgets import QDialog, QPlainTextEdit

        dialog = QDialog(self)
        dialog.setWindowTitle(path.name)
        layout = QVBoxLayout(dialog)
        status = label("Loading preview…")
        layout.addWidget(status)

        def ready(result):
            if result is None:
                status.setText("Use Open to view this item in its associated application.")
            elif isinstance(result, str):
                status.hide()
                view = QPlainTextEdit(result)
                view.setReadOnly(True)
                layout.addWidget(view)
            else:
                status.setPixmap(QPixmap.fromImage(result))

        work = Workers(dialog)
        work.run(lambda: read_preview(path), ready, status.setText)
        dialog.exec()
        dialog.deleteLater()
