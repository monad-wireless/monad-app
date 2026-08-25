package sk.martinvanco.monad.quests.domain

/**
 * Whether the handset is really on the lab network, and if not, the first reason why.
 *
 * Lives in `quests/domain` rather than beside the composable that renders it, for the reason the
 * package exists: this is a judgement about whether a measurement can happen, and it should be
 * readable — and wrong-able — without a Compose runtime in scope. It is also the only part of
 * `connect_to_ap` worth a test, and `commonTest` is pure by house rule.
 */
data class AssociationVerdict(val verified: Boolean, val reason: String)

/**
 * The `connect_to_ap` verification (IP-140).
 *
 * The step used to route to a plain instruction card, so it reported success whether or not the
 * handset had joined anything at all. This replaces that with four stages, and refusing at any one
 * of them is the point.
 *
 * Ordered from the most fundamental failure to the least, so the sentence a participant reads names
 * the **first** thing that is wrong rather than the last thing checked. Somebody told "the
 * collector has not answered" when the real problem is that no session is running has been given a
 * true statement and no way to act on it.
 *
 * Note which stage is last. A pinned socket is an *intention*: `open()` on a UDP socket succeeds
 * against a host that is not there, because UDP is connectionless. A returned four-timestamp clock
 * burst over that same socket is the only evidence in this list that a round trip happened, which
 * is why it is the gate rather than the association.
 */
fun verifyAssociation(
    sessionRunning: Boolean,
    illuminatorRequested: Boolean,
    commandedApId: String,
    joinedApId: String,
    joinedSsid: String,
    socketPinned: Boolean,
    boundInterface: String,
    clockSamples: Int,
): AssociationVerdict = when {
    !sessionRunning -> AssociationVerdict(
        false,
        "The measurement session is not running, so nothing has tried to join a network yet. " +
            "Retry the instrument from the warning banner, or tell the operator.",
    )

    !illuminatorRequested -> AssociationVerdict(
        false,
        "This quest asks the phone to join an access point, but the lab bundle carries none — " +
            "so the session did not attempt it. There is no access point on this deployment: " +
            "the fleet illuminates by monitor-mode injection and there is nothing to associate " +
            "to. Tell the operator; this quest cannot run as written.",
    )

    joinedSsid.isBlank() -> AssociationVerdict(
        false,
        "The session is running but no network was joined. Tell the operator.",
    )

    commandedApId.isNotBlank() && joinedApId.isNotBlank() && commandedApId != joinedApId ->
        AssociationVerdict(
            false,
            "This step asks for access point \"$commandedApId\" and the session joined " +
                "\"$joinedApId\". The quest and the bundle disagree about which network this run " +
                "belongs on, so the measurement would be filed under the wrong one.",
        )

    !socketPinned -> AssociationVerdict(
        false,
        "Joined $joinedSsid, but the socket is not pinned to that interface" +
            (if (boundInterface.isNotBlank()) " ($boundInterface)" else "") +
            ". Traffic could leave over mobile data instead, which would measure nothing.",
    )

    clockSamples <= 0 -> AssociationVerdict(
        false,
        "Joined $joinedSsid and pinned the socket, but nothing has answered from the collector " +
            "yet. Stay where you are for a few seconds. If this does not clear, the network is up " +
            "and the server is not.",
    )

    else -> AssociationVerdict(
        true,
        "On $joinedSsid, socket pinned, and the collector has answered $clockSamples time" +
            (if (clockSamples == 1) "" else "s") + ".",
    )
}
