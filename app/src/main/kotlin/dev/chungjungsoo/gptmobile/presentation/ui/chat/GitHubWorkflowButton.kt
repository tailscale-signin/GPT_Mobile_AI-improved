package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chungjungsoo.gptmobile.data.github.GitHubFixtureTestMode
import dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon

@Composable
internal fun GitHubWorkflowButton(workflows: List<GitHubQuickWorkflow>, enabled: Boolean, onRun: (GitHubQuickWorkflow) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var configured by remember(workflows) { mutableStateOf<GitHubQuickWorkflow?>(null) }
    configured?.let { workflow ->
        val review = dev.chungjungsoo.gptmobile.data.github.GitHubReviewComparison.matches(workflow.prompt)
        var baseline by remember(workflow) { mutableStateOf("") }
        var criteria by remember(workflow) { mutableStateOf("") }
        var fixtureMode by remember(workflow) { mutableStateOf(GitHubFixtureTestMode.SUPPORTED_FIXTURES) }
        val valid = baseline.isBlank() || baseline.trim().matches(Regex("[A-Za-z0-9_][A-Za-z0-9._/+@-]{0,254}"))
        AlertDialog(
            onDismissRequest = { configured = null },
            title = { Text(workflow.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (workflow.id == "integration") {
                        Text("Fixture test scope", style = MaterialTheme.typography.titleSmall)
                        GitHubFixtureTestMode.entries.forEach { mode ->
                            TextButton(onClick = { fixtureMode = mode }, modifier = Modifier.testTag("github_fixture_mode_${mode.name.lowercase()}")) {
                                Text(mode.label + if (fixtureMode == mode) " ✓" else "")
                            }
                        }
                        Text(fixtureMode.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("github_fixture_preflight"))
                        Text("Fixture-only automatic approval is configured in GitHub settings > Chat decisions. A running task retains the policy accepted when it started.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (review) {
                        OutlinedTextField(
                            value = baseline,
                            onValueChange = { baseline = it.take(255) },
                            singleLine = true,
                            label = { Text("Comparison baseline") },
                            isError = !valid,
                            supportingText = { Text("Enter a branch, tag or commit. Leave blank to use the pull request’s base when available.") }
                        )
                    }
                    OutlinedTextField(
                        value = criteria,
                        onValueChange = { criteria = it.take(2000) },
                        minLines = 2,
                        label = { Text("Additional acceptance criteria") },
                        supportingText = { Text("Optional. Enter one observable result per line.") }
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = enabled && valid, onClick = {
                    val prompt = buildString {
                        append(if (workflow.id == "integration") fixtureMode.prompt(workflow.prompt) else workflow.prompt)
                        if (review && baseline.isNotBlank()) append("\nBaseline: ").append(baseline.trim())
                        if (criteria.isNotBlank()) {
                            append("\nAcceptance criteria")
                            criteria.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { append("\n- ").append(it) }
                        }
                    }
                    configured = null
                    onRun(workflow.copy(prompt = prompt))
                }) { Text("Run workflow") }
            },
            dismissButton = { TextButton(onClick = { configured = null }) { Text("Cancel") } }
        )
    }
    Box {
        FilledIconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("github_workflows")) {
            ThemeIcon(Icons.Rounded.AccountTree, "Workflows")
        }
        FadingDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Run a workflow", style = MaterialTheme.typography.titleSmall) }, enabled = false, onClick = {})
            workflows.forEach { workflow ->
                DropdownMenuItem(text = { Text(workflow.title) }, leadingIcon = { ThemeIcon(Icons.Rounded.AccountTree, null) }, onClick = {
                    expanded = false
                    if (dev.chungjungsoo.gptmobile.data.github.GitHubReviewComparison.matches(workflow.prompt) || workflow.id in setOf("integration", "improve")) {
                        configured = workflow
                    } else {
                        onRun(workflow)
                    }
                })
            }
        }
    }
}
