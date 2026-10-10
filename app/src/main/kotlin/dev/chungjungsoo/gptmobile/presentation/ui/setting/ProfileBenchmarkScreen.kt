package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRating
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkRating
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite
import dev.chungjungsoo.gptmobile.data.benchmark.comparableRuns
import dev.chungjungsoo.gptmobile.data.benchmark.delegateRankings
import dev.chungjungsoo.gptmobile.data.benchmark.delegationBenchmarkRating
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet as ModalBottomSheet
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBenchmarkScreen(onBack: () -> Unit, onUsage: () -> Unit, viewModel: ProfileBenchmarkViewModel = hiltViewModel(), embedded: Boolean = false) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val profile by viewModel.selected.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val localEnvironment by viewModel.localEnvironment.collectAsStateWithLifecycle()
    val delegationSettings by viewModel.delegationSettings.collectAsStateWithLifecycle()
    val snapshot by viewModel.scoreSnapshot.collectAsStateWithLifecycle()
    val health by dev.chungjungsoo.gptmobile.data.agent.LocalToolHealth.state.collectAsStateWithLifecycle()
    val delegates by viewModel.delegates.collectAsStateWithLifecycle()
    val delegate by viewModel.delegate.collectAsStateWithLifecycle()
    val selectedDelegates by viewModel.selectedDelegates.collectAsStateWithLifecycle()
    val benchmarkCandidates by viewModel.benchmarkCandidates.collectAsStateWithLifecycle()
    val selectedBenchmarks by viewModel.selectedBenchmarks.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val activeRequests by viewModel.activeRequests.collectAsStateWithLifecycle()
    val everyday by viewModel.everyday.collectAsStateWithLifecycle()
    val everydayTools by viewModel.everydayTools.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val rangeLabel = if (days == 0) "stored history" else "$days days"
    var tab by rememberSaveable { mutableIntStateOf(2) }
    var mode by rememberSaveable { mutableStateOf(BenchmarkMode.QUICK) }
    var typeFilter by rememberSaveable { mutableIntStateOf(0) }
    var performanceOrder by rememberSaveable { mutableStateOf(PerformanceOrder.LATENCY) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var benchmarkOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    val selected = profile
    val local = selected?.compatibleType == ClientType.LITERT_LM
    val tint = benchmarkTint(local)
    val profileHistory = remember(history, selected?.uid) { history.filter { it.profileUid == selected?.uid } }
    val matching = remember(profileHistory, selected, mode, localEnvironment) { selected?.let { comparableRuns(profileHistory, it, mode, localEnvironment) }.orEmpty() }
    val activeScore = snapshot?.rows?.firstOrNull { it.profileUid == selected?.uid && it.revision == selected?.let { profile -> dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(profile, localEnvironment) } && it.cohort.startsWith(mode.name + "|") }
    val rating = remember(matching, local, activeScore) {
        benchmarkRating(matching, local).copy(score = activeScore?.overall?.roundToInt(), medianSpeed = activeScore?.speed, estimatedSpeed = false)
    }
    val detail = everyday.firstOrNull { it.key == detailKey }
    Scaffold(topBar = {
        if (!embedded) {
            TopAppBar(title = { Text("AI profile benchmarks") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }, actions = {
                IconButton(onClick = onUsage) { Icon(Icons.Rounded.BarChart, "Usage") }
            })
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SettingsHero("Performance lab", "Measure. Compare. Improve.", "${history.size} recorded tests · ${profiles.size} profiles") }
            item { SettingsTabs(listOf("Results", "Run", "Delegation", "History"), listOf(2, 0, 4, 3).indexOf(tab).coerceAtLeast(0)) { tab = listOf(2, 0, 4, 3)[it] } }
            item { BenchmarkProfilePicker(profiles, selected, progress == null, viewModel::select) }
            if (error != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
                        }
                    }
                }
            }
            if (selected == null) {
                item { BenchmarkPanel("No AI profiles yet") { Text("Create an AI profile in Settings, choose its model, then return here to measure it.") } }
            } else {
                progress?.let { current ->
                    item {
                        BenchmarkPanel("Running · ${current.profileName}") {
                            LinearProgressIndicator(progress = { current.completed.toFloat() / current.total }, modifier = Modifier.fillMaxWidth(), color = tint)
                            Text("${current.completed} / ${current.total} tests · ${current.testName}")
                            TextButton(onClick = viewModel::cancel) {
                                Icon(Icons.Rounded.Stop, null)
                                Text("Stop benchmark")
                            }
                        }
                    }
                }
                if (tab == 1 || tab == 2) {
                    item {
                        Column {
                            Text("Everyday measurement window", style = MaterialTheme.typography.labelMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(7 to "7 days", 30 to "30 days", 0 to "Stored history").forEach { (value, label) ->
                                    FilterChip(days == value, { viewModel.selectRange(value) }, label = { Text(label) })
                                }
                            }
                        }
                    }
                }
                when (tab) {
                    4 -> {
                        item {
                            BenchmarkPanel("Delegation pipeline") {
                                Text("Primary: ${selected.name}")
                                Text("Delegates to test", style = MaterialTheme.typography.labelLarge)
                                Text("Select one or more helpers. Each model is benchmarked sequentially with the same primary and settings.", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = viewModel::selectAllDelegates, enabled = progress == null) { Text("Select all") }
                                    TextButton(onClick = viewModel::clearDelegates, enabled = progress == null) { Text("Clear") }
                                }
                                delegates.forEach { helper ->
                                    val checked = selectedDelegates.any { it.uid == helper.uid }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Checkbox(checked = checked, onCheckedChange = { viewModel.toggleDelegate(helper) }, enabled = progress == null)
                                        Column(Modifier.weight(1f)) {
                                            Text(helper.name, fontWeight = FontWeight.SemiBold)
                                            Text("${helper.compatibleType.name} · ${helper.model}", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                Text("${selectedDelegates.size} selected · no automatic fallback between benchmarked helpers.", style = MaterialTheme.typography.bodySmall)
                                Text("Ownership ${delegationSettings.processingOwnership}/100 · Worker output ${delegationSettings.maxOutputTokens} tokens · Brief ${delegationSettings.handoffTokens} tokens")
                                val reviewerProfile = profiles.firstOrNull { it.uid == delegationSettings.reviewerProfileUid }
                                Text(
                                    if (delegationSettings.reviewerEnabled) {
                                        "Reviewer: ${reviewerProfile?.name ?: "not selected"} · ${reviewerProfile?.model.orEmpty()} · ${delegationSettings.reviewerOutputTokens} output tokens"
                                    } else {
                                        "Reviewer: Off"
                                    }
                                )
                                Text("${delegationSettings.effectiveLocalModelCalls()} worker calls per turn · ${delegationSettings.maxDelegateRuntimeSeconds}s runtime · ${delegationSettings.timeToFirstTokenTimeoutSeconds}s first progress · ${delegationSettings.idleTokenTimeoutSeconds}s idle · ${delegationSettings.localRetryLimit} same-delegate retries")
                                Text("Each helper runs evidence compaction, a tool-call usability test, and research → handoff → synthesis. The benchmark records token throughput, first-text latency, end-to-end latency, tool success, token usage, and diagnostic events. Each case has a 180-second ceiling.", style = MaterialTheme.typography.bodySmall)
                                Button(
                                    onClick = { viewModel.start(BenchmarkMode.DELEGATION) },
                                    enabled = ready && progress == null && !activeRequests && delegationSettings.enabled && selectedDelegates.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Benchmark ${selectedDelegates.size} selected delegate${if (selectedDelegates.size == 1) "" else "s"}") }
                                if (!delegationSettings.enabled) Text("Enable delegation in Settings first.")
                            }
                        }
                        val rankings = delegateRankings(profileHistory, benchmarkConfigKey(selected, localEnvironment), delegationSettings)
                        item {
                            BenchmarkPanel("Delegation scoreboard · best score") {
                                Text("Same primary and current delegation settings · latest five runs per helper. Delegation score measures reliability, tool usability, throughput, latency, evidence accuracy, research/handoff, and—when Reviewer mode is enabled—the independent Reviewer Score. Reviewer quality contributes a 20-weight dimension and the overall score remains capped by task reliability.", style = MaterialTheme.typography.bodySmall)
                                Text("Throughput prefers provider-reported output tokens; character estimates are used only when token usage is unavailable. Diagnostic events are saved with each run to expose stalls, failures, tool activity, cap violations, and handoff behavior.", style = MaterialTheme.typography.bodySmall)
                                if (rankings.isEmpty()) Text("Benchmark delegates to build the scoreboard.")
                                rankings.take(10).forEachIndexed { index, row ->
                                    val worker = row.run.samples.mapNotNull { it.delegation }.first()
                                    HorizontalDivider()
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("#${index + 1}", fontWeight = FontWeight.Bold)
                                        Column(Modifier.weight(1f)) {
                                            Text(worker.workerName, fontWeight = FontWeight.SemiBold)
                                            Text("${row.rating.passed}/${row.rating.attempts} passed · ${row.runs} runs · ${row.rating.medianDecodeSpeed?.let { "%.1f tok/s".format(it) } ?: "speed —"} · tools ${percent(row.rating.toolTaskSuccessPercent)} · first ${formatLatency(row.rating.medianFirstTextMs)}", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(row.rating.score?.let { "$it / 100" } ?: "Unrated", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            Text("Reviewer Score", style = MaterialTheme.typography.labelSmall)
                                            Text(row.rating.reviewerScore?.let { "$it / 100" } ?: "—", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                        items(rankings) { row ->
                            val worker = row.run.samples.mapNotNull { it.delegation }.first()
                            val result = row.rating
                            BenchmarkPanel("${rankings.indexOf(row) + 1}. ${worker.workerName} · ${result.score?.let { "$it / 100" } ?: "Not rated"}") {
                                Text("${worker.workerProvider} · ${worker.workerModel}", style = MaterialTheme.typography.bodySmall)
                                MetricLine("Successful tasks", "${result.passed} / ${result.attempts} · ${row.runs} runs")
                                MetricLine("Tool task success", percent(result.toolTaskSuccessPercent))
                                MetricLine("Tool call success", percent(result.toolCallSuccessPercent))
                                MetricLine("Valid fixture calls", "${result.successfulToolCalls} / ${result.toolCalls}")
                                MetricLine("Median / p95 case latency", "${formatLatency(result.medianLatencyMs)} / ${formatLatency(result.p95LatencyMs)}")
                                MetricLine("Worker first text", formatLatency(result.medianFirstTextMs))
                                MetricLine("Median token throughput", result.medianDecodeSpeed?.let { "%.1f tok/s".format(it) } ?: "Not observed")
                                MetricLine("Worker input / output tokens", "${result.workerInputTokens} / ${result.workerOutputTokens}")
                                MetricLine("Primary input / output tokens", "${result.primaryInputTokens} / ${result.primaryOutputTokens}")
                                MetricLine("Output cap violations", result.outputCapViolations.toString())
                                MetricLine("Reviewer Score", result.reviewerScore?.let { "$it / 100 · ${result.reviewerEvaluations} evaluations" } ?: "Not measured")
                                if (result.reviewerCalls > 0) {
                                    MetricLine(
                                        "Reviewer calls / input / output tokens",
                                        "${result.reviewerCalls} / ${result.reviewerInputTokens} / ${result.reviewerOutputTokens}${if (result.reviewerEstimated) " (estimated where provider usage was unavailable)" else ""}"
                                    )
                                }
                                MetricLine("Diagnostic events", "${result.diagnosticEvents} total · ${result.warningEvents} warnings · ${result.errorEvents} errors")
                                if (result.estimated) Text("Token totals include estimates.", style = MaterialTheme.typography.labelSmall)
                                result.dimensions.forEach { dimension ->
                                    MetricLine(dimension.label, dimension.score?.let { "${it.roundToInt()} / 100" } ?: "Not measured")
                                }
                                Text("Measured ${benchmarkDate(row.run.startedAt)} · ${row.run.delegationSettings?.maxOutputTokens} worker output cap", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val latest = profileHistory.firstOrNull { it.mode == BenchmarkMode.DELEGATION }
                        if (latest != null) {
                            item {
                                BenchmarkPanel("Latest delegation run · ${benchmarkDate(latest.startedAt)}") {
                                    latest.delegationSettings?.let { saved ->
                                        Text("Tested settings: ownership ${saved.processingOwnership}, worker cap ${saved.maxOutputTokens}, brief ${saved.handoffTokens}, calls ${saved.effectiveLocalModelCalls()}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    latest.samples.forEach { sample ->
                                        HorizontalDivider()
                                        Text("${sample.label} · ${sample.outcome} · ${sample.durationMs} ms", fontWeight = FontWeight.SemiBold)
                                        sample.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        sample.delegation?.let { metrics ->
                                            Text("${metrics.workerName} (${metrics.workerProvider}) · ${metrics.workerCalls} worker calls")
                                            Text("Worker tokens: ${metrics.workerInputTokens} input / ${metrics.workerOutputTokens} output${if (metrics.workerEstimated) " (estimated)" else ""}")
                                            MetricLine("Worker time / first text", "${formatLatency(metrics.workerDurationMs)} / ${formatLatency(metrics.workerFirstTextMs)}")
                                            MetricLine("Worker token throughput", metrics.workerDecodeTokensPerSecond?.let { "${if (metrics.workerSpeedUsesReportedTokens) "" else "≈ "}%.1f tok/s".format(it) } ?: "Not observed")
                                            MetricLine("Output cap violations", metrics.outputCapViolations.toString())
                                            MetricLine("Reviewer Score", metrics.reviewerScore?.let { "$it / 100 · ${metrics.reviewerEvaluations} evaluations" } ?: "Not measured")
                                            if (metrics.reviewerCalls > 0) {
                                                MetricLine(
                                                    "Reviewer usage",
                                                    "${metrics.reviewerCalls} calls · ${metrics.reviewerInputTokens} input / ${metrics.reviewerOutputTokens} output${if (metrics.reviewerEstimated) " (estimated)" else ""}"
                                                )
                                            }
                                            Text("Primary tokens: ${metrics.primaryInputTokens} input / ${metrics.primaryOutputTokens} output${if (metrics.primaryEstimated) " (output estimated)" else ""}")
                                            Text("${metrics.searches} searches · ${metrics.pagesRead} pages · ${metrics.rawEvidenceBytes} evidence bytes → ${metrics.handoffCharacters} brief characters")
                                            if (metrics.fixtureCalls > 0) Text("Fixture calls: ${metrics.successfulFixtureCalls}/${metrics.fixtureCalls} successful")
                                            if (metrics.diagnosticEvents.isNotEmpty()) {
                                                Text("Diagnostic events", fontWeight = FontWeight.SemiBold)
                                                metrics.diagnosticEvents.takeLast(12).forEach { event ->
                                                    Text("[+${event.elapsedMs} ms] ${event.level} · ${event.type} · ${event.message}", style = MaterialTheme.typography.labelSmall, color = if (event.level == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    0 -> {
                        item {
                            BenchmarkPanel("Local Tool Health") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(health.lastStatus, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    SettingsHelpIcon("App-side argument, budget and dispatch outcomes are distinct from model or provider failures. Omitted tools are unavailable in this request. This panel excludes arguments, precise locations and credentials.")
                                }
                                Text("Context ${health.contextTokens} · evidence ${health.evidenceBytes} bytes/result", style = MaterialTheme.typography.bodySmall)
                                Text("Selected: ${health.selected.joinToString().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                                if (health.omitted.isNotEmpty()) Text("Omitted for context: ${health.omitted.joinToString()}", style = MaterialTheme.typography.bodySmall)
                                Text("Payload ${health.retainedBytes} → admitted ${health.admittedBytes} bytes · supporting observations=${health.supportingEvidence}", style = MaterialTheme.typography.bodySmall)
                                Text("Last: ${health.lastTool} · ${health.lastError ?: "no structured error"} · dispatched=${health.dispatched} · compacted=${health.compacted}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            BenchmarkPanel("Run a benchmark") {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BenchmarkMode.entries.filter { it != BenchmarkMode.DELEGATION }.forEach { option -> FilterChip(mode == option, { mode = option }, enabled = progress == null, label = { Text(option.label) }) }
                                }
                                Text(
                                    if (mode == BenchmarkMode.QUICK) {
                                        "6 diagnostic trials · provisional only"
                                    } else if (mode == BenchmarkMode.AGENT) {
                                        "48 trials · 3 warm-ups · text quality and 12 tool contracts"
                                    } else {
                                        "36 trials · 3 warm-ups · 18 quality cases"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text("Up to 512 output tokens per request and 90 seconds per test. Tools use a harmless in-memory fixture. Reasoning follows each model profile instead of being forcibly disabled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${selectedBenchmarks.size} of ${benchmarkCandidates.size} models selected", style = MaterialTheme.typography.labelLarge)
                                    Box {
                                        IconButton(onClick = { benchmarkOptionsExpanded = true }, enabled = progress == null) {
                                            Icon(Icons.Rounded.Tune, "Choose benchmark models", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        DropdownMenu(
                                            expanded = benchmarkOptionsExpanded,
                                            onDismissRequest = { benchmarkOptionsExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Select all models") },
                                                onClick = { viewModel.selectAllBenchmarks() }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Clear selection") },
                                                onClick = { viewModel.clearBenchmarks() }
                                            )
                                            benchmarkCandidates.forEach { candidate ->
                                                val checked = selectedBenchmarks.any { it.uid == candidate.uid }
                                                DropdownMenuItem(
                                                    text = {
                                                        Column {
                                                            Text(candidate.name)
                                                            Text(candidate.model, style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    },
                                                    leadingIcon = {
                                                        Checkbox(
                                                            checked = checked,
                                                            onCheckedChange = null
                                                        )
                                                    },
                                                    onClick = { viewModel.toggleBenchmarkProfile(candidate) }
                                                )
                                            }
                                        }
                                    }
                                }
                                Button(
                                    onClick = { viewModel.startStandardBatch(mode) },
                                    enabled = ready && progress == null && !activeRequests && selectedBenchmarks.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = if (tint.luminance() > .5f) Color.Black else Color.White)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null)
                                    Text(
                                        "Benchmark ${selectedBenchmarks.size} selected model${if (selectedBenchmarks.size == 1) "" else "s"}",
                                        Modifier.padding(start = 8.dp)
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.start(mode) },
                                    enabled = ready && progress == null && !activeRequests
                                ) { Text("Run only ${selected.name}") }
                                if (activeRequests && progress == null) Text("Waiting for active model requests to finish.", style = MaterialTheme.typography.labelSmall)
                                Text("All selected models run sequentially so their provider or local runtime is not contending with another benchmark. Results save after each test.", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        item { BenchmarkScoreCard(rating, local, matching.size, mode) }
                        if (matching.size > 1) {
                            item {
                                BenchmarkPanel("Score over time") {
                                    val trend = matching.reversed().mapNotNull { run -> benchmarkRating(listOf(run)).score?.let { run.startedAt to it } }
                                    TrendChart(trend.map { it.second.toDouble() }, trend.map { benchmarkDate(it.first) }, tint, "app score")
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BenchmarkTile("First response", formatLatency(rating.medianFirstTextMs), "median first text", Modifier.weight(1f), tint)
                                BenchmarkTile("Generation speed", rating.medianSpeed?.let { "${if (rating.estimatedSpeed) "≈ " else ""}%.1f".format(it) } ?: "—", "tokens / decode second", Modifier.weight(1f), tint)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BenchmarkTile("Completion", rating.sampleCount.takeIf { it > 0 }?.let { "${(100.0 * rating.completed / it).roundToInt()}%" } ?: "—", "${rating.completed} / ${rating.sampleCount} requests", Modifier.weight(1f), tint)
                                BenchmarkTile("Tool success", percent(rating.toolSuccessPercent), "validated tool tasks", Modifier.weight(1f), tint)
                            }
                        }
                        item {
                            BenchmarkPanel("What makes the score") {
                                rating.dimensions.forEach { dimension ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(dimension.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(dimension.score?.let { "${it.roundToInt()} / 100" } ?: "Not measured", style = MaterialTheme.typography.labelMedium, color = tint)
                                    }
                                    LinearProgressIndicator(progress = { (dimension.score ?: 0.0).toFloat() / 100 }, modifier = Modifier.fillMaxWidth(), color = tint)
                                    Text("${dimension.weight}% weight · ${dimension.detail}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            BenchmarkPanel("Measurement notes") {
                                MetricLine("p95 first response", formatLatency(rating.p95FirstTextMs))
                                MetricLine("Median request time", formatLatency(rating.medianDurationMs))
                                Text("Suite v1 · latest 5 completed ${mode.label.lowercase()} runs with this configuration. Fixed instructions and temperature 0; each profile's reasoning requirement is preserved. Network, model loading and queue time are included in first response. Token estimates use characters ÷ 4 and are marked ≈. One-chunk responses have no measured decode speed.", style = MaterialTheme.typography.bodySmall)
                                Text("This is an app performance score, not a general intelligence test. Missing measurements are excluded and remaining weights are normalized. Local and remote scores use different speed targets and are ranked separately.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    1 -> {
                        val rows = everyday.filter { it.profileUid == selected.uid && it.metrics.model == selected.model && it.metrics.provider == selected.compatibleType.name }
                        val toolMetrics = everydayTools.firstOrNull { it.profileUid == selected.uid && it.model == selected.model && it.provider == selected.compatibleType.name }
                        item {
                            BenchmarkPanel("Everyday performance · $rangeLabel") {
                                Text("Measured from actual conversations for this profile and model. Prompt lengths, tools and server load vary; these observations do not change the controlled benchmark score.", style = MaterialTheme.typography.bodySmall)
                                MetricLine("Tool success", percent(toolMetrics?.successPercent))
                                MetricLine("Tool completions / failures", "${toolMetrics?.completed ?: 0} / ${toolMetrics?.failed ?: 0}")
                                Text("Canceled and pending tools are excluded. Up to 10,000 stored requests, runs and tool events.", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (rows.isEmpty()) item { Text("Send a message with this profile to start collecting everyday performance.") }
                        items(rows, key = { it.key }) { row -> EverydayPerformanceCard(row, local) { detailKey = row.key } }
                        item {
                            BenchmarkPanel("Latency × throughput") {
                                Text("Reported tokens only. Tap a point for request history.", style = MaterialTheme.typography.bodySmall)
                                PerformanceScatter(rows) { detailKey = it }
                            }
                        }
                    }
                    2 -> {
                        item {
                            BenchmarkPanel("Compare AI profiles") {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BenchmarkMode.entries.filter { it != BenchmarkMode.DELEGATION }.forEach { option -> FilterChip(mode == option, { mode = option }, enabled = progress == null, label = { Text(option.label) }) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("All", "Local", "Remote").forEachIndexed { index, label -> FilterChip(typeFilter == index, { typeFilter = index }, label = { Text(label) }) }
                                }
                                Text("Same suite and test mode; latest 5 runs per current configuration. Compare coverage and sample counts alongside scores. Local and remote use separate verified cohort scales.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            BenchmarkPanel("Adaptive scores") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Scale updates across all eligible profiles", modifier = Modifier.weight(1f))
                                    SettingsHelpIcon("Speed balances each workload band: 100 × its measured rate / its fastest verified compatible rate. Local weights Q/R/S/L/C = 40/25/10/10/15; Remote = 35/20/20/15/10. Overall is capped by quality and reliability. Hidden rows remain in the anchor. Quick and incomplete runs never establish anchors. Reference lexical4 tokenizer v1 is a comparison unit, not provider billing. Five timed trials per workload are required; p95 requires 20 samples.")
                                }
                                Text("Snapshot ${snapshot?.generation ?: 0} · 30-day freshness · Quick runs stay provisional", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val rows = snapshot?.rows.orEmpty().filter { row -> typeFilter == 0 || row.cohort.contains("device:") == (typeFilter == 1) }
                        rows.groupBy { it.cohort }.forEach { (cohort, cohortRows) ->
                            item {
                                BenchmarkPanel(
                                    if (cohort.contains("device:")) {
                                        "Local · this device"
                                    } else if (cohort.contains("self-hosted:")) {
                                        "Self-hosted"
                                    } else {
                                        "Remote · cloud"
                                    }
                                ) {
                                    Text(cohort.substringBefore('|'), style = MaterialTheme.typography.labelSmall)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Profile", modifier = Modifier.weight(1.3f))
                                        Text("Speed /100", modifier = Modifier.weight(1f))
                                        Text("Ref tok/s", modifier = Modifier.weight(1f))
                                        Text("Overall", modifier = Modifier.weight(.8f))
                                    }
                                }
                            }
                            items(cohortRows.sortedWith(compareByDescending<dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreRow> { it.overall ?: -1.0 }.thenByDescending { it.speed ?: -1.0 }), key = { "v2-${it.profileUid}-${it.cohort}-${it.sourceRunIds.firstOrNull()}" }) { row ->
                                val item = profiles.firstOrNull { it.uid == row.profileUid }
                                Card(onClick = {
                                    if (item != null) {
                                        viewModel.select(item)
                                        tab = 0
                                    }
                                }, shape = RoundedCornerShape(16.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(item?.name ?: "Retained profile", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.SemiBold)
                                            Text(row.speedScore?.let { "%.1f".format(it) } ?: "—", modifier = Modifier.weight(1f))
                                            Text(row.speed?.let { "%.1f".format(it) } ?: "—", modifier = Modifier.weight(1f))
                                            Text(row.overall?.let { "%.1f".format(it) } ?: "—", modifier = Modifier.weight(.8f), color = MaterialTheme.colorScheme.primary)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${if (row.verified) "Verified" else "Provisional"} · ${row.sampleCount} samples · first ${row.firstTextMs?.let { "%.0f ms".format(it) } ?: "—"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                            SettingsHelpIcon(row.explanation)
                                        }
                                    }
                                }
                            }
                        }
                        if (rows.isEmpty()) item { Text("No v2 results yet. Open Run to start a Quick Check or a Standard plan.") }
                        item { TextButton(onClick = { tab = 1 }) { Text("Open everyday Statistics") } }
                    }
                    3 -> {
                        item { Text("Saved runs · ${profileHistory.size}", style = MaterialTheme.typography.titleLarge) }
                        item { Text("The latest 200 runs across all profiles are stored on this device. Canceled and interrupted runs are retained but excluded from ratings.", style = MaterialTheme.typography.bodySmall) }
                        if (profileHistory.isEmpty()) item { BenchmarkPanel("Your first result starts here") { Text("Run a quick benchmark from Overview. Every test will include its outcome, timing and response preview.") } }
                        items(profileHistory, key = { it.id }) { run ->
                            BenchmarkHistoryCard(run, run.configKey == benchmarkConfigKey(selected, localEnvironment), progress == null, { deleteId = run.id })
                        }
                        viewModel.legacyReport?.let { report ->
                            item {
                                BenchmarkPanel("Archived connection-doctor benchmark") {
                                    Text("Previous format · excluded from ratings", style = MaterialTheme.typography.labelSmall)
                                    Text(report, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (detail != null) ModalBottomSheet(onDismissRequest = { detailKey = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { ProfilePerformanceDetails(detail) }
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete this benchmark?") },
            text = { Text("The run will be removed from history and its profile rating will be recalculated.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteId?.let(viewModel::delete)
                    deleteId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BenchmarkProfilePicker(profiles: List<PlatformV2>, selected: PlatformV2?, enabled: Boolean, onSelect: (PlatformV2) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Card(onClick = { expanded = true }, enabled = enabled && profiles.isNotEmpty(), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val local = selected?.compatibleType == ClientType.LITERT_LM
                Icon(if (local) Icons.Rounded.Memory else Icons.Rounded.Cloud, null, tint = benchmarkTint(local), modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected?.name ?: "Select an AI profile", style = MaterialTheme.typography.titleMedium)
                    Text(selected?.model.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (selected != null) Text(if (local) "LOCAL · ${selected.accelerator?.uppercase() ?: "AUTO"}" else "REMOTE · ${selected.compatibleType.name}", style = MaterialTheme.typography.labelSmall, color = benchmarkTint(local))
                }
                Icon(Icons.Rounded.ExpandMore, "Choose AI profile")
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            profiles.forEach { item ->
                val local = item.compatibleType == ClientType.LITERT_LM
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.name)
                            Text(item.model, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    leadingIcon = { Icon(if (local) Icons.Rounded.Memory else Icons.Rounded.Cloud, if (local) "Local" else "Remote", tint = benchmarkTint(local)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BenchmarkScoreCard(rating: BenchmarkRating, local: Boolean, runs: Int, mode: BenchmarkMode) {
    val tint = benchmarkTint(local)
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = .1f))) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { (rating.score ?: 0) / 100f }, modifier = Modifier.fillMaxSize(), color = tint, strokeWidth = 7.dp, trackColor = tint.copy(alpha = .15f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rating.score?.toString() ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = tint)
                    Text("APP SCORE", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BenchmarkTypeBadge(local)
                Text(rating.grade, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${mode.label} · $runs runs · ${rating.measuredWeight}% coverage", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (rating.score == null) {
                        "Run tests to build a rating"
                    } else if (runs < 3) {
                        "Early result · repeat for confidence"
                    } else {
                        "Based on ${rating.sampleCount} tests"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun BenchmarkTile(title: String, value: String, subtitle: String, modifier: Modifier, tint: Color) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = tint)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EverydayPerformanceCard(row: ProfilePerformance, local: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BenchmarkTypeBadge(local)
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(row.metrics.model, style = MaterialTheme.typography.bodySmall)
            MetricLine("Median / p95 request", "${formatLatency(row.metrics.medianLatencyMs)} / ${formatLatency(row.metrics.p95LatencyMs)}")
            MetricLine("First text", formatLatency(row.metrics.medianFirstTokenMs))
            MetricLine("Reported output / request second", row.metrics.outputTokensPerSecond?.let { "%.1f tok/s".format(it) } ?: "Not reported")
            MetricLine("Completion success", percent(row.successPercent))
            Text("${row.metrics.requests} requests · tap for charts and history", style = MaterialTheme.typography.labelSmall, color = benchmarkTint(local))
        }
    }
}

@Composable
private fun BenchmarkHistoryCard(run: BenchmarkRun, current: Boolean, canDelete: Boolean, onDelete: () -> Unit) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    val rating = remember(run) { benchmarkRating(listOf(run)) }
    val score = if (run.mode == BenchmarkMode.DELEGATION) delegationBenchmarkRating(listOf(run)).score else rating.score
    Card(onClick = { expanded = !expanded }, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BenchmarkTypeBadge(run.local)
                Text(
                    if (run.canceled) {
                        "Canceled"
                    } else if (run.stoppedReason != null) {
                        "Stopped"
                    } else if (!run.finished) {
                        "Interrupted"
                    } else {
                        score?.let { "$it / 100" } ?: "Not rated"
                    },
                    color = benchmarkTint(run.local),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text("${run.mode.label} · ${benchmarkDate(run.startedAt)}", style = MaterialTheme.typography.titleSmall)
            Text(run.model, style = MaterialTheme.typography.bodySmall)
            Text("${run.samples.count { it.outcome == BenchmarkOutcome.PASSED }} passed · ${run.samples.size} / ${benchmarkSuite(run.mode).size} tests · ${if (current) "current configuration" else "previous configuration"}", style = MaterialTheme.typography.labelSmall)
            run.stoppedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(if (expanded) "Hide test details" else "Show test details", style = MaterialTheme.typography.labelLarge, color = benchmarkTint(run.local))
            if (expanded) {
                MetricLine("Device", run.device)
                if (run.local) {
                    MetricLine("Actual runtime", listOfNotNull(run.backend, run.accelerator).joinToString(" · ").ifBlank { "Not observed" })
                    MetricLine("Engine loaded before run", if (run.engineWasLoaded) "Yes; may be another model" else "No")
                }
                MetricLine("Peak sampled client memory", run.peakClientPssKb?.let { "${it / 1024} MiB PSS" } ?: "Not sampled")
                MetricLine("Thermal before / after", "${thermalLabel(run.thermalBefore)} / ${thermalLabel(run.thermalAfter)}")
                MetricLine("Battery before / after", "${run.batteryBefore?.let { "$it%" } ?: "—"} / ${run.batteryAfter?.let { "$it%" } ?: "—"}")
                Text("Memory is sampled after each test for this Android app, including remote runs; it is not server memory or a continuous peak. Thermal and battery readings are context, not efficiency scores.", style = MaterialTheme.typography.labelSmall)
                run.samples.forEach { sample ->
                    HorizontalDivider()
                    Text(sample.label, fontWeight = FontWeight.SemiBold)
                    Text(sample.outcome.name.lowercase().replace('_', ' '), color = if (sample.outcome in setOf(BenchmarkOutcome.ERROR, BenchmarkOutcome.FAILED, BenchmarkOutcome.TIMED_OUT)) MaterialTheme.colorScheme.error else benchmarkTint(run.local), style = MaterialTheme.typography.labelLarge)
                    MetricLine("Duration / first text", "${formatLatency(sample.durationMs)} / ${formatLatency(sample.firstTextMs)}")
                    if (sample.reconnectAttempts > 0) MetricLine("Connection retries", sample.reconnectAttempts.toString())
                    MetricLine("Output tokens", "${if (sample.estimatedTokens) "≈ " else ""}${sample.outputTokens}")
                    MetricLine("Longest text pause", formatLatency(sample.longestGapMs))
                    sample.nativeMetrics?.takeIf { it.isValid }?.let { native ->
                        MetricLine("Native decode / prefill tok/s", String.format(java.util.Locale.US, "%.1f / %.1f", native.decodeTokensPerSecond, native.prefillTokensPerSecond))
                        Text("Last native segment: ${native.decodeTokens} decoded / ${native.prefillTokens} prefilled tokens. The app score uses observed text delivery.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (sample.category == "tools") MetricLine("Successful fixture calls", "${sample.successfulToolCalls} / ${sample.toolCalls}")
                    sample.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (sample.preview.isNotEmpty()) Text(sample.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDelete, enabled = canDelete) { Text("Delete run", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun BenchmarkPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun BenchmarkTypeBadge(local: Boolean) {
    val tint = benchmarkTint(local)
    Surface(color = tint.copy(alpha = .12f), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (local) Icons.Rounded.Memory else Icons.Rounded.Cloud, null, tint = tint, modifier = Modifier.size(16.dp))
            Text(if (local) "Local" else "Remote", color = tint, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun benchmarkTint(local: Boolean): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return if (local) {
        if (dark) Color(0xFF72DACA) else Color(0xFF006B60)
    } else {
        if (dark) Color(0xFFD2BBFF) else Color(0xFF6942B7)
    }
}

private fun percent(value: Double?): String = value?.let { "%.0f%%".format(it) } ?: "—"
private fun benchmarkDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
private fun thermalLabel(status: Int?): String = when (status) {
    0 -> "Normal"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "—"
}
