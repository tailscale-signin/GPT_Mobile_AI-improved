package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringBudget
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringComparison
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringExternalTransport
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringLane
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringOrdering
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkTaskEfficiency
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

internal const val ENGINEERING_BENCHMARK_TAB = 9

@Composable
internal fun BenchmarkEngineeringWorkspace(
    viewModel: ProfileBenchmarkViewModel,
    profiles: List<PlatformV2>,
    history: List<BenchmarkRun>,
    busy: Boolean,
    ready: Boolean,
    onExport: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val guideBase = remember {
        val revision = dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) } ?: "main"
        "https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/$revision/"
    }
    val comparisons by viewModel.engineeringComparisons.collectAsStateWithLifecycle()
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val gitHubConnections by viewModel.engineeringGitHubConnections.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshEngineeringGitHubConnections() }
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var lane by rememberSaveable { mutableStateOf(BenchmarkEngineeringLane.WORKFLOW_SIMULATION) }
    var firstId by rememberSaveable { mutableStateOf<String?>(null) }
    var secondId by rememberSaveable { mutableStateOf<String?>(null) }
    var repeats by rememberSaveable { mutableIntStateOf(3) }
    var preset by rememberSaveable { mutableStateOf("quick") }
    var taskPack by rememberSaveable { mutableStateOf("coding") }
    var externalTransport by rememberSaveable { mutableStateOf(BenchmarkEngineeringExternalTransport.GATEWAY) }
    var gitHubConnectionUid by rememberSaveable { mutableStateOf<String?>(null) }
    var deadlineMinutes by rememberSaveable(lane) { mutableIntStateOf(if (lane == BenchmarkEngineeringLane.LIVE_GITHUB) 15 else 5) }
    var outputLimit by rememberSaveable { mutableIntStateOf(4096) }
    var repairLimit by rememberSaveable { mutableIntStateOf(2) }
    var ordering by rememberSaveable { mutableStateOf(BenchmarkEngineeringOrdering.SELECTED_ORDER) }
    var strict by rememberSaveable { mutableStateOf(false) }
    var repository by rememberSaveable { mutableStateOf("") }
    var baseSha by rememberSaveable { mutableStateOf("") }
    var comparisonId by rememberSaveable { mutableStateOf<String?>(null) }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var tasksShown by rememberSaveable { mutableIntStateOf(30) }
    var onlyFailures by rememberSaveable { mutableStateOf(false) }
    val first = if (firstId == null) profiles.firstOrNull() else profiles.firstOrNull { it.uid == firstId }
    val second = if (secondId == null) profiles.firstOrNull { it.uid != first?.uid } else profiles.firstOrNull { it.uid == secondId }
    val externalLane = lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB)
    val selectedTaskPack = if (externalLane) taskPack else "coding"
    val selectedTransport = if (lane == BenchmarkEngineeringLane.LIVE_GITHUB) externalTransport else BenchmarkEngineeringExternalTransport.GATEWAY
    val directActions = selectedTransport == BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS
    val selectedConnection = gitHubConnections.firstOrNull { it.connectionUid == gitHubConnectionUid }
    val connectionConfigured = selectedConnection?.let { it.toolPolicy in setOf("ASK_WRITES", "TRUSTED") && !it.secretRef.isNullOrBlank() } == true
    val preview = remember(lane, repeats, preset, selectedTaskPack, selectedTransport) {
        viewModel.previewEngineeringBenchmark(lane, repeats, preset, taskPack = selectedTaskPack, externalTransport = selectedTransport)
    }
    val budget = remember(deadlineMinutes, outputLimit, repairLimit) {
        BenchmarkEngineeringBudget(taskDeadlineMs = deadlineMinutes * 60_000L, outputTokensPerGeneration = outputLimit, maxRepairAttempts = repairLimit)
    }
    val laneRuns = remember(history, lane, selectedTaskPack, selectedTransport) {
        history.filter {
            it.engineeringPlan?.let { plan -> plan.lane == lane && plan.taskPack == selectedTaskPack && plan.externalTransport == selectedTransport }
                ?: (selectedTaskPack == "coding" && !directActions && it.samples.any { sample -> sample.evidenceScope == lane.evidenceClass })
        }.sortedByDescending { it.startedAt }
    }
    val cohortIds = laneRuns.mapNotNull { it.engineeringPlan?.cohortId }.toSet()
    val laneComparisons = comparisons.filter { it.lane == lane && it.cohortId in cohortIds }
    val selectedComparison = laneComparisons.firstOrNull { it.cohortId == comparisonId } ?: laneComparisons.firstOrNull()
    val focusedRuns = selectedComparison?.let { comparison -> laneRuns.filter { it.engineeringPlan?.cohortId == comparison.cohortId } }.orEmpty()
    val selectedRun = laneRuns.firstOrNull { it.id == runId } ?: focusedRuns.firstOrNull() ?: laneRuns.firstOrNull()
    val selectedTask = selectedRun?.samples?.firstOrNull { it.testId == taskId }
    val pausedBatch = batches.filter { batch ->
        batch.engineeringPauseReason != null && batch.engineeringPlan?.let { it.lane == lane && it.taskPack == selectedTaskPack && it.externalTransport == selectedTransport } == true
    }.maxByOrNull { it.createdAt }
    val liveTargetValid = lane != BenchmarkEngineeringLane.LIVE_GITHUB ||
        repository.trim().matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
        baseSha.trim().matches(Regex("[0-9a-fA-F]{40}"))
    val unavailableSelection = firstId != null && first == null || secondId != null && second == null
    val unavailableConnection = directActions && gitHubConnectionUid != null && selectedConnection == null
    val canStart = ready && !busy && first != null && second != null && first.uid != second.uid && liveTargetValid && (!directActions || connectionConfigured)
    val start: () -> Unit = {
        if (first != null && second != null && first.uid != second.uid) {
            firstId = first.uid
            secondId = second.uid
        }
        if (first != null &&
            second != null &&
            first.uid != second.uid &&
            viewModel.startEngineeringBenchmark(
                listOf(first.uid, second.uid),
                lane,
                repeats,
                ordering,
                strict,
                preset,
                repositoryFullName = repository.trim().takeIf { lane == BenchmarkEngineeringLane.LIVE_GITHUB },
                baseSha = baseSha.trim().takeIf { lane == BenchmarkEngineeringLane.LIVE_GITHUB },
                taskPack = selectedTaskPack,
                budget = budget,
                externalTransport = selectedTransport,
                githubConnectionUid = selectedConnection?.connectionUid.takeIf { directActions }
            )
        ) {
            comparisonId = null
            destination = 0
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EngineeringCard("Engineering comparisons") {
            Text("Choose the model that completes your work", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Compare matched tasks, fresh episodes and verified outcomes. Each evidence lane keeps its own results.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BenchmarkEngineeringLane.entries.forEach { option ->
                    FilterChip(lane == option, {
                        lane = option
                        comparisonId = null
                        runId = null
                        taskId = null
                        tasksShown = 30
                    }, label = { Text(option.label) }, enabled = !busy)
                }
            }
            Text(preview.qualification, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Overview" to Icons.Rounded.Assessment,
                "Compare" to Icons.AutoMirrored.Rounded.CompareArrows,
                "Tasks" to Icons.AutoMirrored.Rounded.FactCheck,
                "Evidence" to Icons.Rounded.Science,
                "History" to Icons.Rounded.History
            ).forEachIndexed { index, (title, icon) ->
                BenchmarkAction(title, icon, destination == index, { destination = index })
            }
        }
        pausedBatch?.let { batch ->
            EngineeringCard("Waiting for remote reconciliation") {
                Text(requireNotNull(batch.engineeringPauseReason), style = MaterialTheme.typography.bodyMedium)
                Text("Resume first checks the original remote request. Remaining tasks continue only after the service resolves that request.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { viewModel.resumeRemaining(batch.id) }, enabled = !busy && batch.canResume) { Text("Check remote work and resume") }
            }
        }
        when (destination) {
            0 -> {
                EngineeringCard("Matched run setup") {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 600.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) { EngineeringProfilePicker("Profile A", profiles, first, !busy) { firstId = it.uid } }
                                Column(Modifier.weight(1f)) { EngineeringProfilePicker("Profile B", profiles.filter { it.uid != first?.uid }, second, !busy) { secondId = it.uid } }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                EngineeringProfilePicker("Profile A", profiles, first, !busy) { firstId = it.uid }
                                EngineeringProfilePicker("Profile B", profiles.filter { it.uid != first?.uid }, second, !busy) { secondId = it.uid }
                            }
                        }
                    }
                    if (externalLane) {
                        Text("Task pack", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("coding" to "Python coding", "repository" to "Repository native").forEach { (value, title) ->
                                FilterChip(taskPack == value, {
                                    taskPack = value
                                    comparisonId = null
                                    runId = null
                                    taskId = null
                                }, label = { Text(title) }, enabled = !busy)
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val extents = if (selectedTaskPack == "repository") listOf("quick" to "Quick · 4", "standard" to "Standard · 8", "deep" to "Deep · 12") else listOf("quick" to "Coding Quick · 12", "standard" to "Standard · 36", "deep" to "Deep · 100")
                            extents.forEach { (value, title) ->
                                FilterChip(preset == value, { preset = value }, label = { Text(title) }, enabled = !busy)
                            }
                        }
                        Text(if (selectedTaskPack == "repository") "Repository native covers 12 distinct defects: compiled Kotlin state, SQLite schema migrations, and parsed workflow gates with executed shell steps. It requires a prepared native image. Quick and Standard report observations; Deep reaches the 12-family comparison threshold." else "Quick covers 12 problem families. Standard and Deep add seeded variants; Deep also includes four capstones. Variants are grouped when measuring uncertainty.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Fresh episodes per profile", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 5, 10).forEach { value -> FilterChip(repeats == value, { repeats = value }, label = { Text(value.toString()) }, enabled = !busy) }
                    }
                    Text("${preview.taskCount} tasks × $repeats fresh episodes = ${preview.totalTasksPerModel} tasks per model · ${preview.taskFamilies} task families", style = MaterialTheme.typography.bodyMedium)
                    Text("Predeclared task budget", style = MaterialTheme.typography.titleSmall)
                    Text("Deadline per task", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30).forEach { value -> FilterChip(deadlineMinutes == value, { deadlineMinutes = value }, label = { Text("$value min") }, enabled = !busy) }
                    }
                    Text("Output tokens per generation", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2048, 4096, 8192).forEach { value -> FilterChip(outputLimit == value, { outputLimit = value }, label = { Text("${value / 1024}k") }, enabled = !busy) }
                    }
                    Text("Repair attempts after first submission", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 2).forEach { value -> FilterChip(repairLimit == value, { repairLimit = value }, label = { Text(value.toString()) }, enabled = !busy) }
                    }
                    val plannedRounds = 2L * preview.totalTasksPerModel * budget.maxModelRounds
                    Text("Both profiles: ${2 * preview.totalTasksPerModel} planned task episodes; at most $plannedRounds model rounds and ${plannedRounds * outputLimit} generated tokens under the round caps. Each task allows ${budget.maxToolCalls} tool calls. Actual usage includes failed and repaired attempts; unknown pricing leaves cost unavailable.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        Checkbox(ordering == BenchmarkEngineeringOrdering.COUNTERBALANCED, { ordering = if (it) BenchmarkEngineeringOrdering.COUNTERBALANCED else BenchmarkEngineeringOrdering.SELECTED_ORDER }, enabled = !busy)
                        Column(Modifier.padding(top = 8.dp)) {
                            Text("Counterbalance model order")
                            Text("Default order is Profile A, then Profile B, for each episode.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row {
                        Checkbox(strict, { strict = it }, enabled = !busy)
                        Column(Modifier.padding(top = 8.dp)) {
                            Text("Use deterministic capability settings")
                            Text("Otherwise compare each profile's saved configuration. Requested and confirmed settings stay visible in Evidence.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (preview.requiresGateway) {
                        Text("Execution route", style = MaterialTheme.typography.titleSmall)
                        Text(if (lane == BenchmarkEngineeringLane.EXECUTABLE_CODING) "The selected profile generates the repair; the connected Gateway runs independent code checks in its prepared sandbox." else "The connected Gateway verifies the authorized disposable repository and records actual remote outcomes.", style = MaterialTheme.typography.bodySmall)
                        Text("Connect Gateway and prepare its coding environment in Settings before starting. A missing route is recorded as unavailable, with recovery guidance.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (lane == BenchmarkEngineeringLane.LIVE_GITHUB) {
                        Text("Live execution route", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                BenchmarkEngineeringExternalTransport.GATEWAY to "Gateway evaluator",
                                BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS to "Direct GitHub Actions"
                            ).forEach { (value, title) ->
                                FilterChip(externalTransport == value, {
                                    externalTransport = value
                                    comparisonId = null
                                    runId = null
                                    taskId = null
                                }, label = { Text(title) }, enabled = !busy)
                            }
                        }
                        if (directActions) {
                            EngineeringConnectionPicker(gitHubConnections, selectedConnection, !busy) { gitHubConnectionUid = it.connectionUid }
                            TextButton(onClick = viewModel::refreshEngineeringGitHubConnections, enabled = !busy) { Text("Refresh saved GitHub connections") }
                            Text("The phone uses this exact saved GitHub connection to publish and read back immutable fixture work. The pinned base must contain the provisioned .benchmark-phone/catalog.json fixture bank, and the Actions runner must be prepared before starting.", style = MaterialTheme.typography.bodySmall)
                            Text("This connection must allow repository content and workflow creation, Actions reads and cancellation. Actual account and repository access is checked before execution. Admitted Actions work continues remotely; restore the same connection to reconcile it.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { uriHandler.openUri(guideBase + "gateway/benchmarks/PHONE_ACTIONS.md") }) { Text("Open direct Actions setup guide") }
                            if (selectedTaskPack == "repository") Text("Repository native requires its pinned image on a prepared self-hosted Linux runner labeled gateway-native-benchmark.", style = MaterialTheme.typography.bodySmall)
                            if (gitHubConnections.isEmpty()) Text("Add a GitHub connection in Settings → Tools, then refresh this list.", style = MaterialTheme.typography.bodySmall)
                            if (selectedConnection != null && !connectionConfigured) Text("Review this connection in Settings → Tools. A saved credential and a policy allowing benchmark writes are required; account access is verified before execution.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (unavailableConnection) Text("The selected GitHub connection is unavailable. Explicitly reselect a saved connection; it will not be replaced automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedTextField(repository, { repository = it }, label = { Text("Disposable repository · owner/name") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
                        OutlinedTextField(baseSha, { baseSha = it }, label = { Text("Pinned base commit · 40 hexadecimal characters") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
                        Text("Both models start from this exact base. Repository authority and fixture identity are checked before admitting work.", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(start, Modifier.fillMaxWidth(), enabled = canStart) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Text(
                            if (unavailableSelection) {
                                "Reselect unavailable profile"
                            } else if (unavailableConnection) {
                                "Reselect GitHub connection"
                            } else {
                                "Start matched comparison"
                            },
                            Modifier.padding(start = 8.dp)
                        )
                    }
                    if (unavailableSelection) Text("A selected saved profile is no longer available. Select it again or explicitly choose another profile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (first != null && first.uid == second?.uid) Text("Choose two different saved profiles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (profiles.size < 2) Text("Save two AI profiles to compare them.", style = MaterialTheme.typography.bodySmall)
                    Text("General Quick 5 and Full 50 remain available in Queue. Changing a suite or policy starts a separate comparison.", style = MaterialTheme.typography.labelSmall)
                }
                selectedComparison?.let { EngineeringComparisonSummary(it) } ?: EngineeringEmpty("No matched result yet", "Saved contract checks can be inspected in History. A coding winner requires matched, independently verified task outcomes.")
            }
            1 -> {
                if (selectedComparison == null) EngineeringEmpty("No comparable pair", "Start a matched comparison from Overview. A single model's score cannot establish superiority.")
                selectedComparison?.let { comparison ->
                    EngineeringComparisonSummary(comparison)
                    focusedRuns.groupBy { it.profileUid }.forEach { (_, runs) ->
                        EngineeringCard(runs.first().profileName + " · effort across all episodes") {
                            Text("${runs.size} recorded fresh episodes · ${runs.sumOf { it.samples.size }} recorded task outcomes", style = MaterialTheme.typography.bodySmall)
                            runs.forEach { run ->
                                val efficiency = remember(run) { benchmarkTaskEfficiency(run) }
                                Text("Episode ${(run.engineeringRepeatIndex ?: 0) + 1} · ${run.state}", style = MaterialTheme.typography.titleSmall)
                                Text("All-attempt elapsed: ${benchmarkMetricText(efficiency.elapsedMs?.div(1000.0), "s", 2)}", style = MaterialTheme.typography.bodyMedium)
                                Text("API cost estimate: ${benchmarkApiCostText(efficiency.apiCostEstimateMicros?.toDouble(), efficiency.currency)}", style = MaterialTheme.typography.bodySmall)
                                Text("Input/output tokens: ${engineeringTokens(run.samples, true)} / ${engineeringTokens(run.samples, false)}", style = MaterialTheme.typography.bodySmall)
                                if (lane == BenchmarkEngineeringLane.LIVE_GITHUB) {
                                    Text("Includes recorded unsuccessful and repaired attempts. Missing usage or prices keeps cost unavailable. Remote CI and tool charges are separate unless recorded explicitly.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    BenchmarkTaskEfficiencyDetails(efficiency, expanded = true)
                                }
                            }
                        }
                    }
                    OutlinedButton(start, Modifier.fillMaxWidth(), enabled = canStart) { Text("New comparison with current profiles") }
                }
            }
            2 -> {
                EngineeringRunPicker(laneRuns, selectedRun) {
                    runId = it.id
                    taskId = null
                    tasksShown = 30
                }
                selectedRun?.let { run ->
                    EngineeringCard("${run.profileName} · tasks") {
                        Text("${run.samples.size}/${run.plannedTrials} recorded · ${run.state}", style = MaterialTheme.typography.titleSmall)
                        Row {
                            Checkbox(onlyFailures, {
                                onlyFailures = it
                                tasksShown = 30
                            })
                            Text("Show unsuccessful or unverified tasks", Modifier.padding(top = 12.dp))
                        }
                        val samples = run.samples.filter { !onlyFailures || it.outcome != BenchmarkOutcome.PASSED || it.engineeringVerification?.finalArtifactPassed == false }
                        samples.take(tasksShown).forEach { sample ->
                            HorizontalDivider()
                            Text(sample.label, style = MaterialTheme.typography.titleSmall)
                            Text("${sample.outcome} · ${sample.evidenceScope.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                            sample.engineeringVerification?.let { receipt ->
                                Text("First ${engineeringTruth(receipt.firstAttemptPassed)} · final ${engineeringTruth(receipt.finalArtifactPassed)} · best ${engineeringTruth(receipt.bestAttemptPassed)} · ${receipt.submittedAttempts} generation attempts", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Elapsed ${benchmarkMetricText((sample.endToEndDurationMs ?: sample.durationMs).div(1000.0), "s", 2)} · ${sample.toolCalls} tool calls", style = MaterialTheme.typography.bodySmall)
                            sample.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = {
                                taskId = sample.testId
                                destination = 3
                            }) { Text("Inspect task evidence") }
                        }
                        if (samples.size > tasksShown) TextButton(onClick = { tasksShown += 30 }) { Text("Load next ${minOf(30, samples.size - tasksShown)} tasks · ${samples.size - tasksShown} remaining") }
                        if (samples.isEmpty()) Text("No task matches this filter.")
                    }
                } ?: EngineeringEmpty("No task evidence", "Recorded tasks appear here as the comparison progresses.")
            }
            3 -> {
                EngineeringRunPicker(laneRuns, selectedRun) {
                    runId = it.id
                    taskId = null
                }
                selectedRun?.let { run ->
                    EngineeringRunEvidence(run, selectedTask)
                    if (selectedTask != null) TextButton(onClick = { taskId = null }) { Text("Show whole-run evidence") }
                    OutlinedButton(onExport, Modifier.fillMaxWidth(), enabled = ready) { Text("Export retained benchmark evidence") }
                } ?: EngineeringEmpty("No evidence selected", "Choose a recorded run in History or a task in Tasks.")
            }
            4 -> {
                EngineeringCard("Saved comparisons · ${lane.label}") {
                    if (laneComparisons.isEmpty()) Text("No saved matched comparisons in this lane.")
                    laneComparisons.forEach { comparison ->
                        Text(comparison.profileNames.joinToString(" vs "), style = MaterialTheme.typography.titleSmall)
                        Text("${comparison.label} · ${comparison.matchedTasks}/${comparison.plannedTasks} matched", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            comparisonId = comparison.cohortId
                            destination = 1
                        }) { Text("Open comparison") }
                        HorizontalDivider()
                    }
                }
                EngineeringCard("Individual runs · ${laneRuns.size}") {
                    laneRuns.take(tasksShown).forEach { run ->
                        Text("${run.profileName} · ${benchmarkResultDate(run.startedAt)}", style = MaterialTheme.typography.titleSmall)
                        Text("${run.state} · ${run.samples.size}/${run.plannedTrials} outcomes${if (run.engineeringPlan == null) " · legacy evidence; no matched cohort" else " · episode ${(run.engineeringRepeatIndex ?: 0) + 1}"}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            runId = run.id
                            taskId = null
                            destination = 2
                        }) { Text("Open tasks") }
                        HorizontalDivider()
                    }
                    if (laneRuns.size > tasksShown) TextButton(onClick = { tasksShown += 30 }) { Text("Load earlier runs") }
                    Text("Historical personal best stays available in Results. Comparison recommendations use the complete matched cohort, including failed attempts.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EngineeringComparisonSummary(comparison: BenchmarkEngineeringComparison) {
    EngineeringCard(comparison.label) {
        Text(comparison.reason, style = MaterialTheme.typography.bodyMedium)
        Text("${comparison.matchedTasks}/${comparison.plannedTasks} matched tasks · ${comparison.uniqueFamilies} task families", style = MaterialTheme.typography.bodySmall)
        comparison.profileUids.forEachIndexed { index, uid ->
            val resolved = comparison.resolvedCounts.getOrNull(index) ?: 0
            val eligible = comparison.eligibleCounts.getOrNull(index) ?: 0
            Text(comparison.profileNames.getOrNull(index) ?: uid, style = MaterialTheme.typography.titleSmall)
            Text("$resolved verified completions / $eligible eligible · ${comparison.plannedTasks} planned", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { if (comparison.plannedTasks > 0) resolved.toFloat() / comparison.plannedTasks else 0f }, modifier = Modifier.fillMaxWidth(), color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            if (comparison.lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB)) {
                Text("First submission ${comparison.firstAttemptCounts.getOrNull(index) ?: 0} · final artifact ${comparison.finalArtifactCounts.getOrNull(index) ?: 0} · best within budget ${comparison.bestAttemptCounts.getOrNull(index) ?: 0} · receipt inconsistencies ${comparison.falseCompletionCounts.getOrNull(index) ?: 0}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Family-weighted completion difference: ${benchmarkMetricDeltaText(comparison.deltaPercentagePoints, "percentage points")}", style = MaterialTheme.typography.bodyMedium)
        Text("95% interval: ${benchmarkMetricText(comparison.lower95, "")} to ${benchmarkMetricText(comparison.upper95, "")} · practical margin ${comparison.practicalMarginPercentagePoints} points", style = MaterialTheme.typography.bodySmall)
        Text(if (comparison.settingsVerified) "Effective request settings verified" else "Effective request settings are not fully confirmed", style = MaterialTheme.typography.labelMedium)
        if (comparison.excludedPairs.isNotEmpty()) Text("Unmatched evidence: ${comparison.excludedPairs.entries.joinToString { "${it.key.replace('_', ' ')} (${it.value})" }}", style = MaterialTheme.typography.bodySmall)
        Text("Interpretation applies to this suite, route, task set and budget. Faster incorrect answers do not win. Repeated variants are grouped by family.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EngineeringRunEvidence(run: BenchmarkRun, selectedTask: BenchmarkSample?) {
    val samples = selectedTask?.let(::listOf) ?: run.samples
    EngineeringCard(selectedTask?.label ?: "${run.profileName} · execution evidence") {
        Text("${run.provider} · ${run.model}\nRun ${run.id}\nApp ${run.appCommit.ifBlank { "Unknown" }}", style = MaterialTheme.typography.bodySmall)
        run.engineeringPlan?.let { plan ->
            Text("${plan.lane.label} · ${plan.ordering} · ${plan.repeats} fresh episodes\nTask manifest ${plan.fixtureManifestHash}\nGrader ${plan.graderDigest ?: "Not recorded"}\nCohort ${plan.cohortId}", style = MaterialTheme.typography.bodySmall)
            Text("Pack: ${plan.taskPack} · budget: ${plan.budget.taskDeadlineMs / 1000} s per task · ${plan.budget.outputTokensPerGeneration} output tokens per generation · ${plan.budget.maxToolCalls} tool calls · ${plan.budget.maxModelRounds} model rounds · ${plan.budget.maxRepairAttempts} repairs", style = MaterialTheme.typography.bodySmall)
            Text("Transport: ${plan.externalTransport} · GitHub connection: ${plan.githubConnectionUid ?: "Gateway-owned route"}", style = MaterialTheme.typography.bodySmall)
            plan.repositoryFullName?.let { Text("Repository $it\nPinned base ${plan.baseSha ?: "Unknown"}", style = MaterialTheme.typography.bodySmall) }
        }
        Text("Effective settings: ${samples.count { it.effectiveSettingsConfirmed }}/${samples.size} confirmed", style = MaterialTheme.typography.bodyMedium)
        val buffered = samples.count { it.settingsReceipt?.requested?.streaming == true && it.deliveryMode == "buffered" }
        if (buffered > 0) Text("$buffered responses requested streaming and were observed as buffered. Buffered first-response latency is distinct from token-stream latency.", style = MaterialTheme.typography.bodySmall)
        Text("Observed delivery: ${samples.groupingBy { it.deliveryMode }.eachCount().entries.joinToString { "${it.key} (${it.value})" }}", style = MaterialTheme.typography.bodySmall)
        Text("Input/output tokens: ${engineeringTokens(samples, true)} / ${engineeringTokens(samples, false)}", style = MaterialTheme.typography.bodySmall)
        val phases = samples.flatMap { it.phaseElapsedMs.entries }.groupBy { it.key }.mapValues { (_, values) -> values.sumOf { it.value } }
        phases.forEach { (phase, duration) -> Text("${phase.replace('_', ' ')}: ${benchmarkMetricText(duration / 1000.0, "s", 2)}", style = MaterialTheme.typography.bodySmall) }
        if (phases.isEmpty()) Text("Phase timing not recorded.", style = MaterialTheme.typography.bodySmall)
        selectedTask?.let { sample ->
            sample.settingsReceipt?.let { receipt -> Text("Requested temperature ${receipt.requested.temperature ?: "Default"} · reasoning ${receipt.requested.reasoning} · output limit ${sample.effectiveRequestOutputTokens ?: receipt.requested.outputTokens ?: "Unknown"}\nAcknowledged fields ${receipt.acknowledgedFields.joinToString().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall) }
            sample.engineeringVerification?.let { receipt ->
                if (receipt.verificationScope == "LOCAL_GENERATION_CONTRACT") Text("The observed response violated its output contract. No remote code check ran for this attempt.", style = MaterialTheme.typography.bodySmall)
                Text("Receipt ${receipt.receiptId}\nFixture ${receipt.fixtureDigest}\nCandidate ${receipt.candidateDigest ?: "Unknown"}\nEvidence ${receipt.evidenceDigest}", style = MaterialTheme.typography.bodySmall)
                Text("Hidden checks ${engineeringTruth(receipt.hiddenChecksPassed)} · regressions ${engineeringTruth(receipt.regressionPassed)} · integrity ${engineeringTruth(receipt.integrityPassed)}\nIndependent checks executed ${receipt.independentChecksExecuted} · required checks known ${receipt.requiredChecksKnown}", style = MaterialTheme.typography.bodySmall)
                if (run.engineeringPlan?.lane == BenchmarkEngineeringLane.LIVE_GITHUB) Text("Remote readback ${engineeringTruth(receipt.remoteReadbackVerified)}\nTested ${receipt.testedRevision ?: "Unknown"}\nPublished ${receipt.publishedRevision ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                receipt.reasonCode?.let { Text("${receipt.failureDomain}: $it", style = MaterialTheme.typography.bodySmall) }
                receipt.remoteEvidence?.let { remote ->
                    Text("GitHub run ${remote["workflowRunId"] ?: "Unknown"} · attempt ${remote["workflowRunAttempt"] ?: "Unknown"}\nArtifact ${remote["artifactId"] ?: "Not recorded"}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("${sample.toolCalls} attempted tool calls · ${sample.successfulToolCalls} successful\n${sample.rounds.size} model rounds · ${sample.physicalRequestIds.size} physical requests", style = MaterialTheme.typography.bodySmall)
            sample.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            sample.failure?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
        }
        Text("Unknown measurements stay unknown. Export retains the underlying receipts and all recorded attempts.", style = MaterialTheme.typography.labelSmall)
    }
}

private fun engineeringTruth(value: Boolean?): String = when (value) {
    true -> "passed"
    false -> "failed"
    null -> "unknown"
}

private fun engineeringTokens(samples: List<BenchmarkSample>, input: Boolean): String {
    if (samples.isEmpty()) return "Unknown"
    val complete = samples.all { sample ->
        if (input) {
            sample.inputTokenConfidence == "final_reported"
        } else {
            !sample.estimatedTokens && sample.outputTokenConfidence == "final_reported"
        }
    }
    return if (complete) samples.sumOf { if (input) it.inputTokens.toLong() else it.outputTokens.toLong() }.toString() else "Unknown / incomplete"
}

@Composable
private fun EngineeringProfilePicker(title: String, profiles: List<PlatformV2>, selected: PlatformV2?, enabled: Boolean, onSelect: (PlatformV2) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled && profiles.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                Text(selected?.name ?: "Choose profile", fontWeight = FontWeight.SemiBold)
                selected?.let { Text("${it.compatibleType.name} · ${it.model}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(text = {
                    Column {
                        Text(profile.name)
                        Text("${profile.compatibleType.name} · ${profile.model}", style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = {
                    expanded = false
                    onSelect(profile)
                })
            }
        }
    }
}

@Composable
private fun EngineeringConnectionPicker(connections: List<ToolConnection>, selected: ToolConnection?, enabled: Boolean, onSelect: (ToolConnection) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text("Saved GitHub connection", style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled && connections.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                Text(selected?.name ?: "Choose saved GitHub connection", fontWeight = FontWeight.SemiBold)
                selected?.let { Text("${it.alias} · ${it.connectionUid}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            connections.forEach { connection ->
                DropdownMenuItem(text = {
                    Column {
                        Text(connection.name)
                        Text("${connection.alias} · ${connection.connectionUid}", style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = {
                    expanded = false
                    onSelect(connection)
                })
            }
        }
    }
}

@Composable
private fun EngineeringRunPicker(runs: List<BenchmarkRun>, selected: BenchmarkRun?, onSelect: (BenchmarkRun) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = runs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(selected?.let { "${it.profileName} · ${benchmarkResultDate(it.startedAt)}" } ?: "Choose recorded run") }
        DropdownMenu(expanded, { expanded = false }) {
            runs.forEach { run ->
                DropdownMenuItem(text = { Text("${run.profileName} · ${benchmarkResultDate(run.startedAt)} · ${run.state}") }, onClick = {
                    expanded = false
                    onSelect(run)
                })
            }
        }
    }
}

@Composable
private fun EngineeringEmpty(title: String, detail: String) = EngineeringCard(title) { Text(detail, style = MaterialTheme.typography.bodyMedium) }

@Composable
private fun EngineeringCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
