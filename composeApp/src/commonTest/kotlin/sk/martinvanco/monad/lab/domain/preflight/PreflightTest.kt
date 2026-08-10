package sk.martinvanco.monad.lab.domain.preflight

import sk.martinvanco.monad.core.domain.permissions.LabPermission
import sk.martinvanco.monad.core.domain.permissions.PermissionStatus
import sk.martinvanco.monad.lab.domain.ApProfile
import sk.martinvanco.monad.lab.domain.BeaconPlan
import sk.martinvanco.monad.lab.domain.BeaconZone
import sk.martinvanco.monad.lab.domain.ClockEstimate
import sk.martinvanco.monad.lab.domain.CollectorEndpoint
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.ResidencyCheck
import sk.martinvanco.monad.lab.domain.TrafficProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pre-flight, at each criterion's boundary.
 *
 * The check that justifies the whole thing is
 * [aPhoneThatWouldBeDowngradedToOffsetOnlyIsABlocker]: "this phone will fail the clock gate" is a
 * sentence worth having before ten people are standing in a room, and it is knowable in seconds.
 */
class PreflightTest {

    private val goodConfig = LabConfig(
        version = 7,
        site = "fiit-library",
        collector = CollectorEndpoint(host = "10.0.0.9", udpPort = 9999),
        accessPoints = listOf(ApProfile(id = "ap-1", ssid = "monad-exp", band = "5")),
        beacons = BeaconPlan(uuid = "uuid", zones = listOf(BeaconZone(cellId = "ZONE-A"))),
        trafficProfiles = listOf(TrafficProfile(id = "p", rateHz = 200.0)),
    )

    private fun estimate(monoNanos: Long, offsetNanos: Long, delayNanos: Long = 3_000_000) =
        ClockEstimate(
            offsetNanos = offsetNanos,
            delayNanos = delayNanos,
            skewPpm = 0.0,
            anchorNanos = monoNanos,
            samples = 8,
        )

    private fun goodProbe(count: Int = 3) = CollectorProbe(
        attempted = true,
        reachable = true,
        estimates = (0 until count).map { estimate(1_000_000_000_000L + it * 2_000_000_000L, 5_000) },
        spanMillis = 4_000,
    )

    private fun inputs(
        residency: List<ResidencyCheck> = listOf(ResidencyCheck("always-location", true, "ok")),
        permissions: List<PermissionStatus> = LabPermission.entries.map {
            PermissionStatus(it, granted = true, deniedPermanently = false)
        },
        config: LabConfig = goodConfig,
        probe: CollectorProbe = goodProbe(),
        storage: StorageProbe = StorageProbe(4L * 1024 * 1024 * 1024, 64L * 1024 * 1024 * 1024),
        backlogSessions: Long = 0,
        backlogScans: Long = 0,
        rateHz: Double = 200.0,
    ) = PreflightInputs(
        residency = residency,
        permissions = permissions,
        config = config,
        collector = probe,
        storage = storage,
        backlogSessions = backlogSessions,
        backlogScans = backlogScans,
        commandedRateHz = rateHz,
    )

    private fun check(report: PreflightReport, id: PreflightCheckId) =
        assertNotNull(report.checks.firstOrNull { it.id == id }, "missing check $id")

    @Test
    fun aReadyPhoneIsGo() {
        val report = Preflight.evaluate(inputs())
        assertTrue(report.isGo, report.headline)
        assertTrue(report.blockers.isEmpty())
        assertTrue(report.headline.startsWith("READY"))
    }

    // ---- permissions ----------------------------------------------------------------------

    @Test
    fun aMissingRequiredPermissionIsABlocker() {
        val report = Preflight.evaluate(
            inputs(
                permissions = LabPermission.entries.map {
                    PermissionStatus(
                        it,
                        granted = it != LabPermission.BACKGROUND_LOCATION,
                        deniedPermanently = false,
                    )
                }
            )
        )
        val permissions = check(report, PreflightCheckId.PERMISSIONS)
        assertEquals(PreflightSeverity.FAIL, permissions.severity)
        assertTrue(permissions.detail.contains("Always"), permissions.detail)
        assertFalse(report.isGo)
    }

