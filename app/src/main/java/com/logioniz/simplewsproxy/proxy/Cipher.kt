package com.logioniz.simplewsproxy.proxy

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * XOR stream cipher keyed by a per-session secret.
 *
 * The session key is derived once via HMAC-SHA256(secret_key, time_value) so
 * every (secret_key, timestamp) pair yields a unique 32-byte key stream. XOR is
 * applied starting from offset 0 for every message, mirroring the Python
 * implementation where each WebSocket message is encrypted independently.
 */
class Cipher(secretKey: String, timeValue: String) {

    val sessionKey: ByteArray = run {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.doFinal(timeValue.toByteArray(Charsets.UTF_8))
    }

    /** XOR-process [length] bytes of [data]. Encryption and decryption are identical. */
    fun process(data: ByteArray, length: Int = data.size): ByteArray {
        val key = sessionKey
        val keyLen = key.size
        val out = ByteArray(length)
        for (i in 0 until length) {
            out[i] = (data[i].toInt() xor key[i % keyLen].toInt()).toByte()
        }
        return out
    }
}
