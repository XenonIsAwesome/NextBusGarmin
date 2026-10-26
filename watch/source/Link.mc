import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

// Phone link: sends Hello / RefreshRequest, receives and applies phone messages.
// The phone owns rate limiting; asking more often than it allows is harmless.
module Link {
    const H_OK = 0;
    const H_DISCONNECTED = 1;
    const H_NO_REPLY = 2;

    var lastRequestAt as Number = 0;
    var lastReplyAt as Number = 0;
    var sendFailed as Boolean = false;
    var fake as FakePhone? = null;

    var queue as Array = [];
    var sending as Boolean = false;

    function now() as Number {
        return Time.now().value();
    }

    // App opened: Hello, then an APP_OPENED refresh.
    function start() as Void {
        if (Config.FAKE_PHONE && fake == null) {
            fake = new FakePhone();
        }
        enqueue(Proto.encodeHello(Config.APP_VERSION));
        refresh(Proto.REASON_APP_OPENED);
    }

    function refresh(reason as Number) as Void {
        lastRequestAt = now();
        enqueue(Proto.encodeRefresh([] as Array<Number>, reason));
    }

    // At most two messages wait; a timer refresh behind a stuck send is dropped.
    function enqueue(bytes as ByteArray) as Void {
        if (Config.FAKE_PHONE) {
            (fake as FakePhone).handle(bytes);
            return;
        }
        if (queue.size() < 2) {
            queue.add(bytes);
        }
        pump();
    }

    function pump() as Void {
        if (sending || queue.size() == 0) {
            return;
        }
        var bytes = queue[0] as ByteArray;
        queue = queue.slice(1, null);
        sending = true;
        Communications.transmit(Proto.pack(bytes), null, new SendListener());
    }

    function sendDone(ok as Boolean) as Void {
        sending = false;
        sendFailed = !ok;
        if (!ok) {
            queue = [];
            WatchUi.requestUpdate();
        } else {
            pump();
        }
    }

    // Entry for everything the phone sends (real link or FakePhone).
    function onMessage(data) as Void {
        if (!(data instanceof Array)) {
            return;
        }
        var msg = Proto.decodeMessage(data as Array);
        if (msg != null) {
            onDecoded(msg);
        }
    }

    function onDecoded(msg as Array) as Void {
        lastReplyAt = now();
        sendFailed = false;
        Store.apply(msg);
        WatchUi.requestUpdate();
    }

    function health() as Number {
        if (!System.getDeviceSettings().phoneConnected && !Config.FAKE_PHONE) {
            return H_DISCONNECTED;
        }
        if (sendFailed) {
            return H_NO_REPLY;
        }
        if (lastRequestAt > 0 && lastReplyAt < lastRequestAt && now() - lastRequestAt > Config.REPLY_TIMEOUT_SECONDS) {
            return H_NO_REPLY;
        }
        return H_OK;
    }
}

class SendListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
        Link.sendDone(true);
    }

    function onError() as Void {
        Link.sendDone(false);
    }
}
