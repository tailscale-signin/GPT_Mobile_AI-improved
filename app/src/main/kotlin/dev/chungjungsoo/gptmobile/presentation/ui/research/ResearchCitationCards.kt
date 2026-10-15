package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.chungjungsoo.gptmobile.data.research.feature.articleCitationOccurrences
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@HiltViewModel
class ResearchCitationViewModel @Inject constructor(private val resolver: CitationBindingResolver, private val settings: dev.chungjungsoo.gptmobile.data.repository.SettingRepository, private val examinations: dev.chungjungsoo.gptmobile.data.research.feature.ArticleExaminationCoordinator) : ViewModel() {
    private val mutable = MutableStateFlow<List<MessageEvidenceBinding>>(emptyList())
    val bindings = mutable.asStateFlow()
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
                if (automatic.add("$profileUid:${binding.versionId}")) {
                    try {
                        examinations.automaticExamine(
                            dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest(
                                binding.workId,
                                profileUid,
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
    fun bind(message: MessageV2) {
        val key = "${message.id}:${message.activeRevisionIndex}:${message.effectiveContent().hashCode()}"
        if (identity == key) return
        identity = key
        worker?.cancel()
        mutable.value = emptyList()
        worker = viewModelScope.launch {
            try {
                mutable.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { resolver.bind(message) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The answer remains available when historic evidence is missing or ambiguous.
            }
        }
    }
}

@Composable
fun ResearchCitationCards(message: MessageV2, displayedSegment: String, profileUid: String, compact: Boolean, currentTools: () -> ChatMcpToolConfig) {
    if (message.id <= 0 || message.chatId <= 0) return
    val viewModel: ResearchCitationViewModel = hiltViewModel(key = "research-citations:${message.id}")
    val bindings by viewModel.bindings.collectAsStateWithLifecycle()
    var selected by remember(message.id, message.activeRevisionIndex) { mutableStateOf<MessageEvidenceBinding?>(null) }
    LaunchedEffect(message.id, message.activeRevisionIndex, message.effectiveContent()) { viewModel.bind(message) }
    LaunchedEffect(bindings, profileUid) { if (bindings.isNotEmpty()) viewModel.autoExamine(message, profileUid, currentTools) }
    val keys = remember(displayedSegment) { articleCitationOccurrences(displayedSegment).map { it.key }.toSet() }
    bindings.filter { it.citationKey.substringBefore('|') in keys }.distinctBy { it.versionId }.forEach { binding ->
        val snapshot = remember(binding.snapshotJson) { runCatching { Json.parseToJsonElement(binding.snapshotJson) as? JsonObject }.getOrNull() }
        fun value(key: String) = (snapshot?.get(key) as? JsonPrimitive)?.content.orEmpty()
        var expanded by remember(binding.versionId) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(if (compact) 10.dp else 16.dp)) {
                Text(value("title"), style = MaterialTheme.typography.titleSmall)
                Text(value("coverage").lowercase().replace('_', ' '), style = MaterialTheme.typography.labelMedium)
                Text("Peer review: ${value("peerReview")} · Independent review: ${value("independentReview").replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                if (!compact) Text("Historical citation snapshot · ${binding.versionId.take(16)}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide study evidence" else "Study evidence") }
                if (expanded) {
                    Text("Design: ${value("design").lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                    val fields = snapshot?.get("studyFields") as? kotlinx.serialization.json.JsonArray
                    if (fields.isNullOrEmpty()) Text("Participants, results and limitations have not been extracted from this version.", style = MaterialTheme.typography.bodySmall)
                    fields.orEmpty().filterIsInstance<JsonObject>().forEach { field ->
                        val name = (field["name"] as? JsonPrimitive)?.content.orEmpty().replace('_', ' ')
                        val content = (field["value"] as? JsonPrimitive)?.content?.takeUnless { it == "null" } ?: "Unknown / not reported"
                        Text("$name: $content", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { selected = binding }) { Text("Article details / Examine") }
            }
        }
    }
    selected?.let { binding -> ResearchArticleDialog(binding.workId, binding.versionId, binding.authority, message.chatId, profileUid, currentTools, { selected = null }) }
}
