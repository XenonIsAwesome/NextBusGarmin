import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

class NextBusGarminDelegate extends WatchUi.BehaviorDelegate {
    var _view as NextBusGarminView;

    function initialize(view as NextBusGarminView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    // Up/down buttons and vertical swipes both land here.
    function onNextPage() as Boolean {
        _view.movePage(1);
        return true;
    }

    function onPreviousPage() as Boolean {
        _view.movePage(-1);
        return true;
    }

    // Manual refresh. The phone decides whether curlbus is actually hit.
    function onSelect() as Boolean {
        Link.refresh(Proto.REASON_MANUAL);
        WatchUi.requestUpdate();
        return true;
    }

    // Development aid: cycle the fake phone's failure scenarios.
    function onMenu() as Boolean {
        if (Config.FAKE_PHONE && Link.fake != null) {
            System.println("fake phone scenario: " + Link.fake.nextScenario());
            Link.refresh(Proto.REASON_MANUAL);
            WatchUi.requestUpdate();
        }
        return true;
    }
}
