"""Bounded thumbnails for visible files, decoded away from the GUI thread."""

from collections import OrderedDict
from PySide6.QtCore import QObject, QRunnable, Qt, QThreadPool, Signal
from PySide6.QtGui import QIcon, QImage, QImageReader, QPixmap
from PySide6.QtWidgets import QFileSystemModel
from .widgets import asset


class Channel(QObject):
    done = Signal(object, object)


class Thumbnail(QRunnable):
    def __init__(self, key, channel):
        super().__init__()
        self.key, self.channel = key, channel

    def run(self):
        path = self.key[0]
        image = QImage()
        try:
            if path.lower().endswith(".pdf"):
                from PySide6.QtPdf import QPdfDocument

                doc = QPdfDocument(None)
                if doc.load(path) == QPdfDocument.Error.None_ and doc.pageCount():
                    size = doc.pagePointSize(0).toSize().scaled(128, 128, Qt.AspectRatioMode.KeepAspectRatio)
                    image = doc.render(0, size)
            else:
                reader = QImageReader(path)
                reader.setAutoTransform(True)
                original = reader.size()
                if original.isValid() and original.width() * original.height() < 45000000:
                    reader.setScaledSize(original.scaled(128, 128, Qt.AspectRatioMode.KeepAspectRatio))
                    image = reader.read()
        finally:
            try:
                self.channel.done.emit(self.key, image)
            except RuntimeError:
                pass


class FileModel(QFileSystemModel):
    def __init__(self, parent):
        super().__init__(parent)
        self.pool = QThreadPool(self)
        self.pool.setMaxThreadCount(2)
        self.cache = OrderedDict()
        self.pending = set()
        self.channel = Channel(self)
        self.channel.done.connect(self.ready)
        self.folder = QIcon(asset("icons/folder.svg"))

    def data(self, index, role=Qt.ItemDataRole.DisplayRole):
        if role == Qt.ItemDataRole.DecorationRole and index.column() == 0 and index.isValid():
            info = self.fileInfo(index)
            if info.isDir():
                return self.folder
            path = info.absoluteFilePath()
            if (
                info.suffix().lower() in ("png", "jpeg", "jpg", "webp", "bmp", "pdf")
                and info.size() < 16000000
                and not path.startswith(("//", "\\\\"))
            ):
                key = (path, info.lastModified().toMSecsSinceEpoch())
                if key in self.cache:
                    thumb = self.cache[key]
                    if thumb:
                        return thumb
                elif key not in self.pending and len(self.pending) < 24:
                    self.pending.add(key)
                    self.pool.start(Thumbnail(key, self.channel))
        return super().data(index, role)

    def ready(self, key, image):
        self.pending.discard(key)
        self.cache[key] = None if image.isNull() else QIcon(QPixmap.fromImage(image))
        if len(self.cache) > 256:
            self.cache.popitem(last=False)
        index = self.index(key[0])
        self.dataChanged.emit(index, index, [Qt.ItemDataRole.DecorationRole])
