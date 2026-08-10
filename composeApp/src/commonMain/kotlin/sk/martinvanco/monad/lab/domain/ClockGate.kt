package sk.martinvanco.monad.lab.domain

import kotlin.math.abs

/**
 * Gate G4 — clock alignment — evaluated on the device, before the data leaves it.
 *
 * The pre-registration (`lab-session-2026-08-prereg-v2`, §3.5) fits, **per `recording_session_id`**,
 *
 * ```
 * unix_ts_ns ≈ a · mono_ns + b
 * ```
 *
 * over that session's `ClockEstimate` samples in `clock.tsv`. With **fewer than two usable samples**
 * the fit degenerates to offset-only (`a = 1`, `b` from the single sample) and the fold is flagged;
 * with none, the fold has no join at all and is excluded outright.
 *
 * That threshold is knowable at the bench and catastrophic in analysis, which is the whole reason
 * this class exists: a session that will be thrown away should say so while the operator is still
 * standing in the room, not three weeks later.
 *
 * ### Two budgets, and why the tighter one is the one that matters here
 *
 * - **G4a — 6.0 s**, one CSI analysis window. Below it a fold survives every test.
 * - **G4b — 250 ms**, half the ±0.5 s peak band of T3's peak-to-plateau contrast.
 *
 * Block boundaries (`BLOCK_START` / `BLOCK_STOP`) feed T3, whose **primary** event set is the
 * cycling blocks — and a cycling ramp is 30 s. A boundary accurate only to G4a would be 20 % of a
 * ramp out, which systematically mislabels plateau windows as ramp precisely at the transitions T3
 * exists to analyse. So the operational target for the whole marker path is **G4b**, and both
 * verdicts are surfaced separately rather than folded into one pass/fail.
 *
 * ### The estimator
 *
 * Within a burst, `ClockEstimator` already keeps the **minimum-delay** exchange — the classic
 * Cristian / NTP filter, correct because queueing delay is one-sided noise whose mean is a bias.
 * Across bursts this object does the same thing one level up: the affine fit is taken over the
 * **low-delay clique** rather than over every burst, followed by a single MAD-based outlier
 * rejection pass. Fitting through a congested burst drags the whole line toward it, and the offset
 * is what every block boundary is stamped with.
 *
 * **What is deliberately *not* filtered is the residual.** The fit is computed on the clean subset,
 * but `maxAbsResidualNanos` is measured over **every** usable sample against that fit. Discarding an
 * outlier from the evidence as well as from the fit would turn a clock that genuinely wandered ten
 * seconds into a clean-looking session — the gate exists to catch exactly that, so a rejected sample
 * still counts against it.
 *
 * **What this can and cannot tell you.** The registered estimand is the residual between a sync
 * marker's *predicted* unix time and the *observed* CSI time of the same marker, and the CSI side
 * lives on the fleet. What is computable here is the residual of the phone's own sync samples
 * against their own fit. A large value proves G4 will fail; a small value does not prove it will
 * pass. Reported as `maxFitResidualMillis` and never called the gate residual.
 */
object ClockGate {

    /** Below this the affine fit is not identifiable and the analysis falls back to offset-only. */
    const val MIN_SAMPLES_FOR_FIT: Int = 2

    /** Below this a residual cannot be computed at all — two points always fit a line exactly. */
    const val MIN_SAMPLES_FOR_RESIDUAL: Int = 3

    /** G4a: one CSI analysis window. Beyond this a fold is excluded from every test. */
    const val BUDGET_ALL_TESTS_MILLIS: Double = 6_000.0

    /** G4b: half the ±0.5 s peak band of the T3-CHANGE contrast. The target for block boundaries. */
    const val BUDGET_T3_MILLIS: Double = 250.0

    /**
     * A burst is admitted to the fit while its round-trip stays within this multiple of the best
     * round-trip seen. Two is the conventional NTP-style dispersion cut: it keeps the clique of
     * uncongested exchanges and drops the ones that queued.
     */
    const val DELAY_DISPERSION_FACTOR: Double = 2.0

