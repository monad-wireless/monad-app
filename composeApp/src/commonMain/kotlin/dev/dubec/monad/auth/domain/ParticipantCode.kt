package dev.dubec.monad.auth.domain

import dev.dubec.monad.User

/**
 * The name a person has in every recording.
 *
 * The server makes it from the account (`GET /api/auth/me`, `participant_code`) and the local row
 * keeps it in `backendId`. It is the same on every phone and after every sign-in, and it leads back
 * to an account only for the server.
 *
 * Until 1.2.0+21 `backendId` was never written, so each of four callers fell back to the local row
 * id, which is a login counter: two people could both be `1`. The rule is here once so it cannot
 * differ between the quest path and the console again.
 */
object ParticipantCode {

    /**
     * A phone with no code says so. The prefix keeps a counter from passing for a code, and the
     * recording is kept: a count must not be lost because one request failed at sign-in.
     */
    fun of(code: String?, localId: Long): String =
        code?.trim()?.takeIf { it.isNotEmpty() } ?: "local-$localId"
}

val User.participantCode: String get() = ParticipantCode.of(backendId, id)
