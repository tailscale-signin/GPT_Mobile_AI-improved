package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.delegationFor

@Composable
internal fun SettingsDiscoveryPanel(
    settings: AppFeatureSettings,
    profiles: List<PlatformV2>,
    destinations: Map<String, () -> Unit>,
    change: (AppFeature, Boolean) -> Unit,
    preset: (String) -> Unit,
    resetOverrides: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var inspect by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("Search All Settings") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (query.isNotBlank()) {
            val features = AppFeature.entries.filter { "${it.title} ${it.description}".contains(query, true) }
            val sections = if (query.isBlank()) emptyMap() else destinations.filterKeys { it.contains(query, true) }
            if (features.isEmpty() && sections.isEmpty()) Text("No Matching Settings")
            features.forEach { feature ->
                Card {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(feature.title)
                            Text(feature.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(settings.withFeature(feature, true) == settings, { change(feature, it) })
                    }
                }
            }
            sections.forEach { (title, action) -> TextButton(onClick = action) { Text(title) } }
        }
        Row {
            TextButton(onClick = { inspect = true }) { Text("Effective Settings") }
            TextButton(onClick = { selectedPreset = "Balanced" }) { Text("Presets") }
        }
    }
    if (inspect) {
        AlertDialog(
            onDismissRequest = { inspect = false },
            title = { Text("Effective Settings") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Conversation overrides take precedence over profile and global defaults.") }
                    items(profiles, key = { it.uid }) { profile ->
                        Column {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            Text("Reasoning: ${if (profile.reasoning == true) "on" else "off"} · profile")
                            Text("Delegation: ${if (settings.delegationFor(profile.uid).enabled) "on" else "off"} · global + profile")
                            Text("Tools: ${if (profile.disableAllTools) "disabled by profile" else "profile bindings, then conversation selection"}")
                        }
                    }
                    items((settings.conversationReasoning.keys + settings.conversationDelegation.keys).distinct().sorted()) { id ->
                        Column {
                            Text("Conversation #$id", style = MaterialTheme.typography.titleSmall)
                            settings.conversationReasoning[id]?.let { Text("Reasoning override: ${if (it) "on" else "off"}") }
                            settings.conversationDelegation[id]?.let { Text("Delegation override: ${if (it.enabled) "on" else "off"} · amount ${it.delegationAmount ?: "default"} · depth ${it.researchDepth ?: "default"}") }
                            TextButton(onClick = { resetOverrides(id) }) { Text("Reset conversation overrides") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { inspect = false }) { Text("Done") } }
        )
    }
    selectedPreset?.let { selected ->
        AlertDialog(
            onDismissRequest = { selectedPreset = null },
            title = { Text("Experience presets") },
            text = {
                Column {
                    listOf("Balanced", "Quiet", "Battery saver").forEach { name -> TextButton(onClick = { selectedPreset = name }) { Text(if (selected == name) "✓ $name" else name) } }
                    Text(
                        when (selected) {
                            "Quiet" -> "Turns off response notifications and animations. Background responses remain enabled."
                            "Battery saver" -> "Turns off background generation, parallel searches and animations. Unloads idle local models after one minute."
                            else -> "Enables smooth streaming, background responses, notifications and parallel searches. Unloads idle local models after ten minutes."
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preset(selected)
                    selectedPreset = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { selectedPreset = null }) { Text("Cancel") } }
        )
    }
}
