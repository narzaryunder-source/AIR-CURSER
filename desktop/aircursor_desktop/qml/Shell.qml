import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

Item {
    id: root
    focus: true
    property bool locked: shell.locked
    property bool dark: shell.dark
    property string scene: shell.scene
    property color ink: dark ? "#f4f7fa" : "#1a2836"
    property color sub: dark ? "#c1d0d7" : "#495969"
    property color selection: dark ? "#405e75" : "#a8cbea"
    property string overlay: ""
    onOverlayChanged: shell.cover(overlay)
    property string notice: ""
    property string errorText: ""
    property int dockSize: Math.min(59, Math.max(35, Math.floor((width - 115) / Math.max(9, shell.dock.length))))
    property string family: Qt.platform.os === "windows" ? "Segoe UI Variable" : "DejaVu Sans"
    function showOverlay(name) {
        overlay = overlay === name ? "" : name;
        if (overlay === "spotlight") {
            query.text = "";
            shell.search("");
            query.forceActiveFocus();
        }
        if (overlay === "overview")
            shell.capture();
        if (!overlay)
            root.forceActiveFocus();
    }
    function run(command) {
        overlay = "";
        shell.action(command);
    }
    function authenticate() {
        shell.authenticate(secret.text, repeat.text, trust.checked);
        secret.clear();
        repeat.clear();
    }
    Connections {
        target: shell
        function onOverlayRequested(name) {
            root.showOverlay(name);
        }
        function onFeedback(message) {
            if (root.locked) {
                root.errorText = message;
            } else {
                root.notice = message;
                noticeTimer.restart();
            }
        }
        function onLockedChanged() {
            root.overlay = "";
            root.errorText = "";
            root.notice = "";
            trust.checked = false;
            if (root.locked)
                secret.forceActiveFocus();
        }
    }
    Keys.onEscapePressed: {
        if (overlay) {
            overlay = "";
            root.forceActiveFocus();
        } else
            shell.action("exit-full");
    }
    Image {
        anchors.fill: parent
        source: shell.assets + root.scene + ".jpg"
        fillMode: Image.PreserveAspectCrop
        asynchronous: false
    }
    Rectangle {
        anchors.fill: parent
        color: "#26385d"
        opacity: shell.dusk
    }
    Rectangle {
        anchors.fill: parent
        color: root.dark ? "#21102032" : "#04091418"
    }
    Rectangle {
        anchors.fill: parent
        visible: root.locked
        gradient: Gradient {
            GradientStop {
                position: 0
                color: "#230f1c2b"
            }
            GradientStop {
                position: .45
                color: "transparent"
            }
            GradientStop {
                position: 1
                color: "#7e0c1720"
            }
        }
    }
    component WhiteText: Text {
        color: "white"
        font.family: root.family
        renderType: Text.QtRendering
        style: Text.Raised
        styleColor: "#45081927"
    }
    component Info: Text {
        color: root.ink
        font.family: root.family
        elide: Text.ElideRight
        verticalAlignment: Text.AlignVCenter
    }
    component Hit: MouseArea {
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
    }
    component MenuItem: Item {
        property string text: ""
        property string glyph: ""
        property string command: ""
        property bool bold: false
        width: glyph ? 32 : caption.implicitWidth + 16
        height: 30
        Rectangle {
            anchors.fill: parent
            anchors.margins: 2
            radius: 6
            color: hit.containsMouse ? "#42ffffff" : "transparent"
        }
        WhiteText {
            id: caption
            anchors.centerIn: parent
            text: parent.text
            font.pixelSize: 13
            font.weight: parent.bold ? Font.DemiBold : Font.Normal
        }
        Glyph {
            anchors.centerIn: parent
            width: 17
            height: 17
            symbol: parent.glyph
            visible: parent.glyph !== ""
        }
        Hit {
            id: hit
            onClicked: {
                if (["spotlight", "quick", "notifications", "overview"].includes(command))
                    root.showOverlay(command);
                else if (command.startsWith("menu:")) {
                    let p = mapToItem(root, 0, height);
                    shell.menu(command.slice(5), p.x, p.y);
                } else
                    root.run(command);
            }
        }
        Accessible.role: Accessible.Button
        Accessible.name: text || command
    }

    Item {
        anchors.fill: parent
        visible: !root.locked
        MouseArea {
            anchors.fill: parent
            acceptedButtons: Qt.LeftButton | Qt.RightButton
            onClicked: e => {
                root.overlay = "";
                if (e.button === Qt.RightButton)
                    shell.menu("Desktop", e.x, e.y);
            }
        }
        Rectangle {
            id: menubar
            anchors.top: parent.top
            width: parent.width
            height: 30
            gradient: Gradient {
                GradientStop {
                    position: 0
                    color: "#4c101d29"
                }
                GradientStop {
                    position: 1
                    color: "#29121c27"
                }
            }
            Row {
                anchors.left: parent.left
                anchors.leftMargin: 14
                height: 30
                Item {
                    width: 29
                    height: 30
                    Image {
                        anchors.centerIn: parent
                        width: 18
                        height: 18
                        source: shell.assets + "icons/mark.svg"
                    }
                    Hit {
                        onClicked: shell.menu("AirCursor", 14, 30)
                    }
                }
                MenuItem {
                    text: "AirCursor"
                    bold: true
                    command: "menu:AirCursor"
                }
                MenuItem {
                    text: "File"
                    command: "menu:File"
                }
                MenuItem {
                    text: "View"
                    command: "menu:View"
                }
                MenuItem {
                    text: "Window"
                    command: "menu:Window"
                }
            }
            Row {
                anchors.right: parent.right
                anchors.rightMargin: 11
                height: 30
                MenuItem {
                    text: shell.fullscreen ? "Exit desktop" : ""
                    visible: shell.fullscreen
                    command: "exit-full"
                }
                MenuItem {
                    glyph: "battery"
                    visible: shell.battery !== ""
                    command: "quick"
                }
                MenuItem {
                    glyph: "wireless"
                    command: "quick"
                }
                MenuItem {
                    glyph: "search"
                    command: "spotlight"
                }
                MenuItem {
                    glyph: "control"
                    command: "quick"
                }
                MenuItem {
                    text: shell.clockLine
                    command: "notifications"
                }
            }
        }

        Column {
            id: shortcuts
            anchors.right: parent.right
            anchors.rightMargin: 17
            y: 49
            spacing: 6
            Repeater {
                model: shell.items
                delegate: Item {
                    id: shortcut
                    required property var modelData
                    required property int index
                    width: 94
                    height: 85
                    Image {
                        anchors.horizontalCenter: parent.horizontalCenter
                        y: 2
                        width: 49
                        height: 46
                        source: shell.assets + "icons/" + modelData.icon + ".svg"
                    }
                    WhiteText {
                        anchors.horizontalCenter: parent.horizontalCenter
                        y: 51
                        width: 92
                        text: modelData.label
                        horizontalAlignment: Text.AlignHCenter
                        font.pixelSize: 12
                        font.weight: Font.DemiBold
                        elide: Text.ElideMiddle
                    }
                    MouseArea {
                        id: shortHit
                        anchors.fill: parent
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        drag.target: dragIcon
                        drag.axis: Drag.YAxis
                        onDoubleClicked: root.run(modelData.target)
                        onClicked: e => {
                            if (e.button === Qt.RightButton) {
                                let p = mapToItem(root, e.x, e.y);
                                shell.menu("shortcut:" + index, p.x, p.y);
                            }
                        }
                        onReleased: {
                            if (Math.abs(dragIcon.y) > 28)
                                shell.reorder(index, Math.round(index + dragIcon.y / 91));
                            dragIcon.y = 0;
                        }
                    }
                    Item {
                        id: dragIcon
                        width: 1
                        height: 1
                    }
                    transform: Translate {
                        y: dragIcon.y
                    }
                }
            }
        }

        Item {
            id: dock
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 9
            property real mouseX: -999
            property bool active: dockHover.hovered && root.overlay === ""
            width: dockRow.width + 22
            height: root.dockSize + 19
            Glass {
                anchors.fill: parent
                desktop: root
                density: root.dark ? 83 : 77
                corner: 22
                dark: root.dark
            }
            HoverHandler {
                id: dockHover
                onPointChanged: dock.mouseX = point.position.x
            }
            Row {
                id: dockRow
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.bottom: parent.bottom
                anchors.bottomMargin: 5
                spacing: 4
                Repeater {
                    model: shell.dock
                    delegate: Item {
                        id: slot
                        required property var modelData
                        required property int index
                        property real near: dock.active ? Math.max(0, 1 - Math.abs(dock.mouseX - (11 + index * (root.dockSize + 4) + root.dockSize / 2)) / (root.dockSize * 1.75)) : 0
                        property real grow: shell.motion ? near * 18 : 0
                        Behavior on grow {
                            SpringAnimation {
                                spring: 3.0
                                damping: .52
                                epsilon: .2
                            }
                        }
                        width: root.dockSize + grow * .55 + (modelData.separator ? 8 : 0)
                        height: root.dockSize + 8
                        Rectangle {
                            visible: modelData.separator
                            width: 1
                            height: root.dockSize * .65
                            anchors.left: parent.left
                            anchors.verticalCenter: parent.verticalCenter
                            color: "#72eaf4ff"
                        }
                        Image {
                            id: art
                            anchors.bottom: parent.bottom
                            anchors.bottomMargin: 4
                            anchors.horizontalCenter: parent.horizontalCenter
                            anchors.horizontalCenterOffset: modelData.separator ? 4 : 0
                            width: root.dockSize + slot.grow
                            height: width
                            source: modelData.art
                            sourceSize: Qt.size(155, 155)
                            smooth: true
                        }
                        Rectangle {
                            anchors.horizontalCenter: art.horizontalCenter
                            anchors.bottom: parent.bottom
                            width: 4
                            height: 4
                            radius: 2
                            color: "#ecf6ff"
                            visible: modelData.running
                        }
                        Rectangle {
                            visible: hover.hovered
                            anchors.horizontalCenter: art.horizontalCenter
                            anchors.bottom: art.top
                            anchors.bottomMargin: 9
                            width: tip.implicitWidth + 20
                            height: 28
                            radius: 9
                            color: "#eb24323d"
                            border.color: "#68dce9f0"
                            z: 3
                            Text {
                                id: tip
                                anchors.centerIn: parent
                                text: modelData.label
                                color: "white"
                                font.family: root.family
                                font.pixelSize: 12
                            }
                        }
                        HoverHandler {
                            id: hover
                        }
                        TapHandler {
                            acceptedButtons: Qt.LeftButton
                            onTapped: {
                                if (modelData.target === "Launcher")
                                    root.showOverlay("spotlight");
                                else if (modelData.target === "Overview")
                                    root.showOverlay("overview");
                                else if (modelData.target === "Control Center")
                                    root.showOverlay("quick");
                                else
                                    root.run(modelData.target);
                            }
                        }
                        TapHandler {
                            acceptedButtons: Qt.RightButton
                            onTapped: {
                                let p = slot.mapToItem(root, 0, 0);
                                shell.menu("dock:" + modelData.target, p.x, p.y);
                            }
                        }
                        Accessible.role: Accessible.Button
                        Accessible.name: modelData.label
                    }
                }
            }
        }

        MouseArea {
            anchors.fill: parent
            visible: root.overlay !== ""
            onClicked: {
                root.overlay = "";
                root.forceActiveFocus();
            }
        }
        Item {
            id: search
            visible: root.overlay === "spotlight"
            anchors.horizontalCenter: parent.horizontalCenter
            y: Math.max(48, root.height * .18)
            width: Math.min(652, root.width - 40)
            height: Math.min(465, root.height - y - 26)
            Glass {
                anchors.fill: parent
                desktop: root
                corner: 22
                density: root.dark ? 186 : 195
            }
            MouseArea {
                anchors.fill: parent
            }
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 15
                spacing: 7
                RowLayout {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 45
                    spacing: 11
                    Glyph {
                        width: 24
                        height: 24
                        symbol: "search"
                        ink: root.sub
                    }
                    TextField {
                        id: query
                        Layout.fillWidth: true
                        font.family: root.family
                        font.pixelSize: 23
                        color: root.ink
                        placeholderText: "Search AirCursor"
                        placeholderTextColor: root.sub
                        background: null
                        onTextChanged: shell.search(text)
                        Keys.onDownPressed: results.currentIndex = Math.min(results.count - 1, results.currentIndex + 1)
                        Keys.onUpPressed: results.currentIndex = Math.max(0, results.currentIndex - 1)
                        onAccepted: {
                            if (results.currentIndex >= 0)
                                root.run(shell.results[results.currentIndex].target);
                        }
                    }
                    Info {
                        text: "esc"
                        color: root.sub
                        font.pixelSize: 11
                    }
                }
                Rectangle {
                    Layout.fillWidth: true
                    height: 1
                    color: root.dark ? "#4098a8b7" : "#308297a5"
                }
                Info {
                    text: query.text ? "Results" : "Applications"
                    color: root.sub
                    font.pixelSize: 12
                    font.weight: Font.DemiBold
                    leftPadding: 8
                }
                ListView {
                    id: results
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    model: shell.results
                    currentIndex: 0
                    spacing: 2
                    delegate: Rectangle {
                        required property var modelData
                        required property int index
                        width: results.width
                        height: 41
                        radius: 8
                        color: ListView.isCurrentItem ? root.selection : "transparent"
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 10
                            anchors.rightMargin: 12
                            spacing: 10
                            Image {
                                source: modelData.art
                                Layout.preferredWidth: 30
                                Layout.preferredHeight: 30
                            }
                            Info {
                                text: modelData.label
                                Layout.fillWidth: true
                                font.pixelSize: 14
                            }
                            Info {
                                text: modelData.kind
                                color: root.sub
                                font.pixelSize: 11
                            }
                        }
                        Hit {
                            acceptedButtons: Qt.LeftButton | Qt.RightButton
                            onClicked: e => {
                                if (e.button === Qt.RightButton) {
                                    shell.pinResult(modelData.target);
                                } else {
                                    results.currentIndex = index;
                                    root.run(modelData.target);
                                }
                            }
                        }
                    }
                }
                RowLayout {
                    Layout.fillWidth: true
                    Info {
                        text: "↑ ↓   Select"
                        color: root.sub
                        font.pixelSize: 11
                    }
                    Item {
                        Layout.fillWidth: true
                    }
                    Info {
                        text: "↵   Open"
                        color: root.sub
                        font.pixelSize: 11
                    }
                }
            }
        }

        Item {
            id: quick
            visible: root.overlay === "quick" || root.overlay === "notifications"
            width: Math.min(344, root.width - 26)
            height: root.overlay === "quick" ? 306 : 268
            anchors.right: parent.right
            anchors.rightMargin: 12
            y: 38
            Glass {
                anchors.fill: parent
                desktop: root
                density: root.dark ? 155 : 167
                corner: 24
            }
            MouseArea {
                anchors.fill: parent
            }
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 18
                spacing: 10
                Info {
                    text: root.overlay === "quick" ? "Control Center" : "Notifications"
                    font.pixelSize: 19
                    font.weight: Font.DemiBold
                }
                GridLayout {
                    visible: root.overlay === "quick"
                    columns: 2
                    columnSpacing: 8
                    rowSpacing: 8
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Repeater {
                        model: [["wireless", "Hotspot", shell.network, "Control Center"], ["phone", "Phone input", shell.receiver, "remote"], ["sun", "Appearance", root.dark ? "Dark" : "Light", "toggle-appearance"], ["grid", "Workspace", shell.spaceName, "next-space"]]
                        delegate: Rectangle {
                            required property var modelData
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            Layout.preferredWidth: 135
                            radius: 15
                            color: b.containsMouse ? "#558bc5e2" : (root.dark ? "#402b4051" : "#75ffffff")
                            Column {
                                anchors.left: parent.left
                                anchors.leftMargin: 12
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 3
                                Glyph {
                                    width: 19
                                    height: 19
                                    symbol: modelData[0]
                                    ink: root.dark ? "#ace0f8" : "#1c759e"
                                }
                                Info {
                                    text: modelData[1]
                                    font.weight: Font.DemiBold
                                    font.pixelSize: 13
                                }
                                Info {
                                    text: modelData[2]
                                    width: 123
                                    font.pixelSize: 11
                                    color: root.sub
                                }
                            }
                            Hit {
                                id: b
                                onClicked: root.run(modelData[3])
                            }
                        }
                    }
                }
                ListView {
                    visible: root.overlay === "notifications"
                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    clip: true
                    model: shell.notes
                    delegate: Info {
                        required property string modelData
                        text: modelData
                        width: ListView.view.width
                        wrapMode: Text.WordWrap
                        font.pixelSize: 13
                        topPadding: 6
                        bottomPadding: 6
                    }
                    Info {
                        visible: parent.count === 0
                        anchors.centerIn: parent
                        text: "No new notifications"
                        color: root.sub
                        font.pixelSize: 13
                    }
                }
                RowLayout {
                    Layout.fillWidth: true
                    Info {
                        text: root.overlay === "quick" ? shell.profileName : "AirCursor"
                        font.pixelSize: 12
                        color: root.sub
                        Layout.fillWidth: true
                    }
                    Button {
                        text: root.overlay === "quick" ? "All controls  ›" : "Done"
                        palette.buttonText: root.ink
                        flat: true
                        onClicked: root.overlay === "quick" ? root.run("Control Center") : root.overlay = ""
                    }
                }
            }
        }

        Rectangle {
            anchors.fill: parent
            visible: root.overlay === "overview"
            color: "#b71b2935"
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 34
                spacing: 20
                RowLayout {
                    Layout.fillWidth: true
                    WhiteText {
                        text: "Window overview"
                        font.pixelSize: 24
                    }
                    Item {
                        Layout.fillWidth: true
                    }
                    Button {
                        text: "Workspace 1"
                        onClicked: root.run("space:1")
                    }
                    Button {
                        text: "Workspace 2"
                        onClicked: root.run("space:2")
                    }
                    Button {
                        text: "Done"
                        onClicked: root.overlay = ""
                    }
                }
                GridView {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    model: shell.windows
                    cellWidth: Math.min(330, width / 2)
                    cellHeight: 220
                    delegate: Item {
                        required property var modelData
                        width: GridView.view.cellWidth - 16
                        height: 202
                        Image {
                            anchors.fill: parent
                            anchors.bottomMargin: 29
                            source: modelData.preview
                            fillMode: Image.PreserveAspectFit
                            cache: false
                        }
                        WhiteText {
                            anchors.horizontalCenter: parent.horizontalCenter
                            anchors.bottom: parent.bottom
                            text: modelData.label
                            font.pixelSize: 13
                        }
                        Hit {
                            onClicked: root.run(modelData.target)
                        }
                    }
                    WhiteText {
                        anchors.centerIn: parent
                        visible: parent.count === 0
                        text: "No open windows"
                        font.pixelSize: 16
                    }
                }
            }
        }
    }

    Item {
        anchors.fill: parent
        visible: root.locked
        Column {
            anchors.horizontalCenter: parent.horizontalCenter
            y: Math.max(26, parent.height * .12)
            spacing: 0
            WhiteText {
                anchors.horizontalCenter: parent.horizontalCenter
                text: shell.dateLine
                font.pixelSize: Math.max(17, root.height * .025)
                font.weight: Font.DemiBold
            }
            WhiteText {
                anchors.horizontalCenter: parent.horizontalCenter
                text: shell.timeLine
                font.pixelSize: Math.min(116, root.height * .13)
                font.weight: Font.DemiBold
                font.letterSpacing: -4
                color: "#eaf0f4"
            }
        }
        Column {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Math.max(19, Math.min(parent.height * .075, 70))
            spacing: 8
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: 54
                height: 54
                radius: 27
                color: "#d12d4352"
                border.color: "#b7dae4e8"
                Image {
                    anchors.centerIn: parent
                    source: shell.assets + "icons/mark.svg"
                    width: 32
                    height: 32
                }
            }
            WhiteText {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "AirCursor"
                font.pixelSize: 20
                font.weight: Font.DemiBold
            }
            WhiteText {
                anchors.horizontalCenter: parent.horizontalCenter
                text: shell.first ? "Create a workspace password" : "Unlock your workspace"
                font.pixelSize: 12
            }
            AuthField {
                id: secret
                objectName: "password"
                anchors.horizontalCenter: parent.horizontalCenter
                placeholderText: "Password"
                onAccepted: shell.first ? repeat.forceActiveFocus() : root.authenticate()
                ToolButton {
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    width: 32
                    height: 30
                    text: "→"
                    palette.buttonText: "white"
                    onClicked: root.authenticate()
                }
            }
            AuthField {
                id: repeat
                objectName: "confirmPassword"
                visible: shell.first
                anchors.horizontalCenter: parent.horizontalCenter
                placeholderText: "Confirm password"
                onAccepted: root.authenticate()
            }
            CheckBox {
                id: trust
                visible: shell.canTrust
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Trust this Windows sign-in"
                palette.windowText: "white"
                font.pixelSize: 11
            }
            WhiteText {
                visible: root.errorText !== ""
                text: root.errorText
                width: 290
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                color: "#fff1c2"
                font.pixelSize: 12
            }
            WhiteText {
                visible: !shell.first
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Reset AirCursor…"
                font.pixelSize: 11
                Hit {
                    onClicked: shell.action("reset")
                }
            }
        }
    }
    component AuthField: TextField {
        width: 240
        height: 35
        color: "white"
        echoMode: TextInput.Password
        placeholderTextColor: "#dce7ed"
        font.family: root.family
        font.pixelSize: 13
        leftPadding: 14
        rightPadding: 32
        background: Rectangle {
            radius: 17
            color: "#a52c404e"
            border.color: parent.activeFocus ? "#dbdceff6" : "#7eb7c8d3"
        }
    }
    Rectangle {
        visible: root.notice !== "" && !root.locked
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: root.dockSize + 42
        color: "#e52b3945"
        width: Math.min(root.width - 32, toastMsg.implicitWidth + 32)
        height: 42
        radius: 13
        WhiteText {
            id: toastMsg
            anchors.centerIn: parent
            width: Math.min(implicitWidth, root.width - 64)
            text: root.notice
            font.pixelSize: 13
            elide: Text.ElideRight
        }
    }
    Timer {
        id: noticeTimer
        interval: 4500
        onTriggered: root.notice = ""
    }
}
