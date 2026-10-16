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
import dev.chungjungsoo.gptmobile.data.assist.AssistProcessingPermission
import dev.chungjungsoo.gptmobile.data.assist.processingPermission
import dev.chungjungsoo.gptmobile.data.assist.resolveAssistPolicy
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon

@Composable
internal fun LocalAssistScreen(viewModel: LocalRuntimeSettingsViewModel, onMarketplace: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val testStatus by viewModel.status.collectAsStateWithLifecycle()
    val readiness by dev.chungjungsoo.gptmobile.data.assist.AssistReadinessResolver.state.collectAsStateWithLifecycle()
    val resolution = settings.resolveAssistPolicy()
    val policy = resolution.requested.validated()
    val target = profiles.firstOrNull { it.uid == policy.profileUid }
    var advanced by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingsPanel(stringResource(R.string.local_assist_screen_1)) {
                LocalAssistToggle(settings.localAssist, { viewModel.updateTuning(assist = it) })
                resolution.explanations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Effective: ${resolution.effective.preparation.name.lowercase().replace('_', ' ')} · ${resolution.effective.maxAttempts} attempts · ${resolution.effective.taskTimeoutSeconds}s per task", style = MaterialTheme.typography.bodySmall)
                Text(
                    when {
                        !settings.localAssist -> "Off"
                        target == null && policy.profileUid != null -> stringResource(R.string.local_ai_assist_worker_missing)
                        target == null -> if (resolution.effective.preparation == dev.chungjungsoo.gptmobile.data.assist.AssistPreparation.WARM_ONLY) "Automatic selection · warm models only" else "Automatic selection · prepare an approved compatible model"
                        !target.enabled -> stringResource(R.string.local_ai_assist_worker_disabled)
                        readiness.profileUid != target.uid -> "Readiness has not been checked for this profile"
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
                AssistSwitch("Preload while app is open", "Prepares the explicitly selected approved helper while idle, including in Fast Response. Device protection still applies; remote messages never wait for loading.", policy.preloadInForeground) { enabled -> viewModel.updateAssist { it.copy(preloadInForeground = enabled) } }
                Text("Ordinary remote turns use an already prepared helper. Enable foreground preloading to prepare it while idle; cold loads do not hold up remote tools.", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.assist_preparation_help), style = MaterialTheme.typography.bodySmall)
                dev.chungjungsoo.gptmobile.data.assist.AssistPreparation.entries.forEach { mode ->
                    FilterChip(policy.preparation == mode, { viewModel.updateAssist { it.copy(preparation = mode) } }, label = { Text(stringResource(if (mode == dev.chungjungsoo.gptmobile.data.assist.AssistPreparation.WARM_ONLY) R.string.assist_warm_only else R.string.assist_auto_prepare)) })
                }
                if (settings.localAssist) Text(readiness.reason)
                Text("Runs one local request on synthetic records using the selected preparation policy. It does not call external tools.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::testLocalAssist, enabled = settings.localAssist && !busy) { Text(if (busy) "Testing…" else "Test Local Assist") }
                testStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            SettingsPanel(stringResource(R.string.local_assist_screen_5)) {
                AssistSwitch("Per-turn query planning", "Offers an optional local_assist_plan tool for the current request, including turns without retrieved evidence. Returns query proposals only; the primary decides whether an authorized search is needed.", policy.perTurnQueryPlanning) { enabled -> viewModel.updateAssist { it.copy(perTurnQueryPlanning = enabled) } }
                AssistSwitch("Research preparation", "Locally plans queries, extracts source-linked claims and reviews passages. Uses the same queue and attempt allowance. The selected primary can continue when assistance is unavailable; independent review requires a distinct approved model.", policy.researchPreparation) { enabled -> viewModel.updateAssist { it.copy(researchPreparation = enabled) } }
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
                Text("Permission to process task data is separate from memory learning. New helpers and previously excluded profiles require review.", style = MaterialTheme.typography.bodySmall)
                profiles.filter { it.compatibleType == ClientType.LITERT_LM }.forEach { profile ->
                    val permission = policy.processingPermission(profile)
                    AssistSwitch("Allow ${profile.name}", "This helper may receive the current task and authorized evidence locally. Current permission: ${permission.name.lowercase().replace('_', ' ')}.", permission == AssistProcessingPermission.ALLOWED) { allowed ->
                        viewModel.updateAssist { it.copy(permissionVersion = 1, profilePermissions = it.profilePermissions + (profile.uid to if (allowed) AssistProcessingPermission.ALLOWED else AssistProcessingPermission.DENIED)) }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced limits" else "Advanced limits") }
            if (advanced) {
                SettingsPanel(stringResource(R.string.local_assist_screen_13)) {
                    var seconds by remember(policy.taskTimeoutSeconds) { mutableStateOf(policy.taskTimeoutSeconds.toFloat()) }
                    var attempts by remember(policy.maxAttempts) { mutableStateOf(policy.maxAttempts.toFloat()) }
                    Text("Timeout: ${seconds.toInt()} seconds")
                    Slider(seconds, { seconds = it }, valueRange = 2f..90f, steps = 87, onValueChangeFinished = { viewModel.updateAssist { it.copy(taskTimeoutSeconds = seconds.toInt()) } })
                    Text("Attempts per turn: ${attempts.toInt()}")
                    Slider(attempts, { attempts = it }, valueRange = 1f..8f, steps = 6, onValueChangeFinished = { viewModel.updateAssist { it.copy(maxAttempts = attempts.toInt()) } })
                    var optionalWait by remember(policy.optionalWaitSeconds) { mutableStateOf(policy.optionalWaitSeconds.toFloat()) }
                    Text("Optional response wait: ${optionalWait.toInt()} seconds. The primary continues with original evidence when this expires; worker timeouts remain separate.")
                    Slider(optionalWait, { optionalWait = it }, valueRange = 1f..60f, onValueChangeFinished = { viewModel.updateAssist { it.copy(optionalWaitSeconds = optionalWait.toInt()) } })
                    var waitSeconds by remember(policy.queueWaitSeconds) { mutableStateOf(policy.queueWaitSeconds.toFloat()) }
                    Text("Queue wait: ${waitSeconds.toInt()} seconds")
                    Slider(waitSeconds, { waitSeconds = it }, valueRange = 1f..60f, onValueChangeFinished = { viewModel.updateAssist { it.copy(queueWaitSeconds = waitSeconds.toInt()) } })
                    Text("Preparation context: ${policy.contextTokens} tokens. A fixed-context export keeps its declared allocation.")
                    listOf(1024, 2048, 4096, 8192, 16384).forEach { tokens ->
                        FilterChip(policy.contextTokens == tokens, { viewModel.updateAssist { it.copy(contextTokens = tokens) } }, label = { Text("$tokens tokens") })
                    }
                    Text("One native helper job at a time. Tasks are split against the loaded context, output allowance and uncertainty reserve. Unprocessed evidence is recorded; original sources remain available.", style = MaterialTheme.typography.bodySmall)
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
