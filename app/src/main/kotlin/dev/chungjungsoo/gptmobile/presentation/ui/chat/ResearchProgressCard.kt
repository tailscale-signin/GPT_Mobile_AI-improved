package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import dev.chungjungsoo.gptmobile.data.research.currentReviewRecord
import dev.chungjungsoo.gptmobile.data.research.readableWorkCount

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ResearchProgressCard(sessions: List<ResearchSession>, activeRunIds: Set<String>, stop: (String) -> Unit, writerFailure: String? = null, onRecovery: ((String) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val activeSessions = sessions.filter { it.runId in activeRunIds && !it.snapshot.complete }
    val latest = (activeSessions.ifEmpty { sessions }).maxByOrNull { it.snapshot.updatedAt } ?: return
    val snapshot = latest.snapshot
    val active = latest.runId in activeRunIds && !snapshot.complete
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (active) "Research · ${snapshot.phase}" else "Research evidence", style = MaterialTheme.typography.titleSmall)
            Text("${snapshot.searches} searches · ${snapshot.engineResponses} engine reports · ${snapshot.engineFailures} unavailable", style = MaterialTheme.typography.bodySmall)
            Text("${snapshot.sources.readableWorkCount()} / ${snapshot.targetSources} readable sources · ${snapshot.sources.count { it.status == "Blocked" }} blocked · ${snapshot.claims.count { it.verdict == "Supported" }} supported claims", style = MaterialTheme.typography.bodySmall)
            val accepted = snapshot.sources.readableWorkCount()
            Text(
                "${snapshot.sources.count { it.readable && it.status == "Read" }} complete · ${snapshot.sources.count { it.readable && it.status != "Read" }} partial · ${if (accepted == 0) {
                    "No readable sources"
                } else if (accepted < snapshot.targetSources) {
                    "Partial coverage"
                } else {
                    "Target reached"
                }}",
                style = MaterialTheme.typography.bodySmall
            )
            val coverage = dev.chungjungsoo.gptmobile.data.research.assessResearchCoverage(snapshot.questionPlan, snapshot.claims, snapshot.sources)
            Text("Question coverage: ${coverage.coveredQuestionIds.size}/${snapshot.questionPlan.size} · ${if (coverage.complete) "Covered by relevant passages" else "Incomplete or unverified"}", style = MaterialTheme.typography.bodySmall)
            Text("${snapshot.modelEvents.count { it.local && it.outcome == "SUCCESS" }} local research stages · ${snapshot.claims.count { it.independentReview }} independently passage-reviewed claims", style = MaterialTheme.typography.bodySmall)
            Text("Planning: ${snapshot.planningStatus} · final response delivery is tracked separately", style = MaterialTheme.typography.bodySmall)
            Text(if (snapshot.claims.any { it.verdict == "Supported" }) "Some claims reviewed; see evidence for individual verdicts" else "Unreviewed evidence", style = MaterialTheme.typography.bodySmall)
            remember(snapshot) { snapshot.currentReviewRecord() }?.let { Text("Independent review: " + it.status.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall) }
            val papers = snapshot.sources.filter { it.paperId.isNotBlank() }
            if (papers.isNotEmpty()) Text("${papers.size} academic works · ${papers.flatMap { it.engines }.distinct().size} underlying sources · ${papers.count { it.readable }} passages read", style = MaterialTheme.typography.bodySmall)
            if (!active && writerFailure != null) {
                Text("Evidence saved; final writer unavailable", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text(writerFailure.take(400), style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.foundation.layout.FlowRow {
                TextButton(onClick = { expanded = true }) { Text("View saved evidence") }
                if (!active && writerFailure != null && onRecovery != null) {
                    TextButton(onClick = { onRecovery("Retry failed stage") }) { Text(if (writerFailure.contains("403")) "Recheck writer and retry" else "Retry unfinished stage") }
                    TextButton(onClick = { onRecovery("Proceed primary-only") }) { Text("Primary-only recovery") }
                }
                if (active) TextButton(onClick = { activeSessions.forEach { stop(it.runId) } }) { Text("Stop and summarize") }
            }
        }
    }
    if (expanded) {
        val uri = LocalUriHandler.current
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Research history", style = MaterialTheme.typography.titleLarge) }
                item { OutlinedTextField(filter, { filter = it.take(200) }, label = { Text("Search sources and captured evidence") }, modifier = Modifier.fillMaxWidth()) }
                sessions.sortedByDescending { it.snapshot.updatedAt }.forEach { session ->
                    item(key = session.runId) {
                        Text(session.snapshot.task.take(200), style = MaterialTheme.typography.titleMedium)
                        Text("${session.snapshot.phase} · ${session.snapshot.searches} searches · ${session.snapshot.attempts} page attempts", style = MaterialTheme.typography.bodySmall)
                        session.snapshot.notes.filterNot { it.startsWith("Policy:") }.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (session.snapshot.modelEvents.isNotEmpty()) {
                        item(key = "models:${session.runId}") {
                            Text("Research & Local Assist execution", style = MaterialTheme.typography.titleSmall)
                            Text("Token counts below are estimates; Debug → Runtime shows provider-measured usage when available. Model review is not proof of scientific validity.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(session.snapshot.modelEvents, key = { "model:${session.runId}:${it.id}" }) { event ->
                        OutlinedCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${if (event.local) "Local Assist" else "Research model"} · ${event.model}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text("${event.stage} · ${event.outcome} · ${event.durationMs} ms", style = MaterialTheme.typography.labelMedium)
                                Text("~${event.inputTokensEstimate} input / ~${event.outputTokensEstimate} output tokens · ${event.reason.ifBlank { if (event.independent) "Distinct model review" else "No independent review claimed" }}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(session.snapshot.sources.filter { filter.isBlank() || (it.title + " " + it.passage + " " + it.url).contains(filter, ignoreCase = true) }, key = { session.runId + it.id }) { source ->
                        OutlinedCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(source.title, style = MaterialTheme.typography.titleSmall)
                                Text(source.status + if (source.originId.isNotBlank()) " · Related content: ${source.originId}" else "", style = MaterialTheme.typography.labelSmall)
                                if (source.paperId.isNotBlank()) {
                                    Text("${source.doi.ifBlank { source.paperId }} · ${source.reviewStatus} · ${source.engines.distinct().size} providers", style = MaterialTheme.typography.bodySmall)
                                    if (source.isRetracted == true) Text("Retraction reported · check the article notice", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                                    if (source.isPreprint == true) Text("Preprint · peer review is not established", style = MaterialTheme.typography.bodySmall)
                                    Text("${source.publicationType} · ${source.contentLicense ?: "License not supplied"}", style = MaterialTheme.typography.bodySmall)
                                    Text("Version: ${source.versionId.take(100)} · ${source.providerReceipts.size} receipts", style = MaterialTheme.typography.labelSmall)
                                }
                                if (source.publishedAt.isNotBlank()) Text("Published: ${source.publishedAt}", style = MaterialTheme.typography.bodySmall)
                                if (source.retrievedAt > 0) Text("Read: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(source.retrievedAt))}", style = MaterialTheme.typography.bodySmall)
                                session.snapshot.claims.filter { it.sourceId == source.id }.forEach { claim ->
                                    Text("${claim.verdict}: ${claim.text}", style = MaterialTheme.typography.bodyMedium)
                                    Text(claim.quote, style = MaterialTheme.typography.bodySmall)
                                    Text("Exact quote: ${claim.exactQuoteVerified} · ${if (claim.independentReview) "Distinct model passage review" else "Self-review or unverified independence"}", style = MaterialTheme.typography.labelSmall)
                                }
                                if (source.passage.isNotBlank()) Text(source.passage.take(1800), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { runCatching { if (java.net.URI(source.url).scheme?.lowercase() in setOf("http", "https")) uri.openUri(source.url) } }) { Text("Open source") }
                            }
                        }
                    }
                }
            }
        }
    }
}
