import Toybox.Lang;
import Toybox.Test;

(:test)
function testMinutesText(logger as Test.Logger) as Boolean {
    var now = 1000;
    return Format.minutesText(now - 50, now).equals("now")
        && Format.minutesText(now + 59, now).equals("now")
        && Format.minutesText(now + 60, now).equals("1")
        && Format.minutesText(now + 3 * 60 + 40, now).equals("3")
        && Format.minutesText(now + 125 * 60, now).equals("125");
}

(:test)
function testAgeText(logger as Test.Logger) as Boolean {
    return Format.ageText(-5).equals("0s") && Format.ageText(12).equals("12s")
        && Format.ageText(60).equals("1m") && Format.ageText(3599).equals("59m")
        && Format.ageText(7300).equals("2h");
}

(:test)
function testUpcomingKeepsGraceAndCaps(logger as Test.Logger) as Boolean {
    var now = 1000;
    var u = Format.upcoming([now - 60, now - 10, now + 5, now + 100, now + 200, now + 300], now, 3);
    return u.size() == 3 && u[0] == now - 10 && u[2] == now + 100;
}

(:test)
function testIsNumeric(logger as Test.Logger) as Boolean {
    return Format.isNumeric("29") && Format.isNumeric("480") && !Format.isNumeric("now")
        && !Format.isNumeric("29A") && !Format.isNumeric("");
}

(:test)
function testStoreAppliesAndRejectsVersions(logger as Test.Logger) as Boolean {
    Store.selections = [];
    Store.arrivals = [];
    Store.mismatch = Store.MISMATCH_NONE;
    var v = Config.PROTOCOL_VERSION;
    Store.apply([Proto.T_SELECTIONS, v, [[7, "29", "1", "Stop", "Dest", 0, 0]]]);
    Store.apply([Proto.T_ARRIVALS, v, 500, [[7, Proto.STATUS_OK, [600, 700]], [99, Proto.STATUS_OK, [1]]]]);
    var ok = Store.selections.size() == 1 && Store.generatedAt == 500
        && Store.arrivalFor(7) != null && Store.mismatch == Store.MISMATCH_NONE;
    // Syncing a smaller list drops arrivals of removed selections.
    Store.apply([Proto.T_SELECTIONS, v, []]);
    ok = ok && Store.arrivals.size() == 0;
    // Incompatible versions are not applied.
    Store.apply([Proto.T_SELECTIONS, v + 1, [[1, "5", "1", "S", "D", 0, 0]]]);
    ok = ok && Store.selections.size() == 0 && Store.mismatch == Store.MISMATCH_PHONE_NEW;
    Store.apply([Proto.T_ARRIVALS, v - 1, 1, []]);
    ok = ok && Store.mismatch == Store.MISMATCH_PHONE_OLD;
    Store.mismatch = Store.MISMATCH_NONE;
    return ok;
}
