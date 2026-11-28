import QtQuick

Item {
    id: layer
    required property Item desktop
    property int corner: 20
    property int density: 165
    property bool dark: desktop.dark
    // Coordinates are evaluated in the same aspect-fill space as the photographic background.
    property point origin: {
        let watch = parent.x + parent.y + x + y + width + height + desktop.width + desktop.height;
        return mapToItem(desktop, 0, 0);
    }
    Rectangle {
        x: 0
        y: 5
        width: parent.width
        height: parent.height
        radius: layer.corner
        color: "#22071320"
    }
    Rectangle {
        anchors.fill: parent
        radius: layer.corner
        color: layer.dark ? "#99233343" : "#70eef5fa"
        border.color: "#76eaf4ff"
        visible: glass.status !== Image.Ready
    }
    Image {
        id: glass
        anchors.fill: parent
        asynchronous: true
        cache: true
        source: desktop.materialEffects && layer.visible && width > 1 && height > 1 ? "image://material/" + desktop.scene + "/" + (layer.dark ? "dark" : "light") + "/" + [layer.origin.x, layer.origin.y, width, height, desktop.width, desktop.height, layer.corner, layer.density].map(Math.round).join("/") : ""
    }
}
