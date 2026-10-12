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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.LocalSettingSearchFocus
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.settingSearchFocus
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactVaultScreen(viewModel: FactVaultViewModel, onBack: () -> Unit, onOpenConversation: (Int, Int?) -> Unit = { _, _ -> }, initialTab: String = "Memories") {
    val vault by viewModel.vault.collectAsStateWithLifecycle()
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle(emptyList())
    val attachments by viewModel.attachments.collectAsStateWithLifecycle(emptyList())
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val indexWarning by viewModel.indexWarning.collectAsStateWithLifecycle()
    val semanticStatus by viewModel.semanticStatus.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab.substringBefore(':').takeIf { it in listOf("Memories", "Topics", "Documents", "Controls", "Review", "Overview") } ?: "Memories") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    var inspecting by remember { mutableStateOf<VaultFact?>(null) }
    var editing by remember { mutableStateOf<VaultFact?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var selectedMemories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var restructuring by remember { mutableStateOf(false) }
    var replacementText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var advancedControls by rememberSaveable { mutableStateOf(initialTab.contains(':')) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    inspecting?.let { entry ->
        AlertDialog(
            onDismissRequest = { inspecting = null },
            title = { Text(stringResource(R.string.ui_v2_memory_provenance)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.fact.target.name)
                    Text("Source: ${entry.source.replace('_', ' ')}")
                    Text("Scope: ${entry.scope}")
                    Text(if (entry.source == "manual") "Added or corrected by you" else "Source-bound extraction · verify against the original message")
                    Text("Revision: ${entry.revision}")
                    Text("Saved: ${entry.savedAtMillis.takeIf { it > 0 }?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Not recorded"}")
                    Text("Last supported: ${entry.lastSeenMillis.takeIf { it > 0 }?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Not recorded"}")
                    Text(
                        "Status: ${if (entry.pendingReplacements.isNotEmpty()) {
                            "Needs conflict review"
                        } else if (entry.enabled) {
                            "Enabled fact; recall still follows your memory and profile controls"
                        } else {
                            "Disabled or awaiting review"
                        }}"
                    )
                    Text("${entry.occurrences} supporting messages · ${entry.previousValues.size} recorded edits")
                }
            },
            confirmButton = { TextButton(onClick = { inspecting = null }) { Text(stringResource(R.string.local_tools_settings_panel_label_12)) } }
        )
    }
    val settings = vault.settings
    CompositionLocalProvider(LocalSettingSearchFocus provides initialTab.substringAfter(':', "")) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_memory)) },
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
                                    Text(stringResource(R.string.ui_v2_memory_overview), style = MaterialTheme.typography.titleLarge)
                                    SettingsHelpIcon("Private saved facts, recurring topics, semantic recall, and indexed documents are managed here.")
                                }
                                Switch(vault.enabled, viewModel::setEnabled, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Enable Local Memory" })
                            }
                            Text("${vault.facts.size} memories · ${documents.size} indexed documents", style = MaterialTheme.typography.labelLarge)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${vault.facts.count { it.awaitingReview || it.pendingReplacements.isNotEmpty() }} pending · ${vault.facts.count { !it.enabled && !it.awaitingReview && it.pendingReplacements.isEmpty() && it.supersededBy == null }} disabled", style = MaterialTheme.typography.bodySmall)
                                SettingsHelpIcon(semanticStatus.detail + " Auto-recall skips Free profiles.")
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Memories", "Review", "Overview").forEach { label ->
                            FilterChip(tab == label, {
                                tab = label
                                query = ""
                            }, label = { Text(when (label) { "Memories" -> "Library"; "Overview" -> "Activity"; else -> label }) })
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { tab = "Controls" }) { Text("Controls") }
                        TextButton(onClick = { tab = "Documents" }) { Text("Sources") }
                        TextButton(onClick = { tab = "Topics" }) { Text("Topics") }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let {
                    item {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.attachment_retry)) }
                    }
                }
                status?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
                indexWarning?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                when (tab) {
                    "Overview" -> {
                        item {
                            SettingsPanel("Current memory status") {
                                Text("${vault.facts.count { it.enabled && it.supersededBy == null && !it.awaitingReview }} available facts · ${vault.facts.count { it.awaitingReview || it.pendingReplacements.isNotEmpty() }} need review")
                                Text("${documents.size} indexed documents · ${vault.topics.size} recurring topics")
                                Text(semanticStatus.detail, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { tab = "Review" }) { Text(stringResource(R.string.ui_v2_review_proposed_facts)) }
                                TextButton(onClick = { tab = "Controls" }) { Text(stringResource(R.string.ui_v2_privacy_and_recall_controls)) }
                            }
                        }
                    }
                    "Topics" -> {
                        item { SettingsHero("ON DEVICE", "Recurring interests", "Topics become memories after ${settings.topicRepetitions} different messages.") }
                        val topics = vault.topics.sortedByDescending { it.messageKeys.size }
                        if (topics.isEmpty()) item { Text(stringResource(R.string.ui_v2_repeated_topics_will_appear_here_as_you_chat)) }
                        items(topics, key = { "${it.scope}:${it.label}" }) { topic ->
                            SettingsPanel(topic.label) {
                                Text("${topic.messageKeys.size} mentions · ${topic.scope}", style = MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(progress = { (topic.messageKeys.size.toFloat() / settings.topicRepetitions).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    "Memories", "Review" -> {
                        item {
                            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.ui_v2_find_a_memory)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("All", "Pinned", "Disabled").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
                            }
                        }
                        if (selectedMemories.isNotEmpty()) {
                            item {
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    TextButton(onClick = {
                                        viewModel.reviewFacts(selectedMemories, true)
                                        selectedMemories = emptySet()
                                    }, enabled = !busy) { Text(stringResource(R.string.ui_v2_allow_selected)) }
                                    TextButton(onClick = {
                                        viewModel.reviewFacts(selectedMemories, false)
                                        selectedMemories = emptySet()
                                    }, enabled = !busy) { Text(stringResource(R.string.ui_v2_disable_selected)) }
                                    TextButton(onClick = {
                                        replacementText = vault.facts.filter { it.id in selectedMemories }.joinToString("\n") { it.fact.target.name }
                                        restructuring = true
                                    }, enabled = !busy) { Text(stringResource(R.string.ui_v2_merge_split)) }
                                    TextButton(onClick = { selectedMemories = emptySet() }) { Text(stringResource(R.string.ui_v2_clear_selection)) }
                                }
                            }
                        }
                        val shown = vault.facts.filter {
                            "${it.fact.entity.name} ${it.fact.target.name}".contains(query, true) &&
                                (filter != "Pinned" || it.pinned) &&
                                (filter != "Disabled" || !it.enabled && !it.awaitingReview && it.pendingReplacements.isEmpty() && it.supersededBy == null) &&
                                (tab != "Review" || it.awaitingReview || it.pendingReplacements.isNotEmpty())
                        }.sortedWith(compareByDescending<VaultFact> { it.pinned }.thenByDescending { it.savedAtMillis })
                        if (shown.isEmpty()) item { Text(if (tab == "Review") "All caught up. New suggestions and conflicting changes appear here." else "No matching memories. Add a fact, preference, or useful note.", style = MaterialTheme.typography.bodyMedium) }
                        items(shown, key = { it.id }) { entry ->
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (entry.fact.relation.relationType == "REMEMBERS" || entry.source == "local_model_observation") entry.fact.target.name else "${entry.fact.entity.name} ${entry.fact.relation.relationType.lowercase().replace('_', ' ')} ${entry.fact.target.name}", style = MaterialTheme.typography.titleMedium)
                                    Text("${entry.source.replace('_', ' ')} · ${if (entry.sourceChatId > 0) "chat ${entry.sourceChatId}, message ${entry.sourceMessageId}" else "Added by you"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text("${entry.scope} · ${entry.occurrences} supporting messages · revision ${entry.revision}", style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { inspecting = entry }) { Text(stringResource(R.string.ui_v2_source_and_history)) }
                                    if (entry.awaitingReview && entry.pendingReplacements.isEmpty()) {
                                        Row {
                                            TextButton(onClick = { viewModel.setFactEnabled(entry.id, true) }, enabled = !busy) { Text("Accept") }
                                            TextButton(onClick = { deleting = entry.id }, enabled = !busy) { Text("Reject") }
                                        }
                                    }
                                    if (entry.pendingReplacements.isNotEmpty()) {
                                        Text(stringResource(R.string.ui_v2_change_needs_your_review_existing_memory_stays_active), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                        entry.pendingReplacements.keys.forEach { oldId ->
                                            vault.facts.firstOrNull { it.id == oldId }?.let { old ->
                                                Text("Existing: ${old.fact.target.name}", style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        Row {
                                            TextButton(onClick = { viewModel.setFactEnabled(entry.id, true) }, enabled = !busy) { Text(stringResource(R.string.ui_v2_replace_existing)) }
                                            TextButton(onClick = { viewModel.keepBoth(entry.id) }, enabled = !busy) { Text(stringResource(R.string.ui_v2_keep_both)) }
                                            TextButton(onClick = { viewModel.delete(entry.id) }, enabled = !busy) { Text(stringResource(R.string.ui_v2_keep_existing)) }
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
                                    if (entry.sourceChatId > 0) TextButton(onClick = { onOpenConversation(entry.sourceChatId, entry.sourceMessageId.takeIf { it > 0 }) }) { Text(stringResource(R.string.ui_v2_view_original_message)) }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.ui_v2_recall_details), style = MaterialTheme.typography.labelSmall)
                                        SettingsHelpIcon("Recall uses ${if (entry.pinned) "pinned priority, " else ""}query terms and aliases, semantic similarity when available, recency, and repeated support. The current request scope always applies.")
                                    }
                                    if (entry.source == "recurring_topic") TextButton(onClick = { deleting = entry.id }) { Text(stringResource(R.string.ui_v2_wrong_topic_forget)) }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.Checkbox(entry.id in selectedMemories, { selectedMemories = if (it) selectedMemories + entry.id else selectedMemories - entry.id }, enabled = !busy)
                                        Switch(entry.enabled, { viewModel.setFactEnabled(entry.id, it) }, enabled = !busy && entry.pendingReplacements.isEmpty(), modifier = Modifier.semantics { contentDescription = "Recall this memory" })
                                        TextButton(onClick = {
                                            editing = entry
                                            draft = entry.fact.target.name
                                        }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.fact_vault_screen_label_12)) }
                                        IconButton(onClick = { viewModel.pin(entry.id, !entry.pinned) }, enabled = !busy) { Icon(Icons.Rounded.PushPin, if (entry.pinned) "Unpin" else "Pin", tint = if (entry.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                                        IconButton(onClick = { deleting = entry.id }, enabled = !busy) { Icon(Icons.Rounded.Delete, "Forget memory") }
                                    }
                                }
                            }
                        }
                    }
                    "Documents" -> {
                        item {
                            Text(stringResource(R.string.ui_v2_conversation_library), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.ui_v2_all_attachments_stay_linked_to_their_original_conversations_index), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::indexDocuments, enabled = !busy && vault.enabled) { Text(stringResource(R.string.ui_v2_index_document_text_locally)) }
                            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.ui_v2_find_an_attachment)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (attachments.isEmpty()) item { Text(stringResource(R.string.ui_v2_attach_a_file_in_any_conversation_to_see_it_here)) }
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
                                        }) { Text(stringResource(R.string.ui_v2_open)) }
                                        if (indexed != null) {
                                            TextButton(onClick = { context.startActivity(Intent(context, KnowledgeSourceActivity::class.java).setData(Uri.parse("gptmobile://knowledge/${indexed.id}"))) }) { Text(stringResource(R.string.ui_v2_read_text)) }
                                            TextButton(onClick = { viewModel.removeDocument(indexed.id) }, enabled = !busy) { Text(stringResource(R.string.ui_v2_remove_index)) }
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
                                            Text(stringResource(R.string.ui_v2_memory_controls), style = MaterialTheme.typography.titleMedium)
                                            SettingsHelpIcon("Capture durable facts automatically, then recall only what is useful.")
                                        }
                                        TextButton(onClick = viewModel::applyRecommendedControls, enabled = !busy) { Text(stringResource(R.string.ui_v2_recommended)) }
                                    }
                                    Text("Learning mode", style = MaterialTheme.typography.titleSmall)
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("Manual", "Review first", "Automatic").forEach { mode ->
                                            val selected = when (mode) {
                                                "Manual" -> !settings.learningEnabled
                                                "Review first" -> settings.learningEnabled && settings.reviewBeforeRecall
                                                else -> settings.learningEnabled && !settings.reviewBeforeRecall
                                            }
                                            FilterChip(selected, {
                                                viewModel.updateSettings(settings.copy(learningEnabled = mode != "Manual", reviewBeforeRecall = mode == "Review first"))
                                            }, label = { Text(mode) }, enabled = !busy)
                                        }
                                    }
                                    Text("Manual saves only what you add. Review first holds new suggestions for approval. Automatic admits source-bound facts under your capture controls.", style = MaterialTheme.typography.bodySmall)
                                    var sensitivity by remember(settings.captureSensitivity) { mutableStateOf(settings.captureSensitivity.toFloat()) }
                                    Text("Capture breadth · ${sensitivity.toInt()}%", style = MaterialTheme.typography.titleSmall)
                                    Slider(
                                        value = sensitivity,
                                        onValueChange = { sensitivity = it },
                                        onValueChangeFinished = { viewModel.updateSettings(settings.copy(captureSensitivity = sensitivity.toInt())) },
                                        valueRange = 0f..100f,
                                        enabled = !busy && vault.enabled && settings.learningEnabled,
                                        modifier = Modifier.semantics { contentDescription = "Memory capture breadth" }
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(stringResource(R.string.ui_v2_only_key_facts), style = MaterialTheme.typography.labelSmall)
                                        Text(stringResource(R.string.ui_v2_more_details), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.ui_v2_capture_policy), style = MaterialTheme.typography.labelSmall)
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
                                    TextButton(onClick = viewModel::rebuildSemanticIndex, enabled = !busy && vault.enabled && settings.semanticRecall) { Text(stringResource(R.string.ui_v2_rebuild_semantic_index)) }
                                    VaultToggle("Prioritize Pinned Memories", settings.alwaysRecallPinned, !busy && settings.recallEnabled, "Up to two pinned memories get priority within the recall count and estimated token budget. Scope and cloud permissions always apply.") { viewModel.updateSettings(settings.copy(alwaysRecallPinned = it)) }
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
                                        Text(stringResource(R.string.ui_v2_connected_memory), style = MaterialTheme.typography.titleMedium)
                                        VaultToggle("Automatically Recall From Selected MCPs", settings.externalRecallEnabled, !busy, "Add a compatible memory MCP and enable its memory search tool in the AI profile. Selected servers receive up to 500 characters from the current question; local saved memories and chat history are not uploaded.") { viewModel.updateSettings(settings.copy(externalRecallEnabled = it)) }
                                        if (!settings.allowCloudRecall || settings.sameChatOnly || settings.reviewBeforeRecall) Text(stringResource(R.string.ui_v2_automatic_connected_recall_is_paused_by_cloud_recall_original_cha), style = MaterialTheme.typography.bodySmall)
                                        if (connections.isEmpty()) Text(stringResource(R.string.ui_v2_no_mcp_connections_configured_yet))
                                        connections.forEach { connection ->
                                            val selected = connection.connectionUid in settings.externalMemoryConnections
                                            VaultToggle(connection.name, selected, !busy) { enabled ->
                                                val ids = if (enabled) settings.externalMemoryConnections + connection.connectionUid else settings.externalMemoryConnections - connection.connectionUid
                                                viewModel.updateSettings(settings.copy(externalMemoryConnections = ids))
                                            }
                                            if (selected) {
                                                var memoryScope by remember(connection.connectionUid, settings.externalMemoryScopes) { mutableStateOf(settings.externalMemoryScopes[connection.connectionUid].orEmpty()) }
                                                OutlinedTextField(memoryScope, { memoryScope = it.take(200) }, label = { Text(stringResource(R.string.ui_v2_user_id_group_id_space_key)) }, supportingText = { Text(stringResource(R.string.ui_v2_blank_uses_the_server_s_authorized_default_scope)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                                TextButton(onClick = { viewModel.updateSettings(settings.copy(externalMemoryScopes = settings.externalMemoryScopes + (connection.connectionUid to memoryScope.trim()))) }, enabled = !busy) { Text(stringResource(R.string.ui_v2_save_scope)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.ui_v2_local_memory_tools), style = MaterialTheme.typography.titleMedium)
                                SettingsHelpIcon("Capture and recall, search and open nodes, read the graph, add observations, forget memories, and search indexed documents. These tools run inside the app; cloud recall sends selected reference text only when enabled.")
                            }
                            TextButton(onClick = { clearing = true }, enabled = !busy) { Text(stringResource(R.string.ui_v2_clear_saved_memories)) }
                        }
                    }
                }
            }
        }
        if (adding || editing != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!busy) {
                        adding = false
                        editing = null
                    }
                },
                title = { Text(stringResource(R.string.ui_v2_remember_something)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft, { draft = it.take(1000) }, enabled = !busy, label = { Text(stringResource(R.string.ui_v2_fact_preference_or_note)) }, supportingText = { Text("${draft.length}/1000") })
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(enabled = draft.isNotBlank() && !busy, onClick = {
                        viewModel.saveFact(draft, editing?.id) {
                            adding = false
                            editing = null
                            draft = ""
                        }
                    }) { Text(stringResource(R.string.local_tools_settings_panel_label_13)) }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = {
                        adding = false
                        editing = null
                    }) { Text(stringResource(R.string.fact_vault_screen_label_19)) }
                }
            )
        }
        if (restructuring) {
            AlertDialog(onDismissRequest = { restructuring = false }, title = { Text(stringResource(R.string.ui_v2_merge_or_split_selected_memories)) }, text = {
                Column {
                    Text(stringResource(R.string.ui_v2_one_replacement_memory_per_line_use_one_line_to_merge_scope_and_c))
                    OutlinedTextField(replacementText, { replacementText = it }, label = { Text(stringResource(R.string.ui_v2_reviewed_memories)) }, maxLines = 8)
                }
            }, confirmButton = {
                TextButton(onClick = {
                    viewModel.restructure(selectedMemories, replacementText.lines())
                    selectedMemories = emptySet()
                    restructuring = false
                }) { Text(stringResource(R.string.ui_v2_replace_selected)) }
            }, dismissButton = { TextButton(onClick = { restructuring = false }) { Text(stringResource(R.string.fact_vault_screen_label_19)) } })
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
                    }) { Text(stringResource(R.string.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        deleting = null
                        clearing = false
                    }) { Text(stringResource(R.string.fact_vault_screen_label_19)) }
                }
            )
        }
    }
}

