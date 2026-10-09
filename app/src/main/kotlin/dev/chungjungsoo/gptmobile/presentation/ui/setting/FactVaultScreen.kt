package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactVaultScreen(viewModel: FactVaultViewModel, onBack: () -> Unit, onOpenConversation: (Int, Int?) -> Unit = { _, _ -> }) {
    val vault by viewModel.vault.collectAsStateWithLifecycle()
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle(emptyList())
    val attachments by viewModel.attachments.collectAsStateWithLifecycle(emptyList())
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val indexWarning by viewModel.indexWarning.collectAsStateWithLifecycle()
    val semanticStatus by viewModel.semanticStatus.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Memories") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<VaultFact?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var selectedMemories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var restructuring by remember { mutableStateOf(false) }
    var replacementText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var advancedControls by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = vault.settings
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Memory") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = {
                    adding = true
                    draft = ""
                }, enabled = !busy) { Icon(Icons.Rounded.Add, "Add a memory", tint = MaterialTheme.colorScheme.primary) }
            }
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Memory Overview", style = MaterialTheme.typography.titleLarge)
                                SettingsHelpIcon("Private saved facts, recurring topics, semantic recall, and indexed documents are managed here.")
                            }
                            Switch(vault.enabled, viewModel::setEnabled, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Enable Local Memory" })
                        }
                        Text("${vault.facts.size} memories · ${documents.size} indexed documents", style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${vault.facts.count { !it.enabled }} awaiting review or disabled", style = MaterialTheme.typography.bodySmall)
                            SettingsHelpIcon(semanticStatus.detail + " Auto-recall skips Free profiles.")
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Memories", "Topics", "Documents", "Controls").forEach { label ->
                        FilterChip(tab == label, {
                            tab = label
                            query = ""
                        }, label = { Text(label) })
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::refresh) { Text("Retry") }
                }
            }
            status?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
            indexWarning?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
            when (tab) {
                "Topics" -> {
                    item { SettingsHero("ON DEVICE", "Recurring interests", "Topics become memories after ${settings.topicRepetitions} different messages.") }
                    val topics = vault.topics.sortedByDescending { it.messageKeys.size }
                    if (topics.isEmpty()) item { Text("Repeated topics will appear here as you chat.") }
                    items(topics, key = { "${it.scope}:${it.label}" }) { topic ->
                        SettingsPanel(topic.label) {
                            Text("${topic.messageKeys.size} mentions · ${topic.scope}", style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(progress = { (topic.messageKeys.size.toFloat() / settings.topicRepetitions).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                "Memories" -> {
                    item {
                        OutlinedTextField(query, { query = it }, label = { Text("Find a memory") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("All", "Pinned", "Review").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
                        }
                    }
                    if (selectedMemories.isNotEmpty()) {
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = {
                                    viewModel.reviewFacts(selectedMemories, true)
                                    selectedMemories = emptySet()
                                }, enabled = !busy) { Text("Allow selected") }
                                TextButton(onClick = {
                                    viewModel.reviewFacts(selectedMemories, false)
                                    selectedMemories = emptySet()
                                }, enabled = !busy) { Text("Disable selected") }
                                TextButton(onClick = {
                                    replacementText = vault.facts.filter { it.id in selectedMemories }.joinToString("\n") { it.fact.target.name }
                                    restructuring = true
                                }, enabled = !busy) { Text("Merge / split") }
                                TextButton(onClick = { selectedMemories = emptySet() }) { Text("Clear selection") }
                            }
                        }
                    }
                    val shown = vault.facts.filter {
                        "${it.fact.entity.name} ${it.fact.target.name}".contains(query, true) &&
                            (filter != "Pinned" || it.pinned) &&
                            (filter != "Review" || !it.enabled)
                    }.sortedWith(compareByDescending<VaultFact> { it.pinned }.thenByDescending { it.savedAtMillis })
                    if (shown.isEmpty()) item { Text("No memories here yet. Add one, or tell your AI what to remember.", style = MaterialTheme.typography.bodyMedium) }
                    items(shown, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (entry.fact.relation.relationType == "REMEMBERS" || entry.source == "local_model_observation") entry.fact.target.name else "${entry.fact.entity.name} ${entry.fact.relation.relationType.lowercase().replace('_', ' ')} ${entry.fact.target.name}", style = MaterialTheme.typography.titleMedium)
                                Text("${entry.source.replace('_', ' ')} · ${if (entry.sourceChatId > 0) "chat ${entry.sourceChatId}, message ${entry.sourceMessageId}" else "Added by you"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text("${entry.scope} · ${entry.occurrences} supporting messages · ${(entry.confidence * 100).toInt()}% extraction confidence", style = MaterialTheme.typography.labelSmall)
                                if (entry.pendingReplacements.isNotEmpty()) {
                                    Text("Change needs your review · existing memory stays active", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    entry.pendingReplacements.keys.forEach { oldId ->
                                        vault.facts.firstOrNull { it.id == oldId }?.let { old ->
                                            Text("Existing: ${old.fact.target.name}", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    Row {
                                        TextButton(onClick = { viewModel.setFactEnabled(entry.id, true) }, enabled = !busy) { Text("Replace existing") }
                                        TextButton(onClick = { viewModel.keepBoth(entry.id) }, enabled = !busy) { Text("Keep both") }
                                        TextButton(onClick = { viewModel.delete(entry.id) }, enabled = !busy) { Text("Keep existing") }
                                    }
                                }
                                entry.supersededBy?.let { replacement ->
                                    Text("Replaced by a newer memory: ${vault.facts.firstOrNull { it.id == replacement }?.fact?.target?.name ?: "unavailable"}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (entry.previousValues.isNotEmpty()) {
                                    var historyVisible by remember(entry.id) { mutableStateOf(false) }
                                    TextButton(onClick = { historyVisible = !historyVisible }) { Text(if (historyVisible) "Hide edit history" else "Edit history · ${entry.previousValues.size}") }
                                    if (historyVisible) entry.previousValues.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                                if (entry.sourceChatId > 0) TextButton(onClick = { onOpenConversation(entry.sourceChatId, entry.sourceMessageId.takeIf { it > 0 }) }) { Text("View Original Message") }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Recall Details", style = MaterialTheme.typography.labelSmall)
                                    SettingsHelpIcon("Recall uses ${if (entry.pinned) "pinned priority, " else ""}query terms and aliases, semantic similarity when available, recency, and repeated support. The current request scope always applies.")
                                }
                                if (entry.source == "recurring_topic") TextButton(onClick = { deleting = entry.id }) { Text("Wrong Topic · Forget") }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(entry.id in selectedMemories, { selectedMemories = if (it) selectedMemories + entry.id else selectedMemories - entry.id }, enabled = !busy)
                                    Switch(entry.enabled, { viewModel.setFactEnabled(entry.id, it) }, enabled = !busy && entry.pendingReplacements.isEmpty(), modifier = Modifier.semantics { contentDescription = "Recall this memory" })
                                    TextButton(onClick = {
                                        editing = entry
                                        draft = entry.fact.target.name
                                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Edit") }
                                    IconButton(onClick = { viewModel.pin(entry.id, !entry.pinned) }, enabled = !busy) { Icon(Icons.Rounded.PushPin, if (entry.pinned) "Unpin" else "Pin", tint = if (entry.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                                    IconButton(onClick = { deleting = entry.id }, enabled = !busy) { Icon(Icons.Rounded.Delete, "Forget memory") }
                                }
                            }
                        }
                    }
                }
                "Documents" -> {
                    item {
                        Text("Conversation library", style = MaterialTheme.typography.titleLarge)
                        Text("All attachments stay linked to their original conversations. Index text documents to make them searchable by local memory tools.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::indexDocuments, enabled = !busy && vault.enabled) { Text("Index document text locally") }
                        OutlinedTextField(query, { query = it }, label = { Text("Find an attachment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (attachments.isEmpty()) item { Text("Attach a file in any conversation to see it here.") }
                    items(attachments.filter { it.attachment.resolvedDisplayName.contains(query, true) }, key = { "${it.chatId}:${it.attachment.filePathForDisplay}" }) { entry ->
                        val attachment = entry.attachment
                        val indexed = documents.firstOrNull { it.id == entry.documentId }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(attachment.resolvedDisplayName, style = MaterialTheme.typography.titleMedium)
                                }
                                Text("Chat ${entry.chatId} · ${attachment.sizeBytes / 1024} KB · ${if (indexed != null) "Indexed" else "Original attachment"}", style = MaterialTheme.typography.labelSmall)
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                val file = File(attachment.filePathForDisplay)
                                                require(file.isFile)
                                                val uri = try {
                                                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                } catch (_: IllegalArgumentException) {
                                                    val preview = withContext(Dispatchers.IO) {
                                                        val folder = File(context.cacheDir, "attachment-previews").also { it.mkdirs() }
                                                        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                                                        file.copyTo(File(folder, "${java.util.UUID.randomUUID()}-${file.name}"))
                                                    }
                                                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", preview)
                                                }
                                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "The original file or a compatible viewer is unavailable.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }) { Text("Open") }
                                    if (indexed != null) {
                                        TextButton(onClick = { context.startActivity(Intent(context, KnowledgeSourceActivity::class.java).setData(Uri.parse("gptmobile://knowledge/${indexed.id}"))) }) { Text("Read text") }
                                        TextButton(onClick = { viewModel.removeDocument(indexed.id) }, enabled = !busy) { Text("Remove index") }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Memory Controls", style = MaterialTheme.typography.titleMedium)
                                        SettingsHelpIcon("Capture durable facts automatically, then recall only what is useful.")
                                    }
                                    TextButton(onClick = viewModel::applyRecommendedControls, enabled = !busy) { Text("Recommended") }
                                }
                                var sensitivity by remember(settings.captureSensitivity) { mutableStateOf(settings.captureSensitivity.toFloat()) }
                                Text("Automatic capture strength · ${sensitivity.toInt()}%", style = MaterialTheme.typography.titleSmall)
                                Slider(
                                    value = sensitivity,
                                    onValueChange = { sensitivity = it },
                                    onValueChangeFinished = { viewModel.updateSettings(settings.copy(captureSensitivity = sensitivity.toInt())) },
                                    valueRange = 0f..100f,
                                    enabled = !busy && vault.enabled && settings.learningEnabled,
                                    modifier = Modifier.semantics { contentDescription = "Automatic memory capture strength" }
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Only key facts", style = MaterialTheme.typography.labelSmall)
                                    Text("More details", style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Capture Policy", style = MaterialTheme.typography.labelSmall)
                                    SettingsHelpIcon("Explicit facts and interests are learned locally. Repeated questions build topic evidence.")
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(40 to "Selective", 65 to "Balanced", 85 to "Detailed").forEach { (value, label) ->
                                        FilterChip(
                                            selected = settings.captureSensitivity == value,
                                            onClick = { viewModel.updateSettings(settings.copy(captureSensitivity = value)) },
                                            label = { Text(label) },
                                            enabled = !busy && vault.enabled && settings.learningEnabled
                                        )
                                    }
                                }

                                VaultToggle("Learn From New Messages", settings.learningEnabled, !busy) { viewModel.updateSettings(settings.copy(learningEnabled = it)) }
                                VaultToggle("Forget Memories When Their Conversation Is Deleted", settings.forgetWithConversation, !busy) { viewModel.updateSettings(settings.copy(forgetWithConversation = it)) }
                                VaultToggle("Recall Saved Memories", settings.recallEnabled, !busy) { viewModel.updateSettings(settings.copy(recallEnabled = it)) }
                                VaultToggle("Semantic Recall · Fully On-Device", settings.semanticRecall, !busy) { viewModel.updateSettings(settings.copy(semanticRecall = it)) }
                                Text("${semanticStatus.indexed} / ${semanticStatus.total} semantic memories · no server required", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = viewModel::rebuildSemanticIndex, enabled = !busy && vault.enabled && settings.semanticRecall) { Text("Rebuild Semantic Index") }
                                VaultToggle("Always Include Pinned Memories", settings.alwaysRecallPinned, !busy && settings.recallEnabled) { viewModel.updateSettings(settings.copy(alwaysRecallPinned = it)) }
                                VaultToggle("Allow Recall In Cloud Requests", settings.allowCloudRecall, !busy) { viewModel.updateSettings(settings.copy(allowCloudRecall = it)) }
                                VaultToggle("Recall Only Within The Original Chat", settings.sameChatOnly, !busy) { viewModel.updateSettings(settings.copy(sameChatOnly = it)) }
                                VaultToggle("Review New Memories Before Use", settings.reviewBeforeRecall, !busy) { viewModel.updateSettings(settings.copy(reviewBeforeRecall = it)) }
                                VaultToggle("Learn Preferences", settings.learnPreferences, !busy) { viewModel.updateSettings(settings.copy(learnPreferences = it)) }
                                VaultToggle("Learn Relationships", settings.learnRelationships, !busy) { viewModel.updateSettings(settings.copy(learnRelationships = it)) }
                                VaultToggle("Learn Recurring Topics", settings.learnRecurringTopics, !busy) { viewModel.updateSettings(settings.copy(learnRecurringTopics = it)) }
                                VaultToggle("Prioritize Recent Memories", settings.rankByRecency, !busy) { viewModel.updateSettings(settings.copy(rankByRecency = it)) }
                                VaultToggle("Prioritize Repeated Information", settings.rankByFrequency, !busy) { viewModel.updateSettings(settings.copy(rankByFrequency = it)) }
                                VaultLimit("Mentions before learning a topic", settings.topicRepetitions, 2..10, !busy) { viewModel.updateSettings(settings.copy(topicRepetitions = it)) }
                                VaultToggle("Use Local Model For Richer Learning", settings.localModelLearning, !busy, "Uses an idle on-device model in resumable background work. It selects exact user statements; text capture remains active when no model is loaded.") { viewModel.updateSettings(settings.copy(localModelLearning = it)) }
                                VaultToggle("Make Room For New Automatic Memories", settings.rotateAutomaticFacts, !busy, "Replaces the oldest automatic memories at capacity. Pinned and manually saved memories are kept.") { viewModel.updateSettings(settings.copy(rotateAutomaticFacts = it)) }
                                VaultLimit("New Facts Per Message", settings.maxCapturePerMessage, 1..16, !busy) { viewModel.updateSettings(settings.copy(maxCapturePerMessage = it)) }
                                VaultLimit("Recall Token Budget · Estimate", settings.recallTokens, 128..8192, !busy) { viewModel.updateSettings(settings.copy(recallTokens = it)) }
                                VaultLimit("Memories Per Response", settings.maxRecall, 1..40, !busy) { viewModel.updateSettings(settings.copy(maxRecall = it)) }
                                TextButton(onClick = { advancedControls = !advancedControls }) {
                                    Text(if (advancedControls) "Hide Advanced Memory Controls" else "Show Advanced Memory Controls")
                                }
                                if (advancedControls) {
                                    VaultLimit("Memory Capacity", settings.maxFacts, 16..16384, !busy) { viewModel.updateSettings(settings.copy(maxFacts = it)) }
                                    VaultLimit("Retention days · 0 keeps memories", settings.retentionDays, 0..365, !busy) { viewModel.updateSettings(settings.copy(retentionDays = it)) }
                                }
                            }
                        }
                    }
                    if (advancedControls) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Connected Memory", style = MaterialTheme.typography.titleMedium)
                                    VaultToggle("Automatically Recall From Selected MCPs", settings.externalRecallEnabled, !busy, "Add a compatible memory MCP and enable its memory search tool in the AI profile. Selected servers receive up to 500 characters from the current question; local saved memories and chat history are not uploaded.") { viewModel.updateSettings(settings.copy(externalRecallEnabled = it)) }
                                    if (!settings.allowCloudRecall || settings.sameChatOnly || settings.reviewBeforeRecall) Text("Automatic connected recall is paused by cloud recall, original-chat-only or review-before-use controls.", style = MaterialTheme.typography.bodySmall)
                                    if (connections.isEmpty()) Text("No MCP connections configured yet.")
                                    connections.forEach { connection ->
                                        val selected = connection.connectionUid in settings.externalMemoryConnections
                                        VaultToggle(connection.name, selected, !busy) { enabled ->
                                            val ids = if (enabled) settings.externalMemoryConnections + connection.connectionUid else settings.externalMemoryConnections - connection.connectionUid
                                            viewModel.updateSettings(settings.copy(externalMemoryConnections = ids))
                                        }
                                        if (selected) {
                                            var memoryScope by remember(connection.connectionUid, settings.externalMemoryScopes) { mutableStateOf(settings.externalMemoryScopes[connection.connectionUid].orEmpty()) }
                                            OutlinedTextField(memoryScope, { memoryScope = it.take(200) }, label = { Text("User ID / group ID / space key") }, supportingText = { Text("Blank uses the server's authorized default scope.") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                            TextButton(onClick = { viewModel.updateSettings(settings.copy(externalMemoryScopes = settings.externalMemoryScopes + (connection.connectionUid to memoryScope.trim()))) }, enabled = !busy) { Text("Save scope") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Local Memory Tools", style = MaterialTheme.typography.titleMedium)
                            SettingsHelpIcon("Capture and recall, search and open nodes, read the graph, add observations, forget memories, and search indexed documents. These tools run inside the app; cloud recall sends selected reference text only when enabled.")
                        }
                        TextButton(onClick = { clearing = true }, enabled = !busy) { Text("Clear Saved Memories") }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        AlertDialog(
            onDismissRequest = {
                adding = false
                editing = null
            },
            title = { Text("Remember something") },
            text = { OutlinedTextField(draft, { draft = it.take(1000) }, label = { Text("Fact, preference or note") }, supportingText = { Text("${draft.length}/1000") }) },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    viewModel.saveFact(draft, editing?.id)
                    adding = false
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    adding = false
                    editing = null
                }) { Text("Cancel") }
            }
        )
    }
    if (restructuring) {
        AlertDialog(onDismissRequest = { restructuring = false }, title = { Text("Merge or split selected memories") }, text = {
            Column {
                Text("One replacement memory per line. Use one line to merge. Scope and correction history are retained.")
                OutlinedTextField(replacementText, { replacementText = it }, label = { Text("Reviewed memories") }, maxLines = 8)
            }
        }, confirmButton = {
            TextButton(onClick = {
                viewModel.restructure(selectedMemories, replacementText.lines())
                selectedMemories = emptySet()
                restructuring = false
            }) { Text("Replace selected") }
        }, dismissButton = { TextButton(onClick = { restructuring = false }) { Text("Cancel") } })
    }
    if (deleting != null || clearing) {
        AlertDialog(
            onDismissRequest = {
                deleting = null
                clearing = false
            },
            title = { Text(if (clearing) "Clear saved memories?" else "Forget this memory?") },
            text = { Text(if (clearing) "Removes saved facts and turns memory off. Conversation attachments stay in their chats." else "Stops future recall. Retrying the source message will not restore it.") },
            confirmButton = {
                TextButton(onClick = {
                    if (clearing) viewModel.clear() else deleting?.let(viewModel::delete)
                    deleting = null
                    clearing = false
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleting = null
                    clearing = false
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun VaultToggle(title: String, checked: Boolean, enabled: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(memorySettingTitle(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        description?.let { SettingsHelpIcon(it) }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun VaultLimit(title: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat())) }
    Text("${memorySettingTitle(title)}: ${draft.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onChange(draft.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
}

private fun memorySettingTitle(value: String): String =
    value.split(Regex("\\s+")).joinToString(" ") { word ->
        word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
    }
