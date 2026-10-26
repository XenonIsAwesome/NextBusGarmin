import Toybox.Graphics;
import Toybox.Lang;

// Operator badges shown next to the line number. These are drawn stand-ins
// (a short code on a colour), not the operators' trademarked logos. Ids are
// curlbus / Ministry of Transport operator ids (https://curlbus.app/operators).
module Operators {
    // [id, code, colour]
    const TABLE = [
        [3, "E", 0x00A650],   // Egged
        [4, "ET", 0x6BBE45],  // Egged Taavura
        [5, "D", 0x0072BC],   // Dan
        [6, "SA", 0x8A5A2B],  // Nazareth UNBS
        [7, "NT", 0x7B4FA0],  // Nazareth Transport & Tourism
        [8, "GB", 0xC0392B],  // GB Tours
        [10, "EL", 0x1ABC9C], // Eilot
        [14, "NE", 0xE67E22], // Nativ Express
        [15, "M", 0xF08A00],  // Metropoline
        [16, "S", 0xD81B60],  // Superbus
        [18, "K", 0x2E86C1],  // Kavim
        [20, "C", 0x16A085],  // Carmelit
        [21, "CP", 0x5D6D7E], // Citypass
        [23, "G", 0x884EA0],  // Galim
        [24, "GO", 0x7D6608]  // Golan
    ] as Array;

    // Returns the table row for an operator id, or null if unknown (no badge).
    function find(id as Number) as Array? {
        for (var i = 0; i < TABLE.size(); i++) {
            var row = TABLE[i] as Array;
            if (row[0] == id) {
                return row;
            }
        }
        return null;
    }

    // Draws the badge with its top-left at (x, y) and returns its width.
    function drawBadge(dc as Dc, id as Number, x as Number, y as Number, height as Number) as Number {
        var row = find(id);
        if (row == null) {
            return 0;
        }
        var code = row[1] as String;
        var font = Graphics.FONT_XTINY;
        var width = height * (code.length() > 1 ? 14 : 10) / 10;
        dc.setColor(row[2] as Number, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(x, y, width, height, height / 4);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x + width / 2, y + height / 2, font, code,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        return width;
    }

    function badgeWidth(id as Number, height as Number) as Number {
        var row = find(id);
        if (row == null) {
            return 0;
        }
        return height * ((row[1] as String).length() > 1 ? 14 : 10) / 10;
    }
}
