package sk.martinvanco.monad.core.domain.udp

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UdpService(
    private val udpSender: UdpSender
) {
    private val _config = MutableStateFlow(UdpConfig())
    val config: StateFlow<UdpConfig> = _config.asStateFlow()

    private var sequence = 0

    fun updateHost(host: String) {
        _config.value = _config.value.copy(host = host.trim())
    }

    fun updatePort(port: String) {
        _config.value = _config.value.copy(
            port = port.filter { it.isDigit() }.take(5)
        )
    }

    suspend fun sendData(payload: ByteArray): Result<Int> {
        val current = _config.value
        val port = current.port.toIntOrNull()
        if (current.host.isEmpty() || port == null || port !in 1..65535) {
            return Result.failure(IllegalArgumentException("Invalid host or port"))
        }
        return udpSender.send(current.host, port, payload)
    }

    suspend fun sendTextPoc(message: String): Result<Int> {
        val tagged = "$message seq=${++sequence}".encodeToByteArray()
        Napier.d("UDP POC: sending '$message' (seq=$sequence)")
        return sendData(tagged)
    }
}

data class UdpConfig(
    val host: String = "10.0.2.2",
    val port: String = "9999"
)
