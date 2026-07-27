package sk.martinvanco.monad.lab.domain

/**
 * A UDP socket **pinned to the Wi-Fi interface**.
 *
 * This is not a convenience wrapper — the pinning is the entire point. Both platforms will happily
 * route an app's datagrams over cellular while the UI reports a successful Wi-Fi association, so an
 * unpinned socket produces the worst possible failure: the app says "connected", the observer node
 * sees nothing, and the operator debugs the radio.
 *
 * - iOS: BSD socket with `IP_BOUND_IF` set to the Wi-Fi interface index.
 * - Android: `Network.bindSocket()` on the `WifiNetworkSpecifier` network.
 *
 * One socket serves both the paced data stream and the clock exchange, deliberately: the offset is
 * then measured over exactly the path the data takes.
 */
expect class LabDatagramSocket() {

    /**
     * Open and pin. [interfaceHint] is a platform hint — an interface name such as `en0` on iOS;
     * ignored on Android, where the bound `Network` is resolved from the connectivity manager
     * instead.
     */
    fun open(host: String, port: Int, interfaceHint: String? = null): Result<Unit>

    /** Send one datagram. Returns bytes written. */
    fun send(bytes: ByteArray): Result<Int>

    /**
     * Blocking receive with a timeout. Returns null on timeout (which is an ordinary outcome for
     * the clock exchange, not an error).
     */
    fun receive(timeoutMillis: Long, bufferSize: Int = 1500): Result<ByteArray?>

    fun close()

    /** What the socket actually bound to, for the session sidecar. Empty until [open] succeeds. */
    fun boundInterfaceDescription(): String
}

/** Outcome of a pinning attempt, surfaced in the lab console so a silent fallback is impossible. */
data class SocketBinding(
    val isOpen: Boolean,
    val isPinned: Boolean,
    val description: String,
) {
    companion object {
        val CLOSED = SocketBinding(isOpen = false, isPinned = false, description = "closed")
    }
}
