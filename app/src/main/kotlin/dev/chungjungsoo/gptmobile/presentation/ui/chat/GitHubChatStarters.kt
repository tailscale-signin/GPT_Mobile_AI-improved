package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun GitHubChatStarters(draft: TextFieldState) {
    val empty by remember(draft) { derivedStateOf { draft.text.isEmpty() } }
    if (!empty) return
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "Open pull requests" to "List open pull requests",
            "Recent commits" to "Show recent commits",
            "Recent CI runs" to "Show recent workflow runs"
        ).forEach { (label, prompt) ->
            AssistChip(onClick = { draft.edit { if (length == 0) append(prompt) } }, label = { Text(label) })
        }
    }
}
