package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.RepositoryTaskContextReceipt
import kotlinx.serialization.json.JsonObject

@Composable
internal fun RepositoryTaskContextCard(value: JsonObject?, sourceSha: String) {
    val receipt = remember(value, sourceSha) { RepositoryTaskContextReceipt.from(value, sourceSha) } ?: return
    var expanded by remember(receipt) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().testTag("repository_task_context_receipt")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (receipt.current) "Prepared repository context" else "Context needs refreshing", style = MaterialTheme.typography.titleSmall)
            receipt.summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (receipt.sources.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide source references" else "Show source references") }
                if (expanded) receipt.sources.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
