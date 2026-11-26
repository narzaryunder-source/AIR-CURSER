"""Frameless AirCursor windows retain native move/resize, keyboard snapping and their own focus."""

from PySide6.QtCore import QEasingCurve, QEvent, QPropertyAnimation, Qt
from PySide6.QtGui import QColor, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QFrame,
    QGraphicsDropShadowEffect,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QSizeGrip,
    QVBoxLayout,
    QWidget,
)
from .design import icon
from .material import apply_backdrop
from .widgets import button


class Title(QFrame):
    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.window().windowHandle().startSystemMove()

    def mouseDoubleClickEvent(self, event):
        self.window().zoom()


class Panel(QMainWindow):
    def __init__(self, desk, name, content):
        super().__init__(desk, Qt.WindowType.Window | Qt.WindowType.FramelessWindowHint)
        self.desk, self.name, self.content = desk, name, content
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setWindowTitle(name + " · AirCursor")
        self.setWindowIcon(icon(name))
        self.setMinimumSize(560, 365)
        frame = QWidget()
        outer = QVBoxLayout(frame)
        outer.setContentsMargins(20, 12, 20, 24)
        body = QFrame()
        body.setObjectName("windowBody")
        shadow = QGraphicsDropShadowEffect(body)
        shadow.setBlurRadius(33)
        shadow.setOffset(0, 8)
        shadow.setColor(QColor(8, 20, 32, 95))
        body.setGraphicsEffect(shadow)
        outer.addWidget(body)
        layout = QVBoxLayout(body)
        layout.setContentsMargins(1, 1, 1, 1)
        layout.setSpacing(0)
        self.title = Title()
        self.title.setObjectName("titlebar")
        self.title.setFixedHeight(42)
        bar = QHBoxLayout(self.title)
        bar.setContentsMargins(16, 0, 16, 0)
        bar.setSpacing(7)
        for color, tip, fn in [
            ("#fa7168", "Close", self.close),
            ("#f5c250", "Minimize", self.showMinimized),
            ("#62c887", "Maximize or restore", self.zoom),
        ]:
            dot = button("", fn, tip)
            dot.setAccessibleName(tip)
            dot.setFixedSize(13, 13)
            dot.setStyleSheet(
                f"background:{color};border:1px solid rgba(0,0,0,30);border-radius:6px;padding:0"
            )
            bar.addWidget(dot)
        bar.addSpacing(9)
        appname = QLabel(name)
        appname.setObjectName("windowName")
        bar.addWidget(appname)
        bar.addStretch()
        layout.addWidget(self.title)
        layout.addWidget(content, 1)
        self.grip = QSizeGrip(body)
        self.grip.setFixedSize(14, 14)
        self.body = body
        body.installEventFilter(self)
        self.setCentralWidget(frame)
        self.fade = QPropertyAnimation(self, b"windowOpacity", self)
        self.fade.setDuration(145)
        self.fade.setEasingCurve(QEasingCurve.Type.OutCubic)
        self.closing = False
        self.fade.finished.connect(self.finish_animation)
        if apply_backdrop(self, desk.shell.dark):
            body.setProperty("systemGlass", True)
            self.title.setProperty("systemGlass", True)
            content.setObjectName("content")
        for keys, fn in [
            ("Ctrl+Space", lambda: desk.show_overlay("spotlight")),
            ("Ctrl+Shift+L", desk.lock_now),
            ("Ctrl+Alt+Left", lambda: self.snap(False)),
            ("Ctrl+Alt+Right", lambda: self.snap(True)),
            ("F11", desk.fullscreen),
        ]:
            QShortcut(QKeySequence(keys), self, activated=fn)

    def eventFilter(self, obj, event):
        if obj is self.body and event.type() == QEvent.Type.Resize:
            self.grip.move(obj.width() - 18, obj.height() - 18)
            self.grip.raise_()
        return super().eventFilter(obj, event)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if hasattr(self, "grip"):
            self.grip.move(self.body.width() - 18, self.body.height() - 18)
            self.grip.raise_()

    def mousePressEvent(self, event):
        p = event.position()
        edges = Qt.Edge(0)
        if p.x() < 23:
            edges |= Qt.Edge.LeftEdge
        if p.x() > self.width() - 23:
            edges |= Qt.Edge.RightEdge
        if p.y() < 15:
            edges |= Qt.Edge.TopEdge
        if p.y() > self.height() - 26:
            edges |= Qt.Edge.BottomEdge
        if edges and event.button() == Qt.MouseButton.LeftButton:
            self.windowHandle().startSystemResize(edges)
        else:
            super().mousePressEvent(event)

    def present(self):
        first = not self.isVisible() or self.isMinimized()
        self.closing = False
        self.fade.stop()
        if self.isMinimized():
            self.showNormal()
        self.setWindowOpacity(0.7 if first and self.desk.effects() else 1)
        self.show()
        self.raise_()
        self.activateWindow()
        if first and self.desk.effects():
            self.fade.setStartValue(0.7)
            self.fade.setEndValue(1)
            self.fade.start()

    def snap(self, right):
        r = self.screen().availableGeometry()
        w = r.width() // 2
        self.showNormal()
        self.setGeometry(r.x() + (w if right else 0), r.y(), w, r.height())

    def zoom(self):
        (self.showNormal if self.isMaximized() else self.showMaximized)()

    def finish_animation(self):
        if self.closing:
            self.hide()
            self.closing = False
            self.setWindowOpacity(1)

    def closeEvent(self, event):
        self.desk.panel_closed(self.name)
        if self.desk.effects() and self.isVisible() and not self.desk.locked:
            event.ignore()
            self.fade.stop()
            self.closing = True
            self.fade.setStartValue(1)
            self.fade.setEndValue(0.1)
            self.fade.start()
        else:
            super().closeEvent(event)


