"""Local new-tab UI stays usable offline and starts Chromium only on first navigation."""

from functools import lru_cache

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QColor, QImageReader, QPainter, QPixmap
from PySide6.QtWidgets import QFrame, QHBoxLayout, QLabel, QLineEdit, QVBoxLayout, QWidget
from .design import icon
from .widgets import asset, button, label


@lru_cache(maxsize=1)
def wallpaper():
    reader = QImageReader(asset("fjord.jpg"))
    reader.setScaledSize(reader.size().scaled(QSize(1280, 720), Qt.AspectRatioMode.KeepAspectRatio))
    return QPixmap.fromImage(reader.read())


class NewTab(QWidget):
    navigate = Signal(str)

    def __init__(self, private, bookmarks):
        super().__init__()
        self.private = private
        self.scaled = QPixmap()
        layout = QVBoxLayout(self)
        layout.setContentsMargins(30, 18, 30, 24)
        layout.addStretch(2)
        group = QFrame()
        group.setMaximumWidth(540)
        box = QVBoxLayout(group)
        box.setSpacing(13)
        mark = QLabel()
        mark.setAlignment(Qt.AlignmentFlag.AlignCenter)
        mark.setPixmap(icon("Air Browser").pixmap(54, 54))
        box.addWidget(mark)
        heading = label("Private browsing" if private else "Air Browser")
        heading.setAlignment(Qt.AlignmentFlag.AlignCenter)
        heading.setStyleSheet("color:white;font-size:32px;font-weight:600;letter-spacing:-1px")
        box.addWidget(heading)
        self.search = QLineEdit()
        self.search.setPlaceholderText("Search or enter a web address")
        self.search.setMinimumWidth(340)
        self.search.setFixedHeight(43)
        self.search.setStyleSheet(
            "background:rgba(247,251,253,238);color:#223640;border:1px solid white;border-radius:15px;padding:0 17px"
        )
        self.search.returnPressed.connect(lambda: self.navigate.emit(self.search.text()))
        box.addWidget(self.search)
        links = QHBoxLayout()
        urls = bookmarks[:3] if not private else []
        if not urls:
            urls = ["https://www.wikipedia.org/", "https://duckduckgo.com/", "https://github.com/"]
        from urllib.parse import urlsplit

        for url in urls:
            host = urlsplit(url).hostname or url
            link = button(host.removeprefix("www."), lambda u=url: self.navigate.emit(u))
            link.setStyleSheet(
                "background:rgba(27,49,63,175);color:white;border:1px solid rgba(230,246,255,88);border-radius:10px;padding:5px 10px"
            )
            links.addWidget(link)
        box.addLayout(links)
        layout.addWidget(group, alignment=Qt.AlignmentFlag.AlignHCenter)
        layout.addStretch(3)

    def paintEvent(self, event):
        p = QPainter(self)
        if self.scaled.isNull() or self.last_size != self.size():
            self.last_size = self.size()
            self.scaled = wallpaper().scaled(
                self.last_size,
                Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                Qt.TransformationMode.SmoothTransformation,
            )
        p.drawPixmap(
            (self.width() - self.scaled.width()) // 2,
            (self.height() - self.scaled.height()) // 2,
            self.scaled,
        )
        p.fillRect(self.rect(), QColor(15, 28, 49, 115 if self.private else 47))
