package sk.martinvanco.monad.quests.domain

import io.github.aakira.napier.Napier
import kotlinx.datetime.Instant
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.domain.HandsetIdentity
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.QuestFeatures
import sk.martinvanco.monad.lab.domain.SessionRequest
import sk.martinvanco.monad.quests.domain.port.LabBundleSource
import sk.martinvanco.monad.quests.domain.port.LabSessionArchive
import sk.martinvanco.monad.quests.domain.port.ParticipantDirectory
import sk.martinvanco.monad.quests.domain.port.QuestCompletion
import sk.martinvanco.monad.quests.domain.port.QuestCompletionGateway
import sk.martinvanco.monad.quests.domain.port.QuestSkip
import sk.martinvanco.monad.quests.domain.port.QuestStepJournal
import sk.martinvanco.monad.quests.domain.port.QuestStepOutcome

/**
 * The one place a quest becomes a lab session, and the one place a finished quest is uploaded.
 *
 * Previously this logic existed three times — in the active-quest model, the completed screen, and
 * the abandoned screen — each re-deriving the same export → upload → *flush anyway* sequence. Two
 * consequences followed from the duplication: the three copies drifted (only one of them recorded a
 * skip record), and all three deleted local data on paths where the upload had failed, discarding
 * the only copy of a session.
 *
 * This class enforces the replacement rule: **submit, upload, and only then purge.** A failure at
 * any stage leaves every byte on disk and the session visible as unsynced in the lab console.
 *
 * It names ports rather than repositories and services (see `quests/domain/port`). The rule above
 * is a judgement about data that cannot be re-collected; it should be readable — and wrong-able —
 * without a database, an HTTP client and three other features' data layers in scope. The only
 * cross-feature types it still names are `lab.domain` ones (`LabInstrument`, `LabConfig`,
 * `SessionRequest`), which are domain types on both sides of the boundary.
 */
