package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard
import dev.chungjungsoo.gptmobile.data.benchmark.IntelligenceBenchmark
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardEntry
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardRepository
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardSubmission
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun BenchmarkScoreboardPanel(kind: ScoreboardKind, onRun: () -> Unit, viewModel: ScoreboardViewModel = hiltViewModel()) {
    val local by viewModel.local.collectAsStateWithLifecycle()
    val submissions by viewModel.submissions.collectAsStateWithLifecycle()
    val community by viewModel.online.collectAsStateWithLifecycle()
    val share by viewModel.share.collectAsStateWithLifecycle()
    var online by rememberSaveable(kind) { mutableStateOf(false) }
    var plan by rememberSaveable(kind) { mutableStateOf(BenchmarkMode.QUICK) }
    var query by rememberSaveable(kind) { mutableStateOf("") }
    var visible by rememberSaveable(kind, plan, online, query) { mutableIntStateOf(20) }
    var detail by remember(kind) { mutableStateOf<ScoreboardEntry?>(null) }
    var showShare by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<String?>(null) }
    val entries = (if (online) community.entries else local).filter { it.submission.kind == kind && it.submission.plan == plan }
    val filtered = entries.filter { "${it.submission.provider} ${it.submission.model}".contains(query, ignoreCase = true) }
    LaunchedEffect(online) { if (online) viewModel.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.EmojiEvents, null)
                    Column(Modifier.weight(1f)) {
                        Text(if (kind == ScoreboardKind.MODEL) "Model scoreboard" else "Delegation scoreboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Best complete run · one entry per provider + model", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("${entries.size} models  ·  ${if (plan == BenchmarkMode.QUICK) "5 tests · provisional" else "50 tests · full coverage"}", style = MaterialTheme.typography.labelLarge)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BenchmarkAction("Local", Icons.Rounded.Smartphone, !online, { online = false })
            BenchmarkAction("Online", Icons.Rounded.Cloud, online, { online = true })
            if (online) IconButton(onClick = viewModel::refresh, enabled = !community.loading) { Icon(Icons.Rounded.Refresh, "Refresh shared scores") }
        }
        Text(if (online) "Community results · self-reported. GitHub validates the data and recalculates scores; model identity and execution are not independently verified." else "Your saved remote-model results. Renaming a profile does not create a new entry. On-device models remain in Run, Compare and History.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BenchmarkPlanPicker(plan) { plan = it }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find a model or provider") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
        if (community.loading && online) CircularProgressIndicator()
        if (online && community.updatedAt > 0) Text("${if (community.cached) "Saved copy" else "Updated"} · ${scoreboardDate(community.updatedAt)}", style = MaterialTheme.typography.labelSmall)
        if (online) community.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (filtered.isEmpty() && (!online || (!community.loading && community.hasSnapshot))) {
            ScoreboardSurface {
                Text(if (query.isNotBlank()) "No matching models" else if (online) "No shared results for this plan yet" else "The next result starts with a run", style = MaterialTheme.typography.titleMedium)
                Text(if (online) "Open a completed remote-model result in Local, then choose Share to publish it. A run stays on your device until you choose to share it." else "A complete remote-model ${plan.label.lowercase()} run can join this board. Unsupported, canceled and incomplete runs stay in History.", style = MaterialTheme.typography.bodySmall)
                BenchmarkAction("Run tests", Icons.Rounded.PlayArrow, onClick = onRun)
            }
        }
        filtered.take(visible).forEach { entry ->
            val data = entry.submission
            val previous = if (online) null else previousScoreboardEntry(data, submissions)
            Card(onClick = { detail = entry }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("#${entries.indexOf(entry) + 1}", Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(data.model, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(data.provider, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("%.1f".format(entry.score), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    BenchmarkRatingBar("Capability score", entry.score, previous = previous?.score)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val metric = entry.metrics.firstOrNull { it.label == if (kind == ScoreboardKind.MODEL) "Intelligence" else "Reviewer" }
                        Text("${metric?.label}  ${metric?.value?.let { "%.1f".format(it) } ?: "—"}", style = MaterialTheme.typography.labelMedium)
                        Text("${data.trials.size} test outcomes", style = MaterialTheme.typography.labelMedium)
                    }
                    BenchmarkAction("Full scores & data", Icons.Rounded.OpenInNew, onClick = { detail = entry }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (filtered.size > visible) BenchmarkAction("Show more models", Icons.Rounded.ExpandMore, onClick = { visible += 20 }, modifier = Modifier.fillMaxWidth())
        Text(if (kind == ScoreboardKind.MODEL) BenchmarkScoreboard.MODEL_FORMULA else BenchmarkScoreboard.DELEGATION_FORMULA, style = MaterialTheme.typography.bodySmall)
    }
    detail?.let { entry ->
        val previous = if (entry.submission.issueNumber == null) previousScoreboardEntry(entry.submission, submissions) else null
        ScoreboardDetail(entry, previous, { detail = null }, {
            account = null
            showShare = true
            viewModel.prepareShare()
        })
        if (showShare) {
            val uri = LocalUriHandler.current
            AlertDialog(
                onDismissRequest = { if (!share.busy) showShare = false },
                title = { Text("Share benchmark publicly?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${entry.submission.provider} · ${entry.submission.model}\n${entry.submission.plan.label} · %.1f / 100".format(entry.score))
                        Text("Publishes test outcomes, scores, timings, token counts and primary/reviewer model identities to ${ScoreboardRepository.REPOSITORY}. No prompts, replies, profile names, keys or endpoint URLs. Your GitHub username is public on the submission.")
                        if (!share.busy && share.accounts.isEmpty()) Text("Connect a GitHub account with issue-write permission in Settings → Tools, then reopen Share.")
                        share.accounts.forEach { connection ->
                            BenchmarkAction(connection.name, Icons.Rounded.Cloud, account == connection.connectionUid, { account = connection.connectionUid }, enabled = !share.busy && share.issue == null)
                        }
                        if (share.busy) CircularProgressIndicator()
                        share.message?.let { Text(it) }
                        share.issue?.let { issue -> BenchmarkAction("View submission", Icons.Rounded.OpenInNew, onClick = { uri.openUri("https://github.com/${ScoreboardRepository.REPOSITORY}/issues/$issue") }) }
                    }
                },
                confirmButton = {
                    Button(onClick = { account?.let { viewModel.publish(entry.submission, it) } }, enabled = account != null && !share.busy && share.issue == null) {
                        Icon(Icons.Rounded.Share, null)
                        Text("Publish")
                    }
                },
                dismissButton = { TextButton(onClick = { showShare = false }, enabled = !share.busy) { Text(if (share.issue == null) "Cancel" else "Done") } }
            )
        }
    }
}

internal fun previousScoreboardEntry(current: ScoreboardSubmission, all: List<ScoreboardSubmission>): ScoreboardEntry? = all
    .filter { it.key == current.key && it.id != current.id && it.startedAt < current.startedAt }
    .maxByOrNull { it.startedAt }?.let(BenchmarkScoreboard::evaluate)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreboardDetail(entry: ScoreboardEntry, previous: ScoreboardEntry?, onClose: () -> Unit, onShare: () -> Unit) {
    val data = entry.submission
    val uri = LocalUriHandler.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onClose)
        Scaffold(topBar = {
            TopAppBar(title = { Text("Full benchmark data") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to scoreboard") } }, actions = {
                if (data.issueNumber == null) IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share result online") }
            })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    ScoreboardSurface {
                        Text(data.model, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${data.provider} · ${data.plan.label} · ${scoreboardDate(data.startedAt)}")
                        Text("%.1f / 100".format(entry.score), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                        Text(if (data.issueNumber == null) "Best saved run" else "Community result · self-reported", style = MaterialTheme.typography.labelLarge)
                        if (data.plan == BenchmarkMode.QUICK) Text("Provisional: five tests provide a quick screen. Use Full for broader coverage.", style = MaterialTheme.typography.bodySmall)
                        if (data.kind == ScoreboardKind.DELEGATION) {
                            MetricLine("Primary", "${data.primaryProvider} · ${data.primaryModel}")
                            MetricLine("Reviewer", "${data.reviewerProvider} · ${data.reviewerModel}")
                            Text("Research + reviewer on · controlled fixture research · benchmark preset v1", style = MaterialTheme.typography.bodySmall)
                        }
                        data.issueNumber?.let { issue -> BenchmarkAction("Source submission #$issue", Icons.Rounded.OpenInNew, onClick = { uri.openUri("https://github.com/${ScoreboardRepository.REPOSITORY}/issues/$issue") }) }
                    }
                }
                item { ScoreboardScores(entry, previous) }
                item {
                    ScoreboardSurface {
                        Text("Measurement notes", style = MaterialTheme.typography.titleMedium)
                        Text(if (data.kind == ScoreboardKind.MODEL) BenchmarkScoreboard.MODEL_FORMULA else BenchmarkScoreboard.DELEGATION_FORMULA)
                        if (data.kind == ScoreboardKind.MODEL) Text(IntelligenceBenchmark.LIMITATION)
                        Text("A best-of-runs score is a personal/community high-water mark, not an unbiased estimate. Each entry is one complete run; Quick and Full never mix. Network, primary and reviewer models affect results. Unobserved timing stays unmeasured.", style = MaterialTheme.typography.bodySmall)
                        Text("Suite ${data.suiteVersion} · scoreboard policy ${data.schemaVersion} · run ${data.id}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                item { Text("Every test · ${data.trials.size} outcomes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                val labels = BenchmarkScoreboard.manifest(data.kind, data.plan).associate { it.id to it.label }
                items(data.trials, key = { it.id }) { trial ->
                    ScoreboardSurface {
                        Text(labels[trial.id] ?: trial.id, style = MaterialTheme.typography.titleMedium)
                        Text(trial.outcome.name, color = if (trial.outcome.name == "PASSED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        MetricLine("Duration / first text", "${trial.durationMs} ms / ${trial.firstTextMs?.let { "$it ms" } ?: "Not observed"}")
                        MetricLine("Generation", trial.speed?.let { "%.1f %s".format(it, if (data.kind == ScoreboardKind.MODEL) "ref tok/s" else "tok/s") } ?: "No decoding interval observed")
                        MetricLine("Input / output tokens", "${trial.inputTokens} / ${trial.outputTokens}${if (trial.estimatedTokens) " (estimated)" else ""}")
                        MetricLine("Successful tool calls", "${trial.successfulToolCalls} / ${trial.toolCalls}")
                        trial.intelligence.forEach { (domain, passed) -> MetricLine(domain, if (passed) "Correct" else "Incorrect") }
                        if (data.kind == ScoreboardKind.DELEGATION) {
                            MetricLine("Reviewer", trial.reviewerScore?.let { "$it / 100" } ?: "No valid assessment · failure")
                            MetricLine("Worker / reviewer calls", "${trial.workerCalls} / ${trial.reviewerCalls}")
                            MetricLine("Research", "${trial.searches} searches · ${trial.pagesRead} pages")
                            MetricLine("Handoff", "${trial.handoffCharacters} characters")
                            MetricLine("Primary tokens in / out", "${trial.primaryInputTokens} / ${trial.primaryOutputTokens}")
                            MetricLine("Reviewer tokens in / out", "${trial.reviewerInputTokens} / ${trial.reviewerOutputTokens}")
                            MetricLine("Output cap violations", trial.outputCapViolations.toString())
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScoreboardScores(entry: ScoreboardEntry, previous: ScoreboardEntry?, title: String = "Full score breakdown") {
    ScoreboardSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Timeline, null)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (previous != null) Text("Compared with ${scoreboardDate(previous.submission.startedAt)}. Theme: retained points · red: lost · yellow: gained.", style = MaterialTheme.typography.bodySmall)
        BenchmarkRatingBar("Capability score", entry.score, previous = previous?.score)
        entry.metrics.forEach { metric ->
            if (metric.unit == "/100") {
                BenchmarkRatingBar(metric.label + if (metric.weight > 0) " · ${metric.weight}%" else "", metric.value, previous = previous?.metrics?.firstOrNull { it.label == metric.label }?.value, missing = if (metric.label == "Consistency") "Full repetitions needed" else "Not measured")
            } else {
                MetricLine(metric.label, metric.value?.let { "%.1f %s".format(it, metric.unit) } ?: "Not observed")
            }
        }
    }
}

@Composable
private fun ScoreboardSurface(content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

private fun scoreboardDate(timestamp: Long): String = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
