package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.Serializable

/**
 * What a quest asks the measurement session to do — IP-140's `features` block.
 *
 * Declared once, on the quest's `start` step, and mapped straight onto [SessionRequest]. It lives
 * here rather than beside the quest DTOs for two reasons that point the same way: `quests/domain`
 * is forbidden to import from a `.data.` package (`QuestsBoundaryTest`), and this really is a
 * statement about a *session* — it names the same four roles [SessionRequest] names, in the same
 * vocabulary, so putting it anywhere else would create a second vocabulary for one idea.
 *
 * **Every field defaults to false.** A quest that declares nothing therefore gets exactly the
 * session quests got before IP-140. That is deliberate and it is what keeps the block-bracketing
 * EXP-C1 quests correct without editing six live quests: their on-air interval must equal their
 * labelled block interval, and a session-scoped frame switched on by a new default would break that
 * silently, in captures that had already been taken.
 *
 * The cost of choosing that default is real and is owned rather than hidden: a probe quest that
 * forgets `broadcast` records dwells with nothing on air, and the symptom is a gap in the
 * trajectory rather than an error. `lab_quest_write` warns on exactly that combination, and
 * `lab quest-check` fails on it.
 */
@Serializable
data class QuestFeatures(
    /**
     * Keep the lab identity frame on air for the whole run, including between steps.
     *
     * This is the switch the fingerprinting quests exist for. A participant walking between two
     * surveyed points is producing the most valuable part of the record — a continuous trajectory
     * the fleet's per-node RSSI reconstructs — and a frame scoped to individual steps silences the
     * radio for exactly that interval.
     */
    val broadcast: Boolean = false,

    /**
     * Record the phone's own trajectory and room geometry (visual-inertial odometry, LiDAR mesh).
     *
     * Off for both new quests. Odometry is an interpolator between fixes, never a position source:
     * on the two real walks of 2026-08-19 it reported 92.3 m and 161.1 m of path for a handset that
     * barely moved, while the fleet's BLE reconstructed the same walks at a median 0.73 m/s with no
     * impossible hops. It also costs the camera and holds the screen awake.
     */
    val track: Boolean = false,

    /**
     * Monitor and range the iBeacon anchors — the zone-label channel.
     *
     * Inert until the bundle carries `beacons.zones`, which it does not: the anchors are not yet
     * reflashed to iBeacon or surveyed. A quest may ask; it cannot conjure the hardware.
     */
    val witness: Boolean = false,

    /**
     * Emit paced traffic from the handset — the illuminator role.
     *
     * Requires an access point to associate to, and this deployment has none. Monitor-mode
     * injection replaced the 2.4 GHz soft AP on 2026-08-11 (0.62 Hz delivered against 24.79 Hz),
     * and on 5 GHz an access point is impossible outright because Intel LAR blocks beaconing on
     * every iwlmvm client card. A quest that sets this must open with a `connect_to_ap` step, which
     * verifies a real round trip to the collector and refuses rather than completing silently.
     */
    val illuminator: Boolean = false,
) {
    companion object {
        val NONE = QuestFeatures()
    }
}
