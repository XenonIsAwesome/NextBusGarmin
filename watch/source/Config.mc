import Toybox.Lang;

// Build-time configuration for the watch app. Everything tunable lives here.
(:glance)
module Config {
    // Must match Transport.PROTOCOL_VERSION in android/protocol (see protocol/PROTOCOL.md).
    const PROTOCOL_VERSION = 1;
    // How often the open app asks the phone for fresh arrivals. The phone's
    // REFRESH_SECONDS rate limit is authoritative; asking more often is harmless but wasteful.
    const WATCH_POLL_SECONDS = 40;
    // Selections the watch will keep (phone caps SelectionsSync to the same number).
    const MAX_SELECTIONS = 10;
    // The app declares "phone app not responding" when a request has had no
    // reply for this long.
    const REPLY_TIMEOUT_SECONDS = 15;
    // Data older than this is shown as stale even if the phone said OK.
    const STALE_AFTER_SECONDS = 120;
    // Simulator / no-phone development: answer requests from FakePhone instead
    // of the Mobile SDK link. MUST be false for a real build.
    const FAKE_PHONE = true;
    // Show the fake scenario number ("[2] ...") in the footer; off for demos/videos.
    const FAKE_SCENARIO_TAG = false;
    const APP_VERSION = "0.1.0";
    // false = no glance entry: the simulator then starts the full app directly.
    const GLANCE_ENABLED = true;
    // Relevance: walking speed (m/s), buffer added to the walk (s), and the
    // distance beyond which a selection is deprioritised (m).
    const WALK_SPEED_MPS = 1.3;
    const WALK_BUFFER_SECONDS = 30;
    const MAX_RELEVANT_DISTANCE_M = 2000;
}
