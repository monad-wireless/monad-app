package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.IPPROTO_IP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.if_nametoindex
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_t
import platform.posix.timeval
import platform.posix.uint32_tVar
import platform.posix.connect as posixConnect

/**
 * iOS datagram socket pinned to the Wi-Fi interface with `IP_BOUND_IF`.
 *
 * A BSD socket rather than `NWConnection` deliberately. The emitter needs to hand a datagram to
 * the kernel at a precise instant chosen by its own scheduler; Network.framework's send is
 * queue-dispatched and completion-based, which inserts a variable delay between "the pacing loop
 * decided to send" and "the packet left" — and that delay is measured in exactly the units the
 * experiment is trying to resolve. `send(2)` on a connected UDP socket has no such gap.
 *
 * `IP_BOUND_IF` is the iOS analogue of Android's `Network.bindSocket()`: without it iOS routes app
 * traffic over whichever interface it prefers — normally cellular when the joined Wi-Fi network
 * has no internet route, which is the usual state of an experiment AP.
 */
@OptIn(ExperimentalForeignApi::class)
actual class LabDatagramSocket actual constructor() {

    private var fd: Int = -1
    private var description: String = ""

    actual fun open(host: String, port: Int, interfaceHint: String?): Result<Unit> = runCatching {
        close()

        val descriptor = socket(AF_INET, SOCK_DGRAM, 0)
        if (descriptor < 0) throw IllegalStateException("socket() failed")

        // Pin before connect: the route is resolved at connect time, so binding afterwards would
        // leave the first datagrams on the wrong interface.
        val iface = interfaceHint ?: DEFAULT_WIFI_INTERFACE
        val index = if_nametoindex(iface)
        if (index > 0u) {
            memScoped {
                val value = alloc<uint32_tVar>()
                value.value = index
                val rc = setsockopt(
                    descriptor,
                    IPPROTO_IP,
                    IP_BOUND_IF,
                    value.ptr,
                    sizeOf<uint32_tVar>().convert(),
                )
                if (rc == 0) {
                    description = "$iface (IP_BOUND_IF index $index)"
                } else {
                    description = "unpinned (IP_BOUND_IF rejected on $iface)"
                }
            }
        } else {
            description = "unpinned (interface $iface not found)"
        }

        if (description.startsWith("unpinned")) {
            Napier.w("[lab] socket NOT pinned — $description; datagrams may leave over cellular")
        } else {
            Napier.i("[lab] socket pinned to $description")
        }

        val octets = parseIpv4(host)
        if (octets == null) {
            close(descriptor)
            throw IllegalArgumentException(
                "collector host must be a literal IPv4 address (got '$host') — an experiment AP " +
                    "normally has no DNS, so name resolution would fail on the pinned interface"
            )
        }

        memScoped {
            val address = alloc<sockaddr_in>()
            address.sin_len = sizeOf<sockaddr_in>().toUByte()
            address.sin_family = AF_INET.convert()
            // Network byte order written explicitly rather than via htons/inet_pton, neither of
            // which Kotlin/Native exposes for Apple targets. Apple platforms are little-endian, so
            // a byte-swapped host value lands as big-endian in memory.
            address.sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()
            address.sin_addr.s_addr =
                (octets[0].toUInt() or
                    (octets[1].toUInt() shl 8) or
                    (octets[2].toUInt() shl 16) or
                    (octets[3].toUInt() shl 24))
            if (posixConnect(descriptor, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0) {
                close(descriptor)
                throw IllegalStateException("connect() to $host:$port failed")
            }
        }

        fd = descriptor
        Unit
    }

    actual fun send(bytes: ByteArray): Result<Int> = runCatching {
        val descriptor = fd
        if (descriptor < 0) throw IllegalStateException("socket not open")
        val pinned = bytes.pin()
        try {
            val written = send(descriptor, pinned.addressOf(0), bytes.size.convert(), 0)
            if (written < 0) throw IllegalStateException("send() failed")
            written.toInt()
        } finally {
            pinned.unpin()
        }
    }

    actual fun receive(timeoutMillis: Long, bufferSize: Int): Result<ByteArray?> = runCatching {
        val descriptor = fd
        if (descriptor < 0) throw IllegalStateException("socket not open")

        memScoped {
            val timeout = alloc<timeval>()
            timeout.tv_sec = (timeoutMillis / 1000).convert()
            timeout.tv_usec = ((timeoutMillis % 1000) * 1000).convert()
            setsockopt(
                descriptor,
                SOL_SOCKET,
                SO_RCVTIMEO,
                timeout.ptr,
                sizeOf<timeval>().convert<socklen_t>(),
            )

            val buffer = allocArray<ByteVar>(bufferSize)
            val read = recv(descriptor, buffer, bufferSize.convert(), 0)
            // A negative return here is normally EAGAIN from the receive timeout, which is an
            // ordinary outcome for the clock exchange rather than an error.
            if (read <= 0) null else buffer.readBytes(read.toInt())
        }
    }

    actual fun close() {
        if (fd >= 0) close(fd)
        fd = -1
        description = ""
    }

    actual fun boundInterfaceDescription(): String = description

    /** `a.b.c.d` to four octets; null when the string is not a literal IPv4 address. */
    private fun parseIpv4(host: String): IntArray? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }

    private companion object {
        /** `en0` is Wi-Fi on every iPhone; `pdp_ip0` is cellular. */
        const val DEFAULT_WIFI_INTERFACE = "en0"

        /**
         * `IP_BOUND_IF` from Darwin's `<netinet/in.h>`. Not exposed by the Kotlin/Native posix
         * package, so it is declared here with the value from the SDK header.
         */
        const val IP_BOUND_IF = 25
    }
}
