package com.logioniz.simplewsproxy.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Server-side SOCKS5 handshake (RFC 1928 + RFC 1929), CONNECT only.
 *
 * Mirrors [Socks5Server] from simple-ws-proxy: supports no-auth and
 * username/password auth. Returns the requested `"host:port"` target, or
 * `null` (after writing the appropriate failure reply) on any error.
 */
object Socks5 {

    private const val VERSION = 0x05
    private const val AUTH_NO_AUTH = 0x00
    private const val AUTH_USERNAME_PASSWORD = 0x02
    private const val AUTH_NO_ACCEPTABLE = 0xFF

    private const val CMD_CONNECT = 0x01

    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    private const val REP_SUCCESS = 0x00
    private const val REP_CMD_NOT_SUPPORTED = 0x07
    private const val REP_ATYP_NOT_SUPPORTED = 0x08

    // IPv4 0.0.0.0:0 bind address used in replies.
    private val BIND_ADDR = byteArrayOf(ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)

    fun handshake(
        input: InputStream,
        output: OutputStream,
        username: String?,
        password: String?,
    ): String? {
        // ---- Greeting ----
        val version = input.read()
        if (version != VERSION) return null
        val nmethods = input.read()
        if (nmethods <= 0) return null
        val methods = readExactly(input, nmethods) ?: return null

        val requireAuth = !username.isNullOrEmpty() || !password.isNullOrEmpty()

        if (requireAuth) {
            if (methods.none { it.toInt() and 0xFF == AUTH_USERNAME_PASSWORD }) {
                output.writeAndFlush(byteArrayOf(VERSION.toByte(), AUTH_NO_ACCEPTABLE.toByte()))
                return null
            }
            output.writeAndFlush(byteArrayOf(VERSION.toByte(), AUTH_USERNAME_PASSWORD.toByte()))

            // ---- RFC 1929 sub-negotiation ----
            val subVer = input.read()
            if (subVer != 0x01) return null
            val ulen = input.read()
            if (ulen < 0) return null
            val user = String(readExactly(input, ulen) ?: return null, Charsets.UTF_8)
            val plen = input.read()
            if (plen < 0) return null
            val pass = String(readExactly(input, plen) ?: return null, Charsets.UTF_8)

            if (user != (username ?: "") || pass != (password ?: "")) {
                output.writeAndFlush(byteArrayOf(0x01, 0x01)) // failure
                return null
            }
            output.writeAndFlush(byteArrayOf(0x01, REP_SUCCESS.toByte()))
        } else {
            if (methods.none { it.toInt() and 0xFF == AUTH_NO_AUTH }) {
                output.writeAndFlush(byteArrayOf(VERSION.toByte(), AUTH_NO_ACCEPTABLE.toByte()))
                return null
            }
            output.writeAndFlush(byteArrayOf(VERSION.toByte(), AUTH_NO_AUTH.toByte()))
        }

        // ---- Request ----
        val reqHeader = readExactly(input, 4) ?: return null
        val ver = reqHeader[0].toInt() and 0xFF
        val cmd = reqHeader[1].toInt() and 0xFF
        val atyp = reqHeader[3].toInt() and 0xFF
        if (ver != VERSION) return null

        if (cmd != CMD_CONNECT) {
            output.writeAndFlush(byteArrayOf(VERSION.toByte(), REP_CMD_NOT_SUPPORTED.toByte(), 0x00) + BIND_ADDR)
            return null
        }

        val host = readAddress(input, output, atyp) ?: return null
        val portBytes = readExactly(input, 2) ?: return null
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        // ---- Reply: success ----
        output.writeAndFlush(byteArrayOf(VERSION.toByte(), REP_SUCCESS.toByte(), 0x00) + BIND_ADDR)

        return "$host:$port"
    }

    private fun readAddress(input: InputStream, output: OutputStream, atyp: Int): String? {
        return when (atyp) {
            ATYP_IPV4 -> {
                val addr = readExactly(input, 4) ?: return null
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }

            ATYP_DOMAIN -> {
                val len = input.read()
                if (len < 0) return null
                String(readExactly(input, len) ?: return null, Charsets.UTF_8)
            }

            ATYP_IPV6 -> {
                val addr = readExactly(input, 16) ?: return null
                val sb = StringBuilder("[")
                for (i in 0 until 8) {
                    if (i > 0) sb.append(':')
                    val part = ((addr[i * 2].toInt() and 0xFF) shl 8) or (addr[i * 2 + 1].toInt() and 0xFF)
                    sb.append(String.format("%04x", part))
                }
                sb.append(']')
                sb.toString()
            }

            else -> {
                output.writeAndFlush(byteArrayOf(VERSION.toByte(), REP_ATYP_NOT_SUPPORTED.toByte(), 0x00) + BIND_ADDR)
                null
            }
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = try {
                input.read(buf, off, count - off)
            } catch (_: IOException) {
                return null
            }
            if (n < 0) return null
            off += n
        }
        return buf
    }

    private fun OutputStream.writeAndFlush(data: ByteArray) {
        write(data)
        flush()
    }
}