    /**
     * Floor on the dispersion cut, so a link whose best RTT is a few hundred microseconds does not
     * reject everything on sub-millisecond wobble.
     */
    const val DELAY_DISPERSION_FLOOR_NANOS: Long = 2_000_000L

    /** Residuals beyond this many scaled MADs are dropped from the fit (never from the evidence). */
    const val OUTLIER_MAD_FACTOR: Double = 3.0

    /** Consistency constant that makes the MAD a standard-deviation estimate for a normal sample. */
    private const val MAD_TO_SIGMA: Double = 1.4826

    /**
     * @param applicable false for a witness-only session, which has no socket and therefore no way
     *   to exchange time with the collector. Reported as such rather than as a failure — a
     *   participant carrying a passive phone has not done anything wrong.
     */
    fun evaluate(samples: List<ClockEstimate>, applicable: Boolean = true): ClockGateReport {
        if (!applicable) {
            return ClockGateReport(
                status = ClockGateStatus.NOT_APPLICABLE,
                sampleCount = 0,
                note = "witness-only session: no collector socket, so no time exchange. The fleet " +
                    "clock is authoritative and this phone contributes no join.",
            )
        }
        val usable = samples.filter { it.samples > 0 }
        if (usable.isEmpty()) {
            return ClockGateReport(
                status = ClockGateStatus.NO_SAMPLES,
                sampleCount = 0,
                note = "no clock exchange succeeded — this session cannot be placed on the fleet " +
                    "timeline and would be excluded from every test.",
            )
        }
        if (usable.size < MIN_SAMPLES_FOR_FIT) {
            val only = usable.single()
            return ClockGateReport(
                status = ClockGateStatus.OFFSET_ONLY,
                sampleCount = 1,
                skewPpm = 0.0,
                offsetMillis = only.offsetMillis,
                bestRttMillis = only.delayMillis,
                note = "one sample only: the analysis fixes a = 1 and takes b from it, and flags " +
                    "the fold. A second burst is needed before this session is worth its slot.",
            )
        }

        val fit = fitAffine(usable)
        val residual = if (usable.size >= MIN_SAMPLES_FOR_RESIDUAL) fit.maxAbsResidualNanos else null
        return ClockGateReport(
            status = ClockGateStatus.OK,
            sampleCount = usable.size,
            skewPpm = fit.skewPpm,
            offsetMillis = usable.last().offsetMillis,
            bestRttMillis = usable.minOf { it.delayNanos } / 1e6,
            fit = fit,
            maxFitResidualMillis = residual?.let { it / 1e6 },
            note = if (residual == null) {
                "two samples: the affine fit is exact by construction, so no residual evidence " +
                    "exists yet. A third burst is what turns the fit into a check."
            } else {
                ""
            },
        )
    }

    /**
     * A per-marker precision snapshot: the best estimate available at [atMonotonicNanos], and how
     * good it is.
     *
     * Stamped onto every block boundary so the analysis can weight or exclude a poorly-synced edge
     * rather than trusting it silently.
     */
    fun stamp(
        atMonotonicNanos: Long,
        samples: List<ClockEstimate>,
        applicable: Boolean = true,
    ): ClockStamp {
        val report = evaluate(samples, applicable)
        val usable = samples.filter { it.samples > 0 }
        val newest = usable.maxByOrNull { it.anchorNanos }
        val fit = report.fit
        val projected: Long?
        val source: ClockEstimateSource
        when {
            fit != null -> {
                projected = fit.toReferenceNanos(atMonotonicNanos)
                source = ClockEstimateSource.AFFINE_FIT
            }

            newest != null -> {
                projected = newest.toReferenceNanos(atMonotonicNanos)
                source = ClockEstimateSource.OFFSET_ONLY
            }

            else -> {
                projected = null
                source = ClockEstimateSource.NONE
            }
        }
        return ClockStamp(
            status = report.status,
            samples = report.sampleCount,
            offsetNanos = newest?.offsetNanos ?: 0L,
            rttMillis = report.bestRttMillis,
            skewPpm = report.skewPpm,
            fitResidualMillis = report.maxFitResidualMillis,
            syncAgeMillis = newest
                ?.let { ((atMonotonicNanos - it.anchorNanos) / 1_000_000L).coerceAtLeast(0L) }
                ?: 0L,
            estimatedReferenceNanos = projected,
            estimateSource = source,
            samplesRejected = fit?.rejectedSamples ?: 0,
            meetsAllTestsBudget = !report.wouldFailGate,
            meetsT3Budget = !report.wouldFailGate && !report.wouldFailT3Only,
        )
    }

