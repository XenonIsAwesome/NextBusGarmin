import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

// Watch-side state. Selections and arrivals are kept in the decoder's compact
// positional form and mirrored to Application.Storage so the glance can read
// them without running the decoder.
//
//   selection [id, line, stopCode, stopName, destination, latE6, lonE6, operatorId]
//   arrival   [selectionId, status, [epoch, ...]]
module Store {
    const K_SELECTIONS = "sel";
    const K_ARRIVALS = "arr";
    const K_GENERATED = "gen";

    // Selection / arrival field indices.
    const S_ID = 0;
    const S_LINE = 1;
    const S_CODE = 2;
    const S_NAME = 3;
    const S_DEST = 4;
    const S_LAT = 5;
    const S_LON = 6;
    const S_OP = 7;
    const A_ID = 0;
    const A_STATUS = 1;
    const A_TIMES = 2;

    // 0 = compatible, 1 = phone app is older than the watch, 2 = phone app is newer.
    const MISMATCH_NONE = 0;
    const MISMATCH_PHONE_OLD = 1;
    const MISMATCH_PHONE_NEW = 2;

    var selections as Array = [];
    var arrivals as Array = [];
    var generatedAt as Number = 0;
    var mismatch as Number = MISMATCH_NONE;

    function load() as Void {
        var s = Application.Storage.getValue(K_SELECTIONS);
        var a = Application.Storage.getValue(K_ARRIVALS);
        var g = Application.Storage.getValue(K_GENERATED);
        selections = (s instanceof Array) ? s : [];
        arrivals = (a instanceof Array) ? a : [];
        generatedAt = (g instanceof Number) ? g : 0;
    }

    function arrivalFor(selectionId as Number) as Array? {
        for (var i = 0; i < arrivals.size(); i++) {
            var a = arrivals[i] as Array;
            if (a[A_ID] == selectionId) {
                return a;
            }
        }
        return null;
    }

    // Applies a decoded phone message. Messages from an incompatible protocol
    // version are not applied; they flip the mismatch state instead.
    function apply(msg as Array) as Void {
        var type = msg[0] as Number;
        var version = msg[1] as Number;
        if (version != Config.PROTOCOL_VERSION) {
            mismatch = version > Config.PROTOCOL_VERSION ? MISMATCH_PHONE_NEW : MISMATCH_PHONE_OLD;
            return;
        }
        mismatch = MISMATCH_NONE;
        if (type == Proto.T_SELECTIONS) {
            var list = msg[2] as Array;
            selections = list.size() > Config.MAX_SELECTIONS ? list.slice(0, Config.MAX_SELECTIONS) : list;
            // Drop arrivals of selections that are gone.
            var kept = [] as Array;
            for (var i = 0; i < arrivals.size(); i++) {
                if (hasSelection((arrivals[i] as Array)[A_ID] as Number)) {
                    kept.add(arrivals[i]);
                }
            }
            arrivals = kept;
            save();
        } else if (type == Proto.T_ARRIVALS) {
            arrivals = msg[3] as Array;
            generatedAt = msg[2] as Number;
            save();
        }
    }

    function hasSelection(id as Number) as Boolean {
        for (var i = 0; i < selections.size(); i++) {
            if ((selections[i] as Array)[S_ID] == id) {
                return true;
            }
        }
        return false;
    }

    function save() as Void {
        Application.Storage.setValue(K_SELECTIONS, selections);
        Application.Storage.setValue(K_ARRIVALS, arrivals);
        Application.Storage.setValue(K_GENERATED, generatedAt);
    }
}
