package com.logioniz.simplewsproxy.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.Socket

/**
 * Relays a single SOCKS5 connection through a WebSocket tunnel to the
 * simple-ws-proxy server.
 *
 * Direction `local -> ws` is pumped by a coroutine reading the local socket;
 * direction `ws -> local` is handled in [WebSocketListener.onMessage]. Every
 * message is XOR-encrypted with the per-session [Cipher].
 */
class TunnelConnection(
    private val client: OkHttpClient,
    private val local: Socket,
    private val serverUrl: String,
    private val authenticator: Authenticator,
    private val target: String,
) {

    suspend fun run() = coroutineScope {
        val (timeValue, headers) = authenticator.makeHeaders()
        val cipher = authenticator.makeCipher(timeValue)

        val requestBuilder = Request.Builder().url(serverUrl)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val request = requestBuilder.build()

        val input = local.getInputStream()
        val output = local.getOutputStream()

        val closed = CompletableDeferred<Unit>()
        var pump: Job? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // First message: tell the server where to connect (XOR-encrypted JSON).
                val connectPayload = """{"connect":${jsonString(target)}}""".toByteArray(Charsets.UTF_8)
                webSocket.send(cipher.process(connectPayload).toByteString())

                pump = launch(Dispatchers.IO) {
                    val buffer = ByteArray(4096)
                    try {
                        while (isActive) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            webSocket.send(cipher.process(buffer, n).toByteString())
                        }
                    } catch (_: Exception) {
                        // local side closed or errored
                    } finally {
                        webSocket.close(1000, null)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                writeToLocal(webSocket, cipher.process(bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                writeToLocal(webSocket, cipher.process(text.toByteArray(Charsets.UTF_8)))
            }

            private fun writeToLocal(webSocket: WebSocket, data: ByteArray) {
                try {
                    output.write(data)
                    output.flush()
                } catch (_: Exception) {
                    webSocket.cancel()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logs.add("Tunnel error $target: ${t.message ?: t.javaClass.simpleName}")
                finish()
            }

            private fun finish() {
                if (!closed.isCompleted) closed.complete(Unit)
            }
        }

        val webSocket = client.newWebSocket(request, listener)
        try {
            closed.await()
        } finally {
            pump?.cancel()
            runCatching { webSocket.cancel() }
            runCatching { local.close() }
        }
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
