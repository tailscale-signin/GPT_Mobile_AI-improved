package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactVaultScreen(viewModel: FactVaultViewModel, onBack: () -> Unit) {
    val vault by viewModel.vault.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var deletingId by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    val settings = vault.settings

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Fact Vault") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = { clearing = true }, enabled = !busy && (vault.facts.isNotEmpty() || vault.suppressedIds.isNotEmpty() || vault.enabled || error != null)) { Text("Clear") } }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Learn and recall local facts", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(modifier = Modifier.semantics { contentDescription = "Learn and recall local facts" }, checked = vault.enabled, onCheckedChange = viewModel::setEnabled, enabled = !busy)
                        }
                        Text("Save simple preferences and relationships from your messages on this device. Matching enabled facts are included in future AI requests, including requests to cloud providers.")
                        Text("Facts are encrypted at rest. Extraction uses simple patterns and can be imperfect. Review facts below; disabled facts stay saved but are never recalled.", style = MaterialTheme.typography.bodySmall)
                        Text("${vault.facts.size} / ${settings.maxFacts} facts · ${vault.facts.count { it.enabled }} enabled", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "Hide memory settings" else "Memory settings") }
                if (showOptions) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            VaultToggle("Learn from new messages", settings.learningEnabled, !busy) { viewModel.updateSettings(settings.copy(learningEnabled = it)) }
                            VaultToggle("Recall saved facts", settings.recallEnabled, !busy) { viewModel.updateSettings(settings.copy(recallEnabled = it)) }
                            VaultToggle("Include facts in cloud requests", settings.allowCloudRecall, !busy) { viewModel.updateSettings(settings.copy(allowCloudRecall = it)) }
                            VaultToggle("Recall only in the original chat", settings.sameChatOnly, !busy) { viewModel.updateSettings(settings.copy(sameChatOnly = it)) }
                            VaultToggle("Learn preferences", settings.learnPreferences, !busy) { viewModel.updateSettings(settings.copy(learnPreferences = it)) }
                            VaultToggle("Learn relationships", settings.learnRelationships, !busy) { viewModel.updateSettings(settings.copy(learnRelationships = it)) }
                            VaultToggle("Review new facts before recall", settings.reviewBeforeRecall, !busy) { viewModel.updateSettings(settings.copy(reviewBeforeRecall = it)) }
                            Text("Facts awaiting review stay disabled until you enable them below.", style = MaterialTheme.typography.bodySmall)
                            VaultLimit("Facts per response", settings.maxRecall, 1..10, !busy) { viewModel.updateSettings(settings.copy(maxRecall = it)) }
                            VaultLimit("Storage limit", settings.maxFacts, 16..64, !busy) { viewModel.updateSettings(settings.copy(maxFacts = it)) }
                            Text("Lowering the limit keeps existing facts and pauses learning when full.", style = MaterialTheme.typography.bodySmall)
                            VaultLimit("Retention days (0 = keep)", settings.retentionDays, 0..365, !busy) { viewModel.updateSettings(settings.copy(retentionDays = it)) }
                            Text("Expiry is checked on your next message. Older facts without a saved date stay until deleted.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search saved facts") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::refresh, enabled = !busy) { Text("Retry") }
                }
            }
            if (vault.facts.isEmpty()) {
                item {
                    Text(if (vault.enabled) "No facts yet. Try a message such as ‘I prefer Kotlin’. Relevant facts will be recalled on later turns." else "Enable local memory to start saving facts. Nothing is learned or recalled while it is off.")
                }
            }
            items(vault.facts.filter { "${it.fact.entity.name} ${it.fact.target.name}".contains(query, ignoreCase = true) }, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${entry.fact.entity.name} ${entry.fact.relation.relationType.lowercase().replace('_', ' ')} ${entry.fact.target.name}", style = MaterialTheme.typography.titleMedium)
                        Text("From your message · chat ${entry.sourceChatId}", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (entry.enabled) "Available for recall" else "Excluded from recall", Modifier.weight(1f))
                            Switch(modifier = Modifier.semantics { contentDescription = "Recall ${entry.fact.entity.name} ${entry.fact.target.name}" }, checked = entry.enabled, onCheckedChange = { viewModel.setFactEnabled(entry.id, it) }, enabled = !busy)
                            IconButton(onClick = { deletingId = entry.id }, enabled = !busy) {
                                Icon(Icons.Default.Delete, "Delete saved fact")
                            }
                        }
                    }
                }
            }
        }
    }
    if (deletingId != null || clearing) {
        AlertDialog(
            onDismissRequest = {
                deletingId = null
                clearing = false
            },
            title = { Text(if (clearing) "Clear Fact Vault?" else "Delete this fact?") },
            text = { Text(if (clearing) "This deletes all saved facts and turns off learning and recall. Chat messages are kept." else "This fact will stop being recalled. Retrying the original message will not learn it again.") },
            confirmButton = {
                TextButton(onClick = {
                    if (clearing) viewModel.clear() else deletingId?.let(viewModel::delete)
                    deletingId = null
                    clearing = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deletingId = null
                    clearing = false
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun VaultToggle(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun VaultLimit(title: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toFloat()) }
    Text("$title: ${draft.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onChange(draft.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
}
