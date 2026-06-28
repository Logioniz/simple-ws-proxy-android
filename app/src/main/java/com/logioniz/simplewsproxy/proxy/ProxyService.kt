package com.logioniz.simplewsproxy.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.logioniz.simplewsproxy.MainActivity
import com.logioniz.simplewsproxy.R
import com.logioniz.simplewsproxy.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the lifetime of the local SOCKS5 -> WebSocket
 * tunnel. It reads the persisted [ProxySettings], validates them, builds a
 * [ProxyConfig] and runs a [ProxyServer] for as long as the service is alive.
 *
 * The UI never touches [ProxyServer] directly; it starts/stops this service and
 * observes [ProxyState] / [Logs].
 */
class ProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ProxyServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val config = ProxyConfig.fromSettings(SettingsStore.settings.value)
        if (config == null) {
            // Validation message already published to ProxyState by fromSettings.
            stopSelf()
            return START_NOT_STICKY
        }

        ProxyState.setRunning(true)
        val proxy = ProxyServer(config)
        server = proxy
        scope.launch {
            try {
                proxy.run(this)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Logs.add("Failed to start: $reason")
                ProxyState.setStatus("Error: $reason", StatusLevel.ERROR)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        scope.cancel()
        ProxyState.setRunning(false)
        if (ProxyState.status.value.level != StatusLevel.ERROR) {
            ProxyState.setStatus("Stopped", StatusLevel.NONE)
        }
        Logs.add("Proxy stopped")
        super.onDestroy()
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
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("SOCKS5 proxy running")
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
                "Proxy status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows that the SOCKS5 proxy is running" },
        )
    }

    companion object {
        private const val CHANNEL_ID = "proxy_status"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.logioniz.simplewsproxy.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
