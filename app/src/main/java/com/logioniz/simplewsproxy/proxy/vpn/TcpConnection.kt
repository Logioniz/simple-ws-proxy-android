package com.logioniz.simplewsproxy.proxy.vpn

import com.logioniz.simplewsproxy.proxy.vpn.Packets.ACK
import com.logioniz.simplewsproxy.proxy.vpn.Packets.FIN
import com.logioniz.simplewsproxy.proxy.vpn.Packets.PSH
import com.logioniz.simplewsproxy.proxy.vpn.Packets.RST
import com.logioniz.simplewsproxy.proxy.vpn.Packets.SYN
import java.io.OutputStream

/**
 * A single userspace TCP flow bridged to the WebSocket tunnel.
 *
 * The device's TCP stack is the active opener; we are the passive side. We only
 * ever talk to the app across a loss-free TUN, so the state machine is
 * deliberately tiny: no retransmission and no out-of-order buffering are needed.
 * The one thing we must respect is the app's advertised receive window — segments
 * sent past it would be dropped by the app's stack and, with no retransmission,
 * would hang the flow. So [remoteToApp] blocks until the window has room.
 *
 * Bytes flow as: app -> [appToRemote] (BytePipe) -> TunnelConnection input;
 * TunnelConnection output -> [remoteToApp] (OutputStream) -> TCP segments -> TUN.
 */
class TcpConnection(
    private val srcIp: Int,
    private val srcPort: Int,
    private val destIp: Int,
    private val destPort: Int,
    clientIsn: Long,
    clientWindow: Int,
    private val tun: TunWriter,
    private val onClosed: () -> Unit,
) {

    private val lock = Any()

    /** Next sequence number we will put on a byte we send to the app. */
    private var sndNxt: Long = System.nanoTime() and MASK

    /** Oldest unacknowledged sequence number we've sent to the app. */
    private var sndUna: Long = sndNxt

    /** The app's advertised receive window (no window scaling is negotiated). */
    private var peerWindow: Int = clientWindow.coerceAtLeast(1)

    /** Next sequence number we expect from the app. */
    private var rcvNxt: Long = (clientIsn + 1) and MASK

    private var finReceived = false
    private var finSent = false
    private var done = false

    val appToRemote = BytePipe()

    /** Tunnel output side: the tunnel writes remote bytes here; we segment them to TUN. */
    val remoteToApp: OutputStream = object : OutputStream() {
        private val one = ByteArray(1)

        override fun write(b: Int) {
            one[0] = b.toByte()
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            var remaining = len
            while (remaining > 0) {
                synchronized(lock) {
                    // Wait until the app's receive window can take at least one byte.
                    while (!done && windowRoom() <= 0) {
                        (lock as Object).wait(WINDOW_PROBE_MS)
                    }
                    if (done) return
                    val n = minOf(remaining, MSS, windowRoom())
                    tun.write(
                        Packets.buildTcp(
                            srcIp = destIp, destIp = srcIp,
                            srcPort = destPort, destPort = srcPort,
                            seq = sndNxt, ack = rcvNxt,
                            flags = ACK or PSH, window = WINDOW,
                            payload = b, payloadOff = pos, payloadLen = n,
                        ),
                    )
                    sndNxt = (sndNxt + n) and MASK
                    pos += n
                    remaining -= n
                }
            }
        }
    }

    /** Bytes we may still send before filling the app's advertised window. */
    private fun windowRoom(): Int {
        val inFlight = ((sndNxt - sndUna) and MASK).toInt()
        return peerWindow - inFlight
    }

    /** Send the initial SYN-ACK in response to the app's SYN. */
    fun openHandshake() {
        synchronized(lock) {
            tun.write(synAckPacket())
            sndNxt = (sndNxt + 1) and MASK // SYN consumes one sequence number
            sndUna = sndNxt
        }
    }

    /** Handle an inbound TCP segment from the app. [packet] is the full IP packet. */
    fun onAppPacket(tcp: Packets.Tcp, packet: ByteArray) {
        if (tcp.has(RST)) {
            abort()
            return
        }
        if (tcp.has(SYN)) {
            // Retransmitted SYN (our SYN-ACK was lost/not yet processed) -> resend it.
            synchronized(lock) { if (!done) tun.write(synAckPacket()) }
            return
        }

        synchronized(lock) {
            if (done) return

            // Track the app's window and what it has acknowledged, then wake the
            // sender that may be blocked waiting for window room.
            if (tcp.has(ACK) && seqGt(tcp.ack, sndUna)) sndUna = tcp.ack
            peerWindow = tcp.window
            (lock as Object).notifyAll()

            if (tcp.payloadLen > 0) {
                if (tcp.seq == rcvNxt) {
                    appToRemote.feed(packet, tcp.payloadOffset, tcp.payloadLen)
                    rcvNxt = (rcvNxt + tcp.payloadLen) and MASK
                }
                // ACK rcvNxt (also re-ACKs duplicates whose seq < rcvNxt).
                tun.write(ackPacket())
            }

            if (tcp.has(FIN)) {
                val finSeq = (tcp.seq + tcp.payloadLen) and MASK
                if (!finReceived && finSeq == rcvNxt) {
                    finReceived = true
                    rcvNxt = (rcvNxt + 1) and MASK // FIN consumes one sequence number
                    appToRemote.closeWrite()
                }
                tun.write(ackPacket())
                maybeFinish()
            }
        }
    }

    /** The tunnel side closed: half-close towards the app with a FIN. */
    fun onRemoteClosed() {
        synchronized(lock) {
            if (done || finSent) return
            finSent = true
            tun.write(
                Packets.buildTcp(
                    srcIp = destIp, destIp = srcIp,
                    srcPort = destPort, destPort = srcPort,
                    seq = sndNxt, ack = rcvNxt,
                    flags = FIN or ACK, window = WINDOW,
                ),
            )
            sndNxt = (sndNxt + 1) and MASK
            maybeFinish()
        }
    }

    /** Tear down immediately (app RST or fatal error), releasing the tunnel side. */
    fun abort() {
        synchronized(lock) {
            if (done) return
            done = true
            (lock as Object).notifyAll() // unblock a sender waiting for window room
            appToRemote.closeWrite()
            appToRemote.input.close()
        }
        onClosed()
    }

    private fun maybeFinish() {
        if (finSent && finReceived && !done) {
            done = true
            onClosed()
        }
    }

    private fun synAckPacket(): ByteArray = Packets.buildTcp(
        srcIp = destIp, destIp = srcIp,
        srcPort = destPort, destPort = srcPort,
        seq = sndNxt, ack = rcvNxt,
        flags = SYN or ACK, window = WINDOW,
    )

    private fun ackPacket(): ByteArray = Packets.buildTcp(
        srcIp = destIp, destIp = srcIp,
        srcPort = destPort, destPort = srcPort,
        seq = sndNxt, ack = rcvNxt,
        flags = ACK, window = WINDOW,
    )

    val target: String get() = "${Packets.ipToString(destIp)}:$destPort"

    private companion object {
        const val MASK = 0xFFFFFFFFL
        const val MSS = 1460 // MTU 1500 - 20 (IP) - 20 (TCP)
        const val WINDOW = 65535
        const val WINDOW_PROBE_MS = 200L

        /** Serial-number greater-than (RFC 1982), for 32-bit sequence wraparound. */
        fun seqGt(a: Long, b: Long): Boolean {
            val diff = (a - b) and MASK
            return diff != 0L && diff < 0x80000000L
        }
    }
}
