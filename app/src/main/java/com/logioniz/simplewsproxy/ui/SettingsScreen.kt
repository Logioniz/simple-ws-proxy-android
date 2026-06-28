package com.logioniz.simplewsproxy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.logioniz.simplewsproxy.R
import com.logioniz.simplewsproxy.data.ProxySettings
import com.logioniz.simplewsproxy.data.SettingsStore

/**
 * Editable form for all proxy settings. Values are loaded from [SettingsStore]
 * once and written back via [onSaved] when the user taps Save.
 */
@Composable
fun SettingsScreen(
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saved = remember { SettingsStore.settings.value }

    var server by rememberSaveable { mutableStateOf(saved.server) }
    var listenPort by rememberSaveable { mutableStateOf(saved.listenPort.toString()) }
    var secretKey by rememberSaveable { mutableStateOf(saved.secretKey) }
    var socksUser by rememberSaveable { mutableStateOf(saved.socksUser) }
    var socksPassword by rememberSaveable { mutableStateOf(saved.socksPassword) }
    var routeAllTraffic by rememberSaveable { mutableStateOf(saved.routeAllTraffic) }
    var routedApps by remember { mutableStateOf(saved.routedApps) }

    var secretVisible by rememberSaveable { mutableStateOf(false) }
    var socksPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text(stringResource(R.string.settings_server)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = listenPort,
            onValueChange = { new -> listenPort = new.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.settings_listen_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        OutlinedTextField(
            value = secretKey,
            onValueChange = { secretKey = it },
            label = { Text(stringResource(R.string.settings_secret)) },
            singleLine = true,
            visualTransformation = if (secretVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                PasswordVisibilityToggle(
                    visible = secretVisible,
                    onToggle = { secretVisible = !secretVisible },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        OutlinedTextField(
            value = socksUser,
            onValueChange = { socksUser = it },
            label = { Text(stringResource(R.string.settings_socks_user)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        OutlinedTextField(
            value = socksPassword,
            onValueChange = { socksPassword = it },
            label = { Text(stringResource(R.string.settings_socks_password)) },
            singleLine = true,
            visualTransformation = if (socksPasswordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                PasswordVisibilityToggle(
                    visible = socksPasswordVisible,
                    onToggle = { socksPasswordVisible = !socksPasswordVisible },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_route_all))
                Text(
                    text = stringResource(R.string.settings_route_all_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = routeAllTraffic,
                onCheckedChange = { routeAllTraffic = it },
            )
        }

        if (routeAllTraffic) {
            val appsSummary = if (routedApps.isEmpty()) {
                stringResource(R.string.settings_routed_apps_all)
            } else {
                stringResource(R.string.settings_routed_apps_count, routedApps.size)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_routed_apps_label))
                    Text(
                        text = appsSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = { showAppPicker = true }) {
                    Text(stringResource(R.string.settings_routed_apps_choose))
                }
            }
        }

        if (showAppPicker) {
            AppPickerDialog(
                initialSelected = routedApps,
                onConfirm = {
                    routedApps = it
                    showAppPicker = false
                },
                onDismiss = { showAppPicker = false },
            )
        }

        Button(
            onClick = {
                SettingsStore.save(
                    ProxySettings(
                        server = server.trim(),
                        listenPort = listenPort.toIntOrNull() ?: 1080,
                        secretKey = secretKey,
                        socksUser = socksUser,
                        socksPassword = socksPassword,
                        routeAllTraffic = routeAllTraffic,
                        routedApps = routedApps,
                    ),
                )
                onSaved()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

/**
 * Trailing eye button that flips a password field between masked and plain text.
 */
@Composable
private fun PasswordVisibilityToggle(
    visible: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            painter = painterResource(
                if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
            ),
            contentDescription = stringResource(
                if (visible) R.string.settings_hide_password else R.string.settings_show_password,
            ),
        )
    }
}
