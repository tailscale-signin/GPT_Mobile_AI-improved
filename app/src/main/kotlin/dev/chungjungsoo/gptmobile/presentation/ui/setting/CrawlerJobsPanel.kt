package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.crawl.CrawlJob
import dev.chungjungsoo.gptmobile.data.crawl.CrawlStore
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@HiltViewModel
class CrawlerJobsViewModel @Inject constructor(@ApplicationContext context: Context, private val resolver: AgentToolResolver, private val archive: ResearchArchiveRepository, private val connections: dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository, private val vault: dev.chungjungsoo.gptmobile.data.security.SecretVault) : ViewModel() {
    // Context.filesDir may create/check directories. Resolve it only on IO,
    // including when the first collector opens this settings panel.
    private val store by lazy { CrawlStore.forDirectory(context.filesDir) }
    val jobs = flow { emitAll(store.jobs) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val message = MutableStateFlow("")
    val companions = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection>>(emptyList())
    val readyKeys = MutableStateFlow<Set<String>>(emptySet())
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            store.list()
            companions.value = connections.listConnections().filter(dev.chungjungsoo.gptmobile.data.crawl.CrawlerCompanionGrant::recognizes)
            val references = vault.references()
            readyKeys.value = companions.value.filter { dev.chungjungsoo.gptmobile.data.crawl.CrawlerCompanionGrant.reference(it) in references }.map { it.connectionUid }.toSet()
        }
    }
    fun saveKey(connection: dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection, hex: String) {
        viewModelScope.launch {
            try {
                require(hex.matches(Regex("[a-fA-F0-9]{64,128}")) && hex.length % 2 == 0) { "Enter the grant key as 64–128 hexadecimal characters" }
                check(connections.getConnection(connection.connectionUid) == connection) { "Connection changed; reopen settings" }
                val key = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                try {
                    vault.put(dev.chungjungsoo.gptmobile.data.crawl.CrawlerCompanionGrant.reference(connection), key)
                } finally {
                    key.fill(0)
                }
                readyKeys.update { it + connection.connectionUid }
                message.value = "Companion grant key saved securely. HTTP bearer authentication remains separate."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message ?: "Could not save grant key"
            }
        }
    }
    fun action(job: CrawlJob, action: String) {
        if (job.id in busy.value && action != "cancel") return
        viewModelScope.launch(Dispatchers.IO) {
            busy.update { it + job.id }
            try {
                when (action) {
                    "cancel" -> {
                        dev.chungjungsoo.gptmobile.data.crawl.MultiCrawlerTool.cancel(store, job.id)
                        message.value = "Cancelled; committed evidence retained"
                    }
                    "delete" -> {
                        check(!dev.chungjungsoo.gptmobile.data.crawl.MultiCrawlerTool.isRunning(job.id)) { "Cancel the job before deleting" }
                        store.delete(job.id)
                    }
                    "archive" -> {
                        check(job.scope.persistent) { "Temporary evidence requires an explicit copy in a regular conversation" }
                        archive.initialize()
                        val epoch = archive.dao.sequence()?.archiveEpoch ?: 0L
                        var saved = 0
                        for (id in job.pageIds) {
                            val page = store.page(job.scope, id) ?: continue
                            archive.capture(ResearchSource(page.sourceId, page.url, page.title.ifBlank { page.url }, status = if (page.truncated) "Partially read" else "Read", passage = page.content.take(8000), retrievedAt = page.retrievedAt, engines = listOf(page.engine), retrievalQuality = if (page.truncated) "PARTIAL_TEXT" else "FULL_TEXT", publicationType = "webpage"), page.content, job.scope.evidenceScope, true, job.scope.chatId, job.scope.turnId, true, epoch, ownerKind = "import", ownerId = "crawler:${job.id}")
                            saved++
                        }
                        message.value = "Saved $saved captured pages to Research evidence. Scientific appraisal remains separate."
                    }
                    else -> {
                        val parts = job.scope.turnId.split(':')
                        val tools = resolver.resolve(job.scope.profileUid, userMessage = MessageV2(id = parts.getOrNull(1)?.toIntOrNull() ?: 0, chatId = job.scope.chatId, content = "Resume the accepted crawler job", platformType = null, createdAt = parts.getOrNull(2)?.toLongOrNull() ?: 0), cachedCataloguesOnly = true)
                        val tool = requireNotNull(tools.firstOrNull { it.modelToolName == "crawler" }) { "Enable Crawlers for the original profile before resuming" }
                        val result = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 32, maxConcurrentTools = 2)).bind(tool.tool).execute(
                            "crawler-ui-${java.util.UUID.randomUUID()}",
                            buildJsonObject {
                                put("action", action)
                                put("jobId", job.id)
                            }
                        )
                        message.value = if (result.isError) result.content.toString().take(400) else "Crawler ${if (action == "resume") "progress saved" else "cancelled"}"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message ?: "Crawler action failed"
            } finally {
                busy.update { it - job.id }
                store.list()
            }
        }
    }
}

@Composable
internal fun CrawlerJobsPanel(viewModel: CrawlerJobsViewModel = hiltViewModel()) {
    val companions by viewModel.companions.collectAsStateWithLifecycle()
    val readyKeys by viewModel.readyKeys.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        companions.forEach { connection ->
            androidx.compose.runtime.key(connection.connectionUid) {
                var keyText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                Text("${connection.name} · ${if (connection.connectionUid in readyKeys) "Grant key saved" else "Grant key required"}", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.OutlinedTextField(value = keyText, onValueChange = { keyText = it }, label = { Text("Companion grant key · hexadecimal") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                TextButton(onClick = {
                    viewModel.saveKey(connection, keyText.trim())
                    keyText = ""
                }, enabled = keyText.isNotBlank()) { Text("Save grant key") }
            }
        }
        Text("Saved crawl jobs", style = MaterialTheme.typography.titleMedium)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (jobs.isEmpty()) Text("Ask an enabled profile to crawl seed URLs. Jobs and committed sources appear here.", style = MaterialTheme.typography.bodySmall)
        jobs.take(20).forEach { job ->
            Text("${job.hosts.joinToString()} · ${job.state}", style = MaterialTheme.typography.labelLarge)
            Text("${job.pageIds.size} captured · ${job.visited.size} examined · ${job.failures.size} failures · ${job.mode}", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { if (job.state == "COMPLETED") 1f else (job.visited.size.toFloat() / job.maxPages.coerceAtLeast(1)).coerceIn(0f, .99f) })
            if (job.notice.isNotBlank()) Text(job.notice, style = MaterialTheme.typography.bodySmall)
            Row {
                if (job.state in setOf("PAUSED", "RUNNING")) TextButton(enabled = job.id !in busy, onClick = { viewModel.action(job, "resume") }) { Text("Resume") }
                if (job.state !in setOf("COMPLETED", "CANCELLED")) TextButton(onClick = { viewModel.action(job, "cancel") }) { Text("Cancel") }
                if (job.pageIds.isNotEmpty()) TextButton(enabled = job.id !in busy, onClick = { viewModel.action(job, "archive") }) { Text("Save evidence") }
                TextButton(enabled = job.id !in busy && !dev.chungjungsoo.gptmobile.data.crawl.MultiCrawlerTool.isRunning(job.id), onClick = { viewModel.action(job, "delete") }) { Text("Delete") }
            }
        }
    }
}
