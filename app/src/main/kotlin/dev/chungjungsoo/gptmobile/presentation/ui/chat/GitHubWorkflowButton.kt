package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon

@Composable
internal fun GitHubWorkflowButton(workflows: List<GitHubQuickWorkflow>, enabled: Boolean, onRun: (GitHubQuickWorkflow) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledIconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("github_workflows")) {
            ThemeIcon(Icons.Rounded.AccountTree, "Workflows")
        }
        FadingDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Run a workflow", style = MaterialTheme.typography.titleSmall) }, enabled = false, onClick = {})
            workflows.forEach { workflow ->
                DropdownMenuItem(text = { Text(workflow.title) }, leadingIcon = { ThemeIcon(Icons.Rounded.AccountTree, null) }, onClick = {
                    expanded = false
                    onRun(workflow)
                })
            }
        }
    }
}
