package app.nextbus.protocol

/** One golden vector: the message, plus its expected decode in the watch's compact form. */
class Vector(val name: String, val envelope: Envelope, val expected: String)

private fun selection(id: Int, line: String, code: String, stop: String, dest: String, lat: Int, lon: Int) =
    Selection.newBuilder().setId(id).setLine(line).setStopCode(code).setStopName(stop)
        .setDestination(dest).setLatE6(lat).setLonE6(lon).build()

private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun selJson(s: Selection) =
    "[${s.id},${q(s.line)},${q(s.stopCode)},${q(s.stopName)},${q(s.destination)},${s.latE6},${s.lonE6}]"

private fun arrivals(id: Int, status: Status, vararg t: Int) =
    SelectionArrivals.newBuilder().setSelectionId(id).setStatus(status).addAllArrivalTimes(t.toList()).build()

private fun arrJson(a: SelectionArrivals) =
    "[${a.selectionId},${a.status.number},[${a.arrivalTimesList.joinToString(",")}]]"

private val TEN = (1..10).map {
    selection(it, "${it * 11}", "3660$it", "Beit Loewenstein/Ahuza", "Sport Campus/Weizmann", 32184760 + it, 34855867 - it)
}

private fun syncVector(name: String, list: List<Selection>): Vector {
    val msg = SelectionsSync.newBuilder().setProtocolVersion(Transport.PROTOCOL_VERSION).addAllSelections(list).build()
    return Vector(
        name, Envelope.newBuilder().setSelectionsSync(msg).build(),
        "[2,${msg.protocolVersion},[${list.joinToString(",") { selJson(it) }}]]",
    )
}

private fun updateVector(name: String, at: Int, items: List<SelectionArrivals>): Vector {
    val msg = ArrivalsUpdate.newBuilder().setProtocolVersion(Transport.PROTOCOL_VERSION)
        .setGeneratedAt(at).addAllItems(items).build()
    return Vector(
        name, Envelope.newBuilder().setArrivalsUpdate(msg).build(),
        "[3,${msg.protocolVersion},$at,[${items.joinToString(",") { arrJson(it) }}]]",
    )
}

private fun refreshVector(name: String, ids: List<Int>, reason: Reason): Vector {
    val msg = RefreshRequest.newBuilder().setProtocolVersion(Transport.PROTOCOL_VERSION)
        .addAllSelectionIds(ids).setReason(reason).build()
    return Vector(
        name, Envelope.newBuilder().setRefreshRequest(msg).build(),
        "[4,${msg.protocolVersion},[${ids.joinToString(",")}],${reason.number}]",
    )
}

private const val NOW = 1_792_000_000

val VECTORS: List<Vector> = listOf(
    Vector(
        "hello_phone",
        Envelope.newBuilder().setHello(
            Hello.newBuilder().setProtocolVersion(Transport.PROTOCOL_VERSION).setRole(Role.PHONE).setAppVersion("1.0.0"),
        ).build(),
        "[1,1,1,\"1.0.0\"]",
    ),
    Vector(
        "hello_watch_future_version",
        Envelope.newBuilder().setHello(
            Hello.newBuilder().setProtocolVersion(300).setRole(Role.WATCH).setAppVersion("2.10.3"),
        ).build(),
        "[1,300,2,\"2.10.3\"]",
    ),
    // Negative coordinates exercise the zigzag (sint32) path.
    syncVector("selections_negative_coords", listOf(selection(1, "5", "100", "Opera House", "Circular Quay", -33_856_800, -151_215_300))),
    syncVector("selections_2", TEN.take(2)),
    syncVector("selections_10", TEN),
    syncVector("selections_empty", emptyList()),
    updateVector(
        "arrivals_mixed", NOW,
        listOf(
            arrivals(1, Status.OK, NOW + 60, NOW + 600, NOW + 1800),
            arrivals(2, Status.NO_SERVICE),
            arrivals(3, Status.STALE, NOW - 30),
            arrivals(4, Status.ERROR),
        ),
    ),
    updateVector("arrivals_10", NOW, (1..10).map { arrivals(it, Status.OK, NOW + 60 * it, NOW + 600 * it, NOW + 1200 * it) }),
    refreshVector("refresh_all_app_opened", emptyList(), Reason.APP_OPENED),
    refreshVector("refresh_some_timer", listOf(1, 3, 200), Reason.TIMER),
)
