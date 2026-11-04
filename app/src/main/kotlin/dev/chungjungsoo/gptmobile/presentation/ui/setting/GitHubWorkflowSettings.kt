package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon
import java.util.UUID

@Composable
internal fun GitHubWorkflowSettings(workflows: List<GitHubQuickWorkflow>, enabled: Boolean, onSave: (List<GitHubQuickWorkflow>) -> Unit) {
    var editing by remember { mutableStateOf<GitHubQuickWorkflow?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeIcon(Icons.Rounded.AccountTree, null)
                Text("Workflows", style = MaterialTheme.typography.titleMedium)
            }
            Text("One tap sends the full prompt. These shortcuts appear when your message box is empty.", style = MaterialTheme.typography.bodySmall)
            workflows.forEach { workflow ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editing = workflow }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(workflow.title, modifier = Modifier.fillMaxWidth()) }
                    IconButton(onClick = { editing = workflow }, enabled = enabled) { ThemeIcon(Icons.Rounded.Edit, "Edit ${workflow.title}") }
                    IconButton(onClick = { onSave(workflows.filterNot { it.id == workflow.id }) }, enabled = enabled && workflows.size > 1) { ThemeIcon(Icons.Rounded.DeleteOutline, "Remove ${workflow.title}") }
                }
            }
            OutlinedButton(onClick = { editing = GitHubQuickWorkflow(UUID.randomUUID().toString(), "", "") }, enabled = enabled && workflows.size < 30, modifier = Modifier.testTag("github_add_workflow")) {
                ThemeIcon(Icons.Rounded.Add, null)
                Text("Add workflow")
            }
        }
    }
    editing?.let { original ->
        var title by remember(original.id) { mutableStateOf(original.title) }
        var prompt by remember(original.id) { mutableStateOf(original.prompt) }
        FadingAlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (original.title.isBlank()) "New workflow" else "Edit workflow") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(title, { title = it.take(80) }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(prompt, { prompt = it.take(16000) }, label = { Text("Prompt to send") }, minLines = 4, maxLines = 20)
                    Text("The complete prompt is sent immediately when selected in chat. Existing tool permissions still apply.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = GitHubQuickWorkflow(original.id, title.trim(), prompt.trim())
                    onSave(if (workflows.any { it.id == updated.id }) workflows.map { if (it.id == updated.id) updated else it } else workflows + updated)
                    editing = null
                }, enabled = title.isNotBlank() && prompt.isNotBlank()) { Text("Save workflow") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}
