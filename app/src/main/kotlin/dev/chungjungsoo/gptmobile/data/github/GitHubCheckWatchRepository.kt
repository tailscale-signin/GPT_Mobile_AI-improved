package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringApiException
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApiFactory
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
internal data class GitHubCheckWatch(
    val id: String,
    val chatId: Int,
    val connectionUid: String,
    val profileUid: String,
    val owner: String,
    val repo: String,
    val pullNumber: Long? = null,
    val runId: Long? = null,
    val headSha: String,
    val connectionUpdatedAt: Long,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = startedAt,
    val status: String = "RUNNING",
    val detail: String = "Watching GitHub checks in the background.",
    val retryAt: Long = 0,
    val health: GitHubPullRequestHealth? = null
) {
    val terminal: Boolean get() = status !in setOf("RUNNING", "UNKNOWN", "NONE", "RETRYING")
}

/** Durable, token-free native subscriptions. No webhook server or foreground model loop is needed. */
@Singleton
class GitHubCheckWatchRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: WorkspaceRepository,
    private val connections: ToolConnectionRepository,
    private val settings: SettingRepository,
    private val auth: GitHubPublicAuthCoordinator,
    private val apiFactory: GitHubEngineeringApiFactory
) {
    private val mutex = Mutex()
    internal fun observe(chatId: Int) = workspace.dao.observeGitHubChecks(chatId).map { rows -> rows.mapNotNull { decode(it) } }
    private fun decode(row: WorkspaceRecord): GitHubCheckWatch? = runCatching { workspace.json.decodeFromString<GitHubCheckWatch>(row.payload) }.getOrNull()
    private suspend fun save(watch: GitHubCheckWatch) = workspace.dao.save(WorkspaceRecord(watch.id, "github_checks", "${watch.owner}/${watch.repo} · ${watch.status}", workspace.json.encodeToString(watch), watch.chatId, updatedAt = watch.updatedAt))
    private suspend fun api(watch: GitHubCheckWatch): GitHubEngineeringApi {
        require(workspace.database.chatRoomDao().getChatRoomsByIds(listOf(watch.chatId)).singleOrNull()?.isTemporary == false) { "Conversation was removed or is temporary." }
        val connection = requireNotNull(connections.getConnection(watch.connectionUid)) { "GitHub connection removed." }
        require(connection.type == "GITHUB" && connection.updatedAt == watch.connectionUpdatedAt) { "GitHub connection changed; start a new watch." }
        val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == watch.profileUid }
        val features = settings.getFeatureSettings()
        require(profile != null && profile.enabled && !profile.disableAllTools && !profile.disableRemoteTools && features.isToolPluginEnabledForProfile(watch.profileUid, ToolPluginId.GITHUB) && features.isToolPluginEnabledForProfile(watch.profileUid, ToolPluginId.connection(watch.connectionUid))) { "GitHub access for this profile is disabled." }
        return apiFactory.create { auth.accessToken(connection) }
    }

    internal suspend fun subscribe(connectionUid: String, profileUid: String, chatId: Int, args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        require(chatId > 0 && workspace.database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).singleOrNull()?.isTemporary == false) { "Background checks require a saved conversation." }
        val owner = args["owner"]?.jsonPrimitive?.content.orEmpty()
        val repo = args["repo"]?.jsonPrimitive?.content.orEmpty()
        val root = GitHubEngineeringApi.root(owner, repo)
        val pull = args["pr_number"]?.jsonPrimitive?.longOrNull
        val run = args["run_id"]?.jsonPrimitive?.longOrNull
        require(pull != null || run != null) { "Supply pr_number or run_id." }
        require((pull == null || pull > 0) && (run == null || run > 0)) { "PR and workflow run IDs must be positive." }
        val connection = requireNotNull(connections.getConnection(connectionUid))
        val id = "github-checks:" + GitHubRepositoryStateStore.digest("$chatId|$connectionUid|$profileUid|$owner/$repo|$pull|$run")
        val initial = GitHubCheckWatch(id, chatId, connectionUid, profileUid, owner, repo, pull, run, "", connection.updatedAt)
        val api = api(initial)
        val pullHead = pull?.let { Json.parseToJsonElement(api.request("$root/pulls/$it")).jsonObject["head"]?.jsonObject?.get("sha")?.jsonPrimitive?.content }
        val runHead = run?.let { Json.parseToJsonElement(api.request("$root/actions/runs/$it")).jsonObject["head_sha"]?.jsonPrimitive?.content }
        require(pullHead == null || runHead == null || pullHead == runHead) { "The workflow run belongs to a different PR revision." }
        val head = requireNotNull(pullHead ?: runHead).also { require(it.matches(Regex("[a-fA-F0-9]{40}"))) }
        mutex.withLock {
            val existing = workspace.dao.get(id)?.let(::decode)
            require(existing?.terminal == false || workspace.dao.forChat(chatId).filter { it.kind == "github_checks" }.mapNotNull(::decode).count { !it.terminal } < 20) { "Stop an existing check watch before adding another (20 active watches per chat)." }
            if (existing == null || existing.terminal || existing.headSha != head) save(initial.copy(headSha = head))
            val work = OneTimeWorkRequestBuilder<GitHubCheckWatchWorker>()
                .setInputData(workDataOf("watch_id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag("github-check-watch")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(id, if (existing == null || existing.terminal || existing.headSha != head) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
        }
        buildJsonObject {
            put("watch_id", id)
            put("head_sha", head)
            put("status", "SUBSCRIBED")
            put("delivery", "Native background checks update the chat card automatically. No more model polling calls are needed. Android may defer checks while offline or in battery-saving mode.")
        }
    }

    internal suspend fun refresh(id: String) {
        val watch = workspace.dao.get(id)?.let(::decode) ?: return
        // Terminal watches are restarted through the same credential and chat checks.
        subscribe(
            watch.connectionUid,
            watch.profileUid,
            watch.chatId,
            buildJsonObject {
                put("owner", watch.owner)
                put("repo", watch.repo)
                watch.pullNumber?.let { put("pr_number", it) }
                watch.runId?.let { put("run_id", it) }
            }
        )
    }

    internal suspend fun cancel(id: String) = mutex.withLock {
        workspace.dao.get(id)?.let(::decode)?.let { save(it.copy(status = "CANCELLED", detail = "Watching stopped.", updatedAt = System.currentTimeMillis())) }
        WorkManager.getInstance(context).cancelUniqueWork(id)
    }

    /** Returns true when the subscription is terminal. */
    internal suspend fun poll(id: String, attempt: Int): Boolean = withContext(Dispatchers.IO) {
        val watch = workspace.dao.get(id)?.let(::decode) ?: return@withContext true
        if (watch.terminal) return@withContext true
        val now = System.currentTimeMillis()
        if (now < watch.retryAt) return@withContext false
        val outcome = if (attempt >= 48 || now - watch.startedAt >= TimeUnit.HOURS.toMillis(24)) {
            watch.copy(status = "EXPIRED", detail = "Background watch expired. CI has not been verified.")
        } else {
            try {
                inspect(watch, api(watch))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: EngineeringApiException) {
                if (error.status in setOf(401, 403, 404) && error.retryAtEpochMs == null) watch.copy(status = "BLOCKED", detail = "GitHub access unavailable (HTTP ${error.status}); reconnect or review repository access.") else watch.copy(status = "RETRYING", detail = "GitHub is temporarily unavailable; retrying in the background.", retryAt = error.retryAtEpochMs ?: 0)
            } catch (error: IllegalArgumentException) {
                watch.copy(status = "BLOCKED", detail = error.message.orEmpty().take(240))
            } catch (_: Exception) {
                watch.copy(status = "RETRYING", detail = "Connection interrupted; retrying in the background.")
            }
        }
        mutex.withLock {
            val current = workspace.dao.get(id)?.let(::decode)
            // Cancellation and a replacement subscription win over an in-flight response.
            if (current != null && !current.terminal && current.startedAt == watch.startedAt && current.headSha == watch.headSha) save(outcome.copy(updatedAt = now))
        }
        outcome.terminal
    }

    internal suspend fun inspect(watch: GitHubCheckWatch, api: GitHubEngineeringApi): GitHubCheckWatch {
        val root = GitHubEngineeringApi.root(watch.owner, watch.repo)
        if (watch.pullNumber != null) {
            val current = Json.parseToJsonElement(api.request("$root/pulls/${watch.pullNumber}")).jsonObject["head"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
            if (current != watch.headSha) return watch.copy(status = "STALE", detail = "PR head changed. This watch is attached to ${watch.headSha.take(12)}; start a new watch for the new revision.")
        }
        if (watch.runId != null) {
            val run = Json.parseToJsonElement(api.request("$root/actions/runs/${watch.runId}")).jsonObject
            require(run["head_sha"]?.jsonPrimitive?.content == watch.headSha) { "Workflow revision changed." }
            val result = workflowWatchStatus(run)
            return watch.copy(status = result, detail = "Workflow ${watch.runId}: ${run["conclusion"]?.jsonPrimitive?.contentOrNull ?: "in progress"} · ${watch.headSha.take(12)}", retryAt = 0)
        }
        val runs = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var total = -1
        for (page in 1..10) {
            val checks = Json.parseToJsonElement(api.request("$root/commits/${watch.headSha}/check-runs?per_page=100&page=$page")).jsonObject
            val pageTotal = checks["total_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            if (pageTotal < 0 || total >= 0 && total != pageTotal) return watch.copy(status = "UNKNOWN", detail = "Check inventory changed during pagination; refreshing.")
            total = pageTotal
            val rows = checks["check_runs"]?.jsonArray.orEmpty()
            runs += rows
            if (rows.size < 100 || runs.size >= total) break
        }
        val statuses = Json.parseToJsonElement(api.request("$root/commits/${watch.headSha}/status?per_page=100")).jsonObject
        val summary = gitHubCheckSummary(
            buildJsonObject {
                put("total_count", total)
                put("check_runs", JsonArray(runs))
            },
            statuses
        )
        val health = try {
            gitHubPullRequestHealth(Json.parseToJsonElement(api.pullRequestHealth(watch.owner, watch.repo, requireNotNull(watch.pullNumber).toInt())).jsonObject, watch.headSha)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GitHubPullRequestHealth(detail = "Merge rules unavailable. Check repository access and refresh.")
        }
        // Re-read the mutable PR after fetching checks so a concurrent push cannot produce a green card.
        val after = Json.parseToJsonElement(api.request("$root/pulls/${watch.pullNumber}")).jsonObject
        if (after["head"]?.jsonObject?.get("sha")?.jsonPrimitive?.content != watch.headSha) {
            return watch.copy(status = "STALE", detail = "PR head changed while checks were loading. Refresh this watch.", health = null)
        }
        return watch.copy(status = summary, detail = "PR #${watch.pullNumber} · ${watch.headSha.take(12)}", retryAt = 0, health = health)
    }
}

internal fun workflowWatchStatus(run: JsonObject): String = when {
    run["status"]?.jsonPrimitive?.content != "completed" -> "RUNNING"
    run["conclusion"]?.jsonPrimitive?.contentOrNull == "success" -> "PASSED"
    run["conclusion"]?.jsonPrimitive?.contentOrNull in setOf("neutral", "skipped") -> "SKIPPED"
    run["conclusion"]?.jsonPrimitive?.contentOrNull in setOf("failure", "cancelled", "timed_out", "action_required", "startup_failure", "stale") -> "FAILED"
    else -> "UNKNOWN"
}
