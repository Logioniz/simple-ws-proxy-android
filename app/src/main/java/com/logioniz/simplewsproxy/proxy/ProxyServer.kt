package com.logioniz.simplewsproxy.proxy

import com.logioniz.simplewsproxy.data.ProxySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Runtime configuration for the local SOCKS5 listener and the WS tunnel. */
data class ProxyConfig(
    val serverUrl: String,
    val listenPort: Int,
    val secretKey: String,
    val socksUser: String,
    val socksPassword: String,
) {
    companion object {
        /** Validate [settings] into a [ProxyConfig], or publish an error and return null. */
        fun fromSettings(settings: ProxySettings): ProxyConfig? {
            val serverUrl = normalizeServerUrl(settings.server)
            val error = when {
                serverUrl.isEmpty() -> "Set the server address in Settings"
                settings.secretKey.isEmpty() -> "Set the secret key in Settings"
                settings.listenPort !in 1..65535 -> "Listen port must be 1-65535"
                else -> null
            }
            if (error != null) {
                ProxyState.setStatus(error, StatusLevel.ERROR)
                Logs.add(error)
                return null
            }
            return ProxyConfig(
                serverUrl = serverUrl,
                listenPort = settings.listenPort,
                secretKey = settings.secretKey,
                socksUser = settings.socksUser,
                socksPassword = settings.socksPassword,
            )
        }

        /** Normalize a user-entered `host:port` (or full URL) into a `ws://` URL. */
        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return when {
                trimmed.startsWith("ws://", ignoreCase = true) ||
                    trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) ->
                    "ws://" + trimmed.substring("http://".length)
                trimmed.startsWith("https://", ignoreCase = true) ->
                    "wss://" + trimmed.substring("https://".length)
                else -> "ws://$trimmed"
            }
        }
    }
}

/**
 * Local SOCKS5 listener bound to 127.0.0.1. Each accepted connection is
 * handshaked (RFC 1928/1929) and then tunneled through a WebSocket connection
 * by [TunnelConnection].
 */
class ProxyServer(private val config: ProxyConfig) {

    private val authenticator = Authenticator(config.secretKey)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** Bind and accept connections until [scope] is cancelled. Throws on bind failure. */
    suspend fun run(scope: CoroutineScope) {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.listenPort))
        serverSocket = socket

        val listenInfo = "127.0.0.1:${config.listenPort}"
        Logs.add("SOCKS5 listening on $listenInfo -> ${config.serverUrl}")
        ProxyState.setStatus("Listening on $listenInfo", StatusLevel.SUCCESS)

        while (scope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break // socket closed -> stop accepting
            }
            scope.launch(Dispatchers.IO) { handle(client) }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val target = Socks5.handshake(
                socket.getInputStream(),
                socket.getOutputStream(),
                config.socksUser.ifEmpty { null },
                config.socksPassword.ifEmpty { null },
            )
            if (target == null) {
                runCatching { socket.close() }
                return
            }
            Logs.add("CONNECT $target")
            TunnelConnection(
                client = client,
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                onClose = { runCatching { socket.close() } },
                serverUrl = config.serverUrl,
                authenticator = authenticator,
                target = target,
            ).run()
        } catch (e: Exception) {
            Logs.add("Connection error: ${e.message ?: e.javaClass.simpleName}")
            runCatching { socket.close() }
        }
    }
}
