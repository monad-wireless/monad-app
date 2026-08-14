package sk.martinvanco.monad.device.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.device.data.dto.DeviceDetailDto

/**
 * Reads the public record for a scanned device label (IP-128).
 *
 * No token is attached, and that is the point: `GET /api/device/{slug}` is
 * whitelisted as `PUBLIC_ACCESS` so a stranger who scans a sticker can see what
 * the box is without an account. Sending a stale or absent token here would be
 * the difference between a working page and a 401 for exactly the audience this
 * feature exists for.
 */
class DeviceService(private val ktorClient: KtorClient) {

    suspend fun getDevice(slug: String): DeviceDetailDto {
        val response = ktorClient.client.get("/api/device/$slug")
        return response.body<DeviceDetailDto>()
    }
}
