package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Button
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.LocalSettingSearchFocus
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.glassWindowBackdrop
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
    var draftLocalOnly by rememberSaveable { mutableStateOf(false) }
    var draftType by rememberSaveable { mutableStateOf("NOTE") }
    var selectedMemories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var restructuring by remember { mutableStateOf(false) }
    var replacementText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var memoryCategory by rememberSaveable { mutableStateOf(memoryCategoryForFocus(initialTab.substringAfter(':', ""))) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    inspecting?.let { entry ->
        AlertDialog(
            onDismissRequest = { inspecting = null },
            title = { Text(stringResource(R.string.ui_v2_memory_provenance)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.fact.target.name)
                    Text(if (entry.localOnly) "Available only to local AI" else "Remote sharing follows your settings")
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
                    if (entry.previousValues.isNotEmpty()) {
                        Text("History", style = MaterialTheme.typography.titleSmall)
                        entry.previousValues.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (entry.sourceChatId > 0) TextButton(onClick = { onOpenConversation(entry.sourceChatId, entry.sourceMessageId.takeIf { it > 0 }) }) { Text("Open original message") }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = {
                            editing = entry
                            draft = entry.fact.target.name
                            draftLocalOnly = entry.localOnly
                            draftType = entry.memoryType
                            inspecting = null
                        }) { Text("Edit") }
                        TextButton(onClick = {
                            viewModel.pin(entry.id, !entry.pinned)
                            inspecting = null
                        }) { Text(if (entry.pinned) "Unpin" else "Pin") }
                        TextButton(onClick = {
                            viewModel.setFactEnabled(entry.id, !entry.enabled)
                            inspecting = null
                        }) { Text(if (entry.enabled) "Archive" else "Restore") }
                        TextButton(onClick = {
                            deleting = entry.id
                            inspecting = null
                        }) { Text("Delete") }
                    }
                    TextButton(onClick = {
                        selectedMemories = selectedMemories + entry.id
                        inspecting = null
                    }) { Text("Select for merge or split") }
                }
            },
            confirmButton = { TextButton(onClick = { inspecting = null }) { Text(stringResource(R.string.local_tools_settings_panel_label_12)) } }
        )
    }
    val settings = vault.settings
    CompositionLocalProvider(LocalSettingSearchFocus provides initialTab.substringAfter(':', "")) {
        Scaffold(modifier = Modifier.glassWindowBackdrop(), containerColor = dev.chungjungsoo.gptmobile.presentation.common.overlayWindowContainerColor(), topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_memory)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { SettingsHelpIcon("Saving happens on this phone. Selected memory may be sent to remote AI only when your sharing settings allow it. Temporary chats do not save or recall memory.") }
            )
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (vault.enabled) "Memory is on" else "Memory is off", style = MaterialTheme.typography.titleMedium)
                                    SettingsHelpIcon("Private saved facts, recurring topics, semantic recall, and indexed documents are managed here.")
                                }
                                Switch(vault.enabled, viewModel::setEnabled, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Enable Local Memory" })
                            }
                            Text(if (settings.allowCloudRecall) "Remote sharing allowed for eligible memories" else "Remote sharing is off", style = MaterialTheme.typography.bodyMedium)
                            Text("${vault.facts.count { it.enabled && !it.awaitingReview && it.supersededBy == null }} saved · ${vault.facts.count { it.awaitingReview || it.pendingReplacements.isNotEmpty() }} to review · ${documents.size} sources", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                item {
                    val selectedTab = when (tab) {
                        "Documents" -> 1
                        "Controls", "Overview", "Topics" -> 2
                        else -> 0
                    }
                    TabRow(selectedTabIndex = selectedTab) {
                        listOf("Memories", "Sources", "Settings").forEachIndexed { index, title ->
                            Tab(selected = selectedTab == index, onClick = { tab = listOf("Memories", "Documents", "Controls")[index] }, text = { Text(title) })
                        }
                    }
                    if (selectedTab == 0) {
                        SettingsTabs(listOf("Saved", "Needs review"), if (tab == "Review") 1 else 0) { tab = if (it == 1) "Review" else "Memories" }
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
                        if (tab == "Memories") {
                            item {
                                Button(onClick = {
                                    adding = true
                                    draft = ""
                                    draftLocalOnly = false
                                    draftType = "NOTE"
                                }) {
                                    Icon(Icons.Rounded.Add, null)
                                    Text("Add memory", Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        item {
                            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.ui_v2_find_a_memory)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("All", "Pinned", "Archived", "History").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
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
                                (
                                    tab == "Review" ||
                                        when (filter) {
                                            "Archived" -> !it.enabled && !it.awaitingReview && it.pendingReplacements.isEmpty() && it.supersededBy == null
                                            "History" -> it.supersededBy != null || it.validUntilMillis?.let { end -> end <= System.currentTimeMillis() } == true
                                            else -> it.enabled && !it.awaitingReview && it.supersededBy == null && (it.validUntilMillis == null || it.validUntilMillis > System.currentTimeMillis())
                                        }
                                    ) &&
                                (tab != "Review" || it.awaitingReview || it.pendingReplacements.isNotEmpty())
                        }.sortedWith(compareByDescending<VaultFact> { it.pinned }.thenByDescending { it.savedAtMillis })
                        if (shown.isEmpty()) item { Text(if (tab == "Review") "All caught up. New suggestions and conflicting changes appear here." else "No matching memories. Add a fact, preference, or useful note.", style = MaterialTheme.typography.bodyMedium) }
                        items(shown, key = { it.id }) { entry ->
                            Card(Modifier.fillMaxWidth().clickable { inspecting = entry }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (entry.fact.relation.relationType == "REMEMBERS" || entry.source == "local_model_observation") entry.fact.target.name else "${entry.fact.entity.name} ${entry.fact.relation.relationType.lowercase().replace('_', ' ')} ${entry.fact.target.name}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${entry.memoryType.lowercase().replaceFirstChar { it.titlecase() }} · ${if (entry.source == "manual") "Confirmed by you" else "From a conversation"}${if (entry.localOnly) " · Local only" else ""}${if (entry.pinned) " · Pinned" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    if (entry.id in selectedMemories) Text("Selected", color = MaterialTheme.colorScheme.primary)
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
                        item { SettingsTabs(listOf("Basics", "Learning", "Recall", "Storage", "Connections"), listOf("Basics", "Learning", "Recall", "Storage", "Connections").indexOf(memoryCategory).coerceAtLeast(0)) { memoryCategory = listOf("Basics", "Learning", "Recall", "Storage", "Connections")[it] } }
                        item {
                            SettingsPanel(memoryCategory) {
                                when (memoryCategory) {
                                    "Basics" -> {
                                        Text("Choose how memory learns", style = MaterialTheme.typography.titleSmall, modifier = Modifier.settingSearchFocus("Choose how memory learns"))
                                        SettingsTabs(
                                            listOf("Manual", "Review first", "Automatic"),
                                            if (!settings.learningEnabled) {
                                                0
                                            } else if (settings.reviewBeforeRecall) {
                                                1
                                            } else {
                                                2
                                            }
                                        ) { mode ->
                                            viewModel.updateSettings(settings.copy(learningEnabled = mode != 0, reviewBeforeRecall = mode == 1))
                                        }
                                        Text("Manual saves only what you add. Review first asks before using new memories.", style = MaterialTheme.typography.bodySmall)
                                        VaultToggle("Use saved memories", settings.recallEnabled, !busy) { viewModel.updateSettings(settings.copy(recallEnabled = it)) }
                                        VaultToggle("Allow recall in cloud requests", settings.allowCloudRecall, !busy, "Only selected, eligible memories are shared. Memories marked local only stay on this phone.") { viewModel.updateSettings(settings.copy(allowCloudRecall = it)) }
                                        TextButton(onClick = viewModel::applyRecommendedControls, enabled = !busy) { Text("Use balanced defaults") }
                                    }
                                    "Learning" -> {
                                        Text("Capture detail", style = MaterialTheme.typography.titleSmall)
                                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(40 to "Essential", 65 to "Balanced", 85 to "Detailed").forEach { (value, title) ->
                                                FilterChip(settings.captureSensitivity == value, { viewModel.updateSettings(settings.copy(captureSensitivity = value)) }, enabled = !busy, label = { Text(title) })
                                            }
                                        }
                                        VaultToggle("Learn preferences", settings.learnPreferences, !busy) { viewModel.updateSettings(settings.copy(learnPreferences = it)) }
                                        VaultToggle("Learn relationships", settings.learnRelationships, !busy) { viewModel.updateSettings(settings.copy(learnRelationships = it)) }
                                        VaultToggle("Learn recurring interests", settings.learnRecurringTopics, !busy) { viewModel.updateSettings(settings.copy(learnRecurringTopics = it)) }
                                        VaultToggle("Use an idle local AI for richer learning", settings.localModelLearning, !busy) { viewModel.updateSettings(settings.copy(localModelLearning = it)) }
                                        VaultLimit("Mentions before learning an interest", settings.topicRepetitions, 2..10, !busy) { viewModel.updateSettings(settings.copy(topicRepetitions = it)) }
                                        VaultLimit("New memories per message", settings.maxCapturePerMessage, 1..16, !busy) { viewModel.updateSettings(settings.copy(maxCapturePerMessage = it)) }
                                        TextButton(onClick = { tab = "Topics" }) { Text("View recurring interests") }
                                    }
                                    "Recall" -> {
                                        VaultToggle("Match meaning on this device", settings.semanticRecall, !busy) { viewModel.updateSettings(settings.copy(semanticRecall = it)) }
                                        VaultToggle("Prioritize relevant pinned memories", settings.alwaysRecallPinned, !busy) { viewModel.updateSettings(settings.copy(alwaysRecallPinned = it)) }
                                        VaultToggle("Prioritize recent information", settings.rankByRecency, !busy) { viewModel.updateSettings(settings.copy(rankByRecency = it)) }
                                        VaultToggle("Prioritize repeated information", settings.rankByFrequency, !busy) { viewModel.updateSettings(settings.copy(rankByFrequency = it)) }
                                        VaultToggle("Recall only in the original chat", settings.sameChatOnly, !busy) { viewModel.updateSettings(settings.copy(sameChatOnly = it)) }
                                        VaultLimit("Memories per response", settings.maxRecall, 1..40, !busy) { viewModel.updateSettings(settings.copy(maxRecall = it)) }
                                        VaultLimit("Recall context budget", settings.recallTokens, 128..8192, !busy) { viewModel.updateSettings(settings.copy(recallTokens = it)) }
                                        Text(semanticStatus.detail, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = viewModel::rebuildSemanticIndex, enabled = !busy && vault.enabled && settings.semanticRecall) { Text("Rebuild search index") }
                                    }
                                    "Storage" -> {
                                        VaultLimit("Memory capacity", settings.maxFacts, 16..16384, !busy) { viewModel.updateSettings(settings.copy(maxFacts = it)) }
                                        VaultLimit("Retention days · 0 keeps memories", settings.retentionDays, 0..365, !busy) { viewModel.updateSettings(settings.copy(retentionDays = it)) }
                                        VaultToggle("Make room for new automatic memories", settings.rotateAutomaticFacts, !busy) { viewModel.updateSettings(settings.copy(rotateAutomaticFacts = it)) }
                                        VaultToggle("Forget when the source conversation is deleted", settings.forgetWithConversation, !busy) { viewModel.updateSettings(settings.copy(forgetWithConversation = it)) }
                                        TextButton(onClick = { clearing = true }, enabled = !busy) { Text("Clear saved memories") }
                                    }
                                    else -> Text("Choose which connected memory services may receive your question. Local memory remains managed on this device.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (memoryCategory == "Connections") {
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
                        VaultToggle("Keep on this phone only", draftLocalOnly, !busy) { draftLocalOnly = it }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf("NOTE", "FACT", "PREFERENCE", "PROCEDURE", "CONTINUITY").forEach { type ->
                                FilterChip(draftType == type, { draftType = type }, label = { Text(type.lowercase().replaceFirstChar { it.titlecase() }) })
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(enabled = draft.isNotBlank() && !busy, onClick = {
                        viewModel.saveFact(draft, editing?.id, editing?.revision, draftLocalOnly, draftType) {
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

private val memoryControlGroups = linkedMapOf(
    "Basics" to listOf("Choose how memory learns", "Use saved memories", "Allow recall in cloud requests"),
    "Learning" to listOf("Learn preferences", "Learn relationships", "Learn recurring interests", "Use an idle local AI for richer learning", "Mentions before learning an interest", "New memories per message"),
    "Recall" to listOf("Match meaning on this device", "Prioritize relevant pinned memories", "Prioritize recent information", "Prioritize repeated information", "Recall only in the original chat", "Memories per response", "Recall context budget"),
    "Storage" to listOf("Memory capacity", "Retention days · 0 keeps memories", "Make room for new automatic memories", "Forget when the source conversation is deleted"),
    "Connections" to listOf("Automatically Recall From Selected MCPs")
)

private fun memoryCategoryForFocus(focus: String): String = memoryControlGroups.entries.firstOrNull { (_, titles) -> titles.any { it.equals(focus, true) } }?.key ?: "Basics"

internal val memoryControlSearchEntries = memoryControlGroups.entries.flatMap { (category, titles) -> titles.map { category to it } }.mapIndexed { index, (category, title) ->
    SettingsSearchEntry("memory.control.$index", memorySettingTitle(title), "Plugins › Memory › $category", SettingsSearchDestination.MEMORY, "Controls:$title")
}
