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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R

@Composable
internal fun LocalAiSettingsScreen(models: LocalModelsViewModel, runtime: LocalRuntimeSettingsViewModel, onDownloads: () -> Unit) {
    val ui by models.uiState.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val wifi by models.wifiOnly.collectAsStateWithLifecycle()
    val downloadSettingsError by models.downloadSettingsError.collectAsStateWithLifecycle()
    val sections = listOf(Triple("engine", "Engine and processor", Icons.Rounded.Memory), Triple("performance", "Performance", Icons.Rounded.Speed), Triple("memory", "Memory and power", Icons.Rounded.BatteryChargingFull), Triple("downloads", "Downloads and storage", Icons.Rounded.Download), Triple("accounts", "Accounts and privacy", Icons.Rounded.AccountCircle), Triple("diagnostics", "Diagnostics and advanced", Icons.Rounded.QueryStats))
    val keywords = mapOf("engine" to "cpu gpu npu qnn backend processor runtime", "performance" to "threads cache speculative mtp speed decoding", "memory" to "reserve idle warm heat thermal battery power unload", "downloads" to "wifi storage cancel progress repair", "accounts" to "hugging face token privacy access", "diagnostics" to "benchmark comparison metrics qualification logs")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "local-assist") { LocalAssistCard(runtime, onDownloads) }
        item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.local_ai_settings_screen_1)) }, modifier = Modifier.fillMaxWidth()) }
        sections.filter { query.isBlank() || (it.second + " " + keywords[it.first].orEmpty()).contains(query, true) }.forEach { (id, title, icon) ->
            item(key = id) {
                LocalAiActionCard(icon, title, if (id == group) "Tap to collapse" else "Tap to configure", { group = if (group == id) null else id })
                if (group == id || query.isNotBlank()) {
                    when (id) {
                        "engine", "performance", "memory", "diagnostics" -> LocalRuntimeSettingsCard(runtime, id)
                        "downloads" -> SettingsPanel(title) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.local_ai_settings_screen_2), Modifier.weight(1f))
                                Switch(checked = wifi == true, onCheckedChange = models::updateWifiOnly, enabled = wifi != null)
                            }
                            downloadSettingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Text(dev.chungjungsoo.gptmobile.data.catalog.ModelCatalogParser.formatDownloadSize(ui.totalStorageBytes), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onDownloads) { Text(stringResource(R.string.local_ai_settings_screen_3)) }
                        }
                        "accounts" -> SettingsPanel(title) { HuggingFaceAccountSection(ui.hasHuggingFaceToken, models::openAccessTokenDialog, models::removeHuggingFaceAccessToken) }
                    }
                }
            }
        }
    }
}
