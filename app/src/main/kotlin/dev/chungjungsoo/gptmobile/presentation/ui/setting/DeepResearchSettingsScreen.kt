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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.research.feature.AutoExamination
import dev.chungjungsoo.gptmobile.data.research.feature.ScientificAppraisalMode
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog

@Composable
internal fun DeepResearchSettingsScreen(onClose: () -> Unit, viewModel: DeepResearchSettingsViewModel = hiltViewModel()) = ResearchFeatureScreen(onClose, viewModel)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResearchFeatureScreen(onClose: () -> Unit, viewModel: DeepResearchSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val count by viewModel.archiveCount.collectAsStateWithLifecycle()
    val rows by viewModel.archiveRows.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    var topicLabel by rememberSaveable { mutableStateOf("") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    var articleId by rememberSaveable { mutableStateOf<String?>(null) }
    var articleVersion by rememberSaveable { mutableStateOf<String?>(null) }
    var moveId by rememberSaveable { mutableStateOf<String?>(null) }
    var mergeFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryId by rememberSaveable { mutableStateOf("") }
    var categoryLabel by rememberSaveable { mutableStateOf("") }
    var newTopic by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = settings.isToolPluginEnabled(ToolPluginId.DEEP_RESEARCH)
    LaunchedEffect(Unit) { viewModel.open() }
    LaunchedEffect(query, topic, offset, tab) { if (tab == 2) viewModel.loadArchive(query, topic, offset) }
    val close = {
        if (!state.saving) {
            if (state.dirty) {
                confirmClose = true
            } else {
                viewModel.discard()
                onClose()
            }
        }
    }
    val discard = {
        viewModel.discard()
        onClose()
    }
    FadingDialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize().testTag("deep-research-settings"), topBar = {
            TopAppBar(title = { Text("Research") }, navigationIcon = { TextButton(onClick = close, enabled = !state.saving) { Text("Back") } }, actions = {
                TextButton(onClick = discard, enabled = !state.saving) { Text("Cancel") }
                TextButton(onClick = { viewModel.save(onClose) }, enabled = state.draft != null && !state.saving && enabled, modifier = Modifier.testTag("research-save")) { Text(if (state.saving) "Saving…" else "Save") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                SettingsTabs(listOf("Settings", "Statistics", "Archive"), tab) { tab = it }
                if (!enabled) Text("Research is disabled. Saved evidence remains readable; new jobs are unavailable.", Modifier.padding(16.dp))
                if (state.dirty) Text("Unsaved settings · Tab changes keep your draft", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("research-save-error")) } }
                    if (tab == 0) {
                        item { Text("Global defaults · Changes apply to future runs", style = MaterialTheme.typography.bodySmall) }
                        item { state.draft?.let { DeepResearchSettingsCard(it, enabled && !state.saving, viewModel::edit) } }
                        item {
                            SettingsPanel("Scientific appraisal") {
                                val feature = state.featureDraft
                                Text("Original evidence rubric. Explains methodological concerns and uncertainty; no probability-of-truth score or certification.")
                                ScientificAppraisalMode.entries.forEach { value -> FilterChip(feature.appraisalMode == value, { viewModel.editFeature(feature.copy(appraisalMode = value)) }, enabled = enabled, label = { Text(value.name.lowercase().replaceFirstChar { it.titlecase() }) }) }
                                ResearchFeatureSwitch("Peer-reviewed only", feature.peerReviewedOnly, enabled) { viewModel.editFeature(feature.copy(peerReviewedOnly = it)) }
                                Text("Unknown publication status stays unknown. Independent AI review is recorded separately.")
                            }
                        }
                        item {
                            SettingsPanel("Archive and article cards") {
                                val feature = state.featureDraft
                                ResearchFeatureSwitch("Archive public research", feature.archiveEnabled, enabled) { viewModel.editFeature(feature.copy(archiveEnabled = it)) }
                                ResearchFeatureSwitch("Use archive leads before discovery", feature.archivePreflight, enabled) { viewModel.editFeature(feature.copy(archivePreflight = it)) }
                                ResearchFeatureSwitch("Show article cards", feature.articleCards, enabled) { viewModel.editFeature(feature.copy(articleCards = it)) }
                                ResearchFeatureSwitch("Compact article cards", feature.compactCards, enabled) { viewModel.editFeature(feature.copy(compactCards = it)) }
                                ResearchFeatureSwitch("Show validated method metrics", feature.showMethodMetrics, enabled) { viewModel.editFeature(feature.copy(showMethodMetrics = it)) }
                                Text("At most 1,000 reusable works. The oldest insertion leaves the archive first; original chat citations and active jobs retain their own evidence. This does not limit research acquisition.")
                            }
                        }
                        item {
                            SettingsPanel("Examination") {
                                val feature = state.featureDraft
                                AutoExamination.entries.forEach { value -> FilterChip(feature.autoExamination == value, { viewModel.editFeature(feature.copy(autoExamination = value)) }, enabled = enabled, label = { Text(value.name.lowercase().replace('_', ' ')) }) }
                                ResearchFeatureSwitch("Auto-examine in ordinary chat", feature.ordinaryChatAutoExamination, enabled) { viewModel.editFeature(feature.copy(ordinaryChatAutoExamination = it)) }
                                ResearchFeatureSwitch("Allow background examination", feature.backgroundExamination, enabled) { viewModel.editFeature(feature.copy(backgroundExamination = it)) }
                                ResearchFeatureSwitch("Targeted article crawling", feature.targetedCrawl, enabled) { viewModel.editFeature(feature.copy(targetedCrawl = it)) }
                                Text("Manual Examine does not change research depth. Provider, paper-reading, connection and cloud permissions are rechecked before dispatch. Native PDF support is capability-gated.")
                            }
                        }
                        item {
                            state.storage?.let { storage ->
                                SettingsPanel("Storage & resumable runs") {
                                    Text("${storage.retainedSources} retained run sources · ${storage.bytes / 1024} KiB in the body store")
                                    TextButton(onClick = viewModel::refreshStorage) { Text("Refresh storage") }
                                    storage.resumable.take(8).forEach { session ->
                                        Text("${session.snapshot.phase} · ${session.snapshot.sources.count { it.readable }}/${session.snapshot.targetSources} readable")
                                        TextButton(onClick = { viewModel.migrate(session) }, enabled = enabled) { Text("Remove legacy cap for next resume") }
                                    }
                                }
                            }
                        }
                    }
                    if (tab == 1) item { ResearchEngineHealthPanel() }
                    if (tab == 2) {
                        item {
                            SettingsPanel("Reusable archive · $count / 1,000") {
                                OutlinedTextField(query, {
                                    query = it
                                    offset = 0
                                }, label = { Text("Title, DOI or identifier") }, modifier = Modifier.fillMaxWidth())
                                FilterChip(topic.isBlank(), {
                                    topic = ""
                                    offset = 0
                                }, label = { Text("All categories") })
                                topics.forEach { value ->
                                    FilterChip(topic == value.topicId, {
                                        topic = value.topicId
                                        topicLabel = value.label
                                        offset = 0
                                    }, label = { Text("${categories.firstOrNull { it.categoryId == value.categoryId }?.label ?: value.categoryId} › ${value.label}") })
                                }
                                if (topic.isNotBlank()) {
                                    OutlinedTextField(topicLabel, { topicLabel = it }, label = { Text("Topic name") })
                                    TextButton(onClick = { viewModel.renameTopic(topic, topicLabel) }) { Text("Rename topic") }
                                    TextButton(onClick = { mergeFrom = topic }, enabled = topics.size > 1) { Text("Merge into another topic…") }
                                }
                                Text("Organize categories and topics", style = MaterialTheme.typography.titleSmall)
                                categories.forEach { category ->
                                    FilterChip(categoryId == category.categoryId, {
                                        categoryId = category.categoryId
                                        categoryLabel = category.label
                                    }, label = { Text(category.label) })
                                }
                                if (categoryId.isNotBlank()) {
                                    OutlinedTextField(categoryLabel, { categoryLabel = it }, label = { Text("Category name") })
                                    TextButton(onClick = { viewModel.renameCategory(categoryId, categoryLabel) }) { Text("Rename category") }
                                    OutlinedTextField(newTopic, { newTopic = it }, label = { Text("New topic name") })
                                    TextButton(onClick = {
                                        viewModel.createTopic(categoryId, newTopic)
                                        newTopic = ""
                                    }, enabled = newTopic.isNotBlank()) { Text("Create topic") }
                                }
                                TextButton(onClick = { deleteId = "*" }, enabled = count > 0) { Text("Clear reusable archive") }
                                Text("Archive reuse requires a fresh source check. Saved content may be historical or unavailable.")
                            }
                        }
                        if (rows.isEmpty()) item { Text("No matching archived works. Evidence is added by authorized research; this view does not start a search.") }
                        items(rows, key = { it.workId }) { row ->
                            SettingsPanel(row.title) {
                                Text("${row.coverage.name.lowercase().replace('_', ' ')} · ${row.publicationYear ?: "Year unknown"}")
                                Text("Peer review: ${row.peerReview.name.lowercase()}")
                                if (row.retracted == true) Text("Retraction reported", color = MaterialTheme.colorScheme.error)
                                Text(row.canonicalUrl, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    articleId = row.workId
                                    articleVersion = row.latestVersionId
                                }) { Text("Article details / Examine") }
                                TextButton(onClick = { moveId = row.workId }) { Text("Move to topic…") }
                                TextButton(onClick = { deleteId = row.workId }) { Text("Remove from archive") }
                            }
                        }
                        item {
                            Row {
                                TextButton(onClick = { offset = (offset - 40).coerceAtLeast(0) }, enabled = offset > 0) { Text("Previous") }
                                TextButton(onClick = { offset += 40 }, enabled = rows.size == 40) { Text("Next 40") }
                            }
                        }
                    }
                }
            }
        }
        articleId?.let { id -> dev.chungjungsoo.gptmobile.presentation.ui.research.ResearchArticleDialog(id, articleVersion, onClose = { articleId = null }) }
        if (confirmClose) {
            AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("Keep your research changes?") }, text = { Text("Archive actions are already applied. The settings draft is unsaved.") }, confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    viewModel.save(onClose)
                }, enabled = enabled) { Text("Save") }
            }, dismissButton = {
                Row {
                    TextButton(onClick = discard) { Text("Discard") }
                    TextButton(onClick = { confirmClose = false }) { Text("Keep editing") }
                }
            })
        }
        if (moveId != null || mergeFrom != null) {
            AlertDialog(onDismissRequest = {
                moveId = null
                mergeFrom = null
            }, title = { Text(if (moveId != null) "Move work to topic" else "Merge topic into…") }, text = {
                LazyColumn {
                    items(topics.filter { it.topicId != mergeFrom }, key = { it.topicId }) { destination ->
                        TextButton(onClick = {
                            moveId?.let { viewModel.moveWork(it, destination.topicId) }
                            mergeFrom?.let {
                                viewModel.mergeTopics(it, destination.topicId)
                                topic = ""
                            }
                            moveId = null
                            mergeFrom = null
                        }) { Text("${categories.firstOrNull { it.categoryId == destination.categoryId }?.label.orEmpty()} › ${destination.label}") }
                    }
                }
            }, confirmButton = {}, dismissButton = {
                TextButton(onClick = {
                    moveId = null
                    mergeFrom = null
                }) { Text("Cancel") }
            })
        }
        deleteId?.let { id ->
            AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(if (id == "*") "Clear archive?" else "Remove archived work?") }, text = { Text("Chat citations and active-job evidence keep their separate retention roots. The reusable archive index is removed.") }, confirmButton = {
                TextButton(onClick = {
                    if (id == "*") viewModel.clearArchive() else viewModel.removeFromArchive(id)
                    deleteId = null
                }) { Text("Remove") }
            }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun ResearchFeatureSwitch(label: String, value: Boolean, enabled: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, update, enabled = enabled)
    }
}
