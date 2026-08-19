package sk.martinvanco.monad.lab.domain

/**
 * A clock the device can measure itself against, over something other than the collector's UDP socket.
 *
 * ### Why this exists
 *
 * Every timestamp this app records is on the device's own monotonic clock, which has an arbitrary
 * origin. That is deliberate — it is the only clock that is continuous across sleep — but it means a
 * pose, a waypoint or a broadcast marker cannot be placed against anything recorded elsewhere until
 * something maps `mono_ns` onto a shared epoch. `clock.tsv` is that mapping, and until now the only way
 * to produce it was a four-timestamp exchange over the collector's UDP socket.
 *
 * **A walk has no collector.** The fleet's AX210 cannot enter AP mode, so there is nothing to associate
 * to and no socket to exchange over — which left a walk with a trajectory, an identity frame, and no way
 * to say *when* any of it happened relative to the radio capture it is supposed to explain. The chain
 * the whole exercise depends on is:
 *
 * ```
 * pose / mesh / waypoint  --mono_ns-->  clock.tsv  --Unix epoch-->  csid capture  -->  CSI record
 *                                                     ^
 *                                        the fleet's chrony-disciplined clock
 * ```
 *
 * Break the middle link and the mesh and the trajectory are a map of nowhen.
 *
 * ### What it is honestly worth
 *
 * This runs over HTTP, and the backend's own endpoint documentation says so plainly: TLS handshakes and
 * keep-alive scheduling add jitter the minimum-delay filter cannot remove, so it is coarser than the UDP
 * path. Coarser is not useless, and the budget is what decides:
 *
 * * Gate **G4a** allows 6 s. HTTP clears it by two orders of magnitude.
 * * Gate **G4b** allows 250 ms, which is what block boundaries are held to. A handful of exchanges over
 *   a local network land in the tens of milliseconds, so it clears this too — but not with the margin
 *   the UDP path has, and a congested uplink can push it.
 * * A walking body at 1.4 m/s moves 1.4 cm per 10 ms. For attributing a CSI window of half a second to a
 *   position, tens of milliseconds is far inside the noise.
 *
 * So it is sufficient for a fingerprinting walk and is **not** a substitute for the collector exchange in
 * a session that has one. The estimate records which path produced it and the sidecar carries that.
 */
interface ReferenceClock {

    /** Human-readable name of the path, recorded so a reader knows which precision applies. */
    val source: String

    /**
     * One exchange. The implementation stamps `t1` before the request and `t4` after the response, and
     * the reference supplies `t2` / `t3` — the same four timestamps the UDP path uses, so the same
     * minimum-delay estimator applies without a second implementation.
     */
    suspend fun exchange(): Result<ClockExchange>
}
