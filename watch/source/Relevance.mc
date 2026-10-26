import Toybox.Lang;
import Toybox.Math;

// "Most relevant selection": pure, shared by the glance and the full app's default page.
//
// For a selection at distance d from the user, walk = d / speed + buffer. Its candidate
// arrival is the first arrival with (time - now) >= walk. The winner is the selection with
// the earliest candidate, in three tiers:
//   1. stops within maxDistance that have a catchable arrival,
//   2. stops farther than maxDistance with a catchable arrival (deprioritised),
//   3. nothing is catchable (or no location fix): the soonest upcoming arrival overall.
// Returns [selectionIndex, arrivalTime], or null if no selection has an upcoming arrival.
(:glance)
module Relevance {
    // Metres between two points given in microdegrees (equirectangular approximation,
    // accurate to well under 1% at city scale).
    function distanceM(latE6a as Number, lonE6a as Number, latE6b as Number, lonE6b as Number) as Float {
        var dLat = (latE6b - latE6a) * 0.000001 * 111320.0;
        var midLat = (latE6a + latE6b) * 0.0000005 * 0.017453292519943295;
        var dLon = (lonE6b - lonE6a) * 0.000001 * 111320.0 * Math.cos(midLat);
        return Math.sqrt(dLat * dLat + dLon * dLon).toFloat();
    }

    function arrivalTimes(arrivals as Array, selectionId as Number) as Array? {
        for (var i = 0; i < arrivals.size(); i++) {
            var a = arrivals[i] as Array;
            if (a[0] == selectionId) {
                return a[2] as Array;
            }
        }
        return null;
    }

    // pos is [latE6, lonE6] or null when there is no location fix.
    function pickWith(selections as Array, arrivals as Array, pos as Array?, now as Number,
                      walkSpeed as Float, walkBuffer as Number, maxDistance as Number) as Array? {
        var near = null as Array?;
        var far = null as Array?;
        var soonest = null as Array?;
        for (var i = 0; i < selections.size(); i++) {
            var sel = selections[i] as Array;
            var times = arrivalTimes(arrivals, sel[0] as Number);
            if (times == null) {
                continue;
            }
            for (var k = 0; k < times.size(); k++) {
                if ((times[k] as Number) >= now) {
                    if (soonest == null || times[k] < soonest[1]) {
                        soonest = [i, times[k]];
                    }
                    break;
                }
            }
            if (pos == null) {
                continue;
            }
            // A stop without coordinates counts as right here.
            var dist = 0.0;
            if ((sel[5] as Number) != 0 || (sel[6] as Number) != 0) {
                dist = distanceM(pos[0] as Number, pos[1] as Number, sel[5] as Number, sel[6] as Number);
            }
            var walk = dist / walkSpeed + walkBuffer;
            for (var k = 0; k < times.size(); k++) {
                if ((times[k] as Number) - now >= walk) {
                    var cand = [i, times[k]];
                    if (dist <= maxDistance) {
                        if (near == null || cand[1] < near[1]) {
                            near = cand;
                        }
                    } else if (far == null || cand[1] < far[1]) {
                        far = cand;
                    }
                    break;
                }
            }
        }
        if (pos == null) {
            return soonest;
        }
        if (near != null) {
            return near;
        }
        return far != null ? far : soonest;
    }

    function pick(selections as Array, arrivals as Array, pos as Array?, now as Number) as Array? {
        return pickWith(selections, arrivals, pos, now, Config.WALK_SPEED_MPS,
            Config.WALK_BUFFER_SECONDS, Config.MAX_RELEVANT_DISTANCE_M);
    }
}
