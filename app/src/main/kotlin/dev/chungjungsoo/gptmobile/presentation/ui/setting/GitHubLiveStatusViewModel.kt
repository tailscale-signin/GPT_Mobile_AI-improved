package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringApiException
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApiFactory
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveProcess
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.github.liveProcess
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

@HiltViewModel
internal class GitHubLiveStatusViewModel @Inject constructor(
    private val auth: GitHubPublicAuthCoordinator,
    private val factory: GitHubEngineeringApiFactory,
    private val connections: ToolConnectionRepository,
    private val repositoryState: GitHubRepositoryStateStore
) : ViewModel() {
    private val mutable = MutableStateFlow(GitHubLiveStatus())
    val state = mutable.asStateFlow()
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private var pages = 1
    private val jobPages = mutableMapOf<String, Int>()
    private val attempts = mutableMapOf<String, Int>()
    private var scope = ""
    private val comparisons = mutableSetOf<String>()
    fun compare(id: String) {
        comparisons.add(id)
        refresh()
    }
    fun refresh() {
        refresh.trySend(Unit)
    }
    fun filter(value: String) {
        require(value in setOf("ACTIVE", "CURRENT", "HISTORY"))
        pages = 1
        mutable.value = mutable.value.copy(filter = value, rows = emptyList(), phase = "LOADING", total = null)
        refresh()
    }
    fun more() {
        if (pages < 50) pages++
        refresh()
    }
    fun expand(id: String) {
        val expanded = mutable.value.expanded
        mutable.value = mutable.value.copy(expanded = if (id in expanded) expanded - id else expanded + id)
        refresh()
    }
    fun moreJobs(id: String) {
        jobPages[id] = ((jobPages[id] ?: 1) + 1).coerceAtMost(20)
        refresh()
    }
    fun attempt(id: String, value: Int) {
        require(value > 0)
        attempts[id] = value
        jobPages[id] = 1
        refresh()
    }

    suspend fun observe(connection: ToolConnection, selection: GitHubRepositoryContext, refreshPulls: () -> Unit, pullHeads: () -> Set<String> = { emptySet() }) {
        val selectedScope = "${connection.connectionUid}:${selection.fullName}:${selection.ref}"
        if (scope != selectedScope) {
            scope = selectedScope
            pages = 1
            attempts.clear()
            comparisons.clear()
            jobPages.clear()
            mutable.value = GitHubLiveStatus()
        }
        var failures = 0
        var cycle = 0
        val pullChecks = mutableMapOf<String, Pair<Long, List<JsonObject>>>()
        while (true) {
            var nextDelay = 15_000L
            val filter = mutable.value.filter
            try {
                val current = requireNotNull(connections.getConnection(connection.connectionUid))
                require(current.type == "GITHUB" && current.endpointUrl == connection.endpointUrl)
                val token = auth.accessToken(current)
                val api = factory.create { auth.accessToken(requireNotNull(connections.getConnection(connection.connectionUid))) }
                val root = GitHubEngineeringApi.root(selection.owner, selection.repo)
                suspend fun read(path: String) = withContext(Dispatchers.Default) { Json.parseToJsonElement(api.request(path)) as JsonObject }
                val sha = if (filter == "CURRENT") read("$root/commits/${GitHubWorkspaceClient.segment(selection.ref)}").text("sha") else ""
                val queries = if (filter == "ACTIVE") listOf("in_progress", "queued", "waiting", "pending", "requested").map { "&status=$it" } else listOf(if (sha.isBlank()) "" else "&head_sha=$sha")
                val runs = linkedMapOf<String, JsonObject>()
                var total = 0
                var knownTotal = true
                var more = false
                for (query in queries) {
                    for (page in 1..pages) {
                        val payload = read("$root/actions/runs?branch=${GitHubWorkspaceClient.segment(selection.ref)}&per_page=20&page=$page$query")
                        val count = (payload["total_count"] as? JsonPrimitive)?.intOrNull
                        if (page == 1) {
                            if (count == null) knownTotal = false else total += count
                        }
                        val batch = (payload["workflow_runs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        batch.filter { it.text("head_branch") == selection.ref && (it["head_repository"] as? JsonObject)?.text("full_name")?.equals(selection.fullName, true) == true }.forEach { runs[it.text("id")] = it }
                        if (batch.size < 20) break
                        if (page == pages && (count == null || page * 20 < count)) more = true
                    }
                }
                val heads = pullHeads().take(20).toSet()
                pullChecks.keys.retainAll(heads)
                for (head in heads) {
                    val cached = pullChecks[head]
                    val checks = if (cached != null && System.nanoTime() / 1_000_000 - cached.first < 60_000) {
                        cached.second
                    } else {
                        val payload = read("$root/actions/runs?head_sha=$head&per_page=100&page=1")
                        val observed = (payload["workflow_runs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.text("head_sha") == head }
                        pullChecks[head] = System.nanoTime() / 1_000_000 to observed
                        observed
                    }
                    checks.forEach { runs[it.text("id")] = it }
                }
                if (heads.isNotEmpty()) knownTotal = false
                val rows = mutableListOf<GitHubLiveProcess>()
                val moreJobs = mutableSetOf<String>()
                for ((id, run) in runs) {
                    val item = liveProcess(run)
                    val attempt = attempts[id]?.coerceAtMost(item.attempt) ?: item.attempt
                    val displayed = if (attempt != item.attempt) liveProcess(read("$root/actions/runs/$id/attempts/$attempt")) else item
                    rows += displayed.copy(id = id, attempt = attempt)
                    if (id !in mutable.value.expanded) continue
                    for (page in 1..(jobPages[id] ?: 1)) {
                        val payload = read("$root/actions/runs/$id/attempts/$attempt/jobs?per_page=100&page=$page")
                        val jobs = (payload["jobs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        rows += jobs.map { liveProcess(it, "job:$id:$attempt:").copy(parentId = id, runId = item.runId, attempt = attempt, headSha = item.headSha) }
                        val count = (payload["total_count"] as? JsonPrimitive)?.intOrNull
                        if (jobs.size < 100) break
                        if (page == (jobPages[id] ?: 1) && (count == null || page * 100 < count)) moreJobs += id
                    }
                    if (id in comparisons && attempt > 1) {
                        val prior = read("$root/actions/runs/$id/attempts/${attempt - 1}/jobs?per_page=100&page=1")
                        val previousJobs = (prior["jobs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { liveProcess(it) }
                        val previousTotal = (prior["total_count"] as? JsonPrimitive)?.intOrNull
                        val comparison = dev.chungjungsoo.gptmobile.data.github.compareGitHubAttempts(rows.filter { it.parentId == id }, previousJobs, id !in moreJobs && previousTotal != null && previousJobs.size >= previousTotal)
                        val index = rows.indexOfFirst { it.id == id }
                        rows[index] = rows[index].copy(comparison = comparison)
                    }
                }
                if (scope != selectedScope || mutable.value.filter != filter) continue
                val account = auth.connectedAccount(connection.connectionUid)
                val expires = auth.session(connection.connectionUid)?.expiresAt
                val repo = read(root)
                val permissions = repo["permissions"] as? JsonObject
                val health = listOf(
                    "Account: ${account?.login ?: "unavailable"}",
                    "Repository: ${selection.fullName}",
                    "Running app revision: ${dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT}",
                    "Inspected revision: ${sha.ifBlank { "multiple revisions; shown per run" }}",
                    "Route: native GitHub",
                    "Credential expiry: ${expires?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "not returned"}",
                    "Repository push access: ${permissions?.get("push") ?: "not returned"}; connection policy: ${current.toolPolicy}",
                    "Actions write and installation permissions: verified separately by each operation",
                    "Model readiness: use Test connection in Settings"
                )
                failures = 0
                mutable.value = mutable.value.copy(rows = rows, error = null, phase = if (rows.isEmpty()) "EMPTY" else "READY", total = if (knownTotal) total else null, hasMore = more, moreJobs = moreJobs, checkedAt = System.currentTimeMillis(), health = health, metrics = GitHubSessionCaches.schedulerForCredential(token).metrics())
                if (cycle++ % 3 == 0) refreshPulls()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures++
                val apiError = failure as? EngineeringApiException
                val authError = apiError?.status in setOf(401, 403, 404) && apiError?.retryAtEpochMs == null
                nextDelay = maxOf((15_000L shl failures.coerceAtMost(5)).coerceAtMost(300_000), (apiError?.retryAtEpochMs ?: 0) - System.currentTimeMillis())
                mutable.value = mutable.value.copy(
                    phase = if (authError) {
                        "AUTHORIZATION"
                    } else if (mutable.value.checkedAt != null) {
                        "STALE"
                    } else {
                        "OFFLINE"
                    },
                    error = if (authError) "Repository access could not be verified. Reconnect GitHub or review installation access." else "Refresh unavailable. Retained results are stale; Retry refresh or open GitHub."
                )
            }
            mutable.value = mutable.value.copy(nextCheckAt = System.currentTimeMillis() + nextDelay)
            withTimeoutOrNull(nextDelay) { refresh.receive() }
        }
    }

    fun mutate(connection: ToolConnection, selection: GitHubRepositoryContext, row: GitHubLiveProcess, action: String) = viewModelScope.launch {
        try {
            require(action in setOf("rerun_workflow", "rerun_failed_jobs", "cancel_workflow"))
            val current = requireNotNull(connections.getConnection(connection.connectionUid))
            require(current.toolPolicy != "READ_ONLY") { "This connection is read only." }
            val token = auth.accessToken(current)
            val api = factory.create { auth.accessToken(requireNotNull(connections.getConnection(connection.connectionUid))) }
            val run = Json.parseToJsonElement(api.request("${GitHubEngineeringApi.root(selection.owner, selection.repo)}/actions/runs/${row.runId}")) as JsonObject
            require(run.text("head_sha") == row.headSha && run.text("run_attempt").toIntOrNull() == row.attempt) { "The run changed. Refresh and review the current attempt." }
            val identity = auth.durableIdentity(current.connectionUid)
            val result = GitHubTool(token, connectionUid = current.connectionUid, conversationId = "status", taskId = "run:${row.runId}:${row.attempt}", stateStore = repositoryState, durableIdentity = identity, legacyCredentialIdentities = auth.legacyCredentialIdentities(current.connectionUid), credentialProvider = {
                val live = requireNotNull(connections.getConnection(current.connectionUid))
                require(live.endpointUrl == current.endpointUrl && live.authType == current.authType && live.toolPolicy == current.toolPolicy) { "GitHub authorization changed" }
                auth.accessToken(live).also { require(auth.durableIdentity(live.connectionUid) == identity) }
            }).execute(
                "status:$action:${row.runId}:${row.attempt}",
                buildJsonObject {
                    put("action", action)
                    put("owner", selection.owner)
                    put("repo", selection.repo)
                    put("run_id", requireNotNull(row.runId))
                    put("expected_head_sha", row.headSha)
                }
            )
            mutable.value = mutable.value.copy(notice = if (result.isError) "The action was not confirmed. Inspect its receipt before retrying: ${(result.content as? ToolResultContent.Text)?.text ?: result.errorCode}" else "GitHub accepted the action. Waiting for the new observation; completion is not yet verified.")
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.value = mutable.value.copy(notice = failure.message ?: "Action unavailable; refresh the run.")
        }
    }
}
