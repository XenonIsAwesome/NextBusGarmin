import Toybox.Lang;

// Pure formatting helpers (unit tested in tests/FormatTest.mc).
(:glance)
module Format {
    // An arrival less than a minute away (or just gone) reads "now".
    function minutesText(t as Number, now as Number) as String {
        var d = t - now;
        if (d < 60) {
            return "now";
        }
        return (d / 60).toString();
    }

    function ageText(age as Number) as String {
        if (age < 0) {
            age = 0;
        }
        if (age < 60) {
            return age.toString() + "s";
        }
        if (age < 3600) {
            return (age / 60).toString() + "m";
        }
        return (age / 3600).toString() + "h";
    }

    // Up to `max` arrival times that have not left yet. A bus stays "now" for
    // GRACE seconds after its predicted time.
    const GRACE = 20;

    function upcoming(times as Array<Number>, now as Number, max as Number) as Array<Number> {
        var out = [] as Array<Number>;
        for (var i = 0; i < times.size() && out.size() < max; i++) {
            if (times[i] >= now - GRACE) {
                out.add(times[i]);
            }
        }
        return out;
    }

    function isNumeric(s as String) as Boolean {
        var n = s.toNumber();
        return n != null && n.toString().equals(s);
    }
}