def stylesheet(dark):
    bg, side, field, fg, muted, line, sel = (
        ("#222a32", "#2b3540", "#303d49", "#f0f4f8", "#b3c1ce", "#485664", "#405c75")
        if dark
        else ("#fafbfc", "#edf1f5", "#ffffff", "#1e2b39", "#576879", "#d8e0e6", "#d4e8fb")
    )
    return f"""
    QWidget {{font-family:'Segoe UI Variable Text','Segoe UI','DejaVu Sans';font-size:13px;color:{fg}}}
    QDialog,QMenu,QWidget#content,QFrame#windowBody {{background:{bg}}}
    QFrame#windowBody {{border:1px solid {line};border-radius:17px}}
    QFrame#titlebar {{background:{side};border-top-left-radius:16px;border-top-right-radius:16px;border-bottom:1px solid {line}}}
    QFrame#windowBody[systemGlass="true"] {{background:transparent}}
    QFrame#titlebar[systemGlass="true"] {{background:{"rgba(33,47,59,128)" if dark else "rgba(233,242,248,128)"} }}
    QLabel#windowName {{font-size:12px;font-weight:600;color:{muted}}}
    QWidget#sidebar,QFrame#sidebar {{background:{side};border-right:1px solid {line};border-bottom-left-radius:14px}}
    QLabel {{background:transparent}}
    QLabel#heading {{font-size:26px;font-weight:650;letter-spacing:-1px}}
    QLabel#section {{font-size:12px;font-weight:600;color:{muted};padding-top:8px}}
    QLabel#secondary {{color:{muted};font-size:12px}}
    QLineEdit,QPlainTextEdit,QComboBox,QTextEdit {{background:{field};border:1px solid {line};border-radius:8px;padding:7px 10px;selection-background-color:{sel}}}
    QLineEdit:focus,QPlainTextEdit:focus {{border-color:#589ad2}}
    QPushButton,QToolButton {{background:{field};border:1px solid {line};border-radius:8px;padding:6px 11px}}
    QPushButton:hover,QToolButton:hover {{background:{sel}}}
    QPushButton#primary {{background:#2679bd;color:white;border-color:#266eaf;font-weight:600}}
    QPushButton#flat {{background:transparent;border:0;padding:6px}}
    QTreeView,QListView,QListWidget {{background:transparent;border:0;outline:0;selection-background-color:{sel};selection-color:{fg}}}
    QListWidget::item {{padding:6px 8px;border-radius:7px}}
    QListWidget::item:selected,QTreeView::item:selected,QListView::item:selected {{background:{sel};color:{fg}}}
    QHeaderView::section {{background:{bg};color:{muted};padding:6px;border:0;border-bottom:1px solid {line}}}
    QTabWidget::pane {{border:0}}
    QTabBar::tab {{background:transparent;color:{muted};padding:7px 12px;border-radius:6px}}
    QTabBar::tab:selected {{background:{sel};color:{fg}}}
    QMenu {{border:1px solid {line};border-radius:9px;padding:5px}}
    QMenu::item {{padding:6px 18px;border-radius:5px}}
    QMenu::item:selected {{background:{sel}}}
    QScrollBar:vertical {{width:8px;background:transparent}} QScrollBar::handle:vertical {{background:{line};min-height:16px;border-radius:3px}}
    """
