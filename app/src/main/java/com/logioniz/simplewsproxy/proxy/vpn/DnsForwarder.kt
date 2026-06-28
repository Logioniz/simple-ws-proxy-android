package com.logioniz.simplewsproxy.proxy.vpn

import com.logioniz.simplewsproxy.proxy.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream

/**
 * Forwards captured DNS datagrams (UDP port 53) over the TCP-only tunnel using
 * DNS-over-TCP (RFC 7766): a 2-byte big-endian length prefix followed by the
 * message. Each query is a one-shot tunnel: send the query, read one response,
 * write it back into the TUN as a UDP datagram, then close the tunnel.
 */
class DnsForwarder(
    private val tunnel: Tunnel,
    private val tun: TunWriter,
    private val scope: CoroutineScope,
) {

    fun handle(ip: Packets.Ipv4, udp: Packets.Udp, packet: ByteArray) {
        if (udp.payloadLen <= 0) return

        // Build the length-prefixed query for DNS-over-TCP.
        val prefixed = ByteArray(2 + udp.payloadLen)
        prefixed[0] = ((udp.payloadLen ushr 8) and 0xFF).toByte()
        prefixed[1] = (udp.payloadLen and 0xFF).toByte()
        System.arraycopy(packet, udp.payloadOffset, prefixed, 2, udp.payloadLen)

        val queryPipe = BytePipe()
        queryPipe.feed(prefixed, 0, prefixed.size)

        val queriedName = runCatching { parseQName(packet, udp.payloadOffset, udp.payloadLen) }.getOrNull()

        val collector = DnsResponseCollector { response ->
            tun.write(
                Packets.buildUdp(
                    srcIp = ip.destIp, destIp = ip.sourceIp,
                    srcPort = udp.destPort, destPort = udp.srcPort,
                    payload = response, payloadOff = 0, payloadLen = response.size,
                ),
            )
            logResolved(queriedName, response)
        }

        val target = "${Packets.ipToString(ip.destIp)}:${udp.destPort}"
        tunnel.open(target, queryPipe.input, collector, onClose = {})
        // Once the response is in, close the query side: the pump hits EOF and
        // closes the WebSocket gracefully (no "Canceled" error in the logs).
        collector.onDone = { queryPipe.closeWrite() }

        // Safety net: never let a stuck DNS tunnel linger.
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(DNS_TIMEOUT_MS)
            queryPipe.closeWrite()
        }
    }

    /**
     * Accumulates the DNS-over-TCP response and fires [onResponse] with the bare
     * DNS message (length prefix stripped) once it is complete.
     */
    private class DnsResponseCollector(
        private val onResponse: (ByteArray) -> Unit,
    ) : OutputStream() {

        var onDone: (() -> Unit)? = null

        private val buffer = java.io.ByteArrayOutputStream()
        private var expected = -1
        private var finished = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (finished) return
            buffer.write(b, off, len)
            val data = buffer.toByteArray()
            if (expected < 0 && data.size >= 2) {
                expected = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            }
            if (expected in 0..(data.size - 2)) {
                finished = true
                onResponse(data.copyOfRange(2, 2 + expected))
                onDone?.invoke()
            }
        }
    }

    private companion object {
        const val DNS_TIMEOUT_MS = 5_000L
        private const val DNS_HEADER_LEN = 12

        /** Read the first question's QNAME from a DNS query (no name compression there). */
        fun parseQName(packet: ByteArray, off: Int, len: Int): String {
            val sb = StringBuilder()
            var i = off + DNS_HEADER_LEN
            val end = off + len
            while (i < end) {
                val label = packet[i].toInt() and 0xFF
                if (label == 0 || label and 0xC0 != 0) break // root, or unexpected pointer
                i++
                if (i + label > end) break
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(packet, i, label, Charsets.US_ASCII))
                i += label
            }
            return sb.toString()
        }

        /** Log the resolution if the DNS server answered without an error code. */
        fun logResolved(name: String?, response: ByteArray) {
            if (response.size < DNS_HEADER_LEN) return
            val rcode = response[3].toInt() and 0x0F
            val answers = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
            val host = name?.takeIf { it.isNotEmpty() } ?: "?"
            if (rcode == 0) {
                Logs.add("DNS $host -> $answers ${if (answers == 1) "answer" else "answers"}")
            } else {
                Logs.add("DNS $host failed (rcode $rcode)")
            }
        }
    }
}