    @Test
    fun bluetoothIsAmongTheRequiredPermissions() {
        // Named explicitly, because a witness with no Bluetooth contributes nothing at all and the
        // failure is silent — the session still records, it simply records no zone information.
        assertTrue(LabPermission.BLUETOOTH.required)
        assertTrue(LabPermission.BACKGROUND_LOCATION.required)
    }

    @Test
    fun noPermissionInformationIsAWarningNotAPass() {
        val report = Preflight.evaluate(inputs(permissions = emptyList()))
        assertEquals(PreflightSeverity.WARN, check(report, PreflightCheckId.PERMISSIONS).severity)
        assertTrue(report.isGo, "unknown is not a blocker, but it is never silently a pass")
    }

    // ---- residency ------------------------------------------------------------------------

    @Test
    fun anUnsatisfiedResidencyCheckIsABlocker() {
        val report = Preflight.evaluate(
            inputs(
                residency = listOf(
                    ResidencyCheck("always-location", false, "authorized when-in-use only"),
                )
            )
        )
        assertEquals(PreflightSeverity.FAIL, check(report, PreflightCheckId.RESIDENCY).severity)
    }

    // ---- config ---------------------------------------------------------------------------

    @Test
    fun aBundleThatCannotIlluminateIsABlocker() {
        val report = Preflight.evaluate(
            inputs(config = goodConfig.copy(collector = CollectorEndpoint(host = "")))
        )
        assertEquals(PreflightSeverity.FAIL, check(report, PreflightCheckId.CONFIG).severity)
    }

    @Test
    fun aBundleWithNoBeaconPlanIsAWarning() {
        val report = Preflight.evaluate(inputs(config = goodConfig.copy(beacons = BeaconPlan())))
        assertEquals(PreflightSeverity.WARN, check(report, PreflightCheckId.CONFIG).severity)
        assertTrue(report.isGo)
    }

    // ---- collector ------------------------------------------------------------------------

    @Test
    fun anUnreachableCollectorIsABlocker() {
        val report = Preflight.evaluate(
            inputs(probe = CollectorProbe(attempted = true, reachable = false, error = "timeout"))
        )
        assertEquals(PreflightSeverity.FAIL, check(report, PreflightCheckId.COLLECTOR).severity)
    }

    @Test
    fun aSlowButWorkingLinkIsOnlyAWarning() {
        val slow = CollectorProbe(
            attempted = true,
            reachable = true,
            estimates = (0..2).map {
                estimate(1_000_000_000_000L + it * 2_000_000_000L, 5_000, delayNanos = 400_000_000)
            },
            spanMillis = 4_000,
        )
        val report = Preflight.evaluate(inputs(probe = slow))
        assertEquals(PreflightSeverity.WARN, check(report, PreflightCheckId.COLLECTOR).severity)
        assertTrue(report.isGo)
    }

    // ---- the clock gate -------------------------------------------------------------------

    @Test
    fun aPhoneThatWouldBeDowngradedToOffsetOnlyIsABlocker() {
        // One usable sample is the difference between an identifiable affine fit and an
        // offset-only fallback the pre-registration flags. It is knowable in four seconds at a
        // bench and catastrophic three weeks later.
        val report = Preflight.evaluate(inputs(probe = goodProbe(count = 1)))
        val gate = check(report, PreflightCheckId.CLOCK_GATE)
        assertEquals(PreflightSeverity.FAIL, gate.severity)
        assertTrue(
            gate.remedy.orEmpty().contains("will fail the clock gate"),
            "the operator must read the sentence, not infer it: ${gate.remedy}",
        )
    }

    @Test
    fun noClockExchangeAtAllIsABlocker() {
        val report = Preflight.evaluate(
            inputs(probe = CollectorProbe(attempted = true, reachable = true, estimates = emptyList()))
        )
        assertEquals(PreflightSeverity.FAIL, check(report, PreflightCheckId.CLOCK_GATE).severity)
    }

