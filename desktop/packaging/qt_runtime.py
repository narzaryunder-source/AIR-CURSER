"""Select the shipped runtime from the locked PySide6 wheels."""

from pathlib import PurePosixPath

# The workspace and browser share Qt Essentials. WebEngine also loads the
# QtWebChannel Python binding and uses the positioning and web channel libraries.
# The NMEA position plugin uses Qt Serial Port.
WEB_ADDON_MODULES = {"Positioning", "PositioningQuick", "SerialPort", "WebChannel", "WebChannelQuick"}
DEVELOPMENT_DIRS = {"include", "typesystems", "metatypes", "scripts"}
UNUSED_QML = {("QtQml", "StateMachine"), ("QtQuick", "Scene2D"),
              ("QtQuick", "Scene3D"), ("QtQuick", "VirtualKeyboard")}
QT_TOOLS = {
    "assistant", "balsam", "balsamui", "designer", "linguist", "lrelease", "lupdate",
    "qmlcachegen", "qmlformat", "qmlimportscanner", "qmllint", "qmlls", "qmltyperegistrar",
    "qsb", "rcc", "svgtoqml", "uic",
}


def ship_qt_file(wheel, filename):
    path = PurePosixPath(filename)
    parts = path.parts
    if len(parts) < 2 or parts[0] != "PySide6":
        return True
    area = parts[1]
    if area in DEVELOPMENT_DIRS or path.suffix in {".pyi", ".lib"}:
        return False
    if area == "qml" and parts[2:4] in UNUSED_QML:
        return False
    if area == "plugins" and (parts[2] == "qmltooling" or path.name == "qtvirtualkeyboardplugin.dll"):
        return False
    if path.suffix == ".exe" and path.stem in QT_TOOLS:
        return False
    if area == "resources" and ".debug." in path.name:
        return False
    if not wheel.startswith("pyside6_addons-"):
        return True
    if len(parts) == 2 and area.startswith("Qt6") and path.suffix == ".dll":
        return path.stem[3:] in WEB_ADDON_MODULES
    if len(parts) == 2 and path.suffix == ".pyd":
        return path.stem == "QtWebChannel"
    if len(parts) == 2:
        return path.suffix != ".dll"
    if area == "qml":
        return parts[2] in {"QtPositioning", "QtWebChannel"}
    if area == "plugins":
        return parts[2] == "position"
    return True
