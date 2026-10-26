import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

// Glance: the single most relevant selection, drawn from what the full app cached in
// Application.Storage. The glance has no network or phone link, so it never claims live
// data: if the cache is older than Config.STALE_AFTER_SECONDS it shows the age instead of
// an ETA.
(:glance)
class NextBusGlanceView extends WatchUi.GlanceView {
    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Dc) as Void {
        Store.load();
        var now = Time.now().value();
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        if (Store.selections.size() == 0) {
            drawText2(dc, "Next Bus", "Open the app to add stops", Graphics.COLOR_LT_GRAY);
            return;
        }
        if (Store.generatedAt == 0) {
            drawText2(dc, "Next Bus", "No data yet", Graphics.COLOR_LT_GRAY);
            return;
        }
        var best = Relevance.pick(Store.selections, Store.arrivals, Pos.lastKnown(), now);
        var age = now - Store.generatedAt;
        var stale = age > Config.STALE_AFTER_SECONDS;
        if (best == null) {
            drawText2(dc, "No upcoming buses", Format.ageText(age) + " old", Graphics.COLOR_LT_GRAY);
            return;
        }

        var sel = Store.selections[best[0] as Number] as Array;
        var line = sel[Store.S_LINE] as String;
        var numeric = Format.isNumeric(line);
        var font = numeric ? Graphics.FONT_NUMBER_MEDIUM : Graphics.FONT_MEDIUM;
        dc.setColor(Operators.color(sel[Store.S_OP] as Number, dc), Graphics.COLOR_TRANSPARENT);
        dc.drawText(2, h / 2, font, line, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        var x = dc.getTextWidthInPixels(line, font) + 12;

        var top;
        var topColor = Graphics.COLOR_WHITE;
        if (stale) {
            top = Format.ageText(age) + " old";
            topColor = Graphics.COLOR_YELLOW;
        } else {
            var m = Format.minutesText(best[1] as Number, now);
            top = Format.isNumeric(m) ? m + " min" : m;
        }
        dc.setColor(topColor, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, h * 36 / 100, Graphics.FONT_SMALL, top, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, h * 70 / 100, Graphics.FONT_XTINY, fit(dc, sel[Store.S_NAME] as String, w - x - 2),
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function drawText2(dc as Dc, title as String, detail as String, color as Number) as Void {
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(2, h * 36 / 100, Graphics.FONT_SMALL, title, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(2, h * 70 / 100, Graphics.FONT_XTINY, detail, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function fit(dc as Dc, s as String, maxW as Number) as String {
        if (dc.getTextWidthInPixels(s, Graphics.FONT_XTINY) <= maxW) {
            return s;
        }
        while (s.length() > 1) {
            s = s.substring(0, s.length() - 1);
            if (dc.getTextWidthInPixels(s + "..", Graphics.FONT_XTINY) <= maxW) {
                break;
            }
        }
        return s + "..";
    }
}
