package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon

@Composable
internal fun LocalAssistScreen(viewModel: LocalRuntimeSettingsViewModel, onMarketplace: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val policy = settings.localAssistPolicy.validated()
    val target = profiles.firstOrNull { it.uid == policy.profileUid }
    var advanced by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingsPanel("Local Assist") {
                LocalAssistToggle(settings.localAssist, { viewModel.updateTuning(assist = it) })
                Text(
                    when {
                        !settings.localAssist -> "Off"
                        target == null -> "Choose a worker profile"
                        active.engineSpec == null -> "Waiting for a warm model"
                        else -> "Worker selected · readiness checked before each task"
                    },
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        item {
            SettingsPanel("Worker profile") {
                profiles.filter { it.compatibleType == ClientType.LITERT_LM && it.enabled }.forEach { profile ->
                    FilterChip(policy.profileUid == profile.uid, { viewModel.updateAssist { it.copy(profileUid = profile.uid) } }, label = { Text(profile.name) })
                }
                TextButton(onClick = onMarketplace) { Text("Find a local helper") }
            }
        }
        item {
            SettingsPanel("What it helps with") {
                AssistSwitch("Select evidence", "Keeps exact complete records and source URLs. Originals remain available.", policy.evidenceSelection) { enabled -> viewModel.updateAssist { it.copy(evidenceSelection = enabled) } }
                AssistSwitch("Summarize retrieved text", "Creates a separate source-linked summary of already authorized text. Original evidence is never replaced.", policy.summarizeRetrievedText) { enabled -> viewModel.updateAssist { it.copy(summarizeRetrievedText = enabled) } }
                AssistSwitch("Suggest search queries", "Adds proposed queries to the evidence packet. The primary orchestrator decides whether to execute them with its existing permissions.", policy.proposeQueries) { enabled -> viewModel.updateAssist { it.copy(proposeQueries = enabled) } }
            }
        }
        item {
            SettingsPanel("Routing and privacy") {
                Text("Main AI writes the answer · warm model only · read-only evidence")
                SettingsHelpIcon("Local Assist processes tool results already authorized for the current request. It does not grant new memory, document, cloud-sharing or network permissions. Busy or unavailable helpers are skipped. Original source material is retained on failure.", title = "Local processing and cloud synthesis")
                Text("Processed locally; authorized derived results may be sent to your main AI.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced limits" else "Advanced limits") }
            if (advanced) {
                SettingsPanel("Task limits") {
                    var seconds by remember(policy.taskTimeoutSeconds) { mutableStateOf(policy.taskTimeoutSeconds.toFloat()) }
                    var attempts by remember(policy.maxAttempts) { mutableStateOf(policy.maxAttempts.toFloat()) }
                    Text("Timeout: ${seconds.toInt()} seconds")
                    Slider(seconds, { seconds = it }, valueRange = 2f..30f, steps = 27, onValueChangeFinished = { viewModel.updateAssist { it.copy(taskTimeoutSeconds = seconds.toInt()) } })
                    Text("Attempts per turn: ${attempts.toInt()}")
                    Slider(attempts, { attempts = it }, valueRange = 1f..8f, steps = 6, onValueChangeFinished = { viewModel.updateAssist { it.copy(maxAttempts = attempts.toInt()) } })
                    Text("One native helper job at a time. Up to ${policy.candidateRecords} candidates and ${policy.selectedRecords} selected records; 12,000-character transport boundary. These are character bounds, not token measurements.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Recent activity is recorded in the conversation’s Worked for details. Detailed diagnostics follow the existing app-wide logging preference.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AssistSwitch(title: String, help: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        SettingsHelpIcon(help, title = title)
        Switch(checked, update)
    }
}
