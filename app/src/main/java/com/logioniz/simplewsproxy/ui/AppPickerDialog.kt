package com.logioniz.simplewsproxy.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.logioniz.simplewsproxy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val packageName: String, val label: String)

/**
 * Full-height dialog that lists user-facing installed apps with checkboxes for
 * split tunneling. Returns the chosen package names via [onConfirm]. An empty
 * selection means "route all apps".
 */
@Composable
fun AppPickerDialog(
    initialSelected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initialSelected) } }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_routed_apps_title),
                    style = MaterialTheme.typography.titleLarge,
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.settings_routed_apps_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )

                val loaded = apps
                if (loaded == null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filtered = remember(loaded, query) {
                        if (query.isBlank()) {
                            loaded
                        } else {
                            loaded.filter {
                                it.label.contains(query, ignoreCase = true) ||
                                    it.packageName.contains(query, ignoreCase = true)
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { toggle(selected, app.packageName) }
                                    .padding(vertical = 6.dp),
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { toggle(selected, app.packageName) },
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_routed_apps_cancel))
                    }
                    TextButton(onClick = { onConfirm(selected.toSet()) }) {
                        Text(stringResource(R.string.settings_routed_apps_done))
                    }
                }
            }
        }
    }
}

private fun toggle(selected: MutableList<String>, pkg: String) {
    if (!selected.remove(pkg)) selected.add(pkg)
}

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase() }
}
