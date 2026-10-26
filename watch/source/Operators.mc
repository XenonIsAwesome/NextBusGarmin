import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Operator marks shown next to the line number: the operator's logo where we
// have one (tools/logos/SOURCES.md), otherwise a short code on a colour. Ids
// are curlbus / Ministry of Transport operator ids (https://curlbus.app/operators).
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

    var _logoId as Number = -1;
    var _logo as WatchUi.BitmapResource? = null;

    // The logo bitmap for an operator, or null if we only have a coloured badge.
    // The last one loaded is kept so redraws don't reload the resource.
    function logo(id as Number) as WatchUi.BitmapResource? {
        if (id == _logoId) {
            return _logo;
        }
        var res = null;
        if (id == 3) {
            res = Rez.Drawables.LogoEgged;
        } else if (id == 5) {
            res = Rez.Drawables.LogoDan;
        } else if (id == 14) {
            res = Rez.Drawables.LogoNativExpress;
        } else if (id == 15) {
            res = Rez.Drawables.LogoMetropoline;
        }
        _logo = res == null ? null : WatchUi.loadResource(res) as WatchUi.BitmapResource;
        _logoId = id;
        return _logo;
    }

    // Logos are pre-scaled per screen size (resources-round-*/logos) to this
    // percentage of the screen height; tools/logos/build.py generates them.
    const LOGO_HEIGHT_PERCENT = 11;

    function badgeWidth(id as Number, height as Number) as Number {
        var bmp = logo(id);
        if (bmp != null) {
            return bmp.getWidth();
        }
        var row = find(id);
        if (row == null) {
            return 0;
        }
        return height * ((row[1] as String).length() > 1 ? 14 : 10) / 10;
    }

    // Draws the mark with its top-left at (x, y) and returns its width.
    function drawBadge(dc as Dc, id as Number, x as Number, y as Number, height as Number) as Number {
        var width = badgeWidth(id, height);
        if (width == 0) {
            return 0;
        }
        var bmp = logo(id);
        if (bmp != null) {
            dc.drawBitmap(x, y + (height - bmp.getHeight()) / 2, bmp);
            return width;
        }
        var row = find(id) as Array;
        var code = row[1] as String;
        dc.setColor(row[2] as Number, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(x, y, width, height, height / 4);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x + width / 2, y + height / 2, Graphics.FONT_XTINY, code,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        return width;
    }
}