class QuestSessionCoordinator(
    private val instrument: LabInstrument,
    private val labBundle: LabBundleSource,
    private val archive: LabSessionArchive,
    private val participants: ParticipantDirectory,
    private val stepJournal: QuestStepJournal,
    private val completions: QuestCompletionGateway,
    private val handsets: HandsetIdentity,
) {

    /**
     * Make sure a lab bundle is loaded before the roles are decided.
     *
     * The bundle used to be fetched only by the lab console, so a participant who never opened it
     * started every session against [LabConfig.EMPTY]: no beacon plan, no traffic profile, and
     * therefore neither the witness nor the illuminator role. The session still recorded — it just
     * recorded nothing worth having, silently, which is the failure mode this whole subsystem is
     * built to avoid.
     *
     * Cache first, then a best-effort network refresh: a phone about to join an experiment AP may
     * already have no route to the internet, and a stale bundle beats no bundle. A refresh failure
     * is logged by the adapter and deliberately not fatal.
     */
    private suspend fun ensureLabConfig(): LabConfig {
        if (!labBundle.isLoaded) {
            labBundle.loadCached()
        }
        if (!labBundle.current.isIlluminationReady) {
            labBundle.refresh(participants.current()?.token)
        }
        return labBundle.current
    }

    /**
     * Start instrumenting a quest.
     *
     * What the session does is **declared by the quest** (IP-140), in a `features` block on its
     * `start` step, rather than inferred here. Before that block existed this method decided every
     * role from the bundle alone, which meant a fingerprinting quest and a block-bracketing quest
     * got identical sessions and neither could ask for what it needed.
     *
     * Every feature defaults to off, so a quest that declares nothing gets exactly the session
     * quests got before this change. That is what keeps the block-bracketing EXP-C1 quests correct
     * without editing them: their on-air interval must equal their labelled block interval, and a
     * session-scoped frame would break that silently.
     *
     * Two features are still gated by physical reality rather than by the declaration alone:
     * `illuminator` needs the bundle to carry a traffic profile and an access point, and `witness`
     * needs a beacon plan. A quest may ask for either; it cannot conjure the hardware. When a quest
     * asks and the bundle cannot supply, its `connect_to_ap` step is what says so out loud.
     */
    suspend fun startSession(
        questId: String,
        enrollmentId: String,
        apId: String? = null,
        profileId: String? = null,
        features: QuestFeatures = QuestFeatures.NONE,
    ): Result<String> {
        val config = ensureLabConfig()
        val participant = participants.current()
            ?: return Result.failure(IllegalStateException("not logged in"))

        val profile = profileId?.let { config.trafficProfile(it) }
        val accessPoint = apId?.let { config.accessPoint(it) }
            ?: profile?.apId?.let { config.accessPoint(it) }
        val emit = features.illuminator && profile != null && config.isIlluminationReady

        return instrument.start(
            SessionRequest(
                // The dataset carries the pseudonym, never the account e-mail: the game owns the
                // account, the measurement owns only the participant key.
                participantId = participant.participantId,
                collector = config.collector,
                beacons = config.beacons,
                accessPoint = accessPoint,
                trafficProfile = profile,
                clockSync = config.clockSync,
                site = config.site,
                configVersion = config.version,
                enrollmentId = enrollmentId,
                questId = questId,
                // IP-149 — into the sidecar. Null when the probe failed; a run is never refused
                // over its own description.
                handset = handsets.describe(),
                emit = emit,
                witness = features.witness && config.beacons.isConfigured,
                // Session-scoped broadcasting: on for the whole run when the quest asks for it, so
                // the walk BETWEEN two probes is on air too — that interval is the continuous
                // trajectory the fleet's per-node RSSI reconstructs, and it is the most valuable
                // part of a fingerprinting run. Off by default, in which case a quest still turns
                // the frame on and off through its own ble_advertise steps and the on-air interval
                // is exactly the labelled one.
                broadcast = features.broadcast,
                advertise = config.advertise,
                track = features.track,
            )
        )
    }

    /**
     * Close the instrument, tell the backend the quest is over, upload the artefacts, and purge
     * only what the server acknowledged.
     *
     * [skip] is non-null when the participant abandoned or a step failed — the same path handles
     * both outcomes, because from the data's point of view an abandoned session is a session with
     * a different status, not a session to be thrown away.
     */
    suspend fun finishSession(
        questId: String,
        enrollmentId: String,
        startedWallMillis: Long,
        completed: Boolean,
        skip: QuestSkip? = null,
    ): FinishOutcome {
        val sessionId = instrument.stop().getOrNull()

        val token = participants.current()?.token
        val endedWallMillis = currentTimeMillis()
        val startIso = Instant.fromEpochMilliseconds(startedWallMillis).toString()
        val endIso = Instant.fromEpochMilliseconds(endedWallMillis).toString()

        var submitted = false
        var submitError: String? = null
        if (token != null) {
            runCatching {
                val steps = stepJournal.stepsFor(enrollmentId)
                val stepOutcomes = steps.map { step ->
                    // The backend accepts completed / failed / skipped only. A step the
                    // participant never reached is *skipped*; one they were standing on when the
                    // quest ended is *failed* — the distinction matters because only the second
                    // says something went wrong.
                    val mappedStatus = when (step.status) {
                        "completed" -> "completed"
                        "failed" -> "failed"
                        "skipped" -> "skipped"
                        "pending" -> "skipped"
                        "in_progress" -> "failed"
                        else -> "skipped"
                    }
                    val skipRecord = when (step.status) {
                        "failed", "skipped" -> skip ?: QuestSkip(
                            message = step.skipMessage ?: "Unknown reason",
                            errorCode = step.skipErrorCode,
                        )

                        "pending" -> QuestSkip(
                            message = "Quest ended before this step was reached",
                            errorCode = QUEST_ENDED_EARLY,
                        )

                        "in_progress" -> QuestSkip(
                            message = "Quest ended while this step was in progress",
                            errorCode = QUEST_ENDED_EARLY,
                        )

                        else -> null
                    }
                    QuestStepOutcome(
                        stepCompletionId = step.stepCompletionId,
                        status = mappedStatus,
                        startedAtIso = step.startedAtMillis
                            ?.let { Instant.fromEpochMilliseconds(it).toString() } ?: startIso,
                        completedAtIso = step.completedAtMillis
                            ?.let { Instant.fromEpochMilliseconds(it).toString() } ?: endIso,
                        stepDataJson = step.stepDataJson,
                        skip = skipRecord,
                    )
                }
                completions.submitCompletion(
                    questId = questId,
                    completion = QuestCompletion(
                        enrollmentId = enrollmentId,
                        completedAtIso = endIso,
                        steps = stepOutcomes,
                    ),
                    token = token,
                )
            }.onSuccess { submitted = true }
                .onFailure {
                    submitError = it.message
                    Napier.w("[quest] completion submit failed: ${it.message}")
                }
        } else {
            submitError = "not authenticated"
        }

        // Upload every unsynced session, not just this one: a phone that spent a day offline in a
        // pocket is the normal case, and the moment it has connectivity is the moment to drain the
        // backlog.
        val uploaded = archive.uploadPending()

        // Local step rows are only dropped once the completion actually reached the server.
        if (submitted) {
            stepJournal.clear(enrollmentId)
            participants.current()?.let { participants.clearActiveQuest(it.userId) }
        }

        val unsynced = archive.unsyncedCount()
        return FinishOutcome(
            sessionId = sessionId,
            completionSubmitted = submitted,
            completionError = submitError,
            sessionsUploaded = uploaded,
            sessionsUnsynced = unsynced,
            completed = completed,
        )
    }

    /** Drain the upload backlog without touching quest state — used by the lab console. */
    suspend fun retryUploads(): Int = archive.uploadPending()

    suspend fun unsyncedSessions(): Long = archive.unsyncedCount()

    /**
     * Abandon a run whose local state is unusable (corrupt enrolment, missing steps).
     *
     * Distinct from [finishSession]: nothing is submitted, but the lab session is still closed and
     * its data still queued for upload. A broken quest does not make the radio measurement
     * worthless, and the old flush-everything path threw it away.
     */
    suspend fun abandonSession(enrollmentId: String?) {
        instrument.stop()
        enrollmentId?.let { stepJournal.clear(it) }
        participants.current()?.let { participants.clearActiveQuest(it.userId) }
        runCatching { archive.uploadPending() }
    }

    private companion object {
        const val QUEST_ENDED_EARLY = "QUEST_ENDED_EARLY"
    }
}

data class FinishOutcome(
    val sessionId: String?,
    val completionSubmitted: Boolean,
    val completionError: String?,
    val sessionsUploaded: Int,
    val sessionsUnsynced: Long,
    val completed: Boolean,
) {
    /** True when nothing is left on the device waiting for a network. */
    val isFullySynced: Boolean get() = completionSubmitted && sessionsUnsynced == 0L
}
