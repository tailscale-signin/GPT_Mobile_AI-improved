package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.feature.CitationBindingResolver
import dev.chungjungsoo.gptmobile.data.research.feature.MessageEvidenceBinding
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@HiltViewModel
class ResearchCitationViewModel @Inject constructor(private val resolver: CitationBindingResolver, private val settings: dev.chungjungsoo.gptmobile.data.repository.SettingRepository, private val examinations: dev.chungjungsoo.gptmobile.data.research.feature.ArticleExaminationCoordinator, private val archive: dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository, private val sessions: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore) : ViewModel() {
    private val mutable = MutableStateFlow<List<MessageEvidenceBinding>>(emptyList())
    val bindings = mutable.asStateFlow()
    val reviews = archive.dao.observeStudyReviews().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val works = archive.dao.observeWorks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobs = examinations.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val features = settings.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
    private var worker: Job? = null
    private var identity = ""
    private val automatic = mutableSetOf<String>()
    fun autoExamine(message: MessageV2, profileUid: String, tools: () -> ChatMcpToolConfig) {
        viewModelScope.launch {
            val features = settings.getFeatureSettings()
            val policy = features.researchFeature
            if (!policy.enabled || policy.autoExamination == dev.chungjungsoo.gptmobile.data.research.feature.AutoExamination.OFF) return@launch
            val selection = features.conversationResearch[message.chatId] ?: tools().research
            val research = selection?.academicEligibleByUi == true
            if (!research && !policy.ordinaryChatAutoExamination) return@launch
            val needed = Regex("(?i)sample|participant|randomi[sz]|causal|efficacy|evidence|[0-9]+%|confidence interval").containsMatchIn(message.effectiveContent())
            if (policy.autoExamination == dev.chungjungsoo.gptmobile.data.research.feature.AutoExamination.WHEN_NEEDED && !needed) return@launch
            mutable.value.distinctBy { it.versionId }.forEach { binding ->
                if (automatic.add("${policy.crawlerProfileUid}:${binding.versionId}")) {
                    try {
                        examinations.automaticExamine(
                            dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest(
                                binding.workId,
                                "",
                                message.chatId,
                                binding.versionId,
                                binding.authority,
                                if (research) dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.AUTHORIZED_RESEARCH else dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.AUTOMATIC_CHAT,
                                rootWorkId = "${message.chatId}:${message.linkedMessageId}",
                                parentRunId = message.currentRunId
                            ),
                            tools
                        )
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // Automatic work cannot change permissions or divert to another profile. Manual Examine shows the exact admission reason.
                    }
                }
            }
        }
    }
    fun bind(message: MessageV2, includeCitations: Boolean) {
        val key = "${message.id}:${message.currentRunId}:${message.activeRevisionIndex}:$includeCitations:${if (includeCitations) message.effectiveContent().hashCode() else 0}"
        if (identity == key) return
        identity = key
        worker?.cancel()
        mutable.value = emptyList()
        worker = viewModelScope.launch {
            try {
                sessions.sessions.collectLatest {
                    mutable.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { resolver.bind(message, includeCitations) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The answer remains available when historic evidence is missing or ambiguous.
            }
        }
    }
}

@Composable
fun ResearchCitationCards(message: MessageV2, displayedSegment: String, profileUid: String, compact: Boolean, currentTools: () -> ChatMcpToolConfig, isStreaming: Boolean = false) {
    if (message.id <= 0 || message.chatId <= 0) return
    val viewModel: ResearchCitationViewModel = hiltViewModel(key = "research-citations:${message.id}")
    val bindings by viewModel.bindings.collectAsStateWithLifecycle()
    val reviews by viewModel.reviews.collectAsStateWithLifecycle()
    val works by viewModel.works.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val features by viewModel.features.collectAsStateWithLifecycle()
    var selected by remember(message.id, message.activeRevisionIndex) { mutableStateOf<MessageEvidenceBinding?>(null) }
    var all by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id, message.currentRunId, message.activeRevisionIndex, isStreaming, if (isStreaming) "" else message.effectiveContent()) { viewModel.bind(message, !isStreaming) }
    LaunchedEffect(bindings, profileUid) { if (bindings.isNotEmpty()) viewModel.autoExamine(message, profileUid, currentTools) }
    val collection = bindings.distinctBy { it.workId }
    if (collection.isNotEmpty()) {
        Text("Studies found · ${collection.size} publications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Study-family identity may be unresolved · ${if (features.researchFeature.archiveEnabled) "Archive enabled" else "Archive saving off"}", style = MaterialTheme.typography.bodySmall)
        collection.take(3).forEach { binding ->
            StudyCard(binding, works.firstOrNull { it.workId == binding.workId }, reviews.firstOrNull { it.workId == binding.workId }, jobs.filter { it.workId == binding.workId && it.authority == binding.authority }.maxByOrNull { it.updatedAt }, compact) { selected = binding }
        }
        if (collection.size > 3) TextButton(onClick = { all = true }) { Text("View all ${collection.size} studies") }
    }
    if (all) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingDialog(onDismissRequest = { all = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    TextButton(onClick = { all = false }) { Text("Back to response") }
                    Text("${collection.size} publications", style = MaterialTheme.typography.headlineSmall)
                    androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        items(collection.size, key = { collection[it].workId }) { index ->
                            val binding = collection[index]
                            StudyCard(binding, works.firstOrNull { it.workId == binding.workId }, reviews.firstOrNull { it.workId == binding.workId }, jobs.filter { it.workId == binding.workId && it.authority == binding.authority }.maxByOrNull { it.updatedAt }, compact) { selected = binding }
                        }
                    }
                }
            }
        }
    }
    selected?.let { binding ->
        val crawler = (features.conversationResearch[message.chatId] ?: currentTools().research)?.crawlerProfileUid ?: features.researchFeature.crawlerProfileUid.orEmpty()
        val liveVersion = reviews.firstOrNull { it.workId == binding.workId }?.versionId ?: binding.versionId
        ResearchArticleDialog(binding.workId, liveVersion, binding.authority, message.chatId, crawler, currentTools, { selected = null }, originalVersionId = binding.versionId)
    }
}

