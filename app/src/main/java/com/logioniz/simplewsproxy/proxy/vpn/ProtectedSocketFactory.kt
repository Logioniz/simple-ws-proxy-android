package com.logioniz.simplewsproxy.proxy.vpn

import android.net.VpnService
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * A [SocketFactory] that calls [VpnService.protect] on every socket it creates,
 * so the WebSocket tunnel's own connections go out over the real network
 * instead of being routed back into the TUN (which would loop forever).
 *
 * OkHttp uses the no-arg [createSocket] and connects the socket itself, so the
 * protect call below happens before the connect.
 */
class ProtectedSocketFactory(private val vpn: VpnService) : SocketFactory() {

    private val delegate = getDefault()

    private fun protect(socket: Socket): Socket {
        vpn.protect(socket)
        return socket
    }

    override fun createSocket(): Socket = protect(delegate.createSocket())

    override fun createSocket(host: String?, port: Int): Socket =
        protect(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        protect(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        protect(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        protect(delegate.createSocket(address, port, localAddress, localPort))
}
