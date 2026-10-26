import Toybox.Lang;

// Build-time configuration for the watch app. Everything tunable lives here.
module Config {
    // Must match Transport.PROTOCOL_VERSION in android/protocol (see protocol/PROTOCOL.md).
    const PROTOCOL_VERSION = 1;
    // How often the open app asks the phone for fresh arrivals. The phone's
    // REFRESH_SECONDS rate limit is authoritative; asking more often is harmless but wasteful.
    const WATCH_POLL_SECONDS = 40;
    // Selections the watch will keep (phone caps SelectionsSync to the same number).
    const MAX_SELECTIONS = 10;
    // Relevance: walking speed (m/s), buffer added to the walk (s), and the
    // distance beyond which a selection is deprioritised (m).
    const WALK_SPEED_MPS = 1.3;
    const WALK_BUFFER_SECONDS = 30;
    const MAX_RELEVANT_DISTANCE_M = 2000;
}
