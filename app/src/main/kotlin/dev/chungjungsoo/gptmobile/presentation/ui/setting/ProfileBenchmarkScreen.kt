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
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SelectAll
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
    val debugMode by viewModel.debugMode.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val exportNotice by viewModel.exportNotice.collectAsStateWithLifecycle()
    var detailedExport by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportBenchmarks(uri, detailedExport)
    }
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val profile by viewModel.selected.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val retests by viewModel.retests.collectAsStateWithLifecycle()
    val scoreboardRuns by viewModel.scoreboardRuns.collectAsStateWithLifecycle()
    val localEnvironment by viewModel.localEnvironment.collectAsStateWithLifecycle()
    val pairedComparison by viewModel.pairedComparison.collectAsStateWithLifecycle()
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
    androidx.compose.runtime.LaunchedEffect(debugMode) { if (!debugMode && tab == 7) tab = 6 }
    val selected = profile
    val local = selected?.compatibleType == ClientType.LITERT_LM
    val tint = benchmarkTint(local)
    val profileHistory = remember(history, selected?.uid) { history.filter { it.profileUid == selected?.uid } }
    val matching = remember(profileHistory, selected, mode, localEnvironment) { selected?.let { comparableRuns(profileHistory, it, mode, localEnvironment) }.orEmpty() }
    val activeRetest = retests.filter { it.current.profileUid == selected?.uid && it.current.revision == selected?.let { profile -> benchmarkConfigKey(profile, localEnvironment) } && it.current.cohort.startsWith(mode.name + "|") }
        .maxByOrNull { retest -> history.firstOrNull { it.id == retest.current.sourceRunIds.firstOrNull() }?.startedAt ?: 0 }
    val activeScore = activeRetest?.current
    val previousScore = activeRetest?.previous
    val rating = remember(matching, local, activeScore) {
        val dimensions = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkScoreDimensions(activeScore, local)
        benchmarkRating(matching, local).copy(score = activeScore?.overall?.roundToInt(), medianSpeed = activeScore?.speed, estimatedSpeed = false, dimensions = dimensions, measuredWeight = dimensions.filter { it.score != null }.sumOf { it.weight })
    }
    val latestScoreboard = scoreboardRuns.filter {
        it.kind == dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind.MODEL && it.plan == mode && it.provider == selected?.compatibleType?.name && it.model == selected?.model?.trim()?.let { model -> if (selected.compatibleType == ClientType.OPENROUTER) model.removeSuffix(":batch") else model }
    }.maxByOrNull { it.startedAt }?.let(dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard::evaluate)
    val previousScoreboard = latestScoreboard?.let { previousScoreboardEntry(it.submission, scoreboardRuns) }
    val detail = everyday.firstOrNull { it.key == detailKey }
    Scaffold(topBar = {
        if (!embedded) {
            TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5b2189dfc0)) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }, actions = {
                IconButton(onClick = onUsage) { Icon(Icons.Rounded.BarChart, "Usage") }
            })
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SettingsHero("Performance lab", "Measure. Compare. Improve.", "${history.size} recorded runs · ${profiles.size} profiles") }
            item { BenchmarkNavigation(tab, debugMode) { tab = it } }
            if (tab != 2 && tab != 5 && tab != 6 && !(tab == 4 && delegationBoard)) item { BenchmarkProfilePicker(profiles, selected, progress == null, viewModel::select) }
            if (error != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = viewModel::dismissError) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_a2bd204e93)) }
                        }
                    }
                }
            }
            progress?.let { current ->
                item {
                    BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_73d63d9b5c, current.profileName)) {
                        LinearProgressIndicator(progress = { current.completed.toFloat() / current.total }, modifier = Modifier.fillMaxWidth(), color = tint)
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ad2d56e844, current.completed, current.total, current.testName))
                        OutlinedButton(onClick = viewModel::cancel) {
                            Icon(Icons.Rounded.Stop, null)
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_440ce82b50))
                        }
                    }
                }
            }
            if (tab == 4) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_261dd55b4b), Icons.Rounded.PlayArrow, !delegationBoard, { delegationBoard = false })
                        BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e4891d078f), Icons.Rounded.BarChart, delegationBoard, { delegationBoard = true })
                    }
                }
            }
            if (tab == 6) {
                item {
                    SettingsPanel("Export benchmark data") {
                        Text("Save every stored run, sample, score table and local runtime qualification. Includes all profiles and dates, regardless of the current filters.")
                        Button(onClick = {
                            detailedExport = false
                            exportLauncher.launch("benchmarks-${System.currentTimeMillis()}.json")
                        }, enabled = !exporting && ready) { Text(if (exporting) "Exporting…" else "Export all benchmark data") }
                        exportNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            } else if (tab == 7 && debugMode) {
                item {
                    SettingsPanel("Diagnostic benchmark") {
                        Text("Runs the full test suite and records per-test memory, thermal and battery samples alongside timings, tokens, tool results and score dimensions. Provider usage is billed normally.")
                        Button(onClick = viewModel::startDebugBenchmark, enabled = selected != null && ready && progress == null && !activeRequests) { Text("Run full diagnostic benchmark") }
                        TextButton(onClick = {
                            detailedExport = true
                            exportLauncher.launch("benchmark-debug-${System.currentTimeMillis()}.json")
                        }, enabled = !exporting && ready) { Text("Export diagnostic data & score breakdown") }
                    }
                }
                items(history.filter { it.diagnosticMode }, key = { "debug.${it.id}" }) { run ->
                    SettingsPanel(run.profileName) {
                        val currentRow = snapshot?.rows?.firstOrNull { run.id in it.sourceRunIds }
                        val result = benchmarkRating(listOf(run), run.local).copy(
                            score = currentRow?.overall?.roundToInt(),
                            dimensions = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkScoreDimensions(currentRow, run.local)
                        )
                        Text("${result.grade} · ${run.samples.size} trials · ${run.resources.size} resource samples")
                        result.dimensions.forEach { dimension -> Text("${dimension.label}: ${dimension.score?.let { "%.1f".format(it) } ?: "Not measured"} · weight ${dimension.weight}", style = MaterialTheme.typography.bodySmall) }
                        run.samples.forEach { sample -> Text("${sample.label}: ${sample.outcome} · ${sample.durationMs} ms · ${sample.outputTokens} tokens · first text ${sample.firstTextMs ?: "—"} ms", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } else if (tab == 2) {
                item { BenchmarkScoreboardPanel(dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind.MODEL, onRun = { tab = 0 }) }
                item { BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_fee0cd4cdb), Icons.Rounded.BarChart, onClick = { tab = 1 }) }
            } else if (tab == 4 && delegationBoard) {
                item { BenchmarkScoreboardPanel(dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind.DELEGATION, onRun = { delegationBoard = false }) }
            } else if (selected == null) {
                item { BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_8a75f92384)) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e992833393)) } }
            } else {
                if (tab == 1) {
                    item {
                        Column {
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6aa7be6fc4), style = MaterialTheme.typography.labelMedium)
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
                            BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6e4feb616c)) {
                                BenchmarkPlanPicker(delegationMode, progress == null) { delegationMode = it }
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e530b5e7b1), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_99d46c7b5b), style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(pairedComparison, { viewModel.pairedComparison.value = it }, enabled = progress == null)
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f8f62607ba))
                                }
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e566b8dcfa, selected.name), fontWeight = FontWeight.SemiBold)
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1558a85651), style = MaterialTheme.typography.labelLarge)
                                BenchmarkProfilePicker(benchmarkReviewers, benchmarkReviewer, progress == null, viewModel::selectBenchmarkReviewer)
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b68c419c0c), style = MaterialTheme.typography.bodySmall)
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e3e1c36cae), style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e909b897a8), Icons.Rounded.SelectAll, onClick = viewModel::selectAllDelegates, enabled = progress == null)
                                    BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_d0f86d2093), Icons.Rounded.Deselect, onClick = viewModel::clearDelegates, enabled = progress == null)
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
                                if (delegates.isEmpty()) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_44433acfbb))
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ed08a01807, if (delegationMode == BenchmarkMode.QUICK) 5 else 50), style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { viewModel.startDelegationBatch(delegationMode) }, enabled = ready && progress == null && !activeRequests && benchmarkReviewer != null && selectedDelegates.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.PlayArrow, null)
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_2cf971db8e, delegationMode.label, selectedDelegates.size), Modifier.padding(start = 8.dp))
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
                        item { BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_dfd7a4833e), Icons.Rounded.BarChart, onClick = { delegationBoard = true }, modifier = Modifier.fillMaxWidth()) }
                    }

                    0 -> {
                        item {
                            BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4b446a5e52)) {
                                BenchmarkPlanPicker(mode, progress == null) { mode = it }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_cc97ecc157), style = MaterialTheme.typography.titleSmall)
                                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c73d7a91dd), style = MaterialTheme.typography.bodySmall)
                                    }
                                    SettingsHelpIcon(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e4dc79b946))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_7de7a0624a, selectedBenchmarks.size, benchmarkCandidates.size), style = MaterialTheme.typography.labelLarge)
                                    Box {
                                        IconButton(onClick = { benchmarkOptionsExpanded = true }, enabled = progress == null) {
                                            Icon(Icons.Rounded.Tune, "Choose benchmark models", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        DropdownMenu(
                                            expanded = benchmarkOptionsExpanded,
                                            onDismissRequest = { benchmarkOptionsExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3a311ebe92)) },
                                                leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                                                onClick = { viewModel.selectAllBenchmarks() }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_71f2343818)) },
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
                                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_eb0993963c, mode.label, selectedBenchmarks.size * benchmarkSuite(mode).size),
                                        Modifier.padding(start = 8.dp)
                                    )
                                }
                                BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c8624a55ae, (selected.name).toString()), Icons.Rounded.PlayArrow, onClick = { viewModel.start(mode) }, enabled = ready && progress == null && !activeRequests, modifier = Modifier.fillMaxWidth())
                                if (activeRequests && progress == null) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1d5b120900), style = MaterialTheme.typography.labelSmall)
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f0a6512884), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (latestScoreboard != null) item { ScoreboardScores(latestScoreboard, previousScoreboard, "Latest run · retest changes") }
                        item { BenchmarkScoreCard(rating, local, matching.size, mode) }
                        item {
                            BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_37c194cbad)) {
                                IntelligenceResults(activeScore?.intelligence, mode == BenchmarkMode.QUICK, previousScore?.intelligence)
                            }
                        }
                        item {
                            BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_daa66a9629)) {
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
                            BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_74a5983a25)) {
                                if (previousScore != null) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_13124fb6a2), style = MaterialTheme.typography.bodySmall)
                                rating.dimensions.forEach { dimension ->
                                    BenchmarkRatingBar(dimension.label, dimension.score, missing = benchmarkMissingScoreLabel(activeScore, dimension.label), previous = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkScoreDimensions(previousScore, local).firstOrNull { it.label == dimension.label }?.score)
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_40cd6a3958, dimension.weight, dimension.detail), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6be7d01034)) {
                                MetricLine("p95 first response", formatLatency(rating.p95FirstTextMs))
                                MetricLine("Median request time", formatLatency(rating.medianDurationMs))
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_58e16095e8, dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSuiteRegistry.VERSION), style = MaterialTheme.typography.bodySmall)
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e732ed457a), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item { BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_11e7db4864), Icons.Rounded.Memory, showToolHealth, { showToolHealth = !showToolHealth }, modifier = Modifier.fillMaxWidth()) }
                        if (showToolHealth) {
                            item {
                                BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ac3a6cc1cb)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(health.lastStatus, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        SettingsHelpIcon(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4371adca9b))
                                    }
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_d37369ab2c, health.contextTokens, health.evidenceBytes), style = MaterialTheme.typography.bodySmall)
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ebf52ca1cc, health.selected.joinToString().ifBlank { androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f2953d2931) }), style = MaterialTheme.typography.bodySmall)
                                    if (health.omitted.isNotEmpty()) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ae1775c87f, health.omitted.joinToString()), style = MaterialTheme.typography.bodySmall)
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_60c722e402, health.retainedBytes, health.admittedBytes, health.supportingEvidence), style = MaterialTheme.typography.bodySmall)
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1673713f9a, health.lastTool, health.lastError ?: androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_768ac4641b), health.dispatched, health.compacted), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    1 -> {
                        val rows = everyday.filter { it.profileUid == selected.uid && it.metrics.model == selected.model && it.metrics.provider == selected.compatibleType.name }
                        val toolMetrics = everydayTools.firstOrNull { it.profileUid == selected.uid && it.model == selected.model && it.provider == selected.compatibleType.name }
                        item {
                            BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_73d69870d1, rangeLabel)) {
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_fe2c58ddfb), style = MaterialTheme.typography.bodySmall)
                                MetricLine("Tool success", percent(toolMetrics?.successPercent))
                                MetricLine("Tool completions / failures", "${toolMetrics?.completed ?: 0} / ${toolMetrics?.failed ?: 0}")
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3382a238f9), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (rows.isEmpty()) item { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6bc7e7d523)) }
                        items(rows, key = { it.key }) { row -> EverydayPerformanceCard(row, local) { detailKey = row.key } }
                        item {
                            BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_316e1aa774)) {
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0964400565), style = MaterialTheme.typography.bodySmall)
                                PerformanceScatter(rows) { detailKey = it }
                            }
                        }
                    }
                    5 -> {
                        item { BenchmarkPlanPicker(mode, progress == null) { mode = it } }
                        item {
                            BenchmarkSideBySide(snapshot?.rows.orEmpty().filter { it.cohort.startsWith(mode.name + "|") }.map { row -> retests.firstOrNull { it.current.profileUid == row.profileUid && it.current.revision == row.revision && it.current.cohort == row.cohort }?.current ?: row }, profiles, retests)
                        }
                        item { BenchmarkAction(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_08d721ca13), Icons.Rounded.PlayArrow, onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth()) }
                    }
                    3 -> {
                        item { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e8466c66e4, profileHistory.size), style = MaterialTheme.typography.titleLarge) }
                        item { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_34e19fef52), style = MaterialTheme.typography.bodySmall) }
                        if (profileHistory.isEmpty()) item { BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0a995dffa4)) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_133bb74c5a)) } }
                        items(profileHistory, key = { it.id }) { run ->
                            BenchmarkHistoryCard(run, run.configKey == benchmarkConfigKey(selected, localEnvironment), progress == null, { deleteId = run.id })
                        }
                        viewModel.legacyReport?.let { report ->
                            item {
                                BenchmarkPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3e4530108b)) {
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_79142200ad), style = MaterialTheme.typography.labelSmall)
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
            title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_2fc0a25a06)) },
            text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_76488c74ee)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteId?.let(viewModel::delete)
                    deleteId = null
                }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b26de372b4)) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c0774c1850)) } }
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
                    Text(selected?.name ?: androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_cbd1b31ddb), style = MaterialTheme.typography.titleMedium)
                    Text(selected?.model.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (selected != null) Text(if (local) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_60b2d0e1b3, (selected.accelerator?.uppercase() ?: "AUTO").toString()) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_64c4d479f0, (selected.compatibleType.name).toString()), style = MaterialTheme.typography.labelSmall, color = benchmarkTint(local))
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
                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3dbf43865a), style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BenchmarkTypeBadge(local)
                Text(rating.grade, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4ee0073c2e, mode.label, runs, rating.measuredWeight), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (rating.score == null) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5be5ffe1ef)
                    } else if (runs < 3) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_8cc532c4e8)
                    } else {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ba6b18fb94, (rating.sampleCount).toString())
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
            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_657bdeb07c, row.metrics.requests), style = MaterialTheme.typography.labelSmall, color = benchmarkTint(local))
        }
    }
}

