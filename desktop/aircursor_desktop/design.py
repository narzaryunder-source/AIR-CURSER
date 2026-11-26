"""Original layered landscapes and vector glyphs rendered by Qt at device resolution."""
import math
from PySide6.QtCore import QPointF, QRectF, QSize, Qt, QVariantAnimation, QEasingCurve
from PySide6.QtGui import (QBrush, QColor, QIcon, QLinearGradient, QPainter, QPainterPath,
                           QPen, QPixmap, QRadialGradient)
from .widgets import asset
from PySide6.QtWidgets import QToolButton, QWidget

COLORS = {
    "Files": ("#69cef9", "#2375cb"), "Air Browser": ("#78d9e6", "#3459c8"),
    "Air Store": ("#b4acff", "#6951cb"), "Air AI": ("#facb94", "#ca6a81"),
    "Launcher": ("#cdc2ff", "#8063d3"), "Control Center": ("#a8d4cb", "#4c8b8c"),
    "Settings": ("#afb9cc", "#5b6682"), "Overview": ("#97b5fa", "#596cbb"),
}


def icon(name):
    canvas = QPixmap(112, 112)
    canvas.fill(Qt.GlobalColor.transparent)
    p = QPainter(canvas)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.scale(2, 2)
    top, bottom = COLORS.get(name, COLORS["Files"])
    g = QLinearGradient(0, 2, 46, 53)
    g.setColorAt(0, QColor(top))
    g.setColorAt(1, QColor(bottom))
    p.setPen(QPen(QColor(255, 255, 255, 110), 1))
    p.setBrush(QBrush(g))
    p.drawRoundedRect(QRectF(2, 2, 52, 52), 14, 14)
    p.setBrush(Qt.BrushStyle.NoBrush)
    p.setPen(QPen(QColor("#ffffff"), 2.4, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin))
    def line(a, b, c, d):
        p.drawLine(QPointF(a, b), QPointF(c, d))
    if name == "Files":
        path = QPainterPath(QPointF(12, 19))
        for x, y in [(24, 19), (28, 23), (43, 23), (41, 37), (13, 37)]:
            path.lineTo(x, y)
        path.closeSubpath()
        p.drawPath(path)
        line(16, 27, 38, 27)
    elif name == "Air Browser":
        p.drawEllipse(QRectF(12, 12, 32, 32))
        path = QPainterPath(QPointF(35, 19))
        for x, y in [(30, 31), (20, 37), (25, 24), (35, 19)]:
            path.lineTo(x, y)
        p.drawPath(path)
    elif name == "Air Store":
        p.drawRoundedRect(QRectF(15, 22, 26, 19), 4, 4)
        p.drawArc(QRectF(22, 13, 12, 16), 0, 180 * 16)
        line(25, 31, 31, 31)
    elif name == "Air AI":
        for cx, cy, r in [(25, 29, 12), (38, 17, 5)]:
            path = QPainterPath(QPointF(cx, cy-r))
            for dx, dy in [(0.22, -0.22), (1, 0), (0.22, 0.22), (0, 1), (-0.22, 0.22), (-1, 0), (-0.22, -0.22)]:
                path.lineTo(cx+dx*r, cy+dy*r)
            path.closeSubpath()
            p.drawPath(path)
    elif name == "Launcher":
        for x in (15, 30):
            for y in (15, 30):
                p.drawRoundedRect(QRectF(x, y, 10, 10), 3, 3)
    elif name in ("Control Center", "Settings"):
        for y, x in [(20, 24), (35, 33)]:
            line(14, y, x-5, y)
            line(x+5, y, 42, y)
            p.drawEllipse(QPointF(x, y), 4, 4)
    else:
        p.drawRoundedRect(QRectF(13, 15, 14, 24), 3, 3)
        p.drawRoundedRect(QRectF(31, 18, 12, 18), 3, 3)
    p.end()
    return QIcon(canvas)


