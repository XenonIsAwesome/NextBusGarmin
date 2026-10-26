import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Position;
import Toybox.Timer;
import Toybox.WatchUi;

// The same app object serves the glance and the full app. Everything that talks to the
// phone or runs timers starts in getInitialView(), which the system only calls for the
// full app, so the glance stays within its small memory budget.
(:glance)
class NextBusGarminApp extends Application.AppBase {
    var _timer as Timer.Timer? = null;
    var _ticks as Number = 0;
    var _view as NextBusGarminView? = null;
    var _fullApp as Boolean = false;

    function initialize() {
        AppBase.initialize();
    }

    function getGlanceView() as [WatchUi.GlanceView] or [WatchUi.GlanceView, WatchUi.GlanceViewDelegate] or Null {
        return Config.GLANCE_ENABLED ? [ new NextBusGlanceView() ] : null;
    }

    // The timer only runs while the full app is open.
    function onStop(state as Dictionary?) as Void {
        if (!_fullApp) {
            return;
        }
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

    // One-shot fix on open (the relevance function otherwise uses the last known position).
    function onPosition(info as Position.Info) as Void {
        Pos.store(info);
        if (_view != null) {
            _view.invalidateChoice();
        }
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        _fullApp = true;
        Store.load();
        if (!Config.FAKE_PHONE) {
            Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        }
        Position.enableLocationEvents(Position.LOCATION_ONE_SHOT, method(:onPosition));
        Link.start();
        _timer = new Timer.Timer();
        _timer.start(method(:onTick), 1000, true);
        _view = new NextBusGarminView();
        return [ _view, new NextBusGarminDelegate(_view) ];
    }
}

function getApp() as NextBusGarminApp {
    return Application.getApp() as NextBusGarminApp;
}
