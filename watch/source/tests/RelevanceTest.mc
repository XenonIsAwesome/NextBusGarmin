import Toybox.Lang;
import Toybox.Test;

// Relevance cases. Coordinates are microdegrees; 1 microdegree of latitude is 0.1113 m, so
// +5839 is ~650 m north and +45000 is ~5 km north of the user.
module RelevanceCases {
    const NOW = 10000;
    const SPEED = 1.3f;
    const BUFFER = 30;
    const MAX_DIST = 2000;
    const USER_LAT = 32184760;
    const USER_LON = 34855867;

    function user() as Array {
        return [USER_LAT, USER_LON];
    }

    // [id, line, code, name, dest, lat, lon, operator]
    function stop(id as Number, northE6 as Number) as Array {
        return [id, "1", "c", "n", "d", USER_LAT + northE6, USER_LON, 0];
    }

    function arr(id as Number, offsets as Array<Number>) as Array {
        var times = [] as Array<Number>;
        for (var i = 0; i < offsets.size(); i++) {
            times.add(NOW + offsets[i]);
        }
        return [id, Proto.STATUS_OK, times];
    }

    // Returns the chosen selection index, or -1 for null.
    function winner(sels as Array, arrs as Array, pos as Array?) as Number {
        var r = Relevance.pickWith(sels, arrs, pos, NOW, SPEED, BUFFER, MAX_DIST);
        return r == null ? -1 : r[0] as Number;
    }
}

(:test)
function testRelevanceDistance(logger as Test.Logger) as Boolean {
    var d = Relevance.distanceM(32184760, 34855867, 32184760 + 5839, 34855867);
    var e = Relevance.distanceM(32184760, 34855867, 32184760, 34855867 + 10000);  // ~943 m east at this latitude
    return d > 645.0 && d < 655.0 && e > 935.0 && e < 950.0
        && Relevance.distanceM(1, 2, 1, 2) == 0.0;
}

(:test)
function testRelevanceCatchableBeatsMissed(logger as Test.Logger) as Boolean {
    // A is here (walk 30 s): its 10 s bus is missed, its 600 s bus is catchable.
    // B is 650 m away (walk ~530 s): its 400 s bus is missed, its 900 s bus is catchable.
    var sels = [RelevanceCases.stop(1, 0), RelevanceCases.stop(2, 5839)];
    var arrs = [RelevanceCases.arr(1, [10, 600]), RelevanceCases.arr(2, [400, 900])];
    var first = RelevanceCases.winner(sels, arrs, RelevanceCases.user()) == 0;
    // A's only bus is missed; B's 600 s bus is catchable even though A's bus is sooner.
    arrs = [RelevanceCases.arr(1, [10]), RelevanceCases.arr(2, [600])];
    return first && RelevanceCases.winner(sels, arrs, RelevanceCases.user()) == 1;
}

(:test)
function testRelevanceWalkBoundaryAndBuffer(logger as Test.Logger) as Boolean {
    var sels = [RelevanceCases.stop(1, 0)];  // walk = 0 / 1.3 + 30 = 30 s
    var exactly = Relevance.pickWith(sels, [RelevanceCases.arr(1, [30])], RelevanceCases.user(),
        RelevanceCases.NOW, RelevanceCases.SPEED, RelevanceCases.BUFFER, RelevanceCases.MAX_DIST);
    var justMissed = Relevance.pickWith(sels, [RelevanceCases.arr(1, [29, 31])], RelevanceCases.user(),
        RelevanceCases.NOW, RelevanceCases.SPEED, RelevanceCases.BUFFER, RelevanceCases.MAX_DIST);
    return exactly != null && exactly[1] == RelevanceCases.NOW + 30
        && justMissed != null && justMissed[1] == RelevanceCases.NOW + 31;
}

(:test)
function testRelevanceAllFarAway(logger as Test.Logger) as Boolean {
    // Both stops ~5 km / ~6 km away, beyond the 2 km limit, so tier 2: earliest catchable
    // candidate. Walking takes ~3880 s / ~4650 s (plus the 30 s buffer is included), so
    // stop 1's 100 s bus is missed (candidate 5000 s) and stop 2's 4700 s bus is catchable.
    var sels = [RelevanceCases.stop(1, 45000), RelevanceCases.stop(2, 54000)];
    var arrs = [RelevanceCases.arr(1, [100, 5000]), RelevanceCases.arr(2, [4700])];
    var catchable = RelevanceCases.winner(sels, arrs, RelevanceCases.user()) == 1;
    // Nothing catchable anywhere (all buses leave before anyone could walk there): fall back
    // to the soonest upcoming arrival overall.
    arrs = [RelevanceCases.arr(1, [300]), RelevanceCases.arr(2, [100])];
    return catchable && RelevanceCases.winner(sels, arrs, RelevanceCases.user()) == 1;
}

(:test)
function testRelevanceNearBeatsFarEvenIfFarIsSooner(logger as Test.Logger) as Boolean {
    var sels = [RelevanceCases.stop(1, 45000), RelevanceCases.stop(2, 0)];
    var arrs = [RelevanceCases.arr(1, [4000]), RelevanceCases.arr(2, [900])];
    // Far stop's catchable bus (4000 s) is later anyway; make it earlier than the near one.
    var laterNear = RelevanceCases.winner(sels, arrs, RelevanceCases.user()) == 1;
    arrs = [RelevanceCases.arr(1, [3900]), RelevanceCases.arr(2, [9000])];
    return laterNear && RelevanceCases.winner(sels, arrs, RelevanceCases.user()) == 1;
}

(:test)
function testRelevanceNoLocationUsesSoonestOverall(logger as Test.Logger) as Boolean {
    var sels = [RelevanceCases.stop(1, 0), RelevanceCases.stop(2, 5839)];
    var arrs = [RelevanceCases.arr(1, [300]), RelevanceCases.arr(2, [20, 900])];
    var r = Relevance.pickWith(sels, arrs, null, RelevanceCases.NOW, RelevanceCases.SPEED,
        RelevanceCases.BUFFER, RelevanceCases.MAX_DIST);
    return r != null && r[0] == 1 && r[1] == RelevanceCases.NOW + 20;
}

(:test)
function testRelevanceNoArrivals(logger as Test.Logger) as Boolean {
    var sels = [RelevanceCases.stop(1, 0), RelevanceCases.stop(2, 5839)];
    // No arrival data at all, empty lists, and only past arrivals all give null.
    var none = RelevanceCases.winner(sels, [], RelevanceCases.user()) == -1;
    var empty = RelevanceCases.winner(sels, [RelevanceCases.arr(1, []), RelevanceCases.arr(2, [])], RelevanceCases.user()) == -1;
    var past = RelevanceCases.winner(sels, [RelevanceCases.arr(1, [-50]), RelevanceCases.arr(2, [-5])], RelevanceCases.user()) == -1;
    var noPos = RelevanceCases.winner(sels, [], null) == -1;
    return none && empty && past && noPos;
}