class DockButton(QToolButton):
    def __init__(self, title, action, animate):
        super().__init__()
        self.animate = animate
        self.setObjectName("dockButton")
        self.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
        self.setIcon(icon(title))
        self.setText(title)
        self.setAccessibleName(title)
        self.setIconSize(QSize(39, 39))
        self.setFixedSize(82, 69)
        self.clicked.connect(action)
        self.anim = QVariantAnimation(self, duration=155)
        self.anim.setEasingCurve(QEasingCurve.Type.OutCubic)
        self.anim.valueChanged.connect(lambda n: self.setIconSize(QSize(int(n), int(n))))

    def magnify(self, size):
        self.anim.stop()
        if self.animate():
            self.anim.setStartValue(self.iconSize().width())
            self.anim.setEndValue(size)
            self.anim.start()
        else:
            self.setIconSize(QSize(39, 39))

    def enterEvent(self, e):
        self.magnify(47)
        super().enterEvent(e)

    def leaveEvent(self, e):
        self.magnify(39)
        super().leaveEvent(e)


class Landscape(QWidget):
    def __init__(self):
        super().__init__()
        self.scene = "Fjord"
        self.light = False
        self.phase = 0
        self.photo = QPixmap(asset("fjord.jpg"))
        self.cache = None
        self.key = None

    def paintEvent(self, event):
        key = self.size(), self.devicePixelRatioF(), self.light, self.scene, self.phase
        if self.key != key:
            self.key = key
            ratio = self.devicePixelRatioF()
            self.cache = QPixmap(self.size() * ratio)
            self.cache.setDevicePixelRatio(ratio)
            p = QPainter(self.cache)
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
            w, h = max(1, self.width()), max(1, self.height())
            p.scale(w / 1200, h / 760)
            palettes = {
                "Fjord": ("#122b44", "#68a2a3", "#abd0bc", "#477983", "#315967", "#193945", "#102c37"),
                "Violet": ("#302748", "#a692bf", "#f1cbbb", "#887da9", "#686486", "#464561", "#302f46"),
                "Copper": ("#402e3f", "#bc8581", "#f2c8a0", "#9c7375", "#805864", "#634656", "#493946")}
            sky, mid, sun, *ridges = palettes[self.scene]
            g = QLinearGradient(180, 0, 1000, 700)
            g.setColorAt(0, QColor(sky).lighter(155 if self.light else 100))
            g.setColorAt(0.68, QColor(mid))
            g.setColorAt(1, QColor(sun))
            p.fillRect(QRectF(0, 0, 1200, 760), g)
            glow = QRadialGradient(880 + self.phase * 8, 300, 430)
            glow.setColorAt(0, QColor(sun))
            glow.setColorAt(1, QColor(255, 255, 255, 0))
            p.fillRect(QRectF(0, 0, 1200, 760), glow)
            for i, color in enumerate(ridges):
                y = 365 + 85*i
                path = QPainterPath(QPointF(-10, y+15))
                path.cubicTo(160, y-125+i*24, 325, y+155-i*19, 520, y+58-i*15)
                path.cubicTo(710, y-40, 773+i*32, y-180+i*19, 970, y-97+i*4)
                path.cubicTo(1115, y-65, 1160, y-105, 1210, y-99)
                edge = QPainterPath(path)
                path.lineTo(1210, 765)
                path.lineTo(-10, 765)
                path.closeSubpath()
                shade = QLinearGradient(410, y-90, 520, 790)
                shade.setColorAt(0, QColor(color).lighter(118 if self.light else 106))
                shade.setColorAt(1, QColor(color).darker(154))
                p.fillPath(path, shade)
                p.setPen(QPen(QColor(233, 255, 242, 55), 1.1))
                p.drawPath(edge)
            if self.scene == "Fjord" and not self.photo.isNull():
                p.resetTransform()
                source = QRectF(self.photo.rect())
                aspect = w / h
                if source.width()/source.height() > aspect:
                    crop = source.height()*aspect
                    source.setLeft((source.width()-crop)/2)
                    source.setWidth(crop)
                else:
                    crop = source.width()/aspect
                    source.setTop((source.height()-crop)/2)
                    source.setHeight(crop)
                p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
                p.drawPixmap(QRectF(0, 0, w, h), self.photo, source)
                overlay = QLinearGradient(0, 0, w, h)
                overlay.setColorAt(0, QColor(9, 25, 36, 30 if self.light else 73))
                overlay.setColorAt(1, QColor(27, 71, 74, 20 + abs(self.phase-12)*2))
                p.fillRect(QRectF(0, 0, w, h), overlay)
            p.end()
        p = QPainter(self)
        p.drawPixmap(0, 0, self.cache)
        p.end()
