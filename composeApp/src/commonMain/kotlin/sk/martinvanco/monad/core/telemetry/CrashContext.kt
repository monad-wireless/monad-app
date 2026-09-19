package sk.martinvanco.monad.core.telemetry

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics
import io.github.aakira.napier.Napier
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.util.Platform

/**
 * Crash reporting, and the keys that let a crash be found again in LGTM.
 *
 * ## Why this exists
 *
 * Crashlytics reported nothing. `App()` called `setCrashlyticsCollectionEnabled(false)` at every
 * process start, and the onboarding terms step called it with `true` exactly once in the life of an
 * install, so the sequence was: first launch disables, onboarding enables, second launch disables,
 * and every crash after that went nowhere. The absence looked like a stable app.
 *
 * Collection is now ON by default and not gated on anything. A crash report is diagnostic data
 * about the instrument, this is a research build handed to volunteers who were told what it
 * records, and a handset that crashes silently is a measurement we cannot explain. The participant
 * can still turn it off, and that opt-out is the only thing that stops it.
 *
 * ## The join to LGTM
 *
 * Crashlytics and the LGTM stack see different halves of the same handset, and neither is worth
 * much without the other: Crashlytics has the stack and no idea what the instrument was doing,
 * Mimir has the instrument health and no idea why it stopped. So every crash carries the labels
 * Alloy's `handset_labels` transform keeps — `site`, `platform`, `participant` — plus `build_id`,
 * which is the string the session sidecar already records. Given a Crashlytics crash you can query
 * Mimir for the same participant at the same minute, and given a gap in Mimir you can ask
 * Crashlytics what ended it.
 *
 * `session_id` is deliberately NOT a label in Alloy and is a custom key here for the same reason it
 * is not there: it is unbounded. A custom key is free-form, so it costs nothing to carry.
 *
 * ## What it does not do
 *
 * It does not send a crash to LGTM. `LabTelemetryShipper` ships only while a session records, by
 * design, and a crash by definition ends the process before the next flush. Putting a crash counter
 * on the wire means shipping outside a session, which changes what an idle handset does on a
 * student's phone. That is a decision, not a refactor, and it is not taken here.
 *
 * ## Firebase is optional
 *
 * `GoogleService-Info.plist` and `google-services.json` are gitignored, so a fresh clone, a CI build
 * and a bench handset have no Firebase at all and every call below throws. Every call is therefore
 * wrapped: telemetry must never be the reason a participant cannot start a session.
 */
object CrashContext {

    /** Custom keys, spelled exactly as the Alloy labels they join to. */
    private const val KEY_BUILD_ID = "build_id"
    private const val KEY_APP_VERSION = "app_version"
    private const val KEY_APP_BUILD = "app_build"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_SITE = "site"
    private const val KEY_PARTICIPANT = "participant"
    private const val KEY_SESSION_ID = "session_id"

    /**
     * Called once per process, as early as the app can read the opt-out.
     *
     * A stored opt-out is re-asserted rather than skipped, and so is the default. Firebase persists
     * whatever it was last told, so an install that ran the broken build carries a `false` that only
     * an explicit `true` clears.
     */
    fun applyStartup(optedOut: Boolean) {
        val enabled = !optedOut
        runCatching {
            Firebase.crashlytics.setCrashlyticsCollectionEnabled(enabled)
            if (enabled) {
                Firebase.crashlytics.setCustomKey(KEY_BUILD_ID, AppConfig.BUILD_ID)
                Firebase.crashlytics.setCustomKey(KEY_APP_VERSION, AppConfig.APP_VERSION)
                Firebase.crashlytics.setCustomKey(KEY_APP_BUILD, AppConfig.APP_BUILD)
                Firebase.crashlytics.setCustomKey(KEY_PLATFORM, platformName())
            }
        }.onFailure {
            Napier.w("[crash] Crashlytics unavailable, continuing without it: ${it.message}")
            return
        }

        Napier.i("[crash] collection=$enabled build=${AppConfig.BUILD_ID}")

        // Said out loud on the launch AFTER a crash, which is the only moment the app can still
        // tell anyone. Without it the local log of a crashed run simply stops mid-sentence and the
        // next run's log looks like an ordinary cold start.
        runCatching {
            if (Firebase.crashlytics.didCrashOnPreviousExecution()) {
                Napier.w("[crash] the previous run of this app ended in a crash — see Crashlytics")
            }
        }
    }

    /**
     * Bind the session's LGTM labels onto every crash from here on.
     *
     * Called by `LabTelemetryShipper` at the moment it learns them, so the two cannot disagree about
     * which participant a handset is. `setUserId` takes the participant token and nothing else: it
     * is an opaque pseudonym with no foreign key to an account anywhere in this project, which is
     * what makes Crashlytics' crash-free-users figure usable without naming a person.
     */
    fun bindSession(sessionId: String, participant: String, site: String) {
        runCatching {
            Firebase.crashlytics.setCustomKey(KEY_SESSION_ID, sessionId)
            Firebase.crashlytics.setCustomKey(KEY_PARTICIPANT, participant)
            Firebase.crashlytics.setCustomKey(KEY_SITE, site)
            if (participant.isNotBlank()) Firebase.crashlytics.setUserId(participant)
        }.onFailure { Napier.w("[crash] could not bind session labels: ${it.message}") }
    }

    /** A breadcrumb on the next crash report. Never a substitute for a log line. */
    fun breadcrumb(message: String) {
        runCatching { Firebase.crashlytics.log(message) }
    }

    /** A caught failure worth a report. The app keeps running; the report says it should not have. */
    fun recordNonFatal(throwable: Throwable) {
        runCatching { Firebase.crashlytics.recordException(throwable) }
    }

    private fun platformName(): String = when {
        Platform.isIOS -> "ios"
        Platform.isAndroid -> "android"
        else -> "unknown"
    }
}
