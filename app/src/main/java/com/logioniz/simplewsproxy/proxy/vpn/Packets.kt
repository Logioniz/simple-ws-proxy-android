package com.logioniz.simplewsproxy.proxy.vpn

/**
 * Minimal IPv4 / TCP / UDP packet parsing and building for the userspace
 * tun2socks engine. Only what the engine needs: IPv4 with TCP or UDP payloads,
 * no IP options, no IPv6 (those packets are dropped by [VpnEngine]).
 *
 * Addresses are carried around as [Int] (big-endian 32-bit), ports and 16-bit
 * fields as [Int], and 32-bit sequence numbers as [Long] (to stay unsigned).
 */
object Packets {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    // TCP flag bits.
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    private const val IP_HEADER_LEN = 20
    private const val TCP_HEADER_LEN = 20
    private const val UDP_HEADER_LEN = 8

    /** IP version stored in the high nibble of the first byte. */
    fun version(packet: ByteArray): Int = (packet[0].toInt() ushr 4) and 0x0F

    // ---- IPv4 ----

    class Ipv4(
        val sourceIp: Int,
        val destIp: Int,
        val protocol: Int,
        val headerLen: Int,
        val totalLen: Int,
    )

    fun parseIpv4(packet: ByteArray): Ipv4 {
        val headerLen = (packet[0].toInt() and 0x0F) * 4
        val totalLen = readU16(packet, 2)
        val protocol = packet[9].toInt() and 0xFF
        val sourceIp = readU32(packet, 12)
        val destIp = readU32(packet, 16)
        return Ipv4(sourceIp, destIp, protocol, headerLen, totalLen)
    }

    // ---- TCP ----

    class Tcp(
        val srcPort: Int,
        val destPort: Int,
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val window: Int,
        val payloadOffset: Int,
        val payloadLen: Int,
    ) {
        fun has(flag: Int): Boolean = flags and flag != 0
    }

    /** Parse the TCP header located at [ipHeaderLen] inside an IPv4 [packet]. */
    fun parseTcp(packet: ByteArray, ip: Ipv4): Tcp {
        val off = ip.headerLen
        val srcPort = readU16(packet, off)
        val destPort = readU16(packet, off + 2)
        val seq = readU32(packet, off + 4).toLong() and 0xFFFFFFFFL
        val ack = readU32(packet, off + 8).toLong() and 0xFFFFFFFFL
        val dataOffset = ((packet[off + 12].toInt() and 0xF0) ushr 4) * 4
        val flags = packet[off + 13].toInt() and 0xFF
        val window = readU16(packet, off + 14)
        val payloadOffset = off + dataOffset
        val payloadLen = ip.totalLen - ip.headerLen - dataOffset
        return Tcp(srcPort, destPort, seq, ack, flags, window, payloadOffset, payloadLen.coerceAtLeast(0))
    }

    /**
     * Build a complete IPv4+TCP packet (no options). [payload] in
     * `[payloadOff, payloadOff + payloadLen)` is the TCP segment data, possibly
     * empty.
     */
    fun buildTcp(
        srcIp: Int,
        destIp: Int,
        srcPort: Int,
        destPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray = EMPTY,
        payloadOff: Int = 0,
        payloadLen: Int = 0,
    ): ByteArray {
        val tcpLen = TCP_HEADER_LEN + payloadLen
        val total = IP_HEADER_LEN + tcpLen
        val packet = ByteArray(total)

        writeIpv4Header(packet, srcIp, destIp, PROTO_TCP, total)

        val o = IP_HEADER_LEN
        writeU16(packet, o, srcPort)
        writeU16(packet, o + 2, destPort)
        writeU32(packet, o + 4, seq.toInt())
        writeU32(packet, o + 8, ack.toInt())
        packet[o + 12] = ((TCP_HEADER_LEN / 4) shl 4).toByte() // data offset, no options
        packet[o + 13] = flags.toByte()
        writeU16(packet, o + 14, window)
        // checksum (o+16) left zero for now; urgent pointer (o+18) zero
        if (payloadLen > 0) System.arraycopy(payload, payloadOff, packet, o + TCP_HEADER_LEN, payloadLen)

        writeU16(packet, o + 16, transportChecksum(packet, srcIp, destIp, PROTO_TCP, o, tcpLen))
        writeU16(packet, 10, ipHeaderChecksum(packet))
        return packet
    }

