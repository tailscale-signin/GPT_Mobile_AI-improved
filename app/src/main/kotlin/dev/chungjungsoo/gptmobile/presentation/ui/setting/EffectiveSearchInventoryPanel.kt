package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.tool.SearchServiceHealth

@Composable
internal fun EffectiveSearchInventoryPanel(viewModel: PlatformSettingViewModel) {
    val engines by viewModel.effectiveSearchInventory.collectAsState()
    val health by SearchServiceHealth.observations.collectAsState()
    val features by viewModel.featureSettings.collectAsState()
    LaunchedEffect(viewModel.platformUid, features) { viewModel.refreshSearchInventory() }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Effective search engines", style = MaterialTheme.typography.titleMedium)
            Text("App-assisted search uses these permissions, including for models without native tools. Conversation exclusions can further restrict access.", style = MaterialTheme.typography.bodySmall)
            engines.forEach { engine ->
                Text(engine.name, style = MaterialTheme.typography.labelLarge)
                Text(engine.reason, style = MaterialTheme.typography.bodySmall)
                if (engine.authorized) {
                    val observation = health[engine.id]
                    val status = when {
                        observation == null -> "Not checked"
                        observation.outcome == "SUCCESS" -> "Last search succeeded"
                        observation.retryAt == Long.MAX_VALUE -> "Requires connection repair · use Test connection in Plugins & Tools"
                        observation.retryAt > System.currentTimeMillis() -> "Temporarily unavailable · retry after ${java.text.DateFormat.getTimeInstance().format(java.util.Date(observation.retryAt))}"
                        else -> "Last search: ${observation.outcome}"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (observation?.outcome == "SUCCESS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    observation?.takeIf { it.outcome != "SUCCESS" }?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                }
            }
            TextButton(onClick = viewModel::refreshSearchInventory) { Text("Refresh inventory") }
        }
    }
}
