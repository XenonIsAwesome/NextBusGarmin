import Toybox.Application;
import Toybox.Lang;
import Toybox.Position;
import Toybox.Time;

// Last known position as [latE6, lonE6], without ever starting GPS: safe in the glance.
// Remembers the last good fix in Storage so the glance still has one when the system has none.
(:glance)
module Pos {
    const K_POS = "pos";
    // A remembered position older than this is not trusted.
    const MAX_AGE_SECONDS = 12 * 3600;

    function store(info as Position.Info?) as Void {
        if (info == null || info.position == null || info.accuracy == Position.QUALITY_NOT_AVAILABLE) {
            return;
        }
        var d = (info.position as Position.Location).toDegrees();
        Application.Storage.setValue(K_POS, [(d[0] * 1000000).toNumber(), (d[1] * 1000000).toNumber(), Time.now().value()]);
    }

    function lastKnown() as Array? {
        store(Position.getInfo());
        var s = Application.Storage.getValue(K_POS);
        if (s instanceof Array && s.size() == 3 && Time.now().value() - (s[2] as Number) < MAX_AGE_SECONDS) {
            return [s[0], s[1]];
        }
        return null;
    }
}
