package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val memberships = archive.dao.observeMemberships().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val works = archive.dao.observeWorks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobs = examinations.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val features = settings.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
    private val mutableReceipts = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt>>(emptyList())
    val saveReceipts = mutableReceipts.asStateFlow()
    private var receiptWorker: Job? = null
    private var receiptChatId: Int? = null
    private var worker: Job? = null
    private var identity = ""
    fun bind(message: MessageV2, includeCitations: Boolean) {
        if (receiptChatId != message.chatId) {
            receiptChatId = message.chatId
            receiptWorker?.cancel()
            mutableReceipts.value = emptyList()
            receiptWorker = viewModelScope.launch { archive.dao.observeSaveReceipts(message.chatId).collect { mutableReceipts.value = it } }
        }
        val key = "${message.id}:${message.currentRunId}:${message.activeRevisionIndex}:$includeCitations:${if (includeCitations) message.effectiveContent().hashCode() else 0}"
        if (identity == key) return
        identity = key
        worker?.cancel()
        mutable.value = emptyList()
        worker = viewModelScope.launch {
            try {
                sessions.sessions.map { records ->
                    records.values.filter { it.chatId == message.chatId && (it.runId == message.effectiveRunId() || it.turnId in setOf("${message.chatId}:${message.linkedMessageId}", message.linkedMessageId.toString())) }.map { Triple(it.runId, it.authority, it.snapshot.sources) }
                }.distinctUntilChanged().collectLatest {
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
    val memberships by viewModel.memberships.collectAsStateWithLifecycle()
    val receipts by viewModel.saveReceipts.collectAsStateWithLifecycle()
    val works by viewModel.works.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val features by viewModel.features.collectAsStateWithLifecycle()
    var selected by remember(message.id, message.activeRevisionIndex) { mutableStateOf<MessageEvidenceBinding?>(null) }
    var all by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id, message.currentRunId, message.activeRevisionIndex, isStreaming, if (isStreaming) "" else message.effectiveContent()) { viewModel.bind(message, !isStreaming) }
    val collection = bindings.distinctBy { it.workId }
    fun selectedReview(binding: MessageEvidenceBinding) = memberships.firstOrNull { it.workId == binding.workId }?.acceptedReviewId?.let { id -> reviews.firstOrNull { it.reviewId == id } }
        ?: reviews.firstOrNull { it.workId == binding.workId }
    fun saveStatus(binding: MessageEvidenceBinding): String {
        val membership = memberships.firstOrNull { it.workId == binding.workId }
        if (membership != null) return if (membership.protectionReason != null) "Saved · assessment retained until you delete" else "Saved"
        val receipt = receipts.firstOrNull { it.workId == binding.workId || it.sourceId == binding.citationKey }
        return when (receipt?.disposition) {
            "SAVE_PENDING", "DISCOVERED" -> "Saving"
            "SAVE_FAILED" -> "Save failed · ${receipt.reason ?: "retry pending"}"
            "SUPPRESSED_BY_EXPLICIT_DELETION" -> "Removed from archive by you"
            else -> "Not archived"
        }
    }
    if (collection.isNotEmpty()) {
        Text("Research sources · ${collection.size} references · ${collection.count { binding -> memberships.any { it.workId == binding.workId } }} archived", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Study identity may be unresolved · studies save automatically", style = MaterialTheme.typography.bodySmall)
        val latestReceipts = receipts.distinctBy { it.runId to it.sourceId }
        val failedSaves = latestReceipts.count { it.disposition == "SAVE_FAILED" }
        if (failedSaves > 0) Text("$failedSaves source save(s) failed · retry pending", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        collection.take(3).forEach { binding ->
            StudyCard(binding, works.firstOrNull { it.workId == binding.workId }, selectedReview(binding), jobs.filter { it.workId == binding.workId && it.authority == binding.authority && it.chatId == message.chatId }.maxByOrNull { it.updatedAt }, compact, saveStatus = saveStatus(binding), onExamine = {
                selected = binding
            }) { selected = binding }
        }
        if (collection.size > 3) OutlinedButton(onClick = { all = true }) { Text("View all ${collection.size} studies") }
    }
    if (all) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingDialog(onDismissRequest = { all = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { all = false }) { Text("Back to response") }
                    Text("${collection.size} publications", style = MaterialTheme.typography.headlineSmall)
                    androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        items(collection.size, key = { collection[it].workId }) { index ->
                            val binding = collection[index]
                            StudyCard(binding, works.firstOrNull { it.workId == binding.workId }, selectedReview(binding), jobs.filter { it.workId == binding.workId && it.authority == binding.authority && it.chatId == message.chatId }.maxByOrNull { it.updatedAt }, compact, saveStatus = saveStatus(binding), onExamine = {
                                selected = binding
                            }) { selected = binding }
                        }
                    }
                }
            }
        }
    }
    selected?.let { binding ->
        val crawler = dev.chungjungsoo.gptmobile.data.research.feature.ResearchProfileResolver.examinerUid(features, message.chatId, currentTools()).ifBlank { profileUid }
        val liveVersion = selectedReview(binding)?.versionId ?: binding.versionId
        ResearchArticleDialog(binding.workId, liveVersion, binding.authority, message.chatId, crawler, currentTools, { selected = null }, originalVersionId = binding.versionId)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StudyCard(binding: MessageEvidenceBinding, work: dev.chungjungsoo.gptmobile.data.research.feature.ResearchWork?, review: dev.chungjungsoo.gptmobile.data.research.feature.ResearchStudyReview?, job: dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob?, compact: Boolean, saveStatus: String, onExamine: () -> Unit, onOpen: () -> Unit) {
    val snapshot = remember(binding.snapshotJson) { runCatching { Json.parseToJsonElement(binding.snapshotJson) as? JsonObject }.getOrNull() }
    val artifact = remember(review?.reviewId) { review?.let { runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.StudyReviewArtifact>(it.artifactJson) }.getOrNull() } }
    val retracted = work?.retracted == true
    val active = job?.stage in setOf(dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.QUEUED, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.RESOLVING, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.READING, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.EXTRACTING, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.APPRAISING, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.REVIEWING)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f), contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
        Column(Modifier.padding(if (compact) 14.dp else 18.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Description, contentDescription = null)
                Text(work?.title ?: (snapshot?.get("title") as? JsonPrimitive)?.content.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text(listOfNotNull(work?.publicationYear?.toString(), work?.publicationType?.takeUnless { it == "unknown" }?.replace('_', ' ')).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            Text(artifact?.conclusion ?: "Not examined yet", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val status = when {
                retracted -> "Retraction reported · score suspended"
                active -> "${job?.stage?.name?.lowercase()?.replace('_', ' ')}…"
                job?.stage == dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.FAILED -> "Examination needs attention"
                job?.stage == dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.PAUSED -> "Examination paused"
                review?.overallScore != null -> "AI appraisal ${review.overallScore} / 100"
                review != null -> "Limited evidence · unscored"
                else -> "Unexamined"
            }
            Text(status, style = MaterialTheme.typography.labelLarge, color = if (retracted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(saveStatus, style = MaterialTheme.typography.labelSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = if (active || review != null) onOpen else onExamine, modifier = Modifier.heightIn(min = 48.dp)) {
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
                OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Details") }
            }
        }
    }
}
