package sk.martinvanco.monad.core.domain.udp

import io.github.aakira.napier.Napier
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.io.Buffer

class UdpSender {

    suspend fun send(host: String, port: Int, payload: ByteArray): Result<Int> = runCatching {
        val selector = SelectorManager(Dispatchers.Default)
        val socket = aSocket(selector).udp().bind()
        try {
            val buffer = Buffer().apply { write(payload) }
            socket.send(Datagram(buffer, InetSocketAddress(host, port)))
            Napier.i("UDP: sent ${payload.size} bytes to $host:$port")
            payload.size
        } finally {
            socket.close()
            selector.close()
        }
    }
}
