package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.orchestration.OptimizationMode
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExecutionEfficiencyScreen(onClose: () -> Unit, viewModel: ExecutionEfficiencyViewModel = hiltViewModel(), runtime: LocalRuntimeSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val active by runtime.active.collectAsStateWithLifecycle()
    val readiness by dev.chungjungsoo.gptmobile.data.assist.AssistReadinessResolver.state.collectAsStateWithLifecycle()
    val policy = settings.executionPolicy
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var assist by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var destination by rememberSaveable { mutableStateOf("All") }
    var outcome by rememberSaveable { mutableStateOf("All") }
    var profile by rememberSaveable { mutableStateOf("") }
    val now = System.currentTimeMillis()
    val latestTurn = records.maxByOrNull { it.startedAt }?.turnKey
    val filtered = records.filter {
        (days == 0 || days == -1 && it.turnKey == latestTurn || days > 0 && now - it.startedAt < days * 86_400_000L) &&
            (destination == "All" || (it.provider == "LITERT_LM") == (destination == "Local")) &&
            (outcome == "All" || it.status == outcome) &&
            (profile.isBlank() || "${it.profileUid} ${it.provider} ${it.model} ${it.kind}".contains(profile, true))
    }
    FadingDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize().testTag("execution-efficiency"), topBar = {
            TopAppBar(title = { Text("Execution & efficiency") }, navigationIcon = { TextButton(onClick = onClose) { Text("Back") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                SettingsTabs(listOf("Overview", "Policy", "Statistics", "Diagnostics"), tab) { tab = it }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    if (tab == 0) {
                        item {
                            SettingsPanel("Active policy") {
                                Text(policy.mode.label, style = MaterialTheme.typography.titleLarge)
                                Text(if (settings.localAssist) readiness.reason else "Local Assist is off")
                                Text("Runtime: ${active.backend?.displayName ?: "Idle"} · actual processor: ${active.engineSpec?.accelerator ?: "Not reported"}")
                                TextButton(onClick = { assist = true }) { Text("Configure Local Assist") }
                                TextButton(onClick = runtime::refresh) { Text("Check compatibility metadata") }
                                Text("Metadata checks do not run inference or establish hardware qualification.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            SettingsPanel("Recent work") {
                                val latest = records.firstOrNull()
                                Text(latest?.let { "${it.kind} · ${it.status}" } ?: "No retained model records")
                                Text("Savings not measured for this turn. A paired baseline is required.")
                                Text("Helper completion and final delivery are recorded separately.")
                            }
                        }
                    }
                    if (tab == 1) {
                        item {
                            SettingsPanel("Optimization") {
                                OptimizationMode.entries.forEach { mode -> FilterChip(policy.mode == mode, { viewModel.update { it.copy(mode = mode) } }, label = { Text(mode.label) }) }
                                Text("Preferences apply at the next dispatch. Permissions, selected primary/Combined profiles, requested answer length and hardware protection remain authoritative.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            SettingsPanel("Local assistance and context") {
                                ExecutionSwitch("Warm Local Assist", policy.warmLocalAssist) { value -> viewModel.update { it.copy(warmLocalAssist = value) } }
                                ExecutionSwitch("Exact evidence handoff", policy.evidenceHandoff) { value -> viewModel.update { it.copy(evidenceHandoff = value) } }
                                ExecutionSwitch("Extractive compression", policy.assistCompression) { value -> viewModel.update { it.copy(assistCompression = value) } }
                                ExecutionSwitch("Allow selected helper preparation", policy.coldLoadAssist) { value -> viewModel.update { it.copy(coldLoadAssist = value) } }
                                Text("Cold preparation also requires Maximum savings or Custom and the Local Assist preparation setting. Balanced stays warm-only.")
                                TextButton(onClick = { assist = true }) { Text("Local Assist tasks and profile") }
                            }
                        }
                        item {
                            SettingsPanel("Shared budgets") {
                                ExecutionSwitch("Retain unified usage accounting", policy.unifiedRunAccounting) { value -> viewModel.update { it.copy(unifiedRunAccounting = value) } }
                                ExecutionSwitch("Enforce shared model budgets", policy.sharedRunBudget) { value -> viewModel.update { it.copy(sharedRunBudget = value) } }
                                ExecutionLimit("Remote input tokens", policy.remoteInputTokens) { value -> viewModel.update { it.copy(remoteInputTokens = value) } }
                                ExecutionLimit("Remote output tokens", policy.remoteOutputTokens) { value -> viewModel.update { it.copy(remoteOutputTokens = value) } }
                                ExecutionLimit("Local generated tokens", policy.localGeneratedTokens) { value -> viewModel.update { it.copy(localGeneratedTokens = value) } }
                                ExecutionLimit("Physical model requests", policy.physicalModelRequests?.toLong()) { value -> viewModel.update { it.copy(physicalModelRequests = value?.coerceAtMost(1024)?.toInt()) } }
                                ExecutionLimit("Active model milliseconds", policy.modelActiveMillis) { value -> viewModel.update { it.copy(modelActiveMillis = value) } }
                                ExecutionLimit("Final writer output reserve", policy.finalOutputReserve.toLong()) { value -> viewModel.update { it.copy(finalOutputReserve = value?.coerceIn(1, 131072)?.toInt() ?: 4096) } }
                                ExecutionLimit("Concurrent models", policy.concurrentModels.toLong()) { value -> viewModel.update { it.copy(concurrentModels = value?.coerceIn(1, 8)?.toInt() ?: 4) } }
                                Text("${policy.finalOutputReserve} output tokens reserved for final writing; ${policy.concurrentModels} concurrent models. Existing tool, context and spending limits also apply. Unknown receipts retain a conservative reservation.")
                            }
                        }
                        item { LocalRuntimeSettingsCard(runtime, "engine") }
                        item { LocalRuntimeSettingsCard(runtime, "performance") }
                        item { LocalRuntimeSettingsCard(runtime, "memory") }
                        item { Text("Fallback and privacy: existing approved destinations only. This policy never authorizes cloud recall, new tools or a different writer.") }
                    }
                    if (tab >= 2) {
                        item {
                            SettingsPanel("Filter retained work") {
                                listOf(-1 to "This turn", 1 to "Today / last 24 hours", 7 to "7 days", 30 to "30 days", 0 to "Stored history").forEach { (value, label) -> FilterChip(days == value, { days = value }, label = { Text(label) }) }
                                listOf("All", "Local", "Remote").forEach { value -> FilterChip(destination == value, { destination = value }, label = { Text(value) }) }
                                listOf("All", "COMPLETED", "PARTIAL", "FAILED", "CANCELED", "INTERRUPTED").forEach { value -> FilterChip(outcome == value, { outcome = value }, label = { Text(value.lowercase().replaceFirstChar { it.titlecase() }) }) }
                                OutlinedTextField(profile, { profile = it }, label = { Text("Profile, provider, model or workload") }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        item {
                            SettingsPanel("Usage and coverage") {
                                val remote = filtered.filter { it.provider != "LITERT_LM" }
                                val local = filtered.filter { it.provider == "LITERT_LM" }
                                Text("${filtered.size} physical requests in the retained window (up to 10,000)")
                                Text("Remote: ${remote.filter { it.inputTokenSource == "provider_reported" }.sumOf { it.inputTokens.toLong() }} reported input · ${remote.filter { it.outputTokenSource == "provider_reported" }.sumOf { it.outputTokens.toLong() }} reported output")
                                Text("Local: ${local.filter { it.outputTokenSource == "runtime_reported" }.sumOf { it.outputTokens.toLong() }} native output tokens")
                                Text("${filtered.count { it.estimated }} requests have estimates or incomplete receipts")
                                Text("${filtered.count { it.consumedAssistInvocationIds != null }} primary requests received validated Assist handoffs")
                                Text("Cost: ${if (filtered.isEmpty() || filtered.any { it.costMicros == null || it.estimated } || filtered.map { it.currency }.distinct().size != 1) "Unavailable: missing receipts or prices" else "${filtered.sumOf { it.costMicros ?: 0 } / 1_000_000.0} ${filtered.first().currency}"}")
                                Text("Measured A/B savings: unavailable. Local tokens are not subtracted from remote tokens.")
                            }
                        }
                        if (tab == 3) {
                            items(filtered.take(100), key = { it.id }) { record ->
                                SettingsPanel("${record.kind} · ${record.status}") {
                                    Text("${record.provider} / ${record.model}")
                                    Text("${record.inputTokens} in (${record.inputTokenSource}) · ${record.outputTokens} out (${record.outputTokenSource})")
                                    Text("${record.durationMs} ms · first token ${record.firstTokenMs?.let { "$it ms" } ?: "Unavailable"} · runtime ${record.actualRuntime ?: "Unreported"}")
                                    Text("Finish: ${record.finishReason ?: "Unreported"} · invocation ${record.id}")
                                    record.consumedAssistInvocationIds?.let { Text("Consumed Assist producers: $it") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (assist) {
        FadingDialog(onDismissRequest = { assist = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(Modifier.fillMaxSize(), topBar = { TopAppBar(title = { Text("Local Assist") }, navigationIcon = { TextButton(onClick = { assist = false }) { Text("Back") } }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) { LocalAssistScreen(runtime, onMarketplace = { assist = false }) }
            }
        }
    }
}

@Composable
private fun ExecutionSwitch(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, update)
    }
}

@Composable
private fun ExecutionLimit(label: String, value: Long?, update: (Long?) -> Unit) {
    var draft by rememberSaveable(label, value) { mutableStateOf(value?.toString().orEmpty()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(draft, { draft = it.filter(Char::isDigit).take(12) }, label = { Text(label) }, placeholder = { Text("No additional limit") }, modifier = Modifier.weight(1f), singleLine = true)
        TextButton(onClick = { update(draft.toLongOrNull()?.takeIf { it > 0 }) }) { Text("Apply") }
    }
}
