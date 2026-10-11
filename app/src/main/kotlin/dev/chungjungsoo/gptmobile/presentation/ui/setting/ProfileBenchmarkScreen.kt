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
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
    val benchmarkReviewers by viewModel.benchmarkReviewers.collectAsStateWithLifecycle()
    val benchmarkReviewer by viewModel.benchmarkReviewer.collectAsStateWithLifecycle()
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
    var delegationBoard by rememberSaveable { mutableStateOf(false) }
    var delegationMode by rememberSaveable { mutableStateOf(BenchmarkMode.QUICK) }
    var performanceOrder by rememberSaveable { mutableStateOf(PerformanceOrder.LATENCY) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var benchmarkOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    var showToolHealth by rememberSaveable { mutableStateOf(false) }
    val selected = profile
    val local = selected?.compatibleType == ClientType.LITERT_LM
    val tint = benchmarkTint(local)
    val profileHistory = remember(history, selected?.uid) { history.filter { it.profileUid == selected?.uid } }
    val matching = remember(profileHistory, selected, mode, localEnvironment) { selected?.let { comparableRuns(profileHistory, it, mode, localEnvironment) }.orEmpty() }
    val retests = remember(history) { dev.chungjungsoo.gptmobile.data.benchmark.benchmarkRetests(history) }
    val activeRetest = retests.filter { it.current.profileUid == selected?.uid && it.current.revision == selected?.let { profile -> benchmarkConfigKey(profile, localEnvironment) } && it.current.cohort.startsWith(mode.name + "|") }
        .maxByOrNull { retest -> history.firstOrNull { it.id == retest.current.sourceRunIds.firstOrNull() }?.startedAt ?: 0 }
    val activeScore = activeRetest?.current
    val previousScore = activeRetest?.previous
    val rating = remember(matching, local, activeScore) {
        val dimensions = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkScoreDimensions(activeScore, local)
        benchmarkRating(matching, local).copy(score = activeScore?.overall?.roundToInt(), medianSpeed = activeScore?.speed, estimatedSpeed = false, dimensions = dimensions, measuredWeight = dimensions.filter { it.score != null }.sumOf { it.weight })
    }
    val scoreboardRuns = remember(history) { history.mapNotNull(dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard::fromRun) }
    val latestScoreboard = scoreboardRuns.filter {
        it.kind == dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind.MODEL && it.plan == mode && it.provider == selected?.compatibleType?.name && it.model == selected?.model?.trim()?.let { model -> if (selected.compatibleType == ClientType.OPENROUTER) model.removeSuffix(":batch") else model }
    }.maxByOrNull { it.startedAt }?.let(dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard::evaluate)
    val previousScoreboard = latestScoreboard?.let { previousScoreboardEntry(it.submission, scoreboardRuns) }
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
            item { BenchmarkNavigation(tab) { tab = it } }
            if (tab != 2 && tab != 5 && !(tab == 4 && delegationBoard)) item { BenchmarkProfilePicker(profiles, selected, progress == null, viewModel::select) }
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
            if (tab == 4) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BenchmarkAction("Run delegates", Icons.Rounded.PlayArrow, !delegationBoard, { delegationBoard = false })
                    BenchmarkAction("Scoreboard", Icons.Rounded.BarChart, delegationBoard, { delegationBoard = true })
                }
            }
            if (tab == 2) {
                item { BenchmarkScoreboardPanel(dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind.MODEL, onRun = { tab = 0 }) }
                item { BenchmarkAction("Everyday statistics", Icons.Rounded.BarChart, onClick = { tab = 1 }) }
            } else if (tab == 4 && delegationBoard) {
                item { BenchmarkScoreboardPanel(dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind.DELEGATION, onRun = { delegationBoard = false }) }
            } else if (selected == null) {
                item { BenchmarkPanel("No AI profiles yet") { Text("Create an AI profile in Settings, choose its model, then return here to measure it.") } }
            } else {
                progress?.let { current ->
                    item {
                        BenchmarkPanel("Running · ${current.profileName}") {
                            LinearProgressIndicator(progress = { current.completed.toFloat() / current.total }, modifier = Modifier.fillMaxWidth(), color = tint)
                            Text("${current.completed} / ${current.total} tests · ${current.testName}")
                            OutlinedButton(onClick = viewModel::cancel) {
                                Icon(Icons.Rounded.Stop, null)
                                Text("Stop benchmark")
                            }
                        }
                    }
                }
                if (tab == 1) {
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
                            BenchmarkPanel("Delegation lab") {
                                BenchmarkPlanPicker(delegationMode, progress == null) { delegationMode = it }
                                Text("Research on · independent reviewer on", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text("A fixed benchmark preset evaluates evidence, tools, research, handoff and robustness. Full repeats all eight pipeline checks. Research uses controlled source fixtures. Conversation settings are unchanged.", style = MaterialTheme.typography.bodySmall)
                                Text("Primary: ${selected.name}", fontWeight = FontWeight.SemiBold)
                                Text("Independent reviewer", style = MaterialTheme.typography.labelLarge)
                                BenchmarkProfilePicker(benchmarkReviewers, benchmarkReviewer, progress == null, viewModel::selectBenchmarkReviewer)
                                Text("Choose a reviewer different from the primary and delegate. Its score contributes 20%; objective fixture accuracy caps the result.", style = MaterialTheme.typography.bodySmall)
                                Text("Delegates to evaluate", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BenchmarkAction("Select all", Icons.Rounded.SelectAll, onClick = viewModel::selectAllDelegates, enabled = progress == null)
                                    BenchmarkAction("Clear", Icons.Rounded.Deselect, onClick = viewModel::clearDelegates, enabled = progress == null)
                                }
                                delegates.forEach { helper ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(selectedDelegates.any { it.uid == helper.uid }, { viewModel.toggleDelegate(helper) }, enabled = progress == null)
                                        Column(Modifier.weight(1f)) {
                                            Text(helper.name, fontWeight = FontWeight.SemiBold)
                                            Text("${helper.compatibleType.name} · ${helper.model}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                if (delegates.isEmpty()) Text("Add an enabled delegate profile with tools, separate from the primary and reviewer.")
                                Text("${if (delegationMode == BenchmarkMode.QUICK) 5 else 50} tests per delegate · up to 180 seconds per test · primary, worker and reviewer requests may incur provider charges.", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { viewModel.startDelegationBatch(delegationMode) }, enabled = ready && progress == null && !activeRequests && benchmarkReviewer != null && selectedDelegates.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.PlayArrow, null)
                                    Text("Run ${delegationMode.label} · ${selectedDelegates.size} delegates", Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        val latestDelegation = scoreboardRuns.filter { it.kind == dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind.DELEGATION && it.plan == delegationMode && it.primaryProvider == selected.compatibleType.name && it.primaryModel == selected.model.trim() }
                            .sortedByDescending { it.startedAt }.distinctBy { it.identity }
                        items(latestDelegation) { data ->
                            dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard.evaluate(data)?.let { entry ->
                                ScoreboardScores(entry, previousScoreboardEntry(data, scoreboardRuns), "Latest retest · ${data.model}")
                            }
                        }
                        item { BenchmarkAction("Open delegation scoreboard", Icons.Rounded.BarChart, onClick = { delegationBoard = true }, modifier = Modifier.fillMaxWidth()) }
                    }

                    0 -> {
                        item {
                            BenchmarkPanel("Run a benchmark") {
                                BenchmarkPlanPicker(mode, progress == null) { mode = it }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Five categories. One clear result.", style = MaterialTheme.typography.titleSmall)
                                        Text("Speed · Task accuracy · JSON · Tools · Intelligence", style = MaterialTheme.typography.bodySmall)
                                    }
                                    SettingsHelpIcon("Quick: exactly 5 tests, one per category, including 6 Intelligence questions in one compact test. Full: exactly 50 tests — 18 timing (3 warm-ups), 8 task accuracy, 6 JSON, 12 tool contracts and 6 Intelligence tests covering 36 questions. Tools use a harmless fixture. Unsupported tools are recorded, not failed. A test may include tool round trips or a transport retry. Each test has a 90-second limit. Only Full can establish repeated timing ratings.")
                                }
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
                                                leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                                                onClick = { viewModel.selectAllBenchmarks() }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Clear selection") },
                                                leadingIcon = { Icon(Icons.Rounded.Deselect, null) },
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
                                        "Run ${mode.label} · ${selectedBenchmarks.size * benchmarkSuite(mode).size} tests",
                                        Modifier.padding(start = 8.dp)
                                    )
                                }
                                BenchmarkAction("Run ${selected.name}", Icons.Rounded.PlayArrow, onClick = { viewModel.start(mode) }, enabled = ready && progress == null && !activeRequests, modifier = Modifier.fillMaxWidth())
                                if (activeRequests && progress == null) Text("Waiting for active model requests to finish.", style = MaterialTheme.typography.labelSmall)
                                Text("All selected models run sequentially so their provider or local runtime is not contending with another benchmark. Results save after each test.", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (latestScoreboard != null) item { ScoreboardScores(latestScoreboard, previousScoreboard, "Latest run · retest changes") }
                        item { BenchmarkScoreCard(rating, local, matching.size, mode) }
                        item {
                            BenchmarkPanel("Intelligence · text capability") {
                                IntelligenceResults(activeScore?.intelligence, mode == BenchmarkMode.QUICK, previousScore?.intelligence)
                            }
                        }
                        item {
                            BenchmarkPanel("Progress by category") {
                                BenchmarkCoverage(profileHistory.firstOrNull { it.mode == mode && it.suiteVersion == dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSuiteRegistry.VERSION }, mode)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BenchmarkTile("First response", formatLatency(rating.medianFirstTextMs), "median first text", Modifier.weight(1f), tint)
                                BenchmarkTile("Generation speed", rating.medianSpeed?.let { "${if (rating.estimatedSpeed) "≈ " else ""}%.1f".format(it) } ?: "—", "reference tokens / second", Modifier.weight(1f), tint)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BenchmarkTile("Completion", rating.sampleCount.takeIf { it > 0 }?.let { "${(100.0 * rating.completed / it).roundToInt()}%" } ?: "—", "${rating.completed} / ${rating.sampleCount} requests", Modifier.weight(1f), tint)
                                BenchmarkTile(
                                    "Tool success",
                                    when {
                                        "tools" in activeScore?.unsupportedCategories.orEmpty() && rating.toolSuccessPercent == null -> "Unsupported"
                                        else -> percent(rating.toolSuccessPercent)
                                    },
                                    "Validated fixture calls",
                                    Modifier.weight(1f),
                                    tint
                                )
                            }
                        }
                        item {
                            BenchmarkPanel("Timing score breakdown") {
                                if (previousScore != null) Text("Previous run on the same current cohort scale. Theme: retained points · red: lost · yellow: gained.", style = MaterialTheme.typography.bodySmall)
                                rating.dimensions.forEach { dimension ->
                                    BenchmarkRatingBar(dimension.label, dimension.score, missing = benchmarkMissingScoreLabel(activeScore, dimension.label), previous = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkScoreDimensions(previousScore, local).firstOrNull { it.label == dimension.label }?.score)
                                    Text("${dimension.weight}% weight · ${dimension.detail}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            BenchmarkPanel("Measurement notes") {
                                MetricLine("p95 first response", formatLatency(rating.p95FirstTextMs))
                                MetricLine("Median request time", formatLatency(rating.medianDurationMs))
                                Text("Suite ${dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSuiteRegistry.VERSION} · latest 5 complete blocks with this configuration and context tier. Fixed instructions and temperature 0. First response includes model loading and queue time. Throughput uses the shared lexical4 comparison unit; one-chunk responses have no measured decode speed.", style = MaterialTheme.typography.bodySmall)
                                Text("Quality, reliability, speed, first response and consistency use the same timing policy here and in Compare. The Scoreboard uses absolute capability accuracy. Overall needs every dimension; missing timing is never invented. Quick and Full both include tool fixtures. Quick remains provisional. Local context tiers and remote cohorts are ranked separately.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item { BenchmarkAction("Runtime diagnostics", Icons.Rounded.Memory, showToolHealth, { showToolHealth = !showToolHealth }, modifier = Modifier.fillMaxWidth()) }
                        if (showToolHealth) {
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
                    5 -> {
                        item { BenchmarkPlanPicker(mode, progress == null) { mode = it } }
                        item {
                            BenchmarkSideBySide(snapshot?.rows.orEmpty().filter { it.cohort.startsWith(mode.name + "|") }.map { row -> retests.firstOrNull { it.current.profileUid == row.profileUid && it.current.revision == row.revision && it.current.cohort == row.cohort }?.current ?: row }, profiles, retests)
                        }
                        item { BenchmarkAction("Run another model", Icons.Rounded.PlayArrow, onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth()) }
                    }
                    3 -> {
                        item { Text("Saved runs · ${profileHistory.size}", style = MaterialTheme.typography.titleLarge) }
                        item { Text("Saved runs are retained on this device until you delete them. Canceled and interrupted runs are retained but excluded from ratings.", style = MaterialTheme.typography.bodySmall) }
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
                        "Run Full to measure repeated timing and all rating dimensions"
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
