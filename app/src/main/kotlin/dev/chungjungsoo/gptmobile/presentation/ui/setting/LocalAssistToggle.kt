package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.chungjungsoo.gptmobile.data.assist.AssistMode

@Composable
internal fun LocalAssistToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mode: AssistMode = AssistMode.PREPARATION_ONLY,
    onModeChanged: ((AssistMode) -> Unit)? = null,
    extractEvidence: Boolean = false,
    onExtractionChanged: ((Boolean) -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Local Assist", style = MaterialTheme.typography.titleSmall)
                Text("Let a loaded local model prepare source passages and research records. Your primary model controls the answer. Originals remain available; changes apply on the next request.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (onModeChanged != null && checked) {
            AssistMode.entries.forEach { choice ->
                FilterChip(selected = mode == choice, onClick = { onModeChanged(choice) }, label = { Text(choice.label) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
            }
            if (onExtractionChanged != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Prepare exact source passages", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = extractEvidence, onCheckedChange = onExtractionChanged, enabled = enabled)
                }
            }
            Text("Phone worker only. Adaptive and Local first currently use bounded preparation. Cold loading, reasoning and autonomous tool workflows require separate qualification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
