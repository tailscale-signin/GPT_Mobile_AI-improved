package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class GitHubActivityItem(val id: String, val time: Long, val title: String, val evidence: String, val url: String = "")

@HiltViewModel
internal class GitHubActivityViewModel @Inject constructor(private val workspace: WorkspaceRepository, private val messages: MessageV2Dao, private val auth: GitHubPublicAuthCoordinator, private val ledger: dev.chungjungsoo.gptmobile.data.accounting.InvocationLedger, private val database: dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2) : ViewModel() {
    private val mutable = MutableStateFlow<List<GitHubActivityItem>>(emptyList())
    val items = mutable.asStateFlow()
    private val mutableNotice = MutableStateFlow("History has not been loaded")
    val notice = mutableNotice.asStateFlow()
    private val mutableMeasurements = MutableStateFlow<List<String>>(emptyList())
    val measurements = mutableMeasurements.asStateFlow()

    fun load(connection: ToolConnection, selection: GitHubRepositoryContext, chatId: Int) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val token = auth.accessToken(connection)
            GitHubWorkspaceClient(token, apiOrigins = dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins.fromEndpoint(connection.endpointUrl)).execute(
                "get_repository",
                buildJsonObject {
                    put("owner", selection.owner)
                    put("repo", selection.repo)
                }
            )
            val invocations = ledger.recent.first().filter { it.turnKey.substringBefore(':') == chatId.toString() }
            val runs = database.agentRunDao().rawObserveRecent(100).first().filter { it.chatId == chatId }
            val terminal = runs.filter { it.status in dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus.terminal }
            val firstVisible = invocations.mapNotNull { it.firstTokenMs }
            val measuredInput = invocations.filter { dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(it.inputTokenSource) }.mapNotNull { it.observedInputTokens }
            val measuredOutput = invocations.filter { dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(it.outputTokenSource) }.mapNotNull { it.observedOutputTokens }
            val queues = runs.mapNotNull { run -> run.startedAt?.let { ((it - run.createdAt) * 1000).coerceAtLeast(0) } }
            mutableMeasurements.value = listOf(
                "Measured performance · this chat within the latest 100 retained app invocations/runs; refresh history to update",
                "First model text (provider stream): ${if (firstVisible.isEmpty()) "unknown" else "${firstVisible.average().toLong()} ms mean (${firstVisible.size} observations)"}",
                "First visible UI response: not measured; provider text may precede answer verification/publication",
                "Run queue wait: ${if (queues.isEmpty()) "unknown" else "${queues.average().toLong()} ms mean (${queues.size} observations; stored at second resolution)"}",
                "Model time: ${invocations.sumOf { it.durationMs }} ms observed across ${invocations.size} invocations",
                "Final reported input: ${if (measuredInput.isEmpty()) "unknown" else measuredInput.sum()} tokens (${measuredInput.size}/${invocations.size} invocations); final output: ${if (measuredOutput.isEmpty()) "unknown" else measuredOutput.sum()} (${measuredOutput.size}/${invocations.size}). Partial sums are not totals.",
                "Completed terminal runs: ${terminal.count { it.status == "COMPLETED" }}/${terminal.size}. This measures run outcomes; engineering acceptance remains requirement-specific.",
                "Tool and verification time: inspect individual receipt/export measurements; missing observations remain unknown. Account HTTP queue/cache measurements appear in Integration health."
            )
            val rows = workspace.dao.observeGitHubEvidence(chatId).first().filter { it.id.startsWith("github-evidence:") }
            val events = rows.mapNotNull { row ->
                val receipt = workspace.protectedSynthesisCheckpoint(row.id) ?: return@mapNotNull null
                if (receipt.text("repository") != selection.fullName || receipt.text("branch") != selection.ref) return@mapNotNull null
                val args = receipt["arguments"] as? JsonObject
                val result = receipt["result"] as? JsonObject
                val body = result?.get("data") as? JsonObject ?: result
                val sha = receipt.text("source_head_sha")
                val link = body?.text("html_url").orEmpty().ifBlank { if (sha.matches(Regex("[a-f0-9]{40}"))) "https://github.com/${selection.fullName}/commit/$sha" else "" }
                GitHubActivityItem(
                    row.id,
                    receipt.text("observed_at_ms").toLongOrNull() ?: row.updatedAt,
                    "${receipt.text("tool")} / ${args?.text("action").orEmpty()} · ${if (receipt["success"] == JsonPrimitive(true)) "succeeded" else receipt.text("error_code").ifBlank { "failed" }}",
                    "Receipt ${receipt.text("receipt_id")} · revision ${sha.ifBlank { "not recorded" }}",
                    link
                )
            }
            val requests = messages.comparisonHistory(chatId).filter { it.platformType == null }.map { message ->
                GitHubActivityItem("message:${message.id}", message.createdAt * 1000, "Request: ${message.content.take(240)}", "Conversation $chatId · message ${message.id}; full request remains in Chat")
            }
            mutable.value = (events + requests).distinctBy { it.id }.sortedByDescending { it.time }
            mutableNotice.value = "${events.size} repository operation receipts and ${requests.size} requests. Window: latest 80 saved evidence records and 120 chat messages; older history remains in Chat/export."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = emptyList()
            mutableMeasurements.value = emptyList()
            mutableNotice.value = "History access could not be verified. Reconnect GitHub and retry."
        }
    }
}

@Composable
internal fun GitHubActivityPanel(connection: ToolConnection, selection: GitHubRepositoryContext, chatId: Int?, remote: List<GitHubActivityItem>, model: GitHubActivityViewModel = hiltViewModel()) {
    var expanded by remember { mutableStateOf(false) }
    val local by model.items.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val measurements by model.measurements.collectAsStateWithLifecycle()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    LaunchedEffect(expanded, connection.connectionUid, selection, chatId) { if (expanded && chatId != null) model.load(connection, selection, chatId) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide activity history" else "Activity history") }
    if (expanded) {
        measurements.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(notice, style = MaterialTheme.typography.bodySmall)
        val entries = remember(local, remote) { (local + remote).distinctBy { it.id }.sortedByDescending { it.time } }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(entries, key = { it.id }) { entry ->
                Column {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall)
                    Text(if (entry.time > 0) java.time.Instant.ofEpochMilli(entry.time).toString() else "Observation time not recorded", style = MaterialTheme.typography.labelSmall)
                    Text(entry.evidence, style = MaterialTheme.typography.bodySmall)
                    if (entry.url.startsWith("https://github.com/")) TextButton(onClick = { uri.openUri(entry.url) }) { Text("Open evidence") }
                }
            }
        }
        if (chatId != null) TextButton(onClick = { model.load(connection, selection, chatId) }) { Text("Refresh history") }
    }
}
