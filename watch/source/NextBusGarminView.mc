import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// One selection per page: line number, stop name, next arrivals in minutes,
// and a footer saying how old the data is.
class NextBusGarminView extends WatchUi.View {
    var _page as Number = 0;

    function initialize() {
        View.initialize();
    }

    function movePage(delta as Number) as Void {
        var n = Store.selections.size();
        if (n > 0) {
            _page = (_page + delta + n) % n;
            WatchUi.requestUpdate();
        }
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        if (Store.mismatch != Store.MISMATCH_NONE) {
            drawMessage(dc, "Version mismatch",
                Store.mismatch == Store.MISMATCH_PHONE_NEW ? "Update the watch app" : "Update the phone app");
            return;
        }
        var n = Store.selections.size();
        if (n == 0) {
            drawMessage(dc, "Open the phone app", healthText());
            return;
        }
        if (_page >= n) {
            _page = 0;
        }
        var sel = Store.selections[_page] as Array;
        var now = Link.now();
        var cx = w / 2;

        if (n > 1) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h * 8 / 100, Graphics.FONT_XTINY, (_page + 1) + "/" + n,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // Line number.
        var line = sel[Store.S_LINE] as String;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 25 / 100, Format.isNumeric(line) ? Graphics.FONT_NUMBER_HOT : Graphics.FONT_LARGE,
            line, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Stop name.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 44 / 100, Graphics.FONT_TINY,
            fit(dc, sel[Store.S_NAME] as String, Graphics.FONT_TINY, w * 78 / 100),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        drawArrivals(dc, sel, now);
        drawFooter(dc, sel, now);
    }

    function drawArrivals(dc as Dc, sel as Array, now as Number) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        var a = Store.arrivalFor(sel[Store.S_ID] as Number);
        var text = null as String?;
        var color = Graphics.COLOR_WHITE;
        var times = [] as Array<Number>;
        if (a == null) {
            text = Store.generatedAt == 0 ? "Waiting for data" : "No data";
            color = Graphics.COLOR_LT_GRAY;
        } else {
            var status = a[Store.A_STATUS] as Number;
            times = Format.upcoming(a[Store.A_TIMES] as Array<Number>, now, 3);
            if (status == Proto.STATUS_NO_SERVICE) {
                text = "No service";
                color = Graphics.COLOR_ORANGE;
            } else if (times.size() == 0) {
                text = status == Proto.STATUS_ERROR ? "No data" : (status == Proto.STATUS_STALE ? "Waiting for data" : "No upcoming buses");
                color = Graphics.COLOR_LT_GRAY;
            }
        }
        if (text != null) {
            dc.setColor(color, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h * 64 / 100, Graphics.FONT_SMALL, text,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var stale = isStale(a as Array, now);
        dc.setColor(stale ? Graphics.COLOR_YELLOW : Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var first = Format.minutesText(times[0], now);
        var numeric = Format.isNumeric(first);
        var font = numeric ? Graphics.FONT_NUMBER_MEDIUM : Graphics.FONT_LARGE;
        dc.drawText(cx, h * 62 / 100, font, first, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        if (numeric) {
            var tw = dc.getTextWidthInPixels(first, font);
            dc.drawText(cx + tw / 2 + 4, h * 66 / 100, Graphics.FONT_XTINY, "min",
                Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        if (times.size() > 1) {
            var rest = Format.minutesText(times[1], now);
            if (times.size() > 2) {
                rest = rest + " · " + Format.minutesText(times[2], now);
            }
            dc.drawText(cx, h * 77 / 100, Graphics.FONT_SMALL, rest,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    function isStale(a as Array, now as Number) as Boolean {
        return a[Store.A_STATUS] == Proto.STATUS_STALE
            || now - Store.generatedAt > Config.STALE_AFTER_SECONDS;
    }

    function drawFooter(dc as Dc, sel as Array, now as Number) as Void {
        var a = Store.arrivalFor(sel[Store.S_ID] as Number);
        var warn = false;
        var text;
        if (Store.generatedAt == 0) {
            text = "No data yet";
        } else {
            var age = Format.ageText(now - Store.generatedAt);
            warn = a != null && isStale(a, now);
            text = warn ? "Stale " + age : "Updated " + age + " ago";
        }
        var health = Link.health();
        if (health == Link.H_DISCONNECTED) {
            text = "Phone offline";
            warn = true;
        } else if (health == Link.H_NO_REPLY) {
            text = "Phone not responding";
            warn = true;
        }
        if (Config.FAKE_PHONE && Link.fake != null) {
            text = "[" + Link.fake.scenario + "] " + text;
        }
        dc.setColor(warn ? Graphics.COLOR_YELLOW : Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() * 89 / 100, Graphics.FONT_XTINY,
            fit(dc, text, Graphics.FONT_XTINY, dc.getWidth() * 70 / 100),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function healthText() as String {
        var health = Link.health();
        if (health == Link.H_DISCONNECTED) {
            return "Phone not connected";
        }
        if (health == Link.H_NO_REPLY) {
            return "Phone app not responding";
        }
        return "Add a bus line there";
    }

    function drawMessage(dc as Dc, title as String, detail as String) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 42 / 100, Graphics.FONT_SMALL, fit(dc, title, Graphics.FONT_SMALL, w * 80 / 100),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 58 / 100, Graphics.FONT_XTINY, fit(dc, detail, Graphics.FONT_XTINY, w * 80 / 100),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Truncates with ".." so the text fits maxW pixels.
    function fit(dc as Dc, s as String, font as FontType, maxW as Number) as String {
        if (dc.getTextWidthInPixels(s, font) <= maxW) {
            return s;
        }
        while (s.length() > 1) {
            s = s.substring(0, s.length() - 1);
            if (dc.getTextWidthInPixels(s + "..", font) <= maxW) {
                break;
            }
        }
        return s + "..";
    }
}
