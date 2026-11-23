package dev.dubec.monad.quests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.dubec.monad.quests.domain.port.isFinalRefusal

/**
 * A refused completion was offered for retry forever (monad-app#2).
 *
 * The phone rebuilds the same payload from the same rows on every retry, so a refusal of the
 * content or of the run itself repeats. Those close the run on the phone. A refusal that a new
 * sign-in or a recovered server can change keeps it open for another try.
 */
class CompletionRefusalTest {

    @Test
    fun contentAndRunRefusalsAreFinal() {
        // 400 validation, 403 not this account's run, 404 unknown run,
        // 409 already closed (server reaper or operator), 422 sweep summary rejected.
        listOf(400, 403, 404, 409, 422).forEach { assertTrue(isFinalRefusal(it), "HTTP $it") }
    }

    @Test
    fun authenticationThrottlingAndServerErrorsStayRetryable() {
        listOf(401, 408, 429, 500, 502, 503).forEach { assertFalse(isFinalRefusal(it), "HTTP $it") }
    }
}