@Composable
private fun StudyCard(binding: MessageEvidenceBinding, work: dev.chungjungsoo.gptmobile.data.research.feature.ResearchWork?, review: dev.chungjungsoo.gptmobile.data.research.feature.ResearchStudyReview?, job: dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob?, compact: Boolean, onOpen: () -> Unit) {
    val snapshot = remember(binding.snapshotJson) { runCatching { Json.parseToJsonElement(binding.snapshotJson) as? JsonObject }.getOrNull() }
    val artifact = remember(review?.reviewId) { review?.let { runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.StudyReviewArtifact>(it.artifactJson) }.getOrNull() } }
    var expanded by remember(binding.workId) { mutableStateOf(false) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var openError by remember { mutableStateOf(false) }
    val retracted = work?.retracted == true
    val active = job?.stage in setOf(dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.RESOLVING, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.READING, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.EXTRACTING, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.APPRAISING, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.REVIEWING)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f))) {
        Column(Modifier.padding(if (compact) 14.dp else 18.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Text(work?.title ?: (snapshot?.get("title") as? JsonPrimitive)?.content.orEmpty(), style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(work?.publicationYear?.toString(), work?.publicationType?.replace('_', ' ')).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            Text(artifact?.conclusion ?: "Conclusion not assessed", style = MaterialTheme.typography.bodyMedium)
            artifact?.let {
                Text("Outcome: ${it.outcomeKey}", style = MaterialTheme.typography.labelSmall)
                Text(it.limitation, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (retracted) "Retraction reported · score suspended" else review?.overallScore?.let { "AI review score $it / 100" } ?: (review?.status ?: "Review pending"), style = MaterialTheme.typography.labelLarge, color = if (retracted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            Text((snapshot?.get("coverage") as? JsonPrimitive)?.content.orEmpty().lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall)
            if (review != null && review.versionId != binding.versionId) Text("Updated assessment · original cited version retained", style = MaterialTheme.typography.labelSmall)
            if (active) Text("Review in progress · ${job?.stage?.name?.lowercase()?.replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
            if (openError) Text("No browser is available to open this source.", color = MaterialTheme.colorScheme.error)
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        if (active) {
                            "View progress"
                        } else if (review == null) {
                            "Examine"
                        } else {
                            "View review"
                        }
                    )
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Less" else "Details") }
            }
            if (expanded) {
                Text("Reliability: ${if (retracted) "Suspended" else review?.reliabilityScore ?: "Pending"} · Summary accuracy: ${review?.accuracyScore ?: "Pending"}", style = MaterialTheme.typography.bodySmall)
                Text("App rubric v${review?.rubricVersion ?: 1} · AI assessment, not a probability of scientific truth", style = MaterialTheme.typography.bodySmall)
                review?.let { Text("Reviewed ${java.time.Instant.ofEpochMilli(it.createdAt)}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { runCatching { uri.openUri(work?.canonicalUrl ?: (snapshot?.get("canonicalUrl") as? JsonPrimitive)?.content.orEmpty()) }.onFailure { openError = true } }) { Text("Open paper") }
            }
        }
    }
}
