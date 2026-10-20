package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.common.glassWindowBackdrop
import kotlinx.coroutines.launch

@Composable
internal fun DeepResearchSettingsScreen(onClose: () -> Unit, viewModel: DeepResearchSettingsViewModel = hiltViewModel()) = ResearchFeatureScreen(onClose, viewModel)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ResearchFeatureScreen(onClose: () -> Unit, viewModel: DeepResearchSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val topicCounts by viewModel.topicCounts.collectAsStateWithLifecycle()
    val outcomes by viewModel.outcomes.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val previews by viewModel.citationPreview.collectAsStateWithLifecycle()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var discoverQuery by rememberSaveable { mutableStateOf("") }
    var importText by rememberSaveable { mutableStateOf("") }
    var organize by rememberSaveable { mutableStateOf(false) }
    val citationFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.previewCitationFile(uri, context)
    }
    val comparison by viewModel.comparison.collectAsStateWithLifecycle()
    var selectedVersions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val protectedCount by viewModel.protectedCount.collectAsStateWithLifecycle()
    val count by viewModel.archiveCount.collectAsStateWithLifecycle()
    val rows by viewModel.archiveRows.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsCategory by rememberSaveable { mutableIntStateOf(0) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    var topicLabel by rememberSaveable { mutableStateOf("") }
    var archiveState by rememberSaveable { mutableStateOf("") }
    var assessed by rememberSaveable { mutableStateOf(false) }
    var rank by rememberSaveable { mutableStateOf(false) }
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
    val expanded = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
    var previousCount by rememberSaveable { mutableIntStateOf(count) }
    var newEntries by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(count) {
        if (count > previousCount && previousCount > 0) newEntries += count - previousCount
        previousCount = count
    }
    val archiveNavigation: @Composable () -> Unit = {
        SettingsPanel("Research archive · $count studies") {
            OutlinedTextField(query, {
                query = it
                offset = 0
            }, label = { Text("Title, DOI or identifier") }, modifier = Modifier.fillMaxWidth())
            FilterChip(assessed, {
                assessed = !assessed
                offset = 0
            }, label = { Text("Examined studies") })
            listOf("" to "All", "UNEXAMINED" to "Unexamined", "PROCESSING" to "Processing", "EXAMINED" to "Examined", "EVIDENCE_CHECKED" to "Evidence checked").forEach { (value, label) ->
                FilterChip(archiveState == value, {
                    archiveState = value
                    offset = 0
                }, label = { Text(label) })
            }
            FilterChip(rank, {
                rank = !rank
                offset = 0
            }, label = { Text("Sort by review score") })
            FilterChip(topic.isBlank(), {
                topic = ""
                offset = 0
            }, label = { Text("All categories") })
            topics.filter { value -> topicCounts.any { it.topicId == value.topicId && it.count > 0 } }.forEach { value ->
                FilterChip(topic == value.topicId, {
                    topic = value.topicId
                    topicLabel = value.label
                    offset = 0
                }, label = { Text("${value.label} · ${topicCounts.firstOrNull { it.topicId == value.topicId }?.count ?: 0}") })
            }
            if (topic.isNotBlank()) {
                OutlinedTextField(topicLabel, { topicLabel = it }, label = { Text("Topic name") })
                OutlinedButton(onClick = { viewModel.renameTopic(topic, topicLabel) }) { Text("Rename topic") }
                OutlinedButton(onClick = { mergeFrom = topic }, enabled = topics.size > 1) { Text("Merge into another topic…") }
            }
            OutlinedButton(onClick = { organize = !organize }) { Text(if (organize) "Hide organization controls" else "Organize topics") }
            if (organize) {
                Text("Organize categories and topics", style = MaterialTheme.typography.titleSmall)
                categories.forEach { category ->
                    FilterChip(categoryId == category.categoryId, {
                        categoryId = category.categoryId
                        categoryLabel = category.label
                    }, label = { Text(category.label) })
                }
                if (categoryId.isNotBlank()) {
                    OutlinedTextField(categoryLabel, { categoryLabel = it }, label = { Text("Category name") })
                    OutlinedButton(onClick = { viewModel.renameCategory(categoryId, categoryLabel) }) { Text("Rename category") }
                    OutlinedTextField(newTopic, { newTopic = it }, label = { Text("New topic name") })
                    OutlinedButton(onClick = {
                        viewModel.createTopic(categoryId, newTopic)
                        newTopic = ""
                    }, enabled = newTopic.isNotBlank()) { Text("Create topic") }
                }
            }
            OutlinedButton(onClick = { deleteId = "*" }, enabled = count > 0) { Text("Clear reusable archive") }
            OutlinedButton(onClick = { viewModel.compareVersions(selectedVersions) }, enabled = selectedVersions.size >= 2) { Text("Compare selected (${selectedVersions.size})") }
            Text("Archive reuse requires a fresh source check. Saved content may be historical or unavailable.")
        }
    }
    val enabled = settings.isToolPluginEnabled(ToolPluginId.DEEP_RESEARCH)
    LaunchedEffect(Unit) { viewModel.open() }
    LaunchedEffect(query, topic, offset, tab, assessed, rank, archiveState) { if (tab == 1) viewModel.loadArchive(query, topic, offset, assessed, rank, archiveState) }
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
        Scaffold(modifier = Modifier.fillMaxSize().glassWindowBackdrop().testTag("deep-research-settings"), containerColor = dev.chungjungsoo.gptmobile.presentation.common.overlayWindowContainerColor(), topBar = {
            TopAppBar(title = { Text("Research") }, navigationIcon = { OutlinedButton(onClick = close, enabled = !state.saving) { Text("Back") } }, actions = {
                if (tab == 4) {
                    OutlinedButton(onClick = {
                        viewModel.save {
                            viewModel.open()
                            tab = 0
                        }
                    }, enabled = state.draft != null && !state.saving && enabled, modifier = Modifier.testTag("research-save")) { Text(if (state.saving) "Saving…" else "Save") }
                } else {
                    androidx.compose.material3.IconButton(onClick = { tab = 4 }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Settings, contentDescription = "Research settings")
                    }
                }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (tab != 4) {
                    SettingsTabs(listOf("Search", "Library", "Findings", "Activity"), tab) { tab = it }
                } else {
                    OutlinedButton(onClick = { tab = 0 }) { Text("Return to workspace") }
                }
                if (!enabled) Text("Research is disabled. Saved evidence remains readable; new jobs are unavailable.", Modifier.padding(16.dp))
                if (state.dirty && tab == 4) Text("Unsaved settings · Tab changes keep your draft", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.weight(1f)) {
                    if (expanded && tab == 1) {
                        Column(Modifier.width(280.dp).padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) { archiveNavigation() }
                    }
                    LazyColumn(modifier = Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("research-save-error")) } }
                        if (tab == 4) {
                            item { SettingsTabs(listOf("General", "Evidence", "Library", "Automation", "Storage"), settingsCategory) { settingsCategory = it } }
                            if (settingsCategory == 0) item { state.draft?.let { DeepResearchSettingsCard(it, enabled && !state.saving, viewModel::edit) } }
                            if (settingsCategory == 1) {
                                item {
                                    SettingsPanel("Scientific appraisal") {
                                        val feature = state.featureDraft
                                        Text("Original evidence rubric. Explains methodological concerns and uncertainty; no probability-of-truth score or certification.")
                                        ScientificAppraisalMode.entries.forEach { value -> FilterChip(feature.appraisalMode == value, { viewModel.editFeature(feature.copy(appraisalMode = value)) }, enabled = enabled, label = { Text(value.name.lowercase().replaceFirstChar { it.titlecase() }) }) }
                                        ResearchFeatureSwitch("Peer-reviewed only", feature.peerReviewedOnly, enabled) { viewModel.editFeature(feature.copy(peerReviewedOnly = it)) }
                                        Text("Unknown publication status stays unknown. Independent AI review is recorded separately.")
                                    }
                                }
                            }
                            if (settingsCategory == 2) {
                                item {
                                    SettingsPanel("Archive and article cards") {
                                        val feature = state.featureDraft
                                        ResearchFeatureSwitch("Automatically organize studies", feature.automaticOrganization, enabled) { viewModel.editFeature(feature.copy(automaticOrganization = it)) }
                                        ResearchFeatureSwitch("Recall grounded research outcomes", feature.researchMemory, enabled) { viewModel.editFeature(feature.copy(researchMemory = it)) }
                                        ResearchFeatureSwitch("Share research recall with remote AI", feature.shareResearchRecall, enabled && feature.researchMemory) { viewModel.editFeature(feature.copy(shareResearchRecall = it)) }
                                        Text("Research recall also requires Memory recall permission. Remote sharing additionally requires cloud-memory permission. Study findings remain separate from personal facts.")
                                        Text("Studies save automatically. Evidence-checked results remain until you delete them.")
                                        Text("$protectedCount protected studies · unexamined limit ${feature.unexaminedStudyLimit}")
                                        OutlinedTextField(feature.unexaminedStudyLimit.toString(), { value -> value.toIntOrNull()?.takeIf { it in 1..50000 }?.let { viewModel.editFeature(feature.copy(unexaminedStudyLimit = it)) } }, label = { Text("Unexamined study limit") }, enabled = enabled)
                                        OutlinedButton(onClick = viewModel::clearUnexamined, enabled = enabled) { Text("Clear unexamined") }
                                        ResearchFeatureSwitch("Use archive leads before discovery", feature.archivePreflight, enabled) { viewModel.editFeature(feature.copy(archivePreflight = it)) }
                                        ResearchFeatureSwitch("Show article cards", feature.articleCards, enabled) { viewModel.editFeature(feature.copy(articleCards = it)) }
                                        ResearchFeatureSwitch("Compact article cards", feature.compactCards, enabled) { viewModel.editFeature(feature.copy(compactCards = it)) }
                                        ResearchFeatureSwitch("Show coverage warnings", feature.showWarnings, enabled) { viewModel.editFeature(feature.copy(showWarnings = it)) }
                                        ResearchFeatureSwitch("Show validated method metrics", feature.showMethodMetrics, enabled) { viewModel.editFeature(feature.copy(showMethodMetrics = it)) }
                                        Text("At most 1,000 reusable works. The oldest insertion leaves the archive first; original chat citations and active jobs retain their own evidence. This does not limit research acquisition.")
                                    }
                                }
                            }
                            if (settingsCategory == 3) {
                                item {
                                    SettingsPanel("Examination") {
                                        val feature = state.featureDraft
                                        val crawlerProfiles by viewModel.crawlerProfiles.collectAsStateWithLifecycle()
                                        Text("Crawler AI profile", style = MaterialTheme.typography.titleMedium)
                                        DelegateModelDropdown(
                                            selectedProfileUid = feature.crawlerProfileUid.orEmpty(),
                                            profiles = crawlerProfiles.filter { it.enabled },
                                            enabled = enabled,
                                            onSelected = { viewModel.editFeature(feature.copy(crawlerProfileUid = it?.uid)) },
                                            roleLabel = "Examination profile",
                                            automaticLabel = "Choose profile"
                                        )
                                        Text("Research reviewer", style = MaterialTheme.typography.titleMedium)
                                        DelegateModelDropdown(
                                            selectedProfileUid = feature.reviewerProfileUid.orEmpty(),
                                            profiles = crawlerProfiles.filter { it.enabled },
                                            enabled = enabled,
                                            onSelected = { viewModel.editFeature(feature.copy(reviewerProfileUid = it?.uid)) },
                                            roleLabel = "Research reviewer",
                                            automaticLabel = "Use existing review policy"
                                        )
                                        Text("The selected reviewer receives captured Research evidence. A different model is needed for independent review; this choice also applies in Combined mode.")
                                        AutoExamination.entries.forEach { value -> FilterChip(feature.autoExamination == value, { viewModel.editFeature(feature.copy(autoExamination = value)) }, enabled = enabled, label = { Text(if (value == AutoExamination.OFF) "Manual" else value.name.lowercase().replace('_', ' ')) }) }
                                        ResearchFeatureSwitch("Auto-examine in ordinary chat", feature.ordinaryChatAutoExamination, enabled) { viewModel.editFeature(feature.copy(ordinaryChatAutoExamination = it)) }
                                        ResearchFeatureSwitch("Allow background examination", feature.backgroundExamination, enabled) { viewModel.editFeature(feature.copy(backgroundExamination = it)) }
                                        Text("Native discovery follows up to two explicit full-text HTML links on the same site. Access refusal is terminal; figures and supplements remain unverified.")
                                        ResearchFeatureSwitch("Targeted article crawling", feature.targetedCrawl, enabled) { viewModel.editFeature(feature.copy(targetedCrawl = it)) }
                                        Text("Manual Examine does not change research depth. Provider, paper-reading, connection and cloud permissions are rechecked before dispatch. Native PDF support is capability-gated.")
                                    }
                                }
                            }
                            if (settingsCategory == 4) {
                                item {
                                    state.storage?.let { storage ->
                                        SettingsPanel("Storage & resumable runs") {
                                            Text("${storage.retainedSources} retained run sources · ${storage.bytes / 1024} KiB in the body store")
                                            OutlinedButton(onClick = viewModel::refreshStorage) { Text("Refresh storage") }
                                            storage.resumable.take(8).forEach { session ->
                                                Text("${session.snapshot.phase} · ${session.snapshot.sources.count { it.readable }}/${session.snapshot.targetSources} readable")
                                                OutlinedButton(onClick = { viewModel.migrate(session) }, enabled = enabled) { Text("Remove legacy cap for next resume") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (tab == 0) {
                            item {
                                SettingsPanel("What would you like to explore?") {
                                    Text("Search scholarly sources and keep useful studies in your research library. For a full web research report, choose Research when creating a chat.")
                                    OutlinedTextField(discoverQuery, { discoverQuery = it.take(500) }, label = { Text("Research question or keywords") }, modifier = Modifier.fillMaxWidth())
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { viewModel.discover(discoverQuery) }, enabled = enabled && discoverQuery.isNotBlank()) { Text("Search studies") }
                                        OutlinedButton(onClick = viewModel::stopDiscovery) { Text("Stop") }
                                    }
                                    if (discovery.isNotBlank()) Text(discovery, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            item { OutlinedButton(onClick = { showImport = !showImport }) { Text(if (showImport) "Close citation import" else "Import a citation or use Google Scholar") } }
                            if (showImport) {
                                item {
                                    SettingsPanel("Import sources") {
                                        Text("Open Scholar, choose citations, then paste bibliographic text or select a BibTeX/RIS file. The browser does not return results automatically.")
                                        OutlinedButton(onClick = { viewModel.openScholar(discoverQuery) { uriHandler.openUri(it) } }, enabled = enabled && discoverQuery.isNotBlank()) { Text("Open Scholar") }
                                        OutlinedTextField(importText, { importText = it.take(262144) }, label = { Text("DOI, PMID, URL, BibTeX or RIS") }, maxLines = 6, modifier = Modifier.fillMaxWidth())
                                        OutlinedButton(onClick = { viewModel.previewCitations(importText) }, enabled = enabled && importText.isNotBlank()) { Text("Preview citation import") }
                                        OutlinedButton(onClick = { citationFile.launch(arrayOf("text/*", "application/x-bibtex", "application/x-research-info-systems", "application/octet-stream")) }, enabled = enabled) { Text("Choose citation file") }
                                        if (previews.isNotEmpty()) {
                                            Text("Review ${previews.size} selected citations", style = MaterialTheme.typography.titleSmall)
                                            previews.forEachIndexed { index, preview ->
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(preview.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                                    OutlinedButton(onClick = { viewModel.removeCitation(index) }) { Text("Remove") }
                                                }
                                            }
                                            Text("Identifiers will be resolved where available. Unresolved entries remain unverified; they cannot produce grounded outcomes.")
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = viewModel::importCitations, enabled = enabled) { Text("Import selected citations") }
                                                OutlinedButton(onClick = viewModel::cancelImport) { Text("Cancel import") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (tab == 2) {
                            item { Text("Learned Outcomes", style = MaterialTheme.typography.headlineSmall) }
                            item { Text("Source-linked findings from examined studies. Grounded means supported by captured passages, not scientifically proven. Research recall is opt-in.") }
                            if (outcomes.isEmpty()) item { Text("No supported outcomes yet. Examine an archived study to extract its reported findings and limitations.") }
                            items(outcomes, key = { it.outcomeId }) { outcome ->
                                SettingsPanel(outcome.state.lowercase().replaceFirstChar { it.titlecase() }) {
                                    Text("What the study reported", style = MaterialTheme.typography.titleSmall)
                                    Text(outcome.reported)
                                    Text("Limitations", style = MaterialTheme.typography.titleSmall)
                                    Text(outcome.limitations)
                                    Text("Captured ${java.time.Instant.ofEpochMilli(outcome.createdAt)}", style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = {
                                        articleId = outcome.workId
                                        articleVersion = outcome.versionId
                                    }) { Text("Open supporting evidence") }
                                    OutlinedButton(onClick = { viewModel.forgetOutcome(outcome.outcomeId) }) { Text("Forget outcome") }
                                }
                            }
                        }
                        if (tab == 3) item { ResearchEngineHealthPanel() }
                        if (tab == 1) {
                            if (newEntries > 0) {
                                item(key = "archive-insertions") {
                                    OutlinedButton(onClick = {
                                        offset = 0
                                        newEntries = 0
                                        scrollScope.launch { listState.animateScrollToItem(0) }
                                    }) { Text("$newEntries new studies · Show latest") }
                                }
                            }
                            if (!expanded) item { archiveNavigation() }
                            if (rows.isEmpty()) {
                                item {
                                    if (count == 0) {
                                        Text("Your research collection starts here. Search studies or import a citation; accepted records are saved automatically.")
                                        OutlinedButton(onClick = { tab = 0 }) { Text("Discover studies") }
                                    } else {
                                        Text("No studies match these filters.")
                                        OutlinedButton(onClick = {
                                            query = ""
                                            topic = ""
                                            offset = 0
                                        }) { Text("Clear filters") }
                                    }
                                }
                            }
                            items(rows, key = { it.workId }) { row ->
                                SettingsPanel(row.title) {
                                    Text("${row.coverage.name.lowercase().replace('_', ' ')} · ${row.publicationYear ?: "Year unknown"}")
                                    Text("Peer review: ${row.peerReview.name.lowercase()}")
                                    row.protectionReason?.let { Text(if (it == "EVIDENCE_CHECKED") "Evidence checked · retained until you delete" else "Historic assessment retained · integrity check needed", style = MaterialTheme.typography.labelMedium) }
                                    Text(if (row.retracted == true) "Review score suspended" else row.reviewScore?.let { "AI review score $it / 100" } ?: row.reviewStatus ?: "Review pending", style = MaterialTheme.typography.labelLarge)
                                    if (row.retracted == true) Text("Retraction reported", color = MaterialTheme.colorScheme.error)
                                    Text(row.canonicalUrl, style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = {
                                        articleId = row.workId
                                        articleVersion = row.latestVersionId
                                    }) { Text("Article details / Examine") }
                                    FilterChip(row.latestVersionId in selectedVersions, {
                                        selectedVersions = if (row.latestVersionId in selectedVersions) selectedVersions - row.latestVersionId else (selectedVersions + row.latestVersionId).takeLast(state.featureDraft.comparisonLimit)
                                    }, label = { Text("Select for comparison") })
                                    OutlinedButton(onClick = { viewModel.excludeRecall(row.workId) }) { Text("Exclude from recall") }
                                    OutlinedButton(onClick = { moveId = row.workId }) { Text("Move to topic…") }
                                    OutlinedButton(onClick = { deleteId = row.workId }) { Text("Remove from archive") }
                                }
                            }
                            item {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { offset = (offset - 40).coerceAtLeast(0) }, enabled = offset > 0) { Text("Previous") }
                                    OutlinedButton(onClick = { offset += 40 }, enabled = rows.size == 40) { Text("Next 40") }
                                }
                            }
                        }
                    }
                }
            }
        }
        articleId?.let { id -> dev.chungjungsoo.gptmobile.presentation.ui.research.ResearchArticleDialog(id, articleVersion, onClose = { articleId = null }) }
        if (confirmClose) {
            AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("Keep your research changes?") }, text = { Text("Archive actions are already applied. The settings draft is unsaved.") }, confirmButton = {
                OutlinedButton(onClick = {
                    confirmClose = false
                    viewModel.save(onClose)
                }, enabled = enabled) { Text("Save") }
            }, dismissButton = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = discard) { Text("Discard") }
                    OutlinedButton(onClick = { confirmClose = false }) { Text("Keep editing") }
                }
            })
        }
        if (comparison.isNotEmpty()) dev.chungjungsoo.gptmobile.presentation.ui.research.ResearchComparisonDialog(comparison, viewModel::closeComparison)
        if (moveId != null || mergeFrom != null) {
            AlertDialog(onDismissRequest = {
                moveId = null
                mergeFrom = null
            }, title = { Text(if (moveId != null) "Move work to topic" else "Merge topic into…") }, text = {
                LazyColumn {
                    items(topics.filter { it.topicId != mergeFrom }, key = { it.topicId }) { destination ->
                        OutlinedButton(onClick = {
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
                OutlinedButton(onClick = {
                    moveId = null
                    mergeFrom = null
                }) { Text("Cancel") }
            })
        }
        deleteId?.let { id ->
            AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(if (id == "*") "Clear archive?" else "Remove archived work?") }, text = { Text("This explicitly deletes ${if (id == "*") "$count studies, including $protectedCount protected results" else "the selected study and its retained assessment"}. Running examinations are stopped. Old callbacks and backups cannot silently recreate deleted studies.") }, confirmButton = {
                OutlinedButton(onClick = {
                    if (id == "*") viewModel.clearArchive() else viewModel.removeFromArchive(id)
                    deleteId = null
                }) { Text("Remove") }
            }, dismissButton = { OutlinedButton(onClick = { deleteId = null }) { Text("Cancel") } })
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
