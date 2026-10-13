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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon

@Composable
internal fun LocalAssistScreen(viewModel: LocalRuntimeSettingsViewModel, onMarketplace: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val readiness by dev.chungjungsoo.gptmobile.data.assist.AssistReadinessResolver.state.collectAsStateWithLifecycle()
    val policy = settings.localAssistPolicy.validated()
    val target = profiles.firstOrNull { it.uid == policy.profileUid }
    var advanced by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingsPanel(stringResource(R.string.local_assist_screen_1)) {
                LocalAssistToggle(settings.localAssist, { viewModel.updateTuning(assist = it) })
                Text(
                    when {
                        !settings.localAssist -> "Off"
                        target == null && policy.profileUid != null -> stringResource(R.string.local_ai_assist_worker_missing)
                        target == null -> "Automatic · eligible warm model only"
                        !target.enabled -> stringResource(R.string.local_ai_assist_worker_disabled)
                        active.engineSpec?.modelPath?.contains("/models/${target.model}/") != true -> stringResource(R.string.local_ai_assist_worker_waiting)
                        active.engineSpec == null -> "Waiting for a warm model"
                        else -> readiness.reason
                    },
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        item {
            SettingsPanel(stringResource(R.string.local_assist_screen_2)) {
                FilterChip(policy.profileUid == null, { viewModel.updateAssist { it.copy(profileUid = null) } }, label = { Text(stringResource(R.string.local_assist_screen_3)) })
                profiles.filter { it.compatibleType == ClientType.LITERT_LM && it.enabled }.forEach { profile ->
                    FilterChip(policy.profileUid == profile.uid, { viewModel.updateAssist { it.copy(profileUid = profile.uid) } }, label = { Text(profile.name) })
                }
                TextButton(onClick = onMarketplace) { Text(stringResource(R.string.local_assist_screen_4)) }
            }
        }
        item {
            SettingsPanel(stringResource(R.string.assist_preparation)) {
                Text(stringResource(R.string.assist_preparation_help), style = MaterialTheme.typography.bodySmall)
                dev.chungjungsoo.gptmobile.data.assist.AssistPreparation.entries.forEach { mode ->
                    FilterChip(policy.preparation == mode, { viewModel.updateAssist { it.copy(preparation = mode) } }, label = { Text(stringResource(if (mode == dev.chungjungsoo.gptmobile.data.assist.AssistPreparation.WARM_ONLY) R.string.assist_warm_only else R.string.assist_auto_prepare)) })
                }
                if (settings.localAssist) Text(readiness.reason)
            }
        }
        item {
            SettingsPanel(stringResource(R.string.local_assist_screen_5)) {
                AssistSwitch(stringResource(R.string.local_assist_screen_6), "Keeps exact complete records and source URLs. Originals remain available.", policy.evidenceSelection) { enabled -> viewModel.updateAssist { it.copy(evidenceSelection = enabled) } }
                AssistSwitch(stringResource(R.string.local_assist_screen_7), "Creates a separate source-linked summary of already authorized text. Original evidence is never replaced.", policy.summarizeRetrievedText) { enabled -> viewModel.updateAssist { it.copy(summarizeRetrievedText = enabled) } }
                AssistSwitch(stringResource(R.string.local_assist_screen_8), "Adds proposed queries to the evidence packet. The primary orchestrator decides whether to execute them with its existing permissions.", policy.proposeQueries) { enabled -> viewModel.updateAssist { it.copy(proposeQueries = enabled) } }
            }
        }
        item {
            SettingsPanel(stringResource(R.string.local_assist_screen_9)) {
                Text(stringResource(R.string.local_assist_screen_10))
                SettingsHelpIcon(stringResource(R.string.local_assist_screen_11), title = "Local processing and cloud synthesis")
                Text(stringResource(R.string.local_assist_screen_12), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced limits" else "Advanced limits") }
            if (advanced) {
                SettingsPanel(stringResource(R.string.local_assist_screen_13)) {
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
        item { Text(stringResource(R.string.local_assist_screen_14), style = MaterialTheme.typography.bodySmall) }
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
