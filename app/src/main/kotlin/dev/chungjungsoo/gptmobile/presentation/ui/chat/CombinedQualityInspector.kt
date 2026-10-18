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

/** Debug-only. Successful execution never implies factual/semantic certification. */
@Composable
internal fun CombinedQualityInspector(report: CombinedQualityReport, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Combined quality · " + report.status, style = MaterialTheme.typography.titleSmall)
            Text("${report.wordCount} words · ${report.repairRounds} repair attempts · ${report.repairAccepted} accepted · ${report.repairRejected} rejected · ${report.duplicatePassagesRemoved} duplicates removed", style = MaterialTheme.typography.labelSmall)
            Text("${report.lexicalMissing}/${report.sourceUnits} unresolved lexical hints · semantic/factual verification: not certified", style = MaterialTheme.typography.labelSmall)
            report.turnContext?.let { Text("${it.date} · ${it.timezone} · ${it.authority}", style = MaterialTheme.typography.labelSmall) }
            report.issues.forEach { Text(it, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide contribution records" else "Inspect contribution records") }
            if (expanded) {
                report.passages.forEach { passage ->
                    Text("Profiles: ${passage.profileUids.sorted().joinToString()} · primary display: ${passage.primaryProfileUid.orEmpty()} · ${passage.evidenceStatus} · ${passage.origin}", style = MaterialTheme.typography.labelSmall)
                    Text("Sources: ${passage.sourceIds.joinToString()} · claims: ${passage.claimIds.joinToString()}", style = MaterialTheme.typography.labelSmall)
                    if (passage.matches(answer)) Text(answer.substring(passage.start, passage.end), style = MaterialTheme.typography.bodySmall)
                }
                report.decisions.forEach { Text("${it.claimId} · ${it.disposition}: ${it.reason}", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
