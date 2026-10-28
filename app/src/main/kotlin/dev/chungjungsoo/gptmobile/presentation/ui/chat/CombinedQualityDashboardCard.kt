package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityDashboard
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CombinedQualityDashboardCard(report: CombinedQualityReport, answer: String, documentDigest: String? = null, profileNames: Map<String, String> = emptyMap()) {
    var expanded by remember(report.answerDigest) { mutableStateOf(false) }
    val dashboard by produceState<CombinedQualityDashboard?>(null, report, answer, documentDigest) {
        value = withContext(Dispatchers.Default) { CombinedQualityDashboard.from(report, documentDigest ?: researchHash(answer)) }
    }
    val stats = dashboard ?: return
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("${stats.words} words · ${stats.completion.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide Combined quality" else "Combined quality & contributions") }
        if (expanded) {
            Text(if (stats.evaluated) "${stats.linked}/${stats.claims} distinct claims text-linked · ${stats.partial} partial · ${stats.unresolved} unresolved" else "Coverage not evaluated", style = MaterialTheme.typography.bodySmall)
            Text("${stats.shared} linked claims shared across profiles · ${stats.duplicatesRemoved} duplicate passages removed", style = MaterialTheme.typography.bodySmall)
            stats.profiles.forEach { profile ->
                Text("${profileNames[profile.profileUid] ?: profile.profileUid}: ${profile.linked}/${profile.claims} text-linked (${profile.linkedPercent?.let { "$it%" } ?: "not evaluated"}) · ${profile.partial} partial · ${profile.unresolved} unresolved", style = MaterialTheme.typography.bodySmall)
            }
            stats.usage?.let { usage ->
                Text("Writer input: ${usage.inputTokens ?: "unknown"} (${usage.inputSource}); output: ${usage.outputTokens ?: "unknown"} (${usage.outputSource})", style = MaterialTheme.typography.bodySmall)
            } ?: Text("Writer token usage unavailable; reserved tokens are not measured usage.", style = MaterialTheme.typography.bodySmall)
            Text("Shared claims count for every supporting profile. Text links do not certify factual accuracy; unresolved links do not prove omissions.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
