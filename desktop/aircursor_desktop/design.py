"""Original AirCursor artwork and glass sampled in workspace coordinates."""

from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QBrush, QColor, QIcon, QImage, QLinearGradient, QPainter, QPainterPath, QPen
from PySide6.QtQuick import QQuickImageProvider
from .widgets import asset

ICONS = {
    "Files": "files",
    "Air Browser": "browser",
    "Air Store": "store",
    "Air AI": "ai",
    "Launcher": "launcher",
    "Control Center": "control",
    "Settings": "settings",
    "Overview": "overview",
    "Terminal": "terminal",
    "Downloads": "downloads",
}


def icon(name):
    return QIcon(asset("icons/" + ICONS.get(name, "folder") + ".svg"))


class MaterialProvider(QQuickImageProvider):
    """Refract and tint the matching Gaussian-filtered wallpaper region inside a continuous rim."""

    def __init__(self):
        super().__init__(QQuickImageProvider.ImageType.Image)
        self.sources = {}
        self.previews = {}

    def requestImage(self, key, size, requested):
        if key.startswith("preview/"):
            return self.previews.get(key.split("/")[1], QImage())
        scene, mode, *numbers = key.split("/")
        x, y, w, h, target_w, target_h, radius, strength = map(int, numbers)
        w, h = max(1, w), max(1, h)
        if scene not in self.sources:
            self.sources[scene] = QImage(asset(scene + "-blur.jpg"))
        src = self.sources[scene]
        zoom = max(target_w / src.width(), target_h / src.height())
        src_rect = QRectF(
            (src.width() - target_w / zoom) / 2 + x / zoom,
            (src.height() - target_h / zoom) / 2 + y / zoom,
            w / zoom,
            h / zoom,
        )
        img = QImage(w, h, QImage.Format.Format_ARGB32_Premultiplied)
        img.fill(Qt.GlobalColor.transparent)
        p = QPainter(img)
        p.setRenderHints(QPainter.RenderHint.Antialiasing | QPainter.RenderHint.SmoothPixmapTransform)
        rect = QRectF(0.8, 0.8, w - 1.6, h - 1.6)
        path = QPainterPath()
        path.addRoundedRect(rect, radius, radius)
        p.setClipPath(path)
        # The narrow rim samples progressively displaced pixels; the center stays optically stable.
        for inset in range(min(6, w // 4, h // 4) + 1):
            inner = rect.adjusted(inset, inset, -inset, -inset)
            rounded = QPainterPath()
            rounded.addRoundedRect(inner, max(1, radius - inset), max(1, radius - inset))
            p.save()
            p.setClipPath(rounded, Qt.ClipOperation.IntersectClip)
            displacement = (6 - inset) ** 2 / (8 * zoom)
            p.drawImage(
                rect, src, src_rect.adjusted(displacement, displacement, -displacement, -displacement)
            )
            p.restore()
        dark = mode == "dark"
        color = QColor("#152530" if dark else "#eef3f6")
        color.setAlpha(strength)
        p.fillPath(path, color)
        glow = QLinearGradient(0, 0, w, h)
        glow.setColorAt(0, QColor(255, 255, 255, 55))
        glow.setColorAt(0.44, QColor(255, 255, 255, 3))
        glow.setColorAt(1, QColor(235, 249, 255, 24))
        p.fillPath(path, QBrush(glow))
        p.setClipping(False)
        edge = QLinearGradient(0, 0, w / 3, h)
        edge.setColorAt(0, QColor(255, 255, 255, 205))
        edge.setColorAt(0.52, QColor(237, 249, 255, 62))
        edge.setColorAt(1, QColor(255, 255, 255, 124))
        p.setPen(QPen(QBrush(edge), 1.15))
        p.drawPath(path)
        p.end()
        size.setWidth(w)
        size.setHeight(h)
        return img
