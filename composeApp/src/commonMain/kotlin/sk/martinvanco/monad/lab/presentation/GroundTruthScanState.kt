package sk.martinvanco.monad.lab.presentation

import sk.martinvanco.monad.lab.domain.ScanReceipt
import sk.martinvanco.monad.lab.domain.ZoneState

/**
 * State of the participant-facing check-in screen.
 *
 * The screen has exactly one job and it is worth stating plainly: make the act of scanning
 * unambiguous to the human doing it. A participant who is unsure whether their exit registered will
 * scan again, and a truth channel that cannot be trusted by the person generating it is not truth.
 *
 * Hence three things the state carries that the naive version did not. [receipt] names the
 * *resolved* direction and any zone that was left behind, not the direction the code asked for.
 * [zone] is always on screen, so "which zone am I in" never requires remembering. And [notice]
 * separates "that did not work" from "that already happened" — a double scan is a normal event in a
 * doorway and must not be shown as an error.
 */
data class GroundTruthScanState(
    val isScanning: Boolean = false,
    /** What the last accepted scan actually recorded. */
    val receipt: ScanReceipt? = null,
    /** Where this participant is now, by their own scans. Survives across scans. */
    val zone: ZoneState = ZoneState(),
    val error: String? = null,
    /** Non-fatal information: a duplicate scan, a session change, an implicit zone move. */
    val notice: String? = null,
    /** Scans still on this device waiting for a network. Zero is the resting state. */
    val pendingCount: Long = 0,
    val isBusy: Boolean = false,
    /** Result of the last manual "Send now", so pressing it says what actually succeeded. */
    val flushMessage: String? = null,
)

sealed interface GroundTruthScanEvent {
    data object StartScan : GroundTruthScanEvent
    data object StopScan : GroundTruthScanEvent
    data class Scanned(val raw: String) : GroundTruthScanEvent
    data object Flush : GroundTruthScanEvent
    data object Dismiss : GroundTruthScanEvent
}
