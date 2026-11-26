import QtQuick

Canvas {
    id: glyph
    property string symbol: ""
    property color ink: "white"
    onSymbolChanged: requestPaint()
    onInkChanged: requestPaint()
    onPaint: {
        let c = getContext("2d");
        c.reset();
        c.scale(width / 24, height / 24);
        c.strokeStyle = ink;
        c.fillStyle = ink;
        c.lineWidth = 1.7;
        c.lineCap = "round";
        c.lineJoin = "round";
        function line(points) {
            c.beginPath();
            c.moveTo(points[0], points[1]);
            for (let n = 2; n < points.length; n += 2)
                c.lineTo(points[n], points[n + 1]);
            c.stroke();
        }
        function circle(x, y, r, fill) {
            c.beginPath();
            c.arc(x, y, r, 0, 2 * Math.PI);
            if (fill)
                c.fill();
            else
                c.stroke();
        }
        if (symbol === "search") {
            circle(10, 10, 6, false);
            line([15, 15, 21, 21]);
        } else if (symbol === "wireless") {
            for (let i = 0; i < 3; i++) {
                c.beginPath();
                c.arc(12, 20, 5 + i * 5, -2.38, -.76);
                c.stroke();
            }
            circle(12, 20, 1, true);
        } else if (symbol === "control") {
            for (let i = 0; i < 2; i++) {
                let y = 7 + i * 10;
                line([3, y, 21, y]);
                circle(i ? 15 : 8, y, 3, true);
            }
        } else if (symbol === "bell") {
            c.beginPath();
            c.moveTo(4, 17);
            c.bezierCurveTo(9, 13, 4, 5, 12, 4);
            c.bezierCurveTo(20, 5, 15, 13, 20, 17);
            c.closePath();
            c.stroke();
            line([10, 20, 14, 20]);
        } else if (symbol === "grid") {
            for (let x of [4, 14])
                for (let y of [4, 14])
                    c.strokeRect(x, y, 6, 6);
        } else if (symbol === "lock") {
            c.strokeRect(5, 10, 14, 11);
            c.beginPath();
            c.arc(12, 10, 4, -Math.PI, 0);
            c.stroke();
            circle(12, 15, 1, true);
        } else if (symbol === "battery") {
            c.strokeRect(2, 7, 17, 9);
            c.fillRect(4, 9, 11, 5);
            line([21, 10, 21, 13]);
        } else if (symbol === "arrow") {
            line([5, 12, 19, 12, 14, 7]);
            line([19, 12, 14, 17]);
        } else if (symbol === "check") {
            line([4, 12, 9, 17, 20, 6]);
        } else if (symbol === "sun") {
            circle(12, 12, 4, false);
            for (let i = 0; i < 8; i++) {
                let a = i * Math.PI / 4;
                line([12 + 7 * Math.cos(a), 12 + 7 * Math.sin(a), 12 + 10 * Math.cos(a), 12 + 10 * Math.sin(a)]);
            }
        } else if (symbol === "phone") {
            c.strokeRect(6, 2, 12, 20);
            line([10, 18, 14, 18]);
        } else if (symbol === "exit") {
            line([3, 4, 3, 20, 12, 20]);
            line([10, 12, 22, 12, 17, 7]);
            line([22, 12, 17, 17]);
        } else if (symbol === "chevron") {
            line([8, 5, 15, 12, 8, 19]);
        }
    }
}