@Composable
private fun VaultToggle(title: String, checked: Boolean, enabled: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.settingSearchFocus(title), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(memorySettingTitle(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        description?.let { SettingsHelpIcon(it) }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun VaultLimit(title: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat())) }
    Text("${memorySettingTitle(title)}: ${draft.toInt()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.settingSearchFocus(title))
    Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onChange(draft.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
}

private fun memorySettingTitle(value: String): String =
    value.split(Regex("\\s+")).joinToString(" ") { word ->
        word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
    }

internal val memoryControlSearchEntries = listOf(
    SettingsSearchEntry("memory.control.0", "Learn From New Messages", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Learn From New Messages"),
    SettingsSearchEntry("memory.control.1", "Forget Memories When Their Conversation Is Deleted", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Forget Memories When Their Conversation Is Deleted"),
    SettingsSearchEntry("memory.control.2", "Recall Saved Memories", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Recall Saved Memories"),
    SettingsSearchEntry("memory.control.3", "Semantic Recall · Fully On-Device", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Semantic Recall · Fully On-Device"),
    SettingsSearchEntry("memory.control.4", "Prioritize Pinned Memories", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Always Include Pinned Memories"),
    SettingsSearchEntry("memory.control.5", "Allow Recall In Cloud Requests", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Allow Recall In Cloud Requests"),
    SettingsSearchEntry("memory.control.6", "Recall Only Within The Original Chat", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Recall Only Within The Original Chat"),
    SettingsSearchEntry("memory.control.7", "Review New Memories Before Use", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Review New Memories Before Use"),
    SettingsSearchEntry("memory.control.8", "Learn Preferences", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Learn Preferences"),
    SettingsSearchEntry("memory.control.9", "Learn Relationships", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Learn Relationships"),
    SettingsSearchEntry("memory.control.10", "Learn Recurring Topics", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Learn Recurring Topics"),
    SettingsSearchEntry("memory.control.11", "Prioritize Recent Memories", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Prioritize Recent Memories"),
    SettingsSearchEntry("memory.control.12", "Prioritize Repeated Information", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Prioritize Repeated Information"),
    SettingsSearchEntry("memory.control.13", "Mentions before learning a topic", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Mentions before learning a topic"),
    SettingsSearchEntry("memory.control.14", "Use Local Model For Richer Learning", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Use Local Model For Richer Learning"),
    SettingsSearchEntry("memory.control.15", "Make Room For New Automatic Memories", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Make Room For New Automatic Memories"),
    SettingsSearchEntry("memory.control.16", "New Facts Per Message", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:New Facts Per Message"),
    SettingsSearchEntry("memory.control.17", "Recall Token Budget · Estimate", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Recall Token Budget · Estimate"),
    SettingsSearchEntry("memory.control.18", "Memories Per Response", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Memories Per Response"),
    SettingsSearchEntry("memory.control.19", "Memory Capacity", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Memory Capacity"),
    SettingsSearchEntry("memory.control.20", "Retention days · 0 keeps memories", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Retention days · 0 keeps memories"),
    SettingsSearchEntry("memory.control.21", "Automatically Recall From Selected MCPs", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls:Automatically Recall From Selected MCPs")
)
