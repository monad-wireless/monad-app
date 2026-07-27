package sk.martinvanco.monad.lab.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.aakira.napier.Napier
import sk.martinvanco.monad.core.domain.wifi_v2.WifiConnectionServiceV2
import sk.martinvanco.monad.core.util.ContextProvider
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * UDP socket pinned to the Wi-Fi network.
 *
 * Pinning order, most specific first:
 *
 * 1. the app-scoped network from `WifiNetworkSpecifier` ([WifiConnectionServiceV2.boundNetwork]) —
 *    this is the only one that reaches an experiment AP with no internet route;
 * 2. any transport-Wi-Fi network the connectivity manager knows about;
 * 3. unpinned, reported as such so the operator sees it.
 *
 * `Network.bindSocket()` is what makes (1) work at all. Without it Android routes the datagrams
 * over the default network — usually cellular — and the observer node never sees a frame while the
 * app reports success.
 */
actual class LabDatagramSocket actual constructor() {

    private var socket: DatagramSocket? = null
    private var target: InetSocketAddress? = null
    private var description: String = ""

    private val connectivity: ConnectivityManager
        get() = ContextProvider.getContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    actual fun open(host: String, port: Int, interfaceHint: String?): Result<Unit> = runCatching {
        close()
        val created = DatagramSocket()
        created.reuseAddress = true

        val network = resolveWifiNetwork()
        if (network != null) {
            network.bindSocket(created)
            description = "wifi network ${network.networkHandle}"
            Napier.i("[lab] socket pinned to $description")
        } else {
            description = "unpinned (no wifi network available)"
            Napier.w("[lab] socket NOT pinned — datagrams may leave over cellular")
        }

        // Resolve on the pinned network too; the default resolver would use the default network,
        // and a collector addressed by hostname on an isolated AP would fail to resolve.
        val address = network?.getByName(host) ?: InetAddress.getByName(host)
        target = InetSocketAddress(address, port)
        socket = created
        Unit
    }

    actual fun send(bytes: ByteArray): Result<Int> = runCatching {
        val active = socket ?: throw IllegalStateException("socket not open")
        val destination = target ?: throw IllegalStateException("no target")
        active.send(DatagramPacket(bytes, bytes.size, destination))
        bytes.size
    }

    actual fun receive(timeoutMillis: Long, bufferSize: Int): Result<ByteArray?> = runCatching {
        val active = socket ?: throw IllegalStateException("socket not open")
        active.soTimeout = timeoutMillis.toInt().coerceAtLeast(1)
        val buffer = ByteArray(bufferSize)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            active.receive(packet)
            buffer.copyOf(packet.length)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    actual fun close() {
        runCatching { socket?.close() }
        socket = null
        target = null
        description = ""
    }

    actual fun boundInterfaceDescription(): String = description

    private fun resolveWifiNetwork(): Network? {
        WifiConnectionServiceV2.boundNetwork?.let { return it }
        return runCatching {
            connectivity.allNetworks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull()
    }
}