    /**
     * Least squares of `offset` on `mono`, over the low-delay clique, recentred before fitting.
     *
     * Recentring is not a nicety. Monotonic nanoseconds are order 1e15 and offsets order 1e9;
     * accumulating `Σx²` on the raw values in a Double loses the very variation being fitted. Every
     * `x` here is relative to the first sample's anchor.
     *
     * Selection is two-stage and deterministic — no randomness, no iteration to convergence, so the
     * same `clock.tsv` always produces the same fit:
     *
     * 1. **Dispersion cut.** Keep bursts whose round-trip is within [DELAY_DISPERSION_FACTOR] of the
     *    best round-trip (with a [DELAY_DISPERSION_FLOOR_NANOS] floor). If that leaves fewer than
     *    [MIN_SAMPLES_FOR_FIT], take the lowest-delay [MIN_SAMPLES_FOR_FIT] instead.
     * 2. **One MAD rejection pass**, only when there is enough data for a residual to mean anything.
     *
     * `maxAbsResidualNanos` is then measured over **every** supplied sample against the resulting
     * line, because it is the gate's evidence and not the fit's self-assessment.
     */
    fun fitAffine(samples: List<ClockEstimate>): AffineFit {
        require(samples.size >= MIN_SAMPLES_FOR_FIT) {
            "affine fit needs at least $MIN_SAMPLES_FOR_FIT samples"
        }
        val origin = samples.first().anchorNanos
        val clique = lowDelayClique(samples)
        var kept = clique
        var line = leastSquares(kept, origin)

        if (clique.size > MIN_SAMPLES_FOR_RESIDUAL) {
            val pruned = rejectOutliers(clique, line, origin)
            if (pruned.size >= MIN_SAMPLES_FOR_FIT && pruned.size < clique.size) {
                kept = pruned
                line = leastSquares(kept, origin)
            }
        }

        return AffineFit(
            a = 1.0 + line.slope,
            bNanos = line.intercept - line.slope * origin.toDouble(),
            originNanos = origin,
            samples = samples.size,
            keptSamples = kept.size,
            rejectedSamples = samples.size - kept.size,
            // Evidence: every sample, including the ones the filter refused to fit through.
            maxAbsResidualNanos = maxAbsResidual(samples, line, origin),
            // Self-assessment: how well the clean subset actually lies on a line.
            maxAbsResidualFilteredNanos = maxAbsResidual(kept, line, origin),
            spanMillis = (samples.last().anchorNanos - origin) / 1_000_000.0,
        )
    }

    private fun lowDelayClique(samples: List<ClockEstimate>): List<ClockEstimate> {
        val best = samples.minOf { it.delayNanos }
        val ceiling = maxOf(
            (best.toDouble() * DELAY_DISPERSION_FACTOR).toLong(),
            best + DELAY_DISPERSION_FLOOR_NANOS,
        )
        val clique = samples.filter { it.delayNanos <= ceiling }
        if (clique.size >= MIN_SAMPLES_FOR_FIT) return clique
        // Never fewer than the fit needs: a link on which every burst queued is still worth an
        // offset, and refusing to fit would report NO_SAMPLES for a session that has them.
        return samples.sortedBy { it.delayNanos }.take(MIN_SAMPLES_FOR_FIT)
    }

    private fun rejectOutliers(
        samples: List<ClockEstimate>,
        line: Line,
        origin: Long,
    ): List<ClockEstimate> {
        val residuals = samples.map { residual(it, line, origin) }
        val median = median(residuals)
        val mad = median(residuals.map { abs(it - median) })
        // A perfectly linear series has MAD 0; scaling zero by anything still rejects nothing, but
        // guarding here keeps the threshold from becoming a strict-equality test on floats.
        if (mad <= 0.0) return samples
        val threshold = OUTLIER_MAD_FACTOR * MAD_TO_SIGMA * mad
        return samples.filterIndexed { index, _ -> abs(residuals[index] - median) <= threshold }
    }

