package dev.dubec.monad.lab.presentation

import dev.dubec.monad.core.domain.permissions.PermissionStatus
import dev.dubec.monad.lab.data.RecoveredSession
import dev.dubec.monad.lab.domain.LabInstrumentState
import dev.dubec.monad.lab.domain.SessionReport
import dev.dubec.monad.lab.domain.ZoneState
import dev.dubec.monad.lab.domain.health.InstrumentHealth
import dev.dubec.monad.lab.domain.health.StreamState
import dev.dubec.monad.lab.domain.upload.PendingInventory

/**
 * The participant's answer to "is this thing working?".
 *
 * A session runs for hours with the app backgrounded. The one interaction that matters is a person
 * pulling the phone out of a pocket and needing, in under two seconds and without interpretation:
 * *yes you are recording · you are in ZONE-B · last event 4 s ago*. Everything on this screen exists
 * to make that sentence true and legible; anything that would need a second glance belongs in the
 * lab console instead.
 */
data class SessionStatusState(
    val instrument: LabInstrumentState = LabInstrumentState.IDLE,
    val health: InstrumentHealth = InstrumentHealth.IDLE,
    val zone: ZoneState = ZoneState(),
    val pending: PendingInventory = PendingInventory.EMPTY,
    val permissions: List<PermissionStatus> = emptyList(),
    /** Summary of the most recently closed session — the screenshot. */
    val lastSession: SessionReport? = null,
    /** Sessions rescued from a crash or reboot on this launch. */
    val recovered: List<RecoveredSession> = emptyList(),
    val flushMessage: String? = null,
    val isBusy: Boolean = false,
) {
    val isRecording: Boolean get() = instrument.isRunning

    /**
     * The headline, and the only string on the screen a participant has to read.
     *
     * "Recording" alone would be a lie whenever a stream has quietly died, which is the exact
     * failure this whole pass exists to surface — so a session with a dead stream says so in the
     * headline rather than in a detail row nobody scrolls to.
     */
    val headline: String
        get() = when {
            !isRecording && pending.isEmpty -> "Not recording"
            !isRecording -> "Not recording — data still on this phone"
            health.overall == StreamState.DEAD -> "Recording, but something has stopped"
            health.overall == StreamState.STALE || health.overall == StreamState.DEGRADED ->
                "Recording — one stream is struggling"

            else -> "Recording"
        }

    val isNominal: Boolean get() = isRecording && health.overall.isHealthy

    val permissionsNeedingAttention: List<PermissionStatus>
        get() = permissions.filter { it.needsAttention }
}

sealed interface SessionStatusEvent {
    data object Refresh : SessionStatusEvent
    data object Flush : SessionStatusEvent
    data class PermissionChanged(val statuses: List<PermissionStatus>) : SessionStatusEvent
    data object Dismiss : SessionStatusEvent
}
