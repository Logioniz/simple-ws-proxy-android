package com.logioniz.simplewsproxy.proxy

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds the `Time` / `Authentication` headers for the WebSocket handshake,
 * matching simple-ws-proxy's [Authenticator].
 *
 * token = HMAC-SHA256(session_key, time).hex()
 * session_key = HMAC-SHA256(secret_key, time)
 */
class Authenticator(private val secretKey: String) {

    /** Returns the timestamp used and the headers map keyed to it. */
    fun makeHeaders(): Pair<String, Map<String, String>> {
        val timeValue = (System.currentTimeMillis() / 1000L).toString()
        val token = makeToken(timeValue)
        val headers = mapOf(
            "Time" to timeValue,
            "Authentication" to "$AUTH_TYPE $token",
        )
        return timeValue to headers
    }

    fun makeCipher(timeValue: String): Cipher = Cipher(secretKey, timeValue)

    private fun makeToken(timeValue: String): String {
        val sessionKey = Cipher(secretKey, timeValue).sessionKey
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        return mac.doFinal(timeValue.toByteArray(Charsets.UTF_8)).toHex()
    }

    companion object {
        private const val AUTH_TYPE = "SimpleProxy"
    }
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private const val HEX = "0123456789abcdef"
