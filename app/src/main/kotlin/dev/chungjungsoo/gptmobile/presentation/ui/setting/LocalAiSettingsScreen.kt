package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun LocalAiSettingsScreen(models: LocalModelsViewModel, runtime: LocalRuntimeSettingsViewModel, onDownloads: () -> Unit) {
    val ui by models.uiState.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("local-model-downloads", android.content.Context.MODE_PRIVATE) }
    var wifi by remember { mutableStateOf(preferences.getBoolean("wifiOnly", false)) }
    val sections = listOf(Triple("engine", "Engine and processor", Icons.Rounded.Memory), Triple("performance", "Performance", Icons.Rounded.Speed), Triple("memory", "Memory and power", Icons.Rounded.BatteryChargingFull), Triple("downloads", "Downloads and storage", Icons.Rounded.Download), Triple("accounts", "Accounts and privacy", Icons.Rounded.AccountCircle), Triple("diagnostics", "Diagnostics and advanced", Icons.Rounded.QueryStats))
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(query, { query = it }, label = { Text("Search Local AI settings") }, modifier = Modifier.fillMaxWidth()) }
        sections.filter { query.isBlank() || it.second.contains(query, true) }.forEach { (id, title, icon) ->
            item(key = id) {
                LocalAiActionCard(icon, title, if (id == group) "Tap to collapse" else "Tap to configure", { group = if (group == id) null else id })
                if (group == id || query.isNotBlank()) {
                    when (id) {
                        "engine", "performance", "memory", "diagnostics" -> LocalRuntimeSettingsCard(runtime, id)
                        "downloads" -> SettingsPanel(title) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Wi-Fi-only downloads", Modifier.weight(1f))
                                Switch(wifi, {
                                    wifi = it
                                    preferences.edit().putBoolean("wifiOnly", it).apply()
                                })
                            }
                            Text(dev.chungjungsoo.gptmobile.data.catalog.ModelCatalogParser.formatDownloadSize(ui.totalStorageBytes), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onDownloads) { Text("Manage downloads and installed files") }
                        }
                        "accounts" -> SettingsPanel(title) { HuggingFaceAccountSection(ui.hasHuggingFaceToken, models::openAccessTokenDialog, models::removeHuggingFaceAccessToken) }
                    }
                }
            }
        }
    }
}
