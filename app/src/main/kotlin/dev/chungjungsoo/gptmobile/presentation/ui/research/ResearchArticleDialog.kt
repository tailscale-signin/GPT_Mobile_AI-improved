package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ResearchArticleDialog(workId: String, versionId: String? = null, authority: String = "public", chatId: Int = 0, initialProfileUid: String = "", currentTools: () -> ChatMcpToolConfig = { ChatMcpToolConfig() }, onClose: () -> Unit, viewModel: ResearchArticleViewModel = hiltViewModel(), originalVersionId: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var actionError by rememberSaveable(workId) { mutableStateOf<String?>(null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val features by viewModel.features.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val focusedJob by viewModel.focusedJob.collectAsStateWithLifecycle()
    val admitting by viewModel.admissionInProgress.collectAsStateWithLifecycle()
    var profileUid by rememberSaveable(workId) { mutableStateOf(initialProfileUid) }
    val configuredCrawlerUid = dev.chungjungsoo.gptmobile.data.research.feature.ResearchProfileResolver.examinerUid(features, chatId, currentTools())
    LaunchedEffect(configuredCrawlerUid) {
        if (profileUid.isBlank()) profileUid = configuredCrawlerUid
    }
    var showingOriginal by rememberSaveable(workId, versionId) { mutableStateOf(false) }
    val displayedVersionId = if (showingOriginal) originalVersionId ?: versionId else versionId
    val relevantJobs = (jobs + listOfNotNull(focusedJob)).distinctBy { it.jobId }.filter { it.workId == workId && it.authority == authority && it.chatId == chatId }
    val examinationActive = relevantJobs.any { it.stage in dev.chungjungsoo.gptmobile.data.research.feature.ExaminationLifecycle.active }
    val selection = dev.chungjungsoo.gptmobile.data.research.feature.ResearchProfileResolver.selection(profileUid, profiles)
    val selectedProfile = selection.profile
    val examinationBlocker = dev.chungjungsoo.gptmobile.data.research.feature.examinerSelectionIssue(selection) ?: if (selectedProfile != null) {
        dev.chungjungsoo.gptmobile.data.research.feature.examinationAdmission(features, selectedProfile, ExaminationRequest(workId, profileUid, chatId, origin = dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.USER_EXAMINE), currentTools())
    } else {
        "Choose Examiner to start an examination."
    }
    LaunchedEffect(workId, displayedVersionId, authority, relevantJobs.map { it.updatedAt }) { viewModel.open(workId, displayedVersionId, authority) }
    FadingDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize().glassWindowBackdrop(), containerColor = dev.chungjungsoo.gptmobile.presentation.common.overlayWindowContainerColor(), topBar = { TopAppBar(title = { Text("Article evidence") }, navigationIcon = { OutlinedButton(onClick = onClose) { Text("Back") } }) }) { padding ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) item { Text("Loading saved article…") }
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                actionError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                state.work?.let { work ->
                    item {
                        Text(work.title, style = MaterialTheme.typography.titleLarge)
                        Text(work.canonicalUrl, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            runCatching { uriHandler.openUri(work.canonicalUrl) }.onFailure { actionError = "No browser is available to open this source." }
                        }) { Text("Open source") }
                        state.version?.takeIf { it.publicSource }?.let { version ->
                            OutlinedButton(onClick = {
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
                item {
                    val review = state.reviews.firstOrNull()
                    val artifact = review?.let { runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.StudyReviewArtifact>(it.artifactJson) }.getOrNull() }
                    Text(androidx.compose.ui.res.stringResource(if (review == null) dev.chungjungsoo.gptmobile.R.string.audit_not_examined else dev.chungjungsoo.gptmobile.R.string.audit_current_assessment), style = MaterialTheme.typography.titleMedium)
                    artifact?.let {
                        Text(it.conclusion)
                        Text(it.limitation, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.version?.let { version ->
                    item {
                        if (originalVersionId != null && originalVersionId != versionId) OutlinedButton(onClick = { showingOriginal = !showingOriginal }) { Text(if (showingOriginal) "View updated assessment" else "View original cited version") }
                        Text("Coverage: ${version.coverage.name.lowercase().replace('_', ' ')}")
                        dev.chungjungsoo.gptmobile.presentation.common.DetailsSection {
                            Text("Captured ${java.time.Instant.ofEpochMilli(version.retrievedAt)} · ${version.textBytes} UTF-8 bytes", style = MaterialTheme.typography.bodySmall)
                            Text("Version ${version.versionId.take(16)} · ${if (state.bodyAvailable) "Body integrity verified" else "Body unavailable or incomplete; historical metadata retained"}")
                            Text("Source capture, AI appraisal, publication review and independent review are separate.")
                            state.validations.firstOrNull()?.let { validation -> Text("Capture validation: ${validation.kind.lowercase().replace('_', ' ')} · ${validation.workKind.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
                            if (state.relationships.any { it.disposition == "PROVEN_PUBLIC_IDENTITY_LINKED" }) Text("Related public captures share a verified identifier; their versions and assessment histories remain available.", style = MaterialTheme.typography.bodySmall)
                        }
                        val conflicts = state.relationships.filter { it.disposition == "IDENTITY_REVIEW_REQUIRED" }
                        if (conflicts.isNotEmpty()) {
                            Text("Identity review required", style = MaterialTheme.typography.titleMedium)
                            Text("These DOI, PMID or URL identifiers point to different saved studies. This capture and the existing studies have been preserved separately; conflicting aliases are quarantined.")
                            conflicts.map { it.evidence }.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            androidx.compose.material3.OutlinedButton(onClick = { viewModel.keepWorksSeparate() }) { Text("Keep studies separate") }
                        }
                        if (state.relationships.any { it.disposition == "REVIEWED_KEEP_SEPARATE" }) Text("Identity reviewed: studies kept separate; original identifier mappings preserved.", style = MaterialTheme.typography.bodySmall)
                        if (state.relationships.any { it.disposition == "AUTHORITY_ISOLATED_NO_MERGE" }) Text("Other records share an identifier but use separate permissions. Their evidence remains isolated.", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        dev.chungjungsoo.gptmobile.presentation.ui.setting.DelegateModelDropdown(profiles = profiles.filter { it.enabled }, selectedProfileUid = profileUid, enabled = true, onSelected = { profileUid = it?.uid.orEmpty() }, automaticLabel = "Choose Examiner", roleLabel = "Examiner")
                        dev.chungjungsoo.gptmobile.presentation.common.DetailsSection(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.audit_examiner_settings)) {
                            if (profileUid.isNotBlank()) OutlinedButton(onClick = { profileUid = "" }) { Text("Clear Examiner selection") }
                            if (chatId > 0) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { viewModel.saveCrawlerForChat(chatId, profileUid, currentTools()) }, enabled = selectedProfile != null) { Text("Use this Examiner for this chat") }
                                    if (features.conversationResearch[chatId]?.crawlerProfileUid != null) {
                                        OutlinedButton(onClick = {
                                            viewModel.saveCrawlerForChat(chatId, "", currentTools())
                                            profileUid = ""
                                        }) { Text("Clear chat override") }
                                    }
                                }
                            }
                        }
                        examinationBlocker?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        val selection = androidx.compose.runtime.remember(relevantJobs, profileUid, version.versionId) { examinationPrimarySelection(relevantJobs, profileUid, version.versionId) }
                        val completed = selection.action == ExaminationPrimaryAction.EXAMINE_AGAIN
                        androidx.compose.material3.Button(onClick = { viewModel.examine(ExaminationRequest(workId, profileUid, chatId, version.versionId, authority, reexamine = completed, resumeJobId = selection.resumeJobId), currentTools) }, modifier = Modifier.fillMaxWidth(), enabled = examinationBlocker == null && !examinationActive && !admitting) {
                            Text(
                                if (admitting) {
                                    "Starting examination…"
                                } else if (examinationActive) {
                                    "Examination in progress"
                                } else {
                                    androidx.compose.ui.res.stringResource(
                                        if (completed) {
                                            dev.chungjungsoo.gptmobile.R.string.audit_examine_again
                                        } else if (selection.action == ExaminationPrimaryAction.RESUME) {
                                            dev.chungjungsoo.gptmobile.R.string.audit_resume
                                        } else {
                                            dev.chungjungsoo.gptmobile.R.string.audit_examine
                                        }
                                    )
                                }
                            )
                        }
                        Text("Uses the selected model and permitted article reader. Provider usage may be billable. Research Depth remains unchanged; all paper and profile permissions still apply.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.reviews.forEachIndexed { index, review ->
                    item(key = "review:${review.reviewId}") {
                        dev.chungjungsoo.gptmobile.presentation.common.DetailsSection(androidx.compose.ui.res.stringResource(if (index == 0) dev.chungjungsoo.gptmobile.R.string.audit_assessment_details else dev.chungjungsoo.gptmobile.R.string.audit_assessment_history)) {
                            val artifact = runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.StudyReviewArtifact>(review.artifactJson) }.getOrNull()
                            Text(if (index == 0) "Current assessment · ${artifact?.outcomeKey.orEmpty()}" else "Assessment history · ${artifact?.outcomeKey.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                            Text(artifact?.conclusion.orEmpty())
                            Text(artifact?.limitation.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Text(if (state.work?.retracted == true) "Retraction reported · score suspended" else "${review.status} · AI review score ${review.overallScore ?: "Pending"}")
                            Text("Reliability ${review.reliabilityScore ?: "Pending"} · Summary accuracy ${review.accuracyScore ?: "Pending"} · Rubric ${review.rubricVersion}")
                            Text("${review.model} · ${java.time.Instant.ofEpochMilli(review.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            (artifact?.reliability.orEmpty() + artifact?.accuracy.orEmpty()).forEach { domain ->
                                Text("${domain.domain.replace('_', ' ')}: ${domain.grade ?: "Unknown"} · ${domain.rationale}", style = MaterialTheme.typography.bodySmall)
                                state.passages.filter { it.passageId in domain.passageIds }.forEach { Text("${it.locator}: ${it.quote}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                relevantJobs.forEach { job ->
                    item {
                        Text(examinationStageLabel(job.stage), style = MaterialTheme.typography.titleMedium)
                        job.terminalReason?.let { Text(it) }
                        val checkpoint = runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.ExaminationCheckpoint>(job.checkpointJson) }.getOrNull()
                        checkpoint?.failure?.let { failure ->
                            Text(failure.detail.ifBlank { failure.code.replace('_', ' ') }, style = MaterialTheme.typography.bodySmall)
                            if (job.stage == ExaminationStage.BLOCKED_SOURCE) Text("Source access is blocked. Scientific reliability has not been reassessed.", style = MaterialTheme.typography.bodySmall)
                            if (failure.retryable && failure.retryAtMs != null) {
                                Text("Automatic retry after ${java.time.Instant.ofEpochMilli(failure.retryAtMs)}. You can stop this examination below.", style = MaterialTheme.typography.bodySmall)
                            } else if (job.stage in dev.chungjungsoo.gptmobile.data.research.feature.ExaminationLifecycle.waiting) {
                                Text("Retry with Examine / Resume after the stated condition is resolved.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        checkpoint?.sourceValidation?.let { Text("Source check: ${it.status.replace('_', ' ')} · ${it.owner}", style = MaterialTheme.typography.bodySmall) }
                        checkpoint?.independentReports.orEmpty().forEach { report ->
                            Text("Review by ${report.reviewerModel}: ${report.verdict}")
                            report.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (features.researchFeature.showWarnings) checkpoint?.limitations.orEmpty().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (job.stage in dev.chungjungsoo.gptmobile.data.research.feature.ExaminationLifecycle.active || job.stage in dev.chungjungsoo.gptmobile.data.research.feature.ExaminationLifecycle.waiting || job.stage == ExaminationStage.BLOCKED_SOURCE) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (job.stage in dev.chungjungsoo.gptmobile.data.research.feature.ExaminationLifecycle.active) OutlinedButton(onClick = { viewModel.pause(job.jobId) }) { Text("Pause") }
                                OutlinedButton(onClick = { viewModel.cancel(job.jobId) }) { Text("Stop examination") }
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

internal fun examinationStageLabel(stage: ExaminationStage): String = when (stage) {
    ExaminationStage.WAITING_NETWORK -> "Waiting for network recovery"
    ExaminationStage.WAITING_RATE_LIMIT -> "Waiting for provider rate limit"
    ExaminationStage.WAITING_CAPACITY -> "Waiting for examination capacity"
    ExaminationStage.BLOCKED_SOURCE -> "Source access blocked"
    ExaminationStage.UNAVAILABLE -> "Source unavailable"
    else -> stage.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
}
