package sk.martinvanco.monad.lab.data

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.lab.domain.ClockExchange
import sk.martinvanco.monad.lab.domain.ReferenceClock
import sk.martinvanco.monad.lab.domain.monotonicNanos

/**
 * The backend's coarse time endpoint (`GET /api/lab/time`) as a [ReferenceClock].
 *
 * This endpoint has existed on the backend since the lab stack was written and **the app never called
 * it**. That was the missing link in a walk: the phone recorded a trajectory, a mesh and an identity
 * frame, all on a device-local monotonic clock with an arbitrary origin, and nothing mapped that origin
 * onto the epoch the fleet's `csid` nodes are chrony-disciplined to. The geometry and the CSI were in
 * the same room and on different timelines.
 *
 * Three properties are load-bearing:
 *
 * * **`t1` and `t4` bracket the request as tightly as possible.** They are read on either side of the
 *   call and nothing else happens between them — no logging, no JSON building. Every instruction inside
 *   the bracket inflates the measured round trip, and the minimum-delay filter can only pick the least
 *   contaminated sample it is given, not manufacture a clean one.
 * * **The server's own receive and send instants are used, not one timestamp for both.** The endpoint
 *   returns `t2_ns` and `t3_ns` separately so its own handling time is excluded from the delay rather
 *   than charged to the network. Collapsing them would bias the offset by half the server's turnaround.
 * * **A failure is a failure, not a zero.** Returning a fabricated exchange on a timeout would put an
 *   invented offset into `clock.tsv`, and gate G4 would then pass on evidence that does not exist.
 */
class LabTimeGateway(
    private val users: UserRepository,
) : ReferenceClock {

    override val source: String = "http/api-lab-time"

    override suspend fun exchange(): Result<ClockExchange> {
        val token = users.getCurrentUser()?.token
        if (token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("not authenticated"))
        }
        return runCatching {
            val t1 = monotonicNanos()
            val response = KtorClient.client.get(PATH) {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }.body<LabTimeResponse>()
            val t4 = monotonicNanos()
            if (response.receiveNanos <= 0L || response.sendNanos <= 0L) {
                throw IllegalStateException("server returned no usable timestamps")
            }
            if (response.sendNanos < response.receiveNanos) {
                // The server's send cannot precede its receive. A reply that says so is a clock that
                // stepped mid-request, and folding it in would move the estimate by the step.
                throw IllegalStateException("server timestamps are out of order")
            }
            ClockExchange(
                t1Nanos = t1,
                t2Nanos = response.receiveNanos,
                t3Nanos = response.sendNanos,
                t4Nanos = t4,
            )
        }
    }

    private companion object {
        const val PATH = "/api/lab/time"
    }
}

/** `GET /api/lab/time`. Field names are the backend's; see `LabController::time`. */
@Serializable
private data class LabTimeResponse(
    @SerialName("t2_ns") val receiveNanos: Long = 0,
    @SerialName("t3_ns") val sendNanos: Long = 0,
    val source: String = "",
)
