import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

class NextBusGarminApp extends Application.AppBase {
    var _timer as Timer.Timer? = null;
    var _ticks as Number = 0;
    var _view as NextBusGarminView? = null;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
        Store.load();
        if (!Config.FAKE_PHONE) {
            Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        }
        Link.start();
        _timer = new Timer.Timer();
        _timer.start(method(:onTick), 1000, true);
    }

    // The timer only runs while the app is open.
    function onStop(state as Dictionary?) as Void {
        if (_timer != null) {
            _timer.stop();
            _timer = null;
        }
        if (!Config.FAKE_PHONE) {
            Communications.registerForPhoneAppMessages(null);
        }
    }

    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        Link.onMessage(msg.data);
    }

    function onTick() as Void {
        _ticks++;
        if (_ticks % Config.WATCH_POLL_SECONDS == 0) {
            Link.refresh(Proto.REASON_TIMER);
        }
        if (_ticks % 5 == 0) {
            WatchUi.requestUpdate();
        }
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        _view = new NextBusGarminView();
        return [ _view, new NextBusGarminDelegate(_view) ];
    }
}

function getApp() as NextBusGarminApp {
    return Application.getApp() as NextBusGarminApp;
}
