package com.logioniz.simplewsproxy.proxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.logioniz.simplewsproxy.MainActivity
import com.logioniz.simplewsproxy.R
import com.logioniz.simplewsproxy.data.SettingsStore
import com.logioniz.simplewsproxy.proxy.Authenticator
import com.logioniz.simplewsproxy.proxy.Logs
import com.logioniz.simplewsproxy.proxy.ProxyConfig
import com.logioniz.simplewsproxy.proxy.ProxyState
import com.logioniz.simplewsproxy.proxy.StatusLevel
import com.logioniz.simplewsproxy.proxy.TunnelConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * VpnService that captures all device traffic via a TUN interface and forwards
 * it through the same WebSocket tunnel used by [ProxyConfig]. It owns a
 * userspace tun2socks [VpnEngine] and runs for as long as the VPN is up.
 *
 * Used instead of the local SOCKS5 [com.logioniz.simplewsproxy.proxy.ProxyService]
 * when "Route all traffic" is enabled; the UI starts/stops it and observes
 * [ProxyState] / [Logs].
 */
class ProxyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var engine: VpnEngine? = null

    @Volatile
    private var stopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The VPN framework binds this service, so an external stopService()
            // would not destroy it. Tear everything down here instead. Satisfy the
            // startForegroundService contract before leaving the foreground.
            startForeground(NOTIFICATION_ID, buildNotification())
            shutdown()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val config = ProxyConfig.fromSettings(SettingsStore.settings.value)
        if (config == null) {
            // Validation message already published to ProxyState by fromSettings.
            stopSelf()
            return START_NOT_STICKY
        }

        val tun = try {
            establishTun()
        } catch (e: Exception) {
            return failAndStop("VPN setup failed: ${e.message ?: e.javaClass.simpleName}")
        }
        if (tun == null) return failAndStop("VPN permission missing or revoked")
        tunInterface = tun

        val client = OkHttpClient.Builder()
            .socketFactory(ProtectedSocketFactory(this))
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val authenticator = Authenticator(config.secretKey)

        val tunnel = Tunnel { target, input, output, onClose ->
            scope.launch(Dispatchers.IO) {
                try {
                    TunnelConnection(
                        client = client,
                        input = input,
                        output = output,
                        onClose = onClose,
                        serverUrl = config.serverUrl,
                        authenticator = authenticator,
                        target = target,
                    ).run()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    onClose()
                    throw e
                } catch (e: Exception) {
                    Logs.add("Tunnel error $target: ${e.message ?: e.javaClass.simpleName}")
                    onClose()
                }
            }
        }

        val writer = TunWriter(FileOutputStream(tun.fileDescriptor))
        val vpnEngine = VpnEngine(FileInputStream(tun.fileDescriptor), writer, tunnel, scope)
        engine = vpnEngine

        ProxyState.setRunning(true)
        Logs.add("VPN started -> ${config.serverUrl}")
        ProxyState.setStatus(routingSummary(), StatusLevel.SUCCESS)

        scope.launch(Dispatchers.IO) { vpnEngine.loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** The system may revoke the VPN (e.g. another VPN starts) — stop cleanly. */
    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    /**
     * Close the TUN (which collapses the VPN), stop the engine and leave the
     * foreground. Idempotent: callable from [onStartCommand]'s stop action,
     * [onRevoke] and [onDestroy].
     */
    private fun shutdown() {
        if (stopped) return
        stopped = true

        engine?.stop()
        engine = null
        runCatching { tunInterface?.close() } // closing the fd tears down the VPN
        tunInterface = null
        scope.cancel()

        ProxyState.setRunning(false)
        if (ProxyState.status.value.level != StatusLevel.ERROR) {
            ProxyState.setStatus("Stopped", StatusLevel.NONE)
        }
        Logs.add("VPN stopped")

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .setBlocking(true)
            .addAddress(VPN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(DNS_SERVER)
            // Capture IPv6 too and let the engine drop it, so v6 traffic cannot leak
            // around the VPN; apps fall back to IPv4.
            .addAddress(VPN_ADDRESS6, 128)
            .addRoute("::", 0)

        applyAppFilter(builder)
        return builder.establish()
    }

    /**
     * Split tunneling. With apps selected, route only those (allow-list); our own
     * package is never added, so the tunnel's sockets bypass the TUN. With none
     * selected, route everything except our own package (disallow-list) to avoid
     * the loop. Either way the tunnel never enters the VPN.
     */
    private fun applyAppFilter(builder: Builder) {
        val selected = SettingsStore.settings.value.routedApps - packageName
        if (selected.isEmpty()) {
            runCatching { builder.addDisallowedApplication(packageName) }
            return
        }
        var added = 0
        for (pkg in selected) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                Logs.add("Skipping uninstalled app: $pkg")
            }
        }
        // If every selected app turned out to be uninstalled, fall back to "all".
        if (added == 0) runCatching { builder.addDisallowedApplication(packageName) }
    }

    /** Human-readable description of what is being routed, for status/notification. */
    private fun routingSummary(): String {
        val selected = SettingsStore.settings.value.routedApps - packageName
        return if (selected.isEmpty()) "Routing all traffic" else "Routing apps"
    }

    private fun failAndStop(message: String): Int {
        ProxyState.setStatus(message, StatusLevel.ERROR)
        Logs.add(message)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        createChannel()

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(routingSummary())
            .setSmallIcon(R.drawable.ic_play)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "VPN status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows that all traffic is routed through the proxy" },
        )
    }

    companion object {
        private const val CHANNEL_ID = "proxy_vpn_status"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.logioniz.simplewsproxy.action.STOP_VPN"

        private const val MTU = 1500
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ADDRESS6 = "fd00:1:2:3::2"
        private const val DNS_SERVER = "8.8.8.8"

        fun start(context: Context) {
            val intent = Intent(context, ProxyVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // stopService() would not destroy a VPN service the framework keeps
            // bound; deliver a stop command so the service tears itself down.
            val intent = Intent(context, ProxyVpnService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
