package sk.martinvanco.monad.quests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import sk.martinvanco.monad.quests.domain.verifyAssociation

/**
 * `connect_to_ap` used to complete whether or not anything connected (IP-140).
 *
 * It routed to `TextBoxStep` — a title and a Continue button — so an illuminator arm could report
 * a successful association it never made. These tests pin the replacement: every stage must hold,
 * and the participant is told the *first* thing that is wrong rather than the last thing checked.
 */
class AssociationVerdictTest {

    private fun verdict(
        sessionRunning: Boolean = true,
        illuminatorRequested: Boolean = true,
        commandedApId: String = "lab-ap",
        joinedApId: String = "lab-ap",
        joinedSsid: String = "monad-lab",
        socketPinned: Boolean = true,
        boundInterface: String = "en0",
        clockSamples: Int = 12,
    ) = verifyAssociation(
        sessionRunning = sessionRunning,
        illuminatorRequested = illuminatorRequested,
        commandedApId = commandedApId,
        joinedApId = joinedApId,
        joinedSsid = joinedSsid,
        socketPinned = socketPinned,
        boundInterface = boundInterface,
        clockSamples = clockSamples,
    )

    @Test
    fun verifiesWhenEveryStageHolds() {
        assertTrue(verdict().verified)
    }

    @Test
    fun refusesWithNoSession() {
        assertFalse(verdict(sessionRunning = false).verified)
    }

    @Test
    fun refusesAndExplainsWhenThereIsNoAccessPointOnThisDeployment() {
        // The live case. The bundle carries no access point, so the session never requested the
        // illuminator role — and the sentence has to say that rather than blame the participant.
        val v = verdict(illuminatorRequested = false)
        assertFalse(v.verified)
        assertTrue(
            v.reason.contains("no access point", ignoreCase = true),
            "the refusal must name the real cause, got: ${v.reason}",
        )
    }

    @Test
    fun refusesWhenNoNetworkWasJoined() {
        assertFalse(verdict(joinedSsid = "").verified)
    }

    @Test
    fun refusesWhenTheQuestAndTheBundleNameDifferentAccessPoints() {
        // Not pedantry: the run would be filed against a network it did not use.
        assertFalse(verdict(commandedApId = "lab-ap", joinedApId = "other-ap").verified)
    }

    @Test
    fun refusesWhenTheSocketIsNotPinned() {
        // An unpinned socket can leave over mobile data, which measures nothing at all.
        assertFalse(verdict(socketPinned = false).verified)
    }

    @Test
    fun refusesUntilTheCollectorHasActuallyAnswered() {
        // The stage that separates an intention from a fact. `open()` on a UDP socket succeeds
        // against a host that is not there, so a pinned socket proves nothing about reachability;
        // a returned four-timestamp burst does.
        assertFalse(verdict(clockSamples = 0).verified)
    }

    @Test
    fun namesTheFirstFailureNotTheLast() {
        // A participant told "the collector has not answered" when the real problem is that no
        // session is running has been given a true statement and no way to act on it.
        val v = verdict(sessionRunning = false, socketPinned = false, clockSamples = 0)
        assertTrue(
            v.reason.contains("session is not running", ignoreCase = true),
            "expected the session failure first, got: ${v.reason}",
        )
    }
}
