package sk.martinvanco.monad.quests.domain.port

import sk.martinvanco.monad.lab.domain.LabConfig

/**
 * What a quest run needs of the world outside `quests/domain`, and nothing more.
 *
 * `QuestSessionCoordinator` owns the one rule that matters on this path — **submit, upload, and
 * only then purge** — and it used to own it while holding a `UserRepository`, a `QuestsService`, a
 * `LabConfigService`, a `LabSessionRepository`, a `LabSessionUploader` and a
 * `QuestStepCompletionRepository`: six concrete collaborators drawn from four features' data
 * layers. Two things followed. The rule could not be read without a database, an HTTP client and a
 * Ktor engine in scope; and the coordinator could reach `forceDelete`, `deleteAll` and
 * `purgeUploaded` — methods whose whole point is that they destroy the only copy of a recording.
 *
 * These ports name what the policy needs. Each is deliberately narrower than the object behind it:
 * nothing here can delete a session, clear the user table, or write a step row.
 *
 * Ports rather than a re-export of the data types, because the data types are the wire and the
 * database: `SkipRecordDto` is JSON the backend defined, `QuestStepCompletion` is a SQLDelight row,
 * `User` is a SQLDelight row. A policy expressed in those cannot be read, changed or tested without
 * the transport it happens to travel on today.
 *
 * The adapters live in `quests/data/adapter` — not on the services themselves, which belong to
 * other features and must not learn about this one.
 */

/**
 * The lab configuration bundle.
 *
 * The *policy* of when to load and when to refresh stays in the coordinator (cache first, network
 * best-effort, a stale bundle beats no bundle) because it is a judgement about a phone that is
 * about to lose its route to the internet. This port only exposes the facts that judgement needs.
 */
interface LabBundleSource {

    /** True once any bundle has been loaded — cached, fetched or set by hand. */
    val isLoaded: Boolean

    /** The bundle as it currently stands; [LabConfig.EMPTY] when nothing has been loaded. */
    val current: LabConfig

    /** Load the bundle cached on this device. Never touches the network. */
    suspend fun loadCached()

    /** Fetch from the backend. The bundle is authenticated, hence the token. Failure is not fatal. */
    suspend fun refresh(token: String?)
}

/**
 * The device's local session backlog.
 *
 * Two methods, both safe: drain and count. Purging is the uploader's rule and is applied by the
 * adapter, so this path cannot delete a session that the server has not acknowledged even by
 * mistake — the failure that cost the original three copies of this logic their data.
 */
interface LabSessionArchive {

    /** Upload everything unsynced and purge only what the server acknowledged. Returns sessions sent. */
    suspend fun uploadPending(): Int

    /** Sessions still waiting on this device. */
    suspend fun unsyncedCount(): Long
}

/** The signed-in participant, as the measurement path sees them. */
data class QuestParticipant(
    /** Local row id, used only to clear the active quest. */
    val userId: Long,
    /**
     * The pseudonym the dataset carries. Never the account e-mail: the game owns the account, the
     * measurement owns only the key.
     */
    val participantId: String,
    val token: String?,
)

/** Who is running this quest. */
interface ParticipantDirectory {
    suspend fun current(): QuestParticipant?

    /** Forget the active quest / enrolment for this participant. */
    suspend fun clearActiveQuest(userId: Long)
}

/** Why a step did not complete. */
data class QuestSkip(
    val message: String,
    val errorCode: String? = null,
)

/** One step's outcome, as the backend is told about it. */
data class QuestStepOutcome(
    val stepCompletionId: String,
    /** `completed`, `failed` or `skipped` — the only three the backend accepts. */
    val status: String,
    val startedAtIso: String,
    val completedAtIso: String,
    /** The step's own recorded data as raw JSON, or null. Parsed by the adapter, not the policy. */
    val stepDataJson: String?,
    val skip: QuestSkip?,
)

/** The whole completion, ready to submit. */
data class QuestCompletion(
    val enrollmentId: String,
    val completedAtIso: String,
    val steps: List<QuestStepOutcome>,
)

/**
 * Telling the backend a quest is over.
 *
 * Throws on failure, which the coordinator catches: a completion that did not reach the server must
 * leave every local byte in place, and that decision belongs to the policy rather than to the
 * transport.
 */
interface QuestCompletionGateway {
    suspend fun submitCompletion(questId: String, completion: QuestCompletion, token: String)
}

/** One locally recorded step, as the coordinator needs to read it. */
data class QuestStepRecord(
    val stepCompletionId: String,
    /** Local status vocabulary: `pending`, `in_progress`, `completed`, `failed`, `skipped`. */
    val status: String,
    val startedAtMillis: Long?,
    val completedAtMillis: Long?,
    val stepDataJson: String?,
    val skipMessage: String?,
    val skipErrorCode: String?,
)

/**
 * The device's own record of what the participant did.
 *
 * Read and clear only. Writing step rows is the active-quest screen's job, and the coordinator
 * clearing them is conditional on the completion having actually reached the server.
 */
interface QuestStepJournal {
    suspend fun stepsFor(enrollmentId: String): List<QuestStepRecord>

    suspend fun clear(enrollmentId: String)
}
