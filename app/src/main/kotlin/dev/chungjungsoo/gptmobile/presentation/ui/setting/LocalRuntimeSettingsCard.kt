package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.model.SpeculativeDecodingMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalRuntimeSettingsCard(viewModel: LocalRuntimeSettingsViewModel, section: String = "engine") {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backend by viewModel.backend.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val hardware by viewModel.hardware.collectAsStateWithLifecycle()
    val qnnAvailable by viewModel.qnnAvailable.collectAsStateWithLifecycle()
    val npuStatus by viewModel.npuStatus.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val comparison by viewModel.comparisonProgress.collectAsStateWithLifecycle()
    val comparisonError by viewModel.comparisonError.collectAsStateWithLifecycle()
    val trials by viewModel.runtimeTrials.collectAsStateWithLifecycle()
    val qualifications by viewModel.qualifications.collectAsStateWithLifecycle()
    var selectedProfile by remember { mutableStateOf<String?>(null) }
    var sustained by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var threads by remember(settings.localCpuThreads) { mutableStateOf(settings.localCpuThreads.toFloat()) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (section == "engine") {
                Text("Engine and processor", style = MaterialTheme.typography.titleLarge)
                Text("${viewModel.soc} · ${viewModel.cores} CPU cores · ${viewModel.ramGb} GB RAM", style = MaterialTheme.typography.bodyMedium)
                Text(active.engineSpec?.let { if (active.backend in setOf(LocalRuntimeBackend.GENIEX_QAIRT, LocalRuntimeBackend.GENIEX_LLAMA_CPP)) "${active.backend?.displayName} · Requested ${it.accelerator.uppercase()} · Effective processor not reported by SDK" else "Running: ${active.backend?.displayName} / ${it.accelerator.uppercase()} · ${it.maxTokens} context" } ?: "Engine idle", color = MaterialTheme.colorScheme.primary)
                active.fallbackReason?.let { Text("Fallback: $it", style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalRuntimeBackend.entries.filter { it == LocalRuntimeBackend.LITERT_LM || (it == LocalRuntimeBackend.QUALCOMM_QNN && qnnAvailable && !dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_ENABLED) }.forEach { option -> FilterChip(selected = backend == option, onClick = { viewModel.selectBackend(option) }, label = { Text(option.displayName) }, enabled = !busy) }
                }
                Text(npuStatus, style = MaterialTheme.typography.bodySmall)
                if (dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_ENABLED) Text("GenieX preview · Import a validated .localmodel bundle. Chat only; device qualification pending.", style = MaterialTheme.typography.bodySmall)
                Text("LiteRT-LM ${dev.chungjungsoo.gptmobile.BuildConfig.LITERT_LM_VERSION} · QAIRT ${dev.chungjungsoo.gptmobile.BuildConfig.QAIRT_VERSION}", style = MaterialTheme.typography.labelSmall)
                Text("CPU/GPU use compatible LiteRT-LM packages. NPU requires the exact SoC model build. Choose the accelerator and context in each AI profile.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Execution policy", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon("Profile preserves your explicit accelerator choice. Other policies select only from locally qualified evidence for the same artifact, firmware, runtime and workload. A quick measurement alone does not qualify a plan.")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionPolicy.entries.forEach { policy ->
                        FilterChip(settings.localExecutionPolicy == policy, onClick = { viewModel.updateTuning(policy = policy) }, label = { Text(policy.name.lowercase().replaceFirstChar { it.uppercase() }) }, enabled = !busy)
                    }
                }
            }
            if (section == "diagnostics") {
                Text(if (active.processIsolated) "Inference process: separate" else "Inference process: in-app fallback", style = MaterialTheme.typography.bodySmall)
                Text("State: ${active.lifecycle.lowercase()} · MTP requested: ${active.requestedMtp ?: "model default"} · MTP active: ${active.effectiveMtp ?: "not reported"}", style = MaterialTheme.typography.bodySmall)
                active.decision?.let { Text("${it.qualification.name.lowercase()} · ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Compare on this device", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon("Measures native prefill and decode separately, first visible answer, failures, and heat. Quick comparison is bounded to six minutes. Sustained mode adds three continuous 15-minute blocks per candidate, with a bounded 50-minute budget per candidate plus setup. Keep this screen open to start the foreground run. Native counts cover the last segment. Requested MTP is not proof of activation.")
                }
                val localProfiles = profiles.filter { it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    localProfiles.forEach { profile ->
                        FilterChip((selectedProfile ?: localProfiles.firstOrNull()?.uid) == profile.uid, onClick = { selectedProfile = profile.uid }, label = { Text(profile.name) }, enabled = comparison == null)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sustained comparison (about 50 minutes per candidate)", Modifier.weight(1f))
                    Switch(sustained, { sustained = it }, enabled = comparison == null)
                }
                if (comparison == null) {
                    TextButton(onClick = { (selectedProfile ?: localProfiles.firstOrNull()?.uid)?.let { viewModel.compareProfile(it, sustained) } }, enabled = !busy && localProfiles.isNotEmpty()) { Text("Start comparison") }
                } else {
                    Text("${comparison!!.label} · ${comparison!!.completed} completed", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = viewModel::stopComparison) { Text("Stop comparison") }
                }
                comparisonError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (trials.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Plan", Modifier.weight(1.3f), style = MaterialTheme.typography.labelSmall)
                        Text("Decode /s", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Text("Prefill /s", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Text("First ms", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    }
                    trials.filterNot { it.warmup }.take(8).forEach { trial ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${trial.key.backend.uppercase()} ${if (trial.key.speculativeDecoding == true) "MTP?" else ""}${if (!trial.finished) {
                                    " running"
                                } else if (!trial.completed) {
                                    " failed"
                                } else {
                                    ""
                                }}",
                                Modifier.weight(1.3f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(trial.nativeMetrics?.decodeTokensPerSecond?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(trial.nativeMetrics?.prefillTokensPerSecond?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(trial.firstVisibleAnswerMs?.toString() ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("${trials.count { it.finished && !it.completed }} failed · ${trials.count { it.historical }} historical · ${qualifications.count { it.status == dev.chungjungsoo.gptmobile.data.localruntime.QualificationStatus.QUALIFIED }} qualified plans", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::clearRuntimeEvidence, enabled = comparison == null && !busy) { Text("Clear runtime evidence") }
                }
            }
            if (section == "performance") {
                Text("GPU/NPU experiments", style = MaterialTheme.typography.titleMedium)
                dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionMode.entries.filter { it != dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionMode.SINGLE }.forEach { mode ->
                    Text("${mode.name.lowercase().replace('_', ' ')}: ${dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionExperiments.unavailableReason(mode)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("CPU threads: ${if (threads.toInt() == 0) "Automatic" else threads.toInt().toString()}")
                Slider(value = threads.coerceIn(0f, viewModel.cores.toFloat()), onValueChange = { threads = it }, onValueChangeFinished = { viewModel.updateTuning(threads = threads.toInt()) }, valueRange = 0f..viewModel.cores.toFloat(), steps = (viewModel.cores - 1).coerceAtLeast(0), enabled = !busy, modifier = Modifier.semantics { contentDescription = "CPU threads; zero uses runtime defaults" })
                Text("Automatic uses the runtime default. More threads can increase heat without improving token speed.", style = MaterialTheme.typography.bodySmall)
                Text("Speculative decoding", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeculativeDecodingMode.entries.forEach { option ->
                        FilterChip(
                            selected = settings.localSpeculativeDecoding == option,
                            onClick = { viewModel.updateTuning(speculative = option) },
                            label = { Text(option.label) },
                            enabled = !busy
                        )
                    }
                }
                Text("Model default follows the package's settings. On enables speculative decoding only for packages that declare draft or MTP support; other packages use ordinary decoding. Compare speed and answer quality in Benchmarks.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Native performance counters", Modifier.weight(1f))
                    Switch(settings.localNativeMetrics, { viewModel.updateTuning(nativeMetrics = it) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Native performance counters" })
                }
                Text("Measure prefill and decode separately on this device. Counters cover the last inference segment and may add measurement overhead.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cache compiled models", Modifier.weight(1f))
                    Switch(settings.localModelCache, { viewModel.updateTuning(cache = it) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Cache compiled models" })
                }
                if (qnnAvailable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Automatic QNN fallback", Modifier.weight(1f))
                        Switch(settings.qnnAutomaticFallback, { viewModel.updateTuning(fallback = it) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Automatic QNN fallback" })
                    }
                }
            }
            if (section == "memory") {
                Text("Memory reserve: ${settings.localMemoryReserveMb} MB")
                var reserve by remember(settings.localMemoryReserveMb) { mutableStateOf(settings.localMemoryReserveMb.toFloat()) }
                Slider(reserve, { reserve = it }, valueRange = 256f..4096f, onValueChangeFinished = { viewModel.setMemoryReserve(reserve.toInt()) })
                Text("Release model after idle", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 10, 30, 0).forEach { minutes -> FilterChip(settings.localIdleMinutes == minutes, onClick = { viewModel.updateTuning(idle = minutes) }, label = { Text(if (minutes == 0) "Keep warm" else "$minutes min") }, enabled = !busy) }
                }
                Text("Thermal: ${hardware.thermalState.name.lowercase()} · Battery: ${hardware.batteryPct}%${if (hardware.isPowerSaveMode) " · Power saver" else ""}", style = MaterialTheme.typography.bodySmall)
                hardware.thermalHeadroom?.let { headroom ->
                    Text("10-second thermal forecast: ${(headroom * 100).toInt()}% of the severe-throttling threshold", style = MaterialTheme.typography.bodySmall)
                }
                Text("New work pauses under severe heat or insufficient memory. Optional helpers wait for stable headroom; your sampling and context settings stay explicit.", style = MaterialTheme.typography.bodySmall)
                FlowRow {
                    TextButton(onClick = viewModel::refresh) { Text("Refresh hardware") }
                    TextButton(onClick = { viewModel.releaseIdleMemory() }, enabled = !busy) { Text("Release idle memory") }
                    TextButton(onClick = { viewModel.updateTuning(idle = 10) }, enabled = !busy) { Text("Reset memory timeout") }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
