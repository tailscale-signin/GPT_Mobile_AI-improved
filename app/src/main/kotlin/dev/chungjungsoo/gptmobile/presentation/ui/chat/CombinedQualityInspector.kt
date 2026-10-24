package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.research.researchHash

/** Delivery and passage checks are different outcomes; neither is a whole-answer truth score. */
@Composable
internal fun CombinedDeliveryStatus(report: CombinedQualityReport, answer: String) {
    val stale = report.answerDigest.isNotBlank() && report.answerDigest != researchHash(answer)
    val delivery = when {
        stale -> "Checks for this revision are unavailable"
        report.correctionRequired -> "Correction required"
        report.deliveredScopeStatus == "COMPLETED_WITH_LIMITS" -> "Delivered with limits"
        report.deliveredScopeStatus == "COMPLETED" -> "Completion checks passed"
        report.deliveredScopeStatus == "FAILED" -> "Combined answer unavailable"
        report.deliveredScopeStatus == "PARTIAL" -> "Partial Combined answer"
        else -> "Checking the Combined answer"
    }
    val evidence = when (report.verificationStatus) {
        "PASSAGES_CHECKED_WITH_LIMITS" -> "Supplied passages checked with limits"
        "EVIDENCE_UNAVAILABLE" -> "Required evidence unavailable"
        "NEEDS_VERIFICATION" -> "Evidence checks remain open"
        "CORRECTION_REQUIRED" -> "Material support issue remains"
        else -> "Not factually verified"
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(delivery, style = MaterialTheme.typography.labelMedium, color = if (report.correctionRequired && !stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (!stale) {
            Text(evidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            report.checks.filter { it.category in setOf("RESEARCH_SCOPE", "REQUEST", "SCOPE") && it.result == "FAIL" && it.id != "ANSWER_CONTRACT" }.forEach { question ->
                Text("Unresolved: ${question.message.ifBlank { question.id }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Debug-only. Successful execution never implies factual/semantic certification. */
@Composable
internal fun CombinedQualityInspector(report: CombinedQualityReport, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Combined quality · " + report.status, style = MaterialTheme.typography.titleSmall)
            Text("Delivery: ${report.deliveredScopeStatus} · evidence checks: ${report.verificationStatus}", style = MaterialTheme.typography.labelSmall)
            Text("Contributions supplied: ${report.includedProfileUids.size} · profiles with established text links: ${report.representedProfileUids.size}", style = MaterialTheme.typography.labelSmall)
            Text("Length: ${report.lengthCompliance} · required topics: ${report.topicCoverage} · source links: ${report.sourceLinkStatus}", style = MaterialTheme.typography.labelSmall)
            Text("Uncertain source links are not evidence that material was omitted.", style = MaterialTheme.typography.labelSmall)
            Text("Revision: ${report.revisionId} · word counter: ${report.wordCounterVersion}", style = MaterialTheme.typography.labelSmall)
            Text("${report.wordCount} words · ${report.repairRounds} repair attempts · ${report.repairAccepted} accepted · ${report.repairRejected} rejected · ${report.duplicatePassagesRemoved} duplicates removed", style = MaterialTheme.typography.labelSmall)
            Text("${report.lexicalMissing}/${report.sourceUnits} unresolved lexical hints · semantic/factual verification: not certified", style = MaterialTheme.typography.labelSmall)
            report.turnContext?.let { Text("${it.date} · ${it.timezone} · ${it.authority}", style = MaterialTheme.typography.labelSmall) }
            report.issues.forEach { Text(it, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide contribution records" else "Inspect contribution records") }
            if (expanded) {
                Text("${report.consideredProfileUids.size} contributors considered. Structural usability and matching wording do not measure truth or require equal word shares.", style = MaterialTheme.typography.bodySmall)
                report.passages.forEach { passage ->
                    Text("Profiles: ${passage.profileUids.sorted().joinToString()} · primary display: ${passage.primaryProfileUid.orEmpty()} · ${passage.evidenceStatus} · ${passage.origin} · ${passage.semanticLinkStatus}", style = MaterialTheme.typography.labelSmall)
                    Text("Sources: ${passage.sourceIds.joinToString()} · claims: ${passage.claimIds.joinToString()}", style = MaterialTheme.typography.labelSmall)
                    if (passage.matches(answer)) Text(answer.substring(passage.start, passage.end), style = MaterialTheme.typography.bodySmall)
                }
                report.decisions.forEach { decision ->
                    Text("${decision.claimId} · ${decision.disposition}: ${decision.reason}", style = MaterialTheme.typography.labelSmall)
                    Text("${decision.semanticStatus} · ${decision.finalSpans.size} linked final span(s)", style = MaterialTheme.typography.labelSmall)
                }
                report.checks.forEach { Text("${it.category} · ${it.result} · ${it.id}: ${it.message}", style = MaterialTheme.typography.labelSmall) }
                report.verificationReceipts.forEach { receipt ->
                    Text("${receipt.claimId} · ${receipt.result} · revision ${receipt.revisionId} · ${receipt.evidenceIds.size} source record(s)", style = MaterialTheme.typography.labelSmall)
                    Text(receipt.limitations.joinToString("; "), style = MaterialTheme.typography.labelSmall)
                }
                report.repairRejections.forEach { Text("Rejected revision: $it", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
