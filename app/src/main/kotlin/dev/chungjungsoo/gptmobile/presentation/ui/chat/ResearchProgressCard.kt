package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import dev.chungjungsoo.gptmobile.data.research.readableWorkCount
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet as ModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ResearchProgressCard(
    sessions: List<ResearchSession>,
    activeRunIds: Set<String>,
    stop: (String) -> Unit,
    writerFailure: String? = null,
    onRecovery: ((String) -> Unit)? = null,
    deliveryReceipt: dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt? = null,
    cancelWriting: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val activeSessions = sessions.filter { it.runId in activeRunIds && !it.snapshot.complete }
    val latest = (activeSessions.ifEmpty { sessions }).maxByOrNull { it.snapshot.updatedAt } ?: return
    val snapshot = latest.snapshot
    val collecting = activeSessions.any { session -> session.snapshot.phase.lowercase().let { phase -> !phase.contains("report") && !phase.contains("writing") && !phase.contains("summary") && !phase.contains("handoff") && phase !in setOf("complete", "completed", "ready") } }
    val writing = activeRunIds.isNotEmpty() && !collecting
    val active = collecting || writing
    val readable = snapshot.sources.readableWorkCount()
    val saved = snapshot.sources.distinctBy { it.equivalenceGroup.ifBlank { it.id } }.size
    val progress = (readable.toFloat() / snapshot.targetSources.coerceAtLeast(1)).coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            collecting -> "Research · ${snapshot.phase}"
                            writing -> "Writing from saved evidence"
                            deliveryReceipt?.accepted == true -> "Research report ready"
                            else -> "Research evidence retained"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("$readable of ${snapshot.targetSources} readable · $saved discovered", style = MaterialTheme.typography.bodySmall)
                }
                if (active) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            val checked = snapshot.claims.count { claim ->
                val source = snapshot.sources.firstOrNull { it.id == claim.sourceId }
                claim.exactQuoteVerified &&
                    source?.readable == true &&
                    claim.quote.isNotBlank() &&
                    source.passage.contains(claim.quote) &&
                    claim.reviewedSourceVersion == dev.chungjungsoo.gptmobile.data.research.capturedResearchVersion(source)
            }
            val independent = snapshot.claims.count { claim ->
                val source = snapshot.sources.firstOrNull { it.id == claim.sourceId }
                claim.independentReview &&
                    claim.exactQuoteVerified &&
                    source?.readable == true &&
                    claim.quote.isNotBlank() &&
                    source.passage.contains(claim.quote) &&
                    claim.reviewedSourceVersion == dev.chungjungsoo.gptmobile.data.research.capturedResearchVersion(source)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$saved sources collected", style = MaterialTheme.typography.labelMedium)
                Text("$checked claims checked against passages", style = MaterialTheme.typography.labelMedium)
                Text("$independent independently reviewed", style = MaterialTheme.typography.labelMedium)
                Text(
                    when (deliveryReceipt?.reportValidity) {
                        dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.ACCEPTED -> "Report accepted"
                        dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.EVIDENCE_ONLY -> "Evidence only · report not accepted"
                        dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.REJECTED -> "Report needs correction"
                        dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.INCOMPLETE -> "Report incomplete"
                        null -> if (writing) "Report writing" else "Report not yet accepted"
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (!active && (writerFailure != null || deliveryReceipt?.retryEligible == true)) {
                Text("Evidence retained · writing needs attention", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = { expanded = true }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Description, contentDescription = null)
                    Text("Evidence", modifier = Modifier.padding(start = 6.dp))
                }
                if (collecting) {
                    OutlinedButton(onClick = { activeSessions.forEach { stop(it.runId) } }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Stop, contentDescription = null)
                        Text("Summarize", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (writing && cancelWriting != null) OutlinedButton(onClick = cancelWriting) { Text("Cancel writing") }
                if (!active && onRecovery != null) {
                    if (writerFailure != null || deliveryReceipt?.retryEligible == true) OutlinedButton(onClick = { onRecovery("Retry writing") }) { Text("Retry writing") }
                    if (!snapshot.complete || readable < snapshot.targetSources) OutlinedButton(onClick = { onRecovery("Continue research") }) { Text("Continue research") }
                }
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
                                OutlinedButton(onClick = { runCatching { if (java.net.URI(source.url).scheme?.lowercase() in setOf("http", "https")) uri.openUri(source.url) } }) { Text("Open source") }
                            }
                        }
                    }
                }
            }
        }
    }
}