    @Test
    fun aResidualPastTheT3BudgetIsAWarningNamingT3() {
        // Past G4b (250 ms) but inside G4a (6 s): T1/T2/T4 survive, T3 does not — and T3 is what
        // block boundaries feed.
        val drifting = CollectorProbe(
            attempted = true,
            reachable = true,
            estimates = listOf(
                estimate(1_000_000_000_000L, 0),
                estimate(1_002_000_000_000L, 0),
                estimate(1_004_000_000_000L, 1_000_000_000L),
                estimate(1_006_000_000_000L, 0),
            ),
            spanMillis = 6_000,
        )
        val report = Preflight.evaluate(inputs(probe = drifting))
        val gate = check(report, PreflightCheckId.CLOCK_GATE)
        assertEquals(PreflightSeverity.WARN, gate.severity)
        assertTrue(gate.detail.contains("G4b"), gate.detail)
        assertTrue(report.isGo)
    }

    @Test
    fun aCleanProbeSaysWhatItCannotProve() {
        val report = Preflight.evaluate(inputs())
        val gate = check(report, PreflightCheckId.CLOCK_GATE)
        assertEquals(PreflightSeverity.PASS, gate.severity)
        assertTrue(
            gate.detail.contains("does not prove long-term skew"),
            "a four-second probe must not be read as a G4 guarantee: ${gate.detail}",
        )
    }

    // ---- storage --------------------------------------------------------------------------

    @Test
    fun theSessionEstimateScalesWithTheCommandedRate() {
        val slow = Preflight.estimateSessionBytes(25.0)
        val fast = Preflight.estimateSessionBytes(200.0)
        assertTrue(fast > slow)
        // A 200 Hz three-hour session is millions of rows; the estimate must be in that league.
        assertTrue(fast > 200L * 1024 * 1024, "estimate was $fast bytes")
    }

    @Test
    fun tooLittleFreeSpaceIsABlocker() {
        val report = Preflight.evaluate(inputs(storage = StorageProbe(40L * 1024 * 1024, 0)))
        val storage = check(report, PreflightCheckId.STORAGE)
        assertEquals(PreflightSeverity.FAIL, storage.severity)
        assertFalse(report.isGo)
    }

    @Test
    fun aThinMarginIsAWarning() {
        val required = Preflight.estimateSessionBytes(200.0)
        val report = Preflight.evaluate(
            inputs(storage = StorageProbe((required * 1.2).toLong(), 0))
        )
        assertEquals(PreflightSeverity.WARN, check(report, PreflightCheckId.STORAGE).severity)
        assertTrue(report.isGo)
    }

    @Test
    fun unknownFreeSpaceIsNotReportedAsFull() {
        val report = Preflight.evaluate(inputs(storage = StorageProbe.UNKNOWN))
        assertEquals(PreflightSeverity.WARN, check(report, PreflightCheckId.STORAGE).severity)
        assertTrue(report.isGo, "a platform that cannot answer must not block a session")
    }

    // ---- backlog --------------------------------------------------------------------------

    @Test
    fun anUploadBacklogIsAWarningNotABlocker() {
        val report = Preflight.evaluate(inputs(backlogSessions = 2, backlogScans = 17))
        val backlog = check(report, PreflightCheckId.BACKLOG)
        assertEquals(PreflightSeverity.WARN, backlog.severity)
        assertTrue(backlog.detail.contains("2"))
        assertTrue(backlog.detail.contains("17"))
        assertTrue(report.isGo)
    }

    @Test
    fun theHeadlineNamesEveryBlocker() {
        val report = Preflight.evaluate(
            inputs(
                residency = listOf(ResidencyCheck("always-location", false, "when-in-use")),
                storage = StorageProbe(1024, 0),
            )
        )
        assertFalse(report.isGo)
        assertTrue(report.headline.startsWith("NOT READY"))
        assertTrue(report.headline.contains("Background residency"))
        assertTrue(report.headline.contains("Storage headroom"))
    }
}
