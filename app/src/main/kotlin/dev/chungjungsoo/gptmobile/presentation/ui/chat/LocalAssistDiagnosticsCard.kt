package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.assist.AssistTaskState

/** Uses only the saved response projection; navigation never probes or loads a model. */
@Composable
internal fun LocalAssistDiagnosticsCard(tasks: List<AssistTaskState>, invocations: List<ModelInvocation>) {
    if (tasks.isEmpty() && invocations.isEmpty()) {
        Text("Local Assist · execution unrecorded for this response", style = MaterialTheme.typography.bodySmall)
        return
    }
    var tab by remember { mutableStateOf("Overview") }
    val expected = tasks.firstOrNull { it.taskId.startsWith("expected:") }
    val physical = invocations.filter { it.kind == "assist" }.distinctBy { it.id }
    val included = invocations.filter { it.assistInclusionVerified }.flatMap { invocation ->
        runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(invocation.consumedAssistInvocationIds ?: "[]") }.getOrDefault(emptyList())
    }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Local Assist · ${expected?.model ?: tasks.firstNotNullOfOrNull { it.model } ?: "Profile unavailable"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth()) {
            listOf("Overview", "Tasks", "Evidence", "Runtime").forEach { label ->
                TextButton(onClick = { tab = label }, modifier = Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall) }
            }
        }
        when (tab) {
            "Overview" -> {
                Text("${physical.size} recorded local invocations. ${tasks.count { it.delivery == dev.chungjungsoo.gptmobile.data.assist.AssistDelivery.VALIDATED }} validated task artifacts.")
                Text("${physical.count { it.id in included }} local invocations have evidence confirmed in a serialized primary request.", style = MaterialTheme.typography.bodySmall)
                Text("Generation, validation and request inclusion are separate. No invocation means usage is unrecorded.", style = MaterialTheme.typography.bodySmall)
                expected?.let { Text(it.reason.replace('_', ' '), style = MaterialTheme.typography.bodySmall) }
            }
            "Tasks" -> tasks.filterNot { it.taskId.startsWith("expected:") }.forEach {
                Text("${it.taskType ?: "Admission"} · ${it.execution} · ${it.reason.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            }
            "Evidence" -> tasks.filter { it.sourceSnapshot != null }.forEach {
                Text("${it.delivery} · ${it.selectedSpanIds.size} selected · ${it.examinedSpanIds.size} examined · ${it.unprocessedSpanIds.size} unprocessed", style = MaterialTheme.typography.bodySmall)
            }
            "Runtime" -> {
                physical.forEach { Text("${it.model} · ${it.actualRuntime ?: "Runtime not reported"} · ${it.observedInputTokens ?: it.inputTokens.toLong()} input (${it.inputTokenSource}) / ${it.observedOutputTokens ?: it.outputTokens.toLong()} output (${it.outputTokenSource}) · ${it.durationMs} ms · ${it.status}", style = MaterialTheme.typography.bodySmall) }
                if (physical.isEmpty()) Text("No observed backend or accelerator for this response.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
