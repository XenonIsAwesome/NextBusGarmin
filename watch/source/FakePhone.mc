import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;

// Stand-in for the phone app while developing in the simulator
// (Config.FAKE_PHONE). It answers watch requests through the same decode/apply
// path as real messages. Press MENU (long press UP) to cycle failure scenarios.
class FakePhone {
    const SCENARIOS = ["normal", "no service on #2", "phone says STALE", "phone says ERROR",
        "no selections", "phone silent", "phone app newer", "phone app older"];

    var scenario as Number = 0;
    var started as Number;
    var timer as Timer.Timer = new Timer.Timer();
    var pendingSelections as Boolean = true;

    function initialize() {
        started = Time.now().value();
    }

    function nextScenario() as String {
        scenario = (scenario + 1) % SCENARIOS.size();
        pendingSelections = true;
        return SCENARIOS[scenario];
    }

    function name() as String {
        return SCENARIOS[scenario];
    }

    function handle(bytes as ByteArray) as Void {
        var msg = Proto.decodeEnvelope(bytes);
        if (msg == null || msg[0] != Proto.T_REFRESH || scenario == 5) {
            return;
        }
        timer.start(method(:deliver), 600, false);
    }

    function deliver() as Void {
        var v = Config.PROTOCOL_VERSION;
        if (scenario == 6) {
            v = v + 1;
        } else if (scenario == 7) {
            v = v - 1;
        }
        var sels = [
            [1, "29", "36601", "Beit Loewenstein/Ahuza", "HaShunit/Yorde Yam", 32184760, 34855867, 15],
            [13, "13", "36601", "Beit Loewenstein/Ahuza", "Sport Campus/Weizmann", 32184760, 34855867, 3],
            [3, "531", "36602", "Ahuza/Ben Gurion", "Weizmann/Rehovot", 32190100, 34860200, 5],
            [4, "74", "36602", "Ahuza/Ben Gurion", "Kfar Saba", 32190100, 34860200, 18],
            [5, "45", "36601", "Beit Loewenstein/Ahuza", "Rehovot", 32184760, 34855867, 14]
        ] as Array;
        if (scenario == 4) {
            sels = [] as Array;
        }
        Link.onDecoded([Proto.T_SELECTIONS, v, sels]);

        var now = Time.now().value();
        var status = scenario == 2 ? Proto.STATUS_STALE : (scenario == 3 ? Proto.STATUS_ERROR : Proto.STATUS_OK);
        var gen = scenario == 2 ? now - 150 : now;
        var items = [] as Array;
        for (var i = 0; i < sels.size(); i++) {
            var s = sels[i] as Array;
            var st = status;
            var times = nextTimes(i, now);
            if (scenario == 1 && i == 1) {
                st = Proto.STATUS_NO_SERVICE;
                times = [] as Array<Number>;
            } else if (scenario == 3) {
                times = [] as Array<Number>;
            }
            items.add([s[Store.S_ID], st, times]);
        }
        Link.onDecoded([Proto.T_ARRIVALS, v, gen, items]);
    }

    // Deterministic buses: line i comes every (7 + 6 * i) minutes, offset by 2 + 5 * i.
    function nextTimes(i as Number, now as Number) as Array<Number> {
        var period = (7 + 6 * i) * 60;
        var first = started + (2 + 5 * i) * 60;
        var k = (now - first) / period;
        if (now > first) {
            k = k + 1;
        } else {
            k = 0;
        }
        var out = [] as Array<Number>;
        for (var n = 0; n < 3; n++) {
            out.add(first + (k + n) * period);
        }
        return out;
    }
}