@Composable
private fun BenchmarkHistoryCard(run: BenchmarkRun, current: Boolean, canDelete: Boolean, onDelete: () -> Unit) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    val rating = remember(run) { benchmarkRating(listOf(run)) }
    val portable = remember(run) { dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard.fromRun(run)?.let(dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard::evaluate) }
    val score = portable?.score?.roundToInt() ?: if (run.mode == BenchmarkMode.DELEGATION) delegationBenchmarkRating(listOf(run)).score else rating.score
    Card(onClick = { expanded = !expanded }, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BenchmarkTypeBadge(run.local)
                Text(
                    if (run.canceled) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ad27889799)
                    } else if (run.stoppedReason != null) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f4a4aa0438)
                    } else if (!run.finished) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ec8913fc87)
                    } else {
                        score?.let { "$it / 100" } ?: androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_635663cefb)
                    },
                    color = benchmarkTint(run.local),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text("${run.mode.label}${run.delegationPlan?.let { " · ${it.label}" }.orEmpty()} · ${benchmarkDate(run.startedAt)}", style = MaterialTheme.typography.titleSmall)
            if (portable != null) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_bba707bfe8), style = MaterialTheme.typography.labelSmall)
            Text(run.model, style = MaterialTheme.typography.bodySmall)
            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_be0ee8da37, run.samples.count { it.outcome == BenchmarkOutcome.PASSED }, run.samples.size, dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuiteForRun(run).size, if (current) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c328dc1cfc) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_41e72c8d79)), style = MaterialTheme.typography.labelSmall)
            run.stoppedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(if (expanded) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5bb12f0764) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_2994ed039e), style = MaterialTheme.typography.labelLarge, color = benchmarkTint(run.local))
            if (expanded) {
                MetricLine("Device", run.device)
                if (run.local) {
                    MetricLine("Actual runtime", listOfNotNull(run.backend, run.accelerator).joinToString(" · ").ifBlank { "Not observed" })
                    MetricLine("Engine loaded before run", if (run.engineWasLoaded) "Yes; may be another model" else "No")
                }
                MetricLine("Peak sampled client memory", run.peakClientPssKb?.let { "${it / 1024} MiB PSS" } ?: "Not sampled")
                MetricLine("Thermal before / after", "${thermalLabel(run.thermalBefore)} / ${thermalLabel(run.thermalAfter)}")
                MetricLine("Battery before / after", "${run.batteryBefore?.let { "$it%" } ?: "—"} / ${run.batteryAfter?.let { "$it%" } ?: "—"}")
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_305682051e), style = MaterialTheme.typography.labelSmall)
                run.samples.forEach { sample ->
                    HorizontalDivider()
                    Text(sample.label, fontWeight = FontWeight.SemiBold)
                    Text(sample.outcome.name.lowercase().replace('_', ' '), color = if (sample.outcome in setOf(BenchmarkOutcome.ERROR, BenchmarkOutcome.FAILED, BenchmarkOutcome.TIMED_OUT)) MaterialTheme.colorScheme.error else benchmarkTint(run.local), style = MaterialTheme.typography.labelLarge)
                    MetricLine("Duration / first text", "${formatLatency(sample.durationMs)} / ${formatLatency(sample.firstTextMs)}")
                    if (sample.reconnectAttempts > 0) MetricLine("Connection retries", sample.reconnectAttempts.toString())
                    if (sample.intelligenceAnswers.isNotEmpty()) {
                        MetricLine("Reasoning accuracy", "${sample.intelligenceAnswers.values.count { it }}/${sample.intelligenceAnswers.size} correct")
                        MetricLine("Raw JSON compliance / parseable", "${sample.intelligenceFormatCompliant ?: "unknown"} / ${sample.intelligenceParseable ?: "unknown"}")
                    }
                    MetricLine("Output tokens · cumulative request", "${if (sample.estimatedTokens) "≈ " else ""}${sample.outputTokens}")
                    MetricLine("Longest text pause", formatLatency(sample.longestGapMs))
                    sample.pairedComparison?.let { pair ->
                        MetricLine("Supported fixture facts · primary / delegated", "${pair.primaryOnlyFacts}/${pair.requiredFacts} · ${pair.delegatedFacts}/${pair.requiredFacts}")
                        MetricLine("Wall time · primary / delegated", "${pair.primaryOnlyMillis} / ${pair.delegatedMillis} ms")
                        MetricLine("All accounted tokens · primary / delegated", "${pair.primaryOnlyTokens} / ${pair.delegatedTokens} · ${pair.tokenProvenance}")
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_015b1ee190, pair.taskClass, pair.rubricVersion, pair.order, pair.warmState, pair.cost))
                    }
                    sample.nativeMetrics?.takeIf { it.isValid }?.let { native ->
                        MetricLine("Native decode / prefill tok/s", String.format(java.util.Locale.US, "%.1f / %.1f", native.decodeTokensPerSecond, native.prefillTokensPerSecond))
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0aa4f852ac, native.decodeTokens, native.prefillTokens), style = MaterialTheme.typography.bodySmall)
                    }
                    if (sample.category == "tools") MetricLine("Successful fixture calls", "${sample.successfulToolCalls} / ${sample.toolCalls}")
                    sample.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (sample.preview.isNotEmpty()) Text(sample.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDelete, enabled = canDelete) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4733f5d14b), color = MaterialTheme.colorScheme.error) }
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
            Text(if (local) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e4f82851f6) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_aa956cb103), color = tint, style = MaterialTheme.typography.labelMedium)
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