    private fun leastSquares(samples: List<ClockEstimate>, origin: Long): Line {
        val n = samples.size
        val xs = samples.map { (it.anchorNanos - origin).toDouble() }
        val ys = samples.map { it.offsetNanos.toDouble() }
        val meanX = xs.sum() / n
        val meanY = ys.sum() / n
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            sxx += dx * dx
            sxy += dx * (ys[i] - meanY)
        }
        // Degenerate x (every burst at the same instant) is not a slope of infinity, it is no slope.
        val slope = if (sxx > 0.0) sxy / sxx else 0.0
        return Line(slope = slope, intercept = meanY - slope * meanX)
    }

    private fun residual(sample: ClockEstimate, line: Line, origin: Long): Double {
        val x = (sample.anchorNanos - origin).toDouble()
        return sample.offsetNanos.toDouble() - (line.slope * x + line.intercept)
    }

    private fun maxAbsResidual(samples: List<ClockEstimate>, line: Line, origin: Long): Double {
        var worst = 0.0
        samples.forEach {
            val r = abs(residual(it, line, origin))
            if (r > worst) worst = r
        }
        return worst
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private data class Line(val slope: Double, val intercept: Double)
}

/**
 * The registered transform, as this device would report it.
 *
 * `a` is the skew (near 1) and `b` the offset in nanoseconds, exactly the two numbers the analysis
 * writes into its output for every fold.
 */
data class AffineFit(
    val a: Double,
    val bNanos: Double,
    val originNanos: Long,
    /** Usable samples supplied. */
    val samples: Int,
    /** Samples the low-delay filter actually fitted through. */
    val keptSamples: Int = samples,
    /** Samples the filter discarded. They still count toward [maxAbsResidualNanos]. */
    val rejectedSamples: Int = 0,
    /** Worst residual over **all** supplied samples — the gate's evidence. */
    val maxAbsResidualNanos: Double,
    /** Worst residual over the fitted subset — how linear the clean data actually is. */
    val maxAbsResidualFilteredNanos: Double = maxAbsResidualNanos,
    val spanMillis: Double,
) {
    val skewPpm: Double get() = (a - 1.0) * 1e6

    /** Project a device monotonic stamp onto the collector timeline using the fitted transform. */
    fun toReferenceNanos(monotonicNanos: Long): Long = (a * monotonicNanos + bNanos).toLong()
}

enum class ClockGateStatus {
    /** Not run yet (idle instrument). */
    NOT_RUN,

    /** Witness-only session: no socket, no exchange, no join expected. */
    NOT_APPLICABLE,

    /** Every exchange failed. The fold is excluded from all tests. */
    NO_SAMPLES,

    /** One sample: the analysis falls back to `a = 1` and flags the fold. */
    OFFSET_ONLY,

    /** Two or more: the affine fit is identifiable. */
    OK,
    ;

    val wire: String get() = name.lowercase()
}

/** Which projection produced an estimated reference time. */
enum class ClockEstimateSource(val wire: String) {
    AFFINE_FIT("affine_fit"),
    OFFSET_ONLY("offset_only"),
    NONE("none"),
}

/**
 * What the operator is shown, and what the sidecar carries.
 *
 * [wouldFailGate] is the line that matters on a bench: true means this session, as it stands,
 * produces data the pre-registration will refuse. [wouldFailT3Only] is the line that matters for
 * block boundaries, because T3 is what they feed.
 */
