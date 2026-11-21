package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatStatisticsDialog(
    repliesPerTurn: List<Int>,
    prompts: Int,
    tools: Int,
    invocations: List<dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation>,
    hasOlderHistory: Boolean,
    loadOlder: () -> Unit,
    dismiss: () -> Unit
) {
    val replies = repliesPerTurn.sum()
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Conversation statistics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasOlderHistory) TextButton(onClick = loadOlder) { Text("Showing loaded history · Load earlier turns") }
                if (prompts + replies > 0) {
                    Canvas(Modifier.size(84.dp).align(Alignment.CenterHorizontally)) {
                        val sweep = 360f * prompts / (prompts + replies)
                        drawArc(primary, -90f, sweep, true)
                        drawArc(secondary, -90f + sweep, 360f - sweep, true)
                    }
                }
                Text("$prompts prompts", color = primary)
                Text("$replies responses", color = secondary)
                Text("$tools tool events")
                if (invocations.isNotEmpty()) {
                    Text("${invocations.size} model calls · ${invocations.distinctBy { it.model }.size} models")
                    Text("${invocations.sumOf { it.inputTokens.toLong() }} input · ${invocations.sumOf { it.outputTokens.toLong() }} output tokens" + if (invocations.any { it.estimated }) " (includes estimates)" else "")
                }
                val recent = repliesPerTurn.takeLast(24)
                if (recent.isNotEmpty()) {
                    Text("Responses per turn · last ${recent.size}", style = MaterialTheme.typography.labelMedium)
                    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                        val step = size.width / recent.size
                        val peak = recent.max().coerceAtLeast(1)
                        recent.forEachIndexed { i, count ->
                            val height = size.height * count / peak
                            drawRect(secondary, Offset(i * step, size.height - height), Size(step * 0.8f, height))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}
