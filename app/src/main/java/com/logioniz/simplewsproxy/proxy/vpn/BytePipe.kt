package com.logioniz.simplewsproxy.proxy.vpn

import java.io.InputStream

/**
 * A blocking byte pipe with an [InputStream] face. The VPN engine thread pushes
 * bytes with [feed] (app -> remote direction of a TCP flow); the
 * [com.logioniz.simplewsproxy.proxy.TunnelConnection] pump coroutine drains them
 * by reading [input]. [closeWrite] signals end-of-stream (the app sent FIN).
 *
 * Producer and consumer are always different threads, so the simple
 * monitor-based hand-off below cannot self-deadlock.
 */
class BytePipe {

    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var head: ByteArray? = null
    private var headPos = 0
    private var writeClosed = false
    private var readClosed = false

    /** Copy `[off, off+len)` of [data] into the pipe and wake the reader. */
    fun feed(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            if (writeClosed || readClosed) return
            chunks.addLast(data.copyOfRange(off, off + len))
            lock.notifyAll()
        }
    }

    /** Signal end-of-stream to the reader. */
    fun closeWrite() {
        synchronized(lock) {
            writeClosed = true
            lock.notifyAll()
        }
    }

    val input: InputStream = object : InputStream() {
        private val one = ByteArray(1)

        override fun read(): Int {
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(lock) {
                while (true) {
                    val cur = head ?: chunks.removeFirstOrNull()?.also { head = it; headPos = 0 }
                    if (cur != null) {
                        val available = cur.size - headPos
                        val n = minOf(available, len)
                        System.arraycopy(cur, headPos, b, off, n)
                        headPos += n
                        if (headPos >= cur.size) head = null
                        return n
                    }
                    if (writeClosed || readClosed) return -1
                    lock.wait()
                }
            }
        }

        override fun close() {
            synchronized(lock) {
                readClosed = true
                head = null
                chunks.clear()
                lock.notifyAll()
            }
        }
    }
}