data class ClockGateReport(
    val status: ClockGateStatus,
    val sampleCount: Int,
    val skewPpm: Double = 0.0,
    val offsetMillis: Double = 0.0,
    /** Best round-trip seen across bursts — the floor on how well any of this can be known. */
    val bestRttMillis: Double = 0.0,
    val fit: AffineFit? = null,
    /** Residual of the phone's own samples against their own fit — a proxy, never the gate itself. */
    val maxFitResidualMillis: Double? = null,
    val note: String = "",
) {
    val meetsMinimumSamples: Boolean get() = sampleCount >= ClockGate.MIN_SAMPLES_FOR_FIT

    /** The fold would be dropped outright, or fitted offset-only and flagged. */
    val wouldFailGate: Boolean
        get() = status == ClockGateStatus.NO_SAMPLES ||
            status == ClockGateStatus.OFFSET_ONLY ||
            (maxFitResidualMillis ?: 0.0) > ClockGate.BUDGET_ALL_TESTS_MILLIS

    /** The fold would survive T1/T2/T4 but be dropped from the T3 change-detection leg. */
    val wouldFailT3Only: Boolean
        get() = !wouldFailGate &&
            (maxFitResidualMillis ?: 0.0) > ClockGate.BUDGET_T3_MILLIS

    /** G4a — 6 s. Every test. */
    val meetsAllTestsBudget: Boolean get() = !wouldFailGate

    /**
     * G4b — 250 ms. The budget block boundaries are actually held to.
     *
     * Deliberately **false while the residual is unknown** is *not* the behaviour: with two samples
     * the fit is exact and there is no residual evidence either way, so this reports the same
     * verdict as G4a. The operator sees the sample count next to it and can tell the difference.
     */
    val meetsT3Budget: Boolean get() = !wouldFailGate && !wouldFailT3Only

    /** The measured precision, in words, or why there is none yet. */
    val precisionLine: String
        get() = when {
            status == ClockGateStatus.NOT_RUN -> "no sync yet"
            status == ClockGateStatus.NOT_APPLICABLE -> "not applicable"
            status == ClockGateStatus.NO_SAMPLES -> "no samples"
            status == ClockGateStatus.OFFSET_ONLY -> "1 sample — offset only, no residual"
            maxFitResidualMillis == null -> "$sampleCount samples — fit exact, no residual yet"
            else -> "${maxFitResidualMillis.roundTo(1)} ms over $sampleCount samples"
        }

    /** One line for a status card. */
    val headline: String
        get() = when (status) {
            ClockGateStatus.NOT_RUN -> "clock not disciplined yet"
            ClockGateStatus.NOT_APPLICABLE -> "clock sync not applicable (witness-only)"
            ClockGateStatus.NO_SAMPLES -> "NO clock samples — session would be excluded"
            ClockGateStatus.OFFSET_ONLY -> "1 clock sample — needs 2, session would be flagged"
            ClockGateStatus.OK -> "$sampleCount clock samples — affine fit available"
        }

    companion object {
        val NOT_RUN = ClockGateReport(status = ClockGateStatus.NOT_RUN, sampleCount = 0)
    }
}

/**
 * The precision of one instant, stamped onto a marker.
 *
 * A session-level clock verdict cannot serve a block boundary: sync quality varies through a
 * session, and T3 asks whether *this* ramp edge is placed to within 250 ms. So each boundary carries
 * its own.
 */
data class ClockStamp(
    val status: ClockGateStatus,
    val samples: Int,
    val offsetNanos: Long,
    val rttMillis: Double,
    val skewPpm: Double,
    val fitResidualMillis: Double?,
    /** Milliseconds since the newest sync sample, at the marked instant. */
    val syncAgeMillis: Long,
    val estimatedReferenceNanos: Long?,
    val estimateSource: ClockEstimateSource,
    val samplesRejected: Int,
    val meetsAllTestsBudget: Boolean,
    val meetsT3Budget: Boolean,
) {
    val precisionLine: String
        get() = buildString {
            append(
                fitResidualMillis?.let { "${it.roundTo(1)} ms residual" }
                    ?: "no residual ($samples sample${if (samples == 1) "" else "s"})"
            )
            append(", sync ").append(syncAgeMillis).append(" ms old")
        }

    companion object {
        val NONE = ClockStamp(
            status = ClockGateStatus.NOT_RUN,
            samples = 0,
            offsetNanos = 0,
            rttMillis = 0.0,
            skewPpm = 0.0,
            fitResidualMillis = null,
            syncAgeMillis = 0,
            estimatedReferenceNanos = null,
            estimateSource = ClockEstimateSource.NONE,
            samplesRejected = 0,
            meetsAllTestsBudget = false,
            meetsT3Budget = false,
        )
    }
}
