package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.feature.AppraisalJudgment
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.common.glassWindowBackdrop
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchArticleDialog(workId: String, versionId: String? = null, authority: String = "public", chatId: Int = 0, initialProfileUid: String = "", currentTools: () -> ChatMcpToolConfig = { ChatMcpToolConfig() }, onClose: () -> Unit, viewModel: ResearchArticleViewModel = hiltViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var actionError by rememberSaveable(workId) { mutableStateOf<String?>(null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val features by viewModel.features.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    var profileUid by rememberSaveable(workId) { mutableStateOf(initialProfileUid) }
    val relevantJobs = jobs.filter { it.workId == workId && it.authority == authority && it.chatId == chatId }
    LaunchedEffect(workId, versionId, authority, relevantJobs.map { it.updatedAt }) { viewModel.open(workId, versionId, authority) }
    FadingDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize().glassWindowBackdrop(), containerColor = dev.chungjungsoo.gptmobile.presentation.common.overlayWindowContainerColor(), topBar = { TopAppBar(title = { Text("Article evidence") }, navigationIcon = { TextButton(onClick = onClose) { Text("Back") } }) }) { padding ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) item { Text("Loading saved article…") }
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                actionError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                state.work?.let { work ->
                    item {
                        Text(work.title, style = MaterialTheme.typography.titleLarge)
                        Text(work.canonicalUrl, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            runCatching { uriHandler.openUri(work.canonicalUrl) }.onFailure { actionError = "No browser is available to open this source." }
                        }) { Text("Open source") }
                        state.version?.takeIf { it.publicSource }?.let { version ->
                            TextButton(onClick = {
                                val share = android.content.Intent(context, dev.chungjungsoo.gptmobile.presentation.ui.main.ShareIncomingActivity::class.java).apply {
                                    action = android.content.Intent.ACTION_SEND
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, "Use this saved article as historical evidence, preserving its coverage and limitations. Cite [article:${version.versionId}] with its URL.\n${work.title}\n${work.canonicalUrl}\nCaptured: ${java.time.Instant.ofEpochMilli(version.retrievedAt)}; coverage: ${version.coverage.name}")
                                    if (chatId > 0) putExtra("research_origin_chat", chatId)
                                }
                                context.startActivity(share)
                            }) { Text("Use in chat") }
                        }
                        Text("Publication: ${work.publicationType} · ${work.publicationYear ?: "Year unknown"}")
                        Text("Peer review: ${work.peerReview.name.lowercase().replace('_', ' ')} · Preprint: ${work.preprint ?: "Unknown"}")
                        if (work.retracted == true) Text("Retraction reported", color = MaterialTheme.colorScheme.error)
                    }
                }
                state.version?.let { version ->
                    item {
                        Text("Coverage: ${version.coverage.name.lowercase().replace('_', ' ')}")
                        Text("Captured ${java.time.Instant.ofEpochMilli(version.retrievedAt)} · ${version.textBytes} UTF-8 bytes", style = MaterialTheme.typography.bodySmall)
                        Text("Version ${version.versionId.take(16)} · ${if (state.bodyAvailable) "Body integrity verified" else "Body unavailable or incomplete; historical metadata retained"}")
                        Text("Source capture, AI appraisal, publication review and independent review are separate.")
                    }
                    item {
                        Text("Examination model", style = MaterialTheme.typography.titleMedium)
                        profiles.filter { it.enabled }.forEach { profile -> FilterChip(profileUid == profile.uid, { profileUid = profile.uid }, label = { Text("${profile.name} · ${profile.model}") }) }
                        TextButton(onClick = { viewModel.examine(ExaminationRequest(workId, profileUid, chatId, version.versionId, authority), currentTools) }, enabled = profileUid.isNotBlank() && features.researchFeature.enabled && features.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH)) { Text("Examine / Resume") }
                        Text("Uses the selected model and permitted article reader. Provider usage may be billable. Research Depth remains unchanged; all paper and profile permissions still apply.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                relevantJobs.forEach { job ->
                    item {
                        Text(job.stage.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.titleMedium)
                        job.terminalReason?.let { Text(it) }
                        val checkpoint = runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.ExaminationCheckpoint>(job.checkpointJson) }.getOrNull()
                        checkpoint?.sourceValidation?.let { Text("Source check: ${it.status.replace('_', ' ')} · ${it.owner}", style = MaterialTheme.typography.bodySmall) }
                        checkpoint?.independentReports.orEmpty().forEach { report ->
                            Text("Review by ${report.reviewerModel}: ${report.verdict}")
                            report.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (features.researchFeature.showWarnings) checkpoint?.limitations.orEmpty().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (job.stage in setOf(ExaminationStage.RESOLVING, ExaminationStage.READING, ExaminationStage.EXTRACTING, ExaminationStage.APPRAISING, ExaminationStage.REVIEWING)) {
                            Row {
                                TextButton(onClick = { viewModel.pause(job.jobId) }) { Text("Pause") }
                                TextButton(onClick = { viewModel.cancel(job.jobId) }) { Text("Stop examination") }
                            }
                        }
                    }
                }
                if (features.researchFeature.showMethodMetrics && state.fields.isEmpty()) item { Text("Methods and numbers have not been validated. Missing sample sizes and effects remain unknown.") }
                (if (features.researchFeature.showMethodMetrics) state.fields else emptyList()).forEach { field ->
                    item {
                        Text("${field.name.replace('_', ' ')} · ${field.outcomeKey}", style = MaterialTheme.typography.labelLarge)
                        Text(field.value?.let { "$it ${field.unit.orEmpty()}" } ?: field.missingness.name.lowercase().replace('_', ' '))
                        val ids = runCatching { Json.decodeFromString<List<String>>(field.passageIdsJson) }.getOrDefault(emptyList())
                        state.passages.filter { it.passageId in ids }.forEach { Text("${it.locator}: “${it.quote}”", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                state.appraisals.forEach { appraisal ->
                    item {
                        Text("${appraisal.design.name.lowercase().replace('_', ' ')} · ${appraisal.status}", style = MaterialTheme.typography.titleMedium)
                        Text("Independent review: ${appraisal.independentReview}")
                        runCatching { Json.decodeFromString<List<AppraisalJudgment>>(appraisal.judgmentsJson) }.getOrDefault(emptyList()).forEach { judgment ->
                            Text("${judgment.domain}: ${judgment.concern.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelLarge)
                            Text(judgment.rationale)
                            state.passages.filter { it.passageId in judgment.passageIds }.forEach { Text("${it.locator}: “${it.quote}”", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}
