import QtQuick
import QtQuick.Controls.Basic

Item {
    id: calendar
    objectName: "desktopCalendar"
    required property Item desktop
    property date month: new Date()
    width: 236
    height: 217
    function shift(delta) {
        month = new Date(month.getFullYear(), month.getMonth() + delta, 1)
    }
    Glass {
        desktop: calendar.desktop
        anchors.fill: parent
        density: 215
    }
    Text {
        x: 16; y: 16
        text: Qt.formatDate(calendar.month, "MMMM yyyy")
        color: desktop.ink
        font.family: desktop.family
        font.pixelSize: 15
        font.weight: Font.DemiBold
    }
    Row {
        anchors.right: parent.right
        anchors.rightMargin: 9
        y: 6
        Repeater {
            model: [-1, 1]
            delegate: ToolButton {
                required property int modelData
                width: 32; height: 32
                text: modelData < 0 ? "‹" : "›"
                Accessible.name: modelData < 0 ? "Previous month" : "Next month"
                palette.buttonText: desktop.ink
                onClicked: calendar.shift(modelData)
            }
        }
    }
    DayOfWeekRow {
        id: week
        x: 10; y: 46; width: parent.width - 20; height: 20
        locale: Qt.locale()
        delegate: Text {
            required property var model
            text: model.narrowName
            color: desktop.sub
            font.pixelSize: 11
            horizontalAlignment: Text.AlignHCenter
        }
    }
    MonthGrid {
        x: week.x; y: 70; width: week.width; height: 135
        month: calendar.month.getMonth()
        year: calendar.month.getFullYear()
        locale: week.locale
        delegate: Text {
            required property var model
            text: model.day
            color: model.today ? (desktop.dark ? "#94cfef" : "#18577b") : desktop.ink
            opacity: model.month === calendar.month.getMonth() ? 1 : 0.43
            font.bold: model.today
            font.pixelSize: 13
            horizontalAlignment: Text.AlignHCenter
        }
    }
}
