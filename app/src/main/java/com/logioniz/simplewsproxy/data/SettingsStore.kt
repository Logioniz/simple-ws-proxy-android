package com.logioniz.simplewsproxy.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-configurable proxy settings. */
data class ProxySettings(
    val server: String = "",
    val listenPort: Int = 1080,
    val secretKey: String = "",
    val socksUser: String = "",
    val socksPassword: String = "",
    /** When true, the Play button brings up a system VPN that routes all traffic. */
    val routeAllTraffic: Boolean = false,
    /**
     * Package names to route through the VPN (split tunneling). Empty means
     * route every app (except this one). Only used when [routeAllTraffic] is on.
     */
    val routedApps: Set<String> = emptySet(),
)

/**
 * Persists [ProxySettings] in SharedPreferences and exposes them as a
 * [StateFlow] so both the UI and the foreground service observe the same value.
 */
object SettingsStore {

    private const val PREFS = "proxy_settings"
    private const val KEY_SERVER = "server"
    private const val KEY_LISTEN_PORT = "listen_port"
    private const val KEY_SECRET = "secret_key"
    private const val KEY_USER = "socks_user"
    private const val KEY_PASSWORD = "socks_password"
    private const val KEY_ROUTE_ALL = "route_all_traffic"
    private const val KEY_ROUTED_APPS = "routed_apps"

    private lateinit var prefs: SharedPreferences

    private val _settings = MutableStateFlow(ProxySettings())
    val settings: StateFlow<ProxySettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = load()
    }

    fun save(settings: ProxySettings) {
        prefs.edit()
            .putString(KEY_SERVER, settings.server)
            .putInt(KEY_LISTEN_PORT, settings.listenPort)
            .putString(KEY_SECRET, settings.secretKey)
            .putString(KEY_USER, settings.socksUser)
            .putString(KEY_PASSWORD, settings.socksPassword)
            .putBoolean(KEY_ROUTE_ALL, settings.routeAllTraffic)
            .putStringSet(KEY_ROUTED_APPS, settings.routedApps)
            .apply()
        _settings.value = settings
    }

    private fun load(): ProxySettings = ProxySettings(
        server = prefs.getString(KEY_SERVER, "") ?: "",
        listenPort = prefs.getInt(KEY_LISTEN_PORT, 1080),
        secretKey = prefs.getString(KEY_SECRET, "") ?: "",
        socksUser = prefs.getString(KEY_USER, "") ?: "",
        socksPassword = prefs.getString(KEY_PASSWORD, "") ?: "",
        routeAllTraffic = prefs.getBoolean(KEY_ROUTE_ALL, false),
        // Copy: getStringSet returns a shared instance that must not be mutated.
        routedApps = prefs.getStringSet(KEY_ROUTED_APPS, emptySet())?.toSet() ?: emptySet(),
    )
}