    // ---- UDP ----

    class Udp(
        val srcPort: Int,
        val destPort: Int,
        val payloadOffset: Int,
        val payloadLen: Int,
    )

    fun parseUdp(packet: ByteArray, ip: Ipv4): Udp {
        val off = ip.headerLen
        val srcPort = readU16(packet, off)
        val destPort = readU16(packet, off + 2)
        val udpLen = readU16(packet, off + 4)
        val payloadLen = (udpLen - UDP_HEADER_LEN).coerceAtLeast(0)
        return Udp(srcPort, destPort, off + UDP_HEADER_LEN, payloadLen)
    }

    /** Build a complete IPv4+UDP packet carrying [payloadLen] bytes from [payload]. */
    fun buildUdp(
        srcIp: Int,
        destIp: Int,
        srcPort: Int,
        destPort: Int,
        payload: ByteArray,
        payloadOff: Int,
        payloadLen: Int,
    ): ByteArray {
        val udpLen = UDP_HEADER_LEN + payloadLen
        val total = IP_HEADER_LEN + udpLen
        val packet = ByteArray(total)

        writeIpv4Header(packet, srcIp, destIp, PROTO_UDP, total)

        val o = IP_HEADER_LEN
        writeU16(packet, o, srcPort)
        writeU16(packet, o + 2, destPort)
        writeU16(packet, o + 4, udpLen)
        // checksum (o+6) left zero for now
        if (payloadLen > 0) System.arraycopy(payload, payloadOff, packet, o + UDP_HEADER_LEN, payloadLen)

        val sum = transportChecksum(packet, srcIp, destIp, PROTO_UDP, o, udpLen)
        // A zero UDP checksum means "not computed"; per RFC use 0xFFFF instead.
        writeU16(packet, o + 6, if (sum == 0) 0xFFFF else sum)
        writeU16(packet, 10, ipHeaderChecksum(packet))
        return packet
    }

    fun ipToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    // ---- Internals ----

    private fun writeIpv4Header(packet: ByteArray, srcIp: Int, destIp: Int, protocol: Int, totalLen: Int) {
        packet[0] = 0x45 // version 4, IHL 5
        packet[1] = 0 // DSCP/ECN
        writeU16(packet, 2, totalLen)
        writeU16(packet, 4, 0) // identification
        writeU16(packet, 6, 0x4000) // flags: don't fragment
        packet[8] = 64 // TTL
        packet[9] = protocol.toByte()
        // header checksum (10) filled by caller after the rest is in place
        writeU32(packet, 12, srcIp)
        writeU32(packet, 16, destIp)
    }

    private fun ipHeaderChecksum(packet: ByteArray): Int {
        writeU16(packet, 10, 0)
        return checksum16(0, packet, 0, IP_HEADER_LEN)
    }

    /** TCP/UDP checksum over the pseudo-header plus the transport segment. */
    private fun transportChecksum(
        packet: ByteArray,
        srcIp: Int,
        destIp: Int,
        protocol: Int,
        segOffset: Int,
        segLen: Int,
    ): Int {
        var sum = 0
        sum += (srcIp ushr 16) and 0xFFFF
        sum += srcIp and 0xFFFF
        sum += (destIp ushr 16) and 0xFFFF
        sum += destIp and 0xFFFF
        sum += protocol and 0xFFFF
        sum += segLen and 0xFFFF
        return checksum16(sum, packet, segOffset, segLen)
    }

    /** Fold a running 16-bit one's-complement sum over [data] in `[off, off+len)`. */
    private fun checksum16(initial: Int, data: ByteArray, off: Int, len: Int): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8 // odd trailing byte, padded with 0
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xFF).toByte()
        b[off + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 24) and 0xFF).toByte()
        b[off + 1] = ((value ushr 16) and 0xFF).toByte()
        b[off + 2] = ((value ushr 8) and 0xFF).toByte()
        b[off + 3] = (value and 0xFF).toByte()
    }

    private val EMPTY = ByteArray(0)
}
