package sk.martinvanco.monad.lab.domain

/**
 * The roles a device can play in a MonadCount experiment.
 *
 * The lab is described role-first, not device-first: an experiment is an assignment of roles to
 * devices plus a schedule. A phone is unusual in that it holds three roles simultaneously —
 * [ILLUMINATOR], [WITNESS] and [SUBJECT] — which is what no infrastructure-only lab can do.
 *
 * See the vault note "Wireless Laboratory Framework" for the contracts each role must satisfy.
 */
enum class LabRole {
    /**
     * Emit frames on a declared PHY at a **commanded pace**, and report the pace actually
     * delivered. A source whose delivered rate is not measured is not an illuminator.
     *
     * Phone implementation: [TrafficGenerator] over an associated Wi-Fi link.
     */
    ILLUMINATOR,

    /**
     * Capture CSI with a full provenance sidecar. Never played by a phone — no mobile OS exposes
     * CSI. Present here only so the role vocabulary is complete.
     */
    OBSERVER,

    /**
     * Advertise a stable, surveyed identity at a known rate from a known position.
     * Played by the ESP32-C6 iBeacon anchors, never by the phone.
     */
    ANCHOR,

    /**
     * Advertise a **moving, session-scoped** identity so the fleet's passive BLE scan can observe
     * this handset. Unlike [ANCHOR] the position is the unknown, not the datum — the fleet's
     * per-node RSSI on this frame is what localises it. Phone implementation:
     * [IdentityBroadcaster]; on iOS honest only in the foreground.
     */
    BROADCASTER,

    /**
     * Observe anchors and report `{anchor_id, rssi, t}` and zone transitions **without being in
     * the foreground**. Phone implementation: [BeaconWitness].
     */
    WITNESS,

    /** A body in the room. No contract; it is what is being measured. */
    SUBJECT,
}
