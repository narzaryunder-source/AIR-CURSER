"""Chromium integration, imported on the first request for live web content."""

from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineSettings
from PySide6.QtWebEngineWidgets import QWebEngineView


class Page(QWebEnginePage):
    def acceptNavigationRequest(self, url, kind, main):
        return not main or url.scheme() in ("https", "http") or url.toString() == "about:blank"
