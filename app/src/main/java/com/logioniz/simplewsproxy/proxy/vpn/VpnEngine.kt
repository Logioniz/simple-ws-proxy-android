package com.logioniz.simplewsproxy.proxy.vpn

import com.logioniz.simplewsproxy.proxy.Logs
import com.logioniz.simplewsproxy.proxy.vpn.Packets.SYN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** Opens one tunnel relay to [target], returning the [Job] running it. */
fun interface Tunnel {
    fun open(target: String, input: InputStream, output: OutputStream, onClose: () -> Unit): Job
}

/**
 * The tun2socks core. Reads IP packets from the TUN file descriptor and routes
 * them: IPv4 TCP flows go through per-connection [TcpConnection]s, IPv4 DNS
 * (UDP:53) through [DnsForwarder]; everything else (other UDP, IPv6) is dropped
 * — the tunnel only carries TCP, so apps fall back to TCP/IPv4.
 */
class VpnEngine(
    private val tunIn: FileInputStream,
    private val tun: TunWriter,
    private val tunnel: Tunnel,
    scope: CoroutineScope,
) {

    private val connections = ConcurrentHashMap<String, TcpConnection>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val dns = DnsForwarder(tunnel, tun, scope)

    /** Blocking read loop. Returns when the TUN is closed. */
    fun loop() {
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = try {
                tunIn.read(buf)
            } catch (_: Exception) {
                break // TUN closed -> stop
            }
            if (n <= 0) break
            try {
                dispatch(buf)
            } catch (e: Exception) {
                Logs.add("Packet error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun dispatch(packet: ByteArray) {
        if (Packets.version(packet) != 4) return // drop IPv6 (captured but not tunnelable)
        val ip = Packets.parseIpv4(packet)
        when (ip.protocol) {
            Packets.PROTO_TCP -> handleTcp(packet, ip)
            Packets.PROTO_UDP -> {
                val udp = Packets.parseUdp(packet, ip)
                if (udp.destPort == DNS_PORT) dns.handle(ip, udp, packet)
                // other UDP (incl. QUIC) is dropped
            }
        }
    }

    private fun handleTcp(packet: ByteArray, ip: Packets.Ipv4) {
        val tcp = Packets.parseTcp(packet, ip)
        val key = "${ip.sourceIp}:${tcp.srcPort}>${ip.destIp}:${tcp.destPort}"
        val existing = connections[key]
        if (existing != null) {
            existing.onAppPacket(tcp, packet)
            return
        }
        if (!tcp.has(SYN)) return // unknown flow, not an opener -> drop

        val conn = TcpConnection(
            srcIp = ip.sourceIp, srcPort = tcp.srcPort,
            destIp = ip.destIp, destPort = tcp.destPort,
            clientIsn = tcp.seq,
            clientWindow = tcp.window,
            tun = tun,
            onClosed = {
                connections.remove(key)
                jobs.remove(key)?.cancel()
            },
        )
        connections[key] = conn
        conn.openHandshake()
        Logs.add("CONNECT ${conn.target}")
        jobs[key] = tunnel.open(
            conn.target,
            conn.appToRemote.input,
            conn.remoteToApp,
            onClose = { conn.onRemoteClosed() },
        )
    }

    fun stop() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        connections.clear()
        runCatching { tunIn.close() }
    }

    private companion object {
        const val BUFFER_SIZE = 32767
        const val DNS_PORT = 53
    }
}
