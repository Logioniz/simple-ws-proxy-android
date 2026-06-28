package com.logioniz.simplewsproxy.proxy.vpn

import java.io.FileOutputStream

/**
 * Serializes outbound IP packets to the TUN file descriptor. Multiple threads
 * (per-flow TCP output, DNS replies) write here concurrently, so every write of
 * a whole packet is guarded by a single lock.
 */
class TunWriter(private val out: FileOutputStream) {

    private val lock = Any()

    fun write(packet: ByteArray) {
        synchronized(lock) {
            runCatching { out.write(packet) }
        }
    }
}
