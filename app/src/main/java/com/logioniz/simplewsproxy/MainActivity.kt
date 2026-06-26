package com.logioniz.simplewsproxy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.logioniz.simplewsproxy.proxy.ProxyService
import com.logioniz.simplewsproxy.ui.LogsScreen
import com.logioniz.simplewsproxy.ui.PlayScreen
import com.logioniz.simplewsproxy.ui.SettingsScreen
import com.logioniz.simplewsproxy.ui.theme.SimpleWSProxyTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        enableEdgeToEdge()
        setContent {
            SimpleWSProxyTheme {
                SimpleWSProxyApp(
                    onToggleProxy = { start ->
                        if (start) ProxyService.start(this) else ProxyService.stop(this)
                    },
                )
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
fun SimpleWSProxyApp(onToggleProxy: (start: Boolean) -> Unit) {
    var current by rememberSaveable { mutableStateOf(AppDestinations.PLAY) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { destination ->
                item(
                    icon = {
                        Icon(
                            painter = painterResource(destination.icon),
                            contentDescription = stringResource(destination.label),
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                    selected = destination == current,
                    onClick = { current = destination },
                )
            }
        },
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            when (current) {
                AppDestinations.PLAY -> PlayScreen(onToggle = onToggleProxy, modifier = contentModifier)
                AppDestinations.SETTINGS -> SettingsScreen(onSaved = { current = AppDestinations.PLAY }, modifier = contentModifier)
                AppDestinations.LOGS -> LogsScreen(modifier = contentModifier)
            }
        }
    }
}

enum class AppDestinations(
    val label: Int,
    val icon: Int,
) {
    PLAY(R.string.tab_play, R.drawable.ic_play),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings),
    LOGS(R.string.tab_logs, R.drawable.ic_logs),
}
