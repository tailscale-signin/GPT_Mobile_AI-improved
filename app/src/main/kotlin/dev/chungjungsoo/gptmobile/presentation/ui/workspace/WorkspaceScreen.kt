package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.chungjungsoo.gptmobile.data.accounting.ModelPrice
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.workspace.ContextExclusions
import dev.chungjungsoo.gptmobile.data.workspace.ContextReceipt
import dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskHandle
import dev.chungjungsoo.gptmobile.data.workspace.ResearchPin
import dev.chungjungsoo.gptmobile.data.workspace.TaskRecipe
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import java.util.Date
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(onBack: () -> Unit, onChat: (Int) -> Unit, initialChat: Int = 0, initialTab: String = "Tasks", initialRun: String = "", model: WorkspaceViewModel = hiltViewModel()) {
    val records by model.records.collectAsState()
    val requestReceipts by model.requestReceipts.collectAsState()
    LaunchedEffect(initialRun) { model.inspectedRun.value = initialRun }
    val runs by model.runs.collectAsState()
    val benchmarkHistory by model.benchmarks.history.collectAsState()
    val evaluating by model.evaluating.collectAsState()
    val pending by model.pending.collectAsState()
    val approvals by model.approvals.collectAsState()
    val chats by model.chats.collectAsState()
    val selectedChat by model.chatId.collectAsState()
    val events by model.events.collectAsState()
    val comparison by model.comparison.collectAsState()
    val notice by model.notice.collectAsState()
    val inputs by model.interactions.pending.collectAsState()
    var tab by remember { mutableStateOf(initialTab) }
    var selectedContext by remember { mutableStateOf<WorkspaceRecord?>(null) }
    var editingRecipe by remember { mutableStateOf<WorkspaceRecord?>(null) }
    var createRecipe by remember { mutableStateOf(false) }
    var evidenceEdit by remember { mutableStateOf<dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent?>(null) }
    LaunchedEffect(initialChat, chats) { if (initialChat > 0) model.chatId.value = initialChat }
    inputs.firstOrNull()?.let { dev.chungjungsoo.gptmobile.presentation.ui.chat.McpInputDialog(it) { _, value -> model.interactions.respond(it.id, value) } }
    Scaffold(topBar = { TopAppBar(title = { Text("Workspaces") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tasks", "Context", "Evidence", "Branches", "Recipes", "Models", "Budgets", "GitHub", "Plugins").forEach { name -> FilterChip(tab == name, { tab = name }, label = { Text(name) }) }
            }
            notice?.let { TextButton(onClick = { model.notice.value = null }) { Text(it) } }
            if (tab in setOf("Context", "Evidence", "Branches", "Recipes")) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chats.forEach { chat -> FilterChip(selectedChat == chat.id, { model.chatId.value = chat.id }, label = { Text(chat.title.take(35)) }) }
                }
            }
            when (tab) {
                "GitHub" -> GitHubReviewPanel()
                "Plugins" -> PluginConfigurationPanel()
                "Budgets" -> BudgetPanel(model)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (tab) {
                        "Tasks" -> {
                            item { Text("Queue, model runs and remote tasks", style = MaterialTheme.typography.titleLarge) }
                            if (pending.isEmpty() && runs.isEmpty()) item { Text("No tasks yet. Requests appear here when sent.") }
                            items(approvals, key = { "approval:${it.id}" }) { approval ->
                                WorkspaceCard("${approval.tool} · ${if (approval.state == "PENDING") "Waiting for approval" else "Outcome unknown / interrupted"}") {
                                    Text(approval.argumentPreview.take(1000))
                                    Text("Inspect the conversation and remote state before retrying. This action is not replayed automatically.", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { model.action { workspaceRunChat(model, approval.runId)?.let(onChat) } }) { Text("Review in conversation") }
                                }
                            }
                            items(pending, key = { "queue:${it.id}" }) { prompt ->
                                WorkspaceCard(if (prompt.paused) "Paused question" else "Queued question") {
                                    Text(prompt.text.take(600))
                                    val configuration = prompt.details()
                                    Text("${configuration.profileUids.joinToString { uid -> model.profiles.value.firstOrNull { it.uid == uid }?.name ?: uid }} · ${if (configuration.localOnly) "On-device only" else "Selected provider"}", style = MaterialTheme.typography.labelSmall)
                                    Text("May join a compatible delegation turn at its next request boundary; otherwise sends as a separate question.", style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton(onClick = { model.action { model.queue.pause(prompt.id, !prompt.paused) } }) { Text(if (prompt.paused) "Resume" else "Pause") }
                                        TextButton(onClick = { model.action { model.queue.remove(prompt.id) } }) { Text("Remove") }
                                        TextButton(onClick = { onChat(prompt.chatId) }) { Text("Open") }
                                    }
                                }
                            }
                            items(records.filter { it.kind == "remote" }, key = { it.id }) { entry ->
                                val task = remember(entry.payload) { model.workspace.json.decodeFromString<RemoteTaskHandle>(entry.payload) }
                                WorkspaceCard(entry.title) {
                                    Text(task.task["taskId"]?.jsonPrimitive?.content.orEmpty(), style = MaterialTheme.typography.labelSmall)
                                    task.task["statusMessage"]?.jsonPrimitive?.content?.let { Text(it.take(500)) }
                                    if (task.cancellationRequested) Text("Cancellation requested; outcome awaits the server.")
                                    Row {
                                        TextButton(onClick = { model.remote(entry) }) { Text("Refresh") }
                                        TextButton(onClick = { model.remote(entry, answer = true) }) { Text("Continue saved task") }
                                        TextButton(onClick = { model.remote(entry, cancel = true) }) { Text("Cancel") }
                                    }
                                    task.task["result"]?.let { SelectionContainer { Text(it.toString().take(6000)) } }
                                }
                            }
                            items(runs, key = { it.runId }) { run ->
                                WorkspaceCard("${run.modelSnapshot} · ${run.status}") {
                                    run.terminalError?.let { Text(it.take(400)) }
                                    if (run.status == "INTERRUPTED") Text(if (run.gatewayJobId != null) "A saved remote job may be recoverable." else "Open the conversation to review evidence before starting another response.", style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton(onClick = { onChat(run.chatId) }) { Text("Open conversation") }
                                        if (run.status in setOf("RUNNING", "QUEUED")) TextButton(onClick = { model.coordinator.cancelChat(run.chatId) }) { Text("Stop") }
                                    }
                                }
                            }
                        }
                        "Context" -> {
                            item {
                                Text("Actual assembled requests", style = MaterialTheme.typography.titleLarge)
                                Text("Immutable request receipts. Token counts are estimates; provider-side changes are outside this record.", style = MaterialTheme.typography.bodySmall)
                            }
                            item {
                                TextButton(onClick = {
                                    selectedChat?.let { id ->
                                        model.action { model.workspace.exclude(id, ContextExclusions()) }
                                        model.notice.value = "Exclusions cleared for future requests."
                                    }
                                }) { Text("Clear next-request exclusions") }
                            }
                            items(if (initialRun.isNotBlank()) requestReceipts else records.filter { it.kind == "context" && it.chatId == selectedChat }, key = { it.id }) { entry ->
                                WorkspaceCard(entry.title) {
                                    Text(Date(entry.updatedAt).toString())
                                    Text("Response run: ${entry.runId}", style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { selectedContext = entry }) { Text("Inspect request") }
                                }
                            }
                        }
                        "Evidence" -> {
                            item {
                                Text("Retained research evidence", style = MaterialTheme.typography.titleLarge)
                                Text("Search snippets and tool results keep their original event and time. Refreshing creates a new request.", style = MaterialTheme.typography.bodySmall)
                            }
                            items(records.filter { it.kind == "evidence" && it.chatId == selectedChat }, key = { it.id }) { entry ->
                                val pin = remember(entry.payload) { model.workspace.json.decodeFromString<ResearchPin>(entry.payload) }
                                WorkspaceCard("Pinned · ${entry.title}") {
                                    SelectionContainer { Text("${pin.url}\n${pin.kind} · ${Date(pin.retrievedAt)}\n${pin.excerpt}\nClaim: ${pin.claim}") }
                                    TextButton(onClick = { model.draft("Refresh the evidence at ${pin.url}. Verify this claim: ${pin.claim}. Keep new evidence separate from earlier findings.", onChat) }) { Text("Review refresh request") }
                                    TextButton(onClick = { model.delete(entry) }) { Text("Unpin") }
                                }
                            }
                            items(events.sortedByDescending { it.startedAt }, key = { it.eventId }) { event ->
                                WorkspaceCard("${event.toolName} · ${event.status}") {
                                    Text(Date((event.completedAt ?: event.startedAt ?: 0) * if ((event.startedAt ?: 0) < 100000000000L) 1000 else 1).toString(), style = MaterialTheme.typography.labelSmall)
                                    SelectionContainer { Text((event.result ?: event.error ?: "No retained result").take(5000)) }
                                    if (event.result?.length ?: 0 > 5000) Text("Preview truncated; full retained result remains in the conversation tool trace.")
                                    TextButton(onClick = { evidenceEdit = event }) { Text("Pin excerpt & source") }
                                    if (event.isError) TextButton(onClick = { model.draft("Continue the interrupted research using retained evidence. Revisit only failed read-only sources; do not repeat completed writes. Failed tool: ${event.toolName}", onChat) }) { Text("Continue from evidence") }
                                }
                            }
                        }
                        "Branches" -> {
                            item {
                                Text("Conversation alternatives", style = MaterialTheme.typography.titleLarge)
                                Text("Editing a user message creates a branch. Compare the latest 120 messages from each path; originals remain intact.", style = MaterialTheme.typography.bodySmall)
                            }
                            val selected = chats.firstOrNull { it.id == selectedChat }
                            val related = chats.filter { it.id != selectedChat && (it.parentChatId == selectedChat || it.id == selected?.parentChatId || it.parentChatId != null && it.parentChatId == selected?.parentChatId) }
                            items(related, key = { "branch:${it.id}" }) { branch ->
                                WorkspaceCard(branch.title) {
                                    Text("Parent #${branch.parentChatId ?: "root"} · fork at message #${branch.branchMessageId ?: "—"}")
                                    Row {
                                        TextButton(onClick = { onChat(branch.id) }) { Text("Open path") }
                                        TextButton(onClick = { model.compare(branch.id) }) { Text("Compare paths") }
                                    }
                                }
                            }
                            comparison?.let { (left, right) ->
                                items(maxOf(left.size, right.size)) { index ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(left.getOrNull(index), right.getOrNull(index)).forEach { message ->
                                            Card(Modifier.weight(1f)) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Text(message?.platformType ?: "User", style = MaterialTheme.typography.labelSmall)
                                                    SelectionContainer { Text(message?.content?.take(2000).orEmpty()) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "Recipes" -> {
                            item {
                                Text("Reviewed, reusable tasks", style = MaterialTheme.typography.titleLarge)
                                Text("Manual launch creates a paused task for review with its saved profile. Optional scheduled runs disable all tools and delegation; they never guarantee an exact time.", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { createRecipe = true }) { Text("Create recipe") }
                            }
                            items(records.filter { it.kind == "recipe" && it.chatId == selectedChat }, key = { it.id }) { entry ->
                                val recipe = remember(entry.payload) { model.workspace.json.decodeFromString<TaskRecipe>(entry.payload) }
                                WorkspaceCard(entry.title) {
                                    Text(recipe.prompt)
                                    Text("${recipe.outputFormat} · ${if (recipe.localOnly) "On-device only" else "Selected provider"}${if (recipe.scheduled) " · every ${recipe.intervalHours}h, deferrable" else " · manual"}", style = MaterialTheme.typography.bodySmall)
                                    if (recipe.lastQueuedAt > 0) Text("Last queued: ${Date(recipe.lastQueuedAt)}")
                                    Row {
                                        TextButton(onClick = { model.reviewRecipe(entry) }) { Text("Prepare paused task") }
                                        TextButton(onClick = { editingRecipe = entry }) { Text("Edit") }
                                        TextButton(onClick = { model.delete(entry) }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                        "Models" -> {
                            item {
                                Text("Measured profile guidance", style = MaterialTheme.typography.titleLarge)
                                Text("Compare the same suite on this device. Results describe measured runs, not guarantees for future tasks.", style = MaterialTheme.typography.bodySmall)
                            }
                            item { Button(enabled = !evaluating, onClick = { model.evaluateMemory() }) { Text("Evaluate local memory · 4K / 16K") } }
                            items(records.filter { it.kind == "memory_benchmark" }.take(5), key = { it.id }) { entry ->
                                val result = remember(entry.payload) { model.workspace.json.decodeFromString<dev.chungjungsoo.gptmobile.data.rag.MemoryQualityReport>(entry.payload) }
                                WorkspaceCard(entry.title) {
                                    Text("${result.engine} · ${result.passedGuards}/${result.totalGuards} extraction, privacy, language and scope guards")
                                    result.samples.forEach { sample -> Text("${sample.facts} facts · ${sample.correctRetrievals}/${sample.queries} exact-code recall · p95 ${"%.2f".format(sample.p95RecallMillis)} ms · client PSS ${sample.clientPssKiB / 1024} MiB") }
                                    Text("Synthetic lexical fixtures; does not establish semantic paraphrase quality.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            val eligible = benchmarkHistory.filter { it.finished && !it.canceled && it.samples.isNotEmpty() }.sortedByDescending { it.startedAt }.distinctBy { it.profileUid to it.mode }
                            if (eligible.isEmpty()) item { Text("Run a profile benchmark in Debug & statistics to receive measured guidance.") }
                            items(eligible, key = { it.id }) { run ->
                                val passed = run.samples.count { it.outcome == BenchmarkOutcome.PASSED }
                                val times = run.samples.mapNotNull { it.firstTextMs }.sorted()
                                WorkspaceCard("${run.profileName} · ${run.mode}") {
                                    Text("Passed $passed/${run.samples.size} · suite ${run.suiteVersion}")
                                    Text("${run.device} · ${run.backend ?: run.provider} · ${run.accelerator.orEmpty()}")
                                    if (times.isNotEmpty()) Text("Median first text: ${times[times.size / 2]} ms")
                                    run.peakClientPssKb?.let { Text("Peak client PSS: ${it / 1024} MiB") }
                                    Text(
                                        when {
                                            run.thermalAfter != null && run.thermalAfter >= 3 -> "Thermal pressure observed. Try fewer threads or a smaller model, then benchmark again."
                                            passed < run.samples.size -> "Resolve failed fixtures before choosing this profile for similar tasks."
                                            run.samples.size < 3 -> "More samples are needed for a reliable recommendation."
                                            else -> "Passed measured fixtures. Compare latency and memory against another run of this suite."
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedContext?.let { entry ->
        var receipt by remember(entry.payload) { mutableStateOf(model.workspace.json.decodeFromString<ContextReceipt>(entry.payload)) }
        LaunchedEffect(entry.id) {
            try {
                receipt = model.workspace.readReceipt(entry)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                model.notice.value = "Protected context is unavailable. Request metadata is still shown."
            }
        }
        AlertDialog(onDismissRequest = { selectedContext = null }, title = { Text("Request context") }, text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("${receipt.model}\n${receipt.notice}\nHistory message IDs: ${receipt.messageIds.joinToString()}\nOutput cap: ${receipt.outputLimit ?: "provider default"}\nReasoning override: ${receipt.reasoning ?: "profile default"}\nDelegated preparation: ${receipt.delegation}") }
                item { Text("Tools: ${receipt.tools.joinToString().ifEmpty { "None" }}") }
                items(receipt.facts.toList()) { (id, fact) ->
                    Text(fact)
                    TextButton(onClick = { model.exclude(entry, fact = id) }) { Text("Exclude this memory next time") }
                }
                items(receipt.attachments) { path ->
                    Text(java.io.File(path).name)
                    TextButton(onClick = { model.exclude(entry, attachment = path) }) { Text("Exclude attachment next time") }
                }
                if (receipt.documents.isNotBlank()) {
                    item {
                        SelectionContainer { Text(receipt.documents) }
                        TextButton(onClick = { model.exclude(entry, documents = true) }) { Text("Exclude document recall next time") }
                    }
                }
                item { Text("System SHA-256: ${receipt.systemDigest}\nRequest SHA-256: ${receipt.requestDigest}", style = MaterialTheme.typography.labelSmall) }
            }
        }, confirmButton = { TextButton(onClick = { selectedContext = null }) { Text("Done") } })
    }
    if (createRecipe || editingRecipe != null) {
        RecipeEditor(model, editingRecipe) {
            createRecipe = false
            editingRecipe = null
        }
    }
    evidenceEdit?.let { event ->
        var url by remember(event) { mutableStateOf(Regex("https?://[^\\s\"<>]+").find(event.result.orEmpty())?.value.orEmpty()) }
        var excerpt by remember(event) { mutableStateOf(event.result.orEmpty().take(2000)) }
        var claim by remember(event) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { evidenceEdit = null }, title = { Text("Pin evidence") }, text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("Source URL") })
                OutlinedTextField(excerpt, { excerpt = it }, label = { Text("Observed excerpt") }, maxLines = 5)
                OutlinedTextField(claim, { claim = it }, label = { Text("Claim supported or questioned") })
            }
        }, confirmButton = {
            TextButton(onClick = {
                model.pin(event, url, excerpt, claim)
                evidenceEdit = null
            }) { Text("Pin") }
        }, dismissButton = { TextButton(onClick = { evidenceEdit = null }) { Text("Cancel") } })
    }
}

@Composable
internal fun WorkspaceCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RecipeEditor(model: WorkspaceViewModel, entry: WorkspaceRecord?, dismiss: () -> Unit) {
    val old = remember(entry) { entry?.let { model.workspace.json.decodeFromString<TaskRecipe>(it.payload) } }
    val profiles by model.profiles.collectAsState()
    var title by remember { mutableStateOf(entry?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(old?.prompt ?: "Summarize the documents in this project. Cite retained sources and identify unresolved questions.") }
    var format by remember { mutableStateOf(old?.outputFormat ?: "A concise, sourced brief") }
    var uid by remember { mutableStateOf(old?.profileUid ?: profiles.firstOrNull { it.compatibleType == ClientType.LITERT_LM }?.uid.orEmpty()) }
    var local by remember { mutableStateOf(old?.localOnly ?: true) }
    var schedule by remember { mutableStateOf(old?.scheduled ?: false) }
    var hours by remember { mutableStateOf(old?.intervalHours?.toFloat() ?: 24f) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Task recipe") }, text = {
        LazyColumn {
            item {
                OutlinedTextField(title, { title = it }, label = { Text("Name") })
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Task & allowed inputs") }, maxLines = 5)
                OutlinedTextField(format, { format = it }, label = { Text("Output format") })
                Row {
                    Text("On-device only", Modifier.weight(1f))
                    Switch(local, { local = it })
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) { profiles.filter { it.enabled }.forEach { profile -> FilterChip(uid == profile.uid, { uid = profile.uid }, label = { Text(profile.name) }) } }
                Row {
                    Text("Schedule read-only generation", Modifier.weight(1f))
                    Switch(schedule, { schedule = it })
                }
                if (schedule) {
                    Text("Every ${hours.toInt()} hours · battery-aware")
                    Slider(hours, { hours = it }, valueRange = 1f..168f, steps = 166)
                    Text("All tools and delegation are disabled. Remote runs require a price, spending limit and output cap.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            model.saveRecipe(entry?.id, title, TaskRecipe(prompt, uid, format, local, schedule, hours.toLong(), old?.lastQueuedAt ?: 0))
            dismiss()
        }) { Text("Save recipe") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun BudgetPanel(model: WorkspaceViewModel) {
    val features by model.features.collectAsState()
    val profiles by model.profiles.collectAsState()
    val spending by model.spending.collectAsState()
    var currency by remember(features.spendBudget.currency) { mutableStateOf(features.spendBudget.currency) }
    var turn by remember(features.spendBudget.perTurnMicros) { mutableStateOf((features.spendBudget.perTurnMicros / 1000000.0).toString()) }
    var daily by remember(features.spendBudget.perDayMicros) { mutableStateOf((features.spendBudget.perDayMicros / 1000000.0).toString()) }
    var uid by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    fun micros(text: String): Long = text.toBigDecimal().multiply(java.math.BigDecimal(1000000)).longValueExact().also { require(it >= 0) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            WorkspaceCard("Spending allowances") {
                Text("Estimated model costs, not an invoice. Active requests reserve their maximum estimated cost. Unknown/failed usage retains its reservation. Prices expire after 30 days; tool fees are not included.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, label = { Text("Currency") })
                OutlinedTextField(turn, { turn = it }, label = { Text("Per turn · 0 disables") })
                OutlinedTextField(daily, { daily = it }, label = { Text("Per UTC day · 0 disables") })
                Button(onClick = {
                    model.action {
                        val current = model.settings.getFeatureSettings()
                        val budget = current.spendBudget.copy(currency = currency, perTurnMicros = micros(turn), perDayMicros = micros(daily), prices = if (currency == current.spendBudget.currency) current.spendBudget.prices else emptyMap())
                        budget.validate()
                        model.settings.updateFeatureSettings(current.copy(spendBudget = budget))
                        model.notice.value = "Allowances saved. A finite output cap is required for remote spending reservations."
                    }
                }) { Text("Save allowances") }
            }
        }
        item {
            WorkspaceCard("Recorded model spend") {
                Text("Recent 10,000 invocations · reservations and settled estimates. Daily enforcement uses the full ledger.", style = MaterialTheme.typography.bodySmall)
                spending.groupBy { it.currency ?: "Unknown currency" }.forEach { (unit, rows) ->
                    val known = rows.mapNotNull { it.costMicros }.sum() / 1000000.0
                    Text("$unit ${"%.4f".format(known)} · ${rows.count { it.costMicros == null }} unknown-price requests")
                    rows.groupBy { it.profileUid?.let { uid -> profiles.firstOrNull { it.uid == uid }?.name } ?: it.provider }.forEach { (name, requests) ->
                        Text("$name · ${requests.groupBy { it.kind }.entries.joinToString { (role, items) -> "$role: ${"%.4f".format(items.mapNotNull { it.costMicros }.sum() / 1000000.0)}" }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            WorkspaceCard("Model price · per million tokens") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    profiles.forEach { profile ->
                        FilterChip(uid == profile.uid, {
                            uid = profile.uid
                            val price = features.spendBudget.prices[uid]
                            input = price?.let { (it.inputMicrosPerMillion / 1000000.0).toString() }.orEmpty()
                            output = price?.let { (it.outputMicrosPerMillion / 1000000.0).toString() }.orEmpty()
                            source = price?.source.orEmpty()
                        }, label = { Text(profile.name) })
                    }
                }
                OutlinedTextField(input, { input = it }, label = { Text("Input price (${features.spendBudget.currency})") })
                OutlinedTextField(output, { output = it }, label = { Text("Output price") })
                OutlinedTextField(source, { source = it }, label = { Text("Price source URL or contract reference") })
                Button(onClick = {
                    model.action {
                        val current = model.settings.getFeatureSettings()
                        val profile = profiles.first { it.uid == uid }
                        val price = ModelPrice(profile.model, micros(input), micros(output), source, System.currentTimeMillis())
                        val budget = current.spendBudget.copy(prices = current.spendBudget.prices + (uid to price))
                        budget.validate()
                        model.settings.updateFeatureSettings(current.copy(spendBudget = budget))
                        model.notice.value = "Price saved for ${profile.model}. Estimates exclude cache discounts and tool charges."
                    }
                }) { Text("Confirm price") }
            }
        }
    }
}

private suspend fun workspaceRunChat(model: WorkspaceViewModel, runId: String): Int? = model.workspace.database.agentRunDao().getById(runId)?.chatId
