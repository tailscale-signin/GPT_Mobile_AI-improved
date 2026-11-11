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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@OptIn(ExperimentalSerializationApi::class)
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
    @EncodeDefault val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = startedAt,
    val status: String = "RUNNING",
    val detail: String = "Watching GitHub checks in the background.",
    val retryAt: Long = 0,
    val health: GitHubPullRequestHealth? = null,
    val branch: String = "",
    val draft: Boolean = false,
    val failureCount: Int = 0,
    val followPullRequest: Boolean = false,
    val permissionRevision: String = "",
    val observationGeneration: Long = 0
) {
    val terminal: Boolean get() = status !in setOf("RUNNING", "UNKNOWN", "NONE", "RETRYING") && !(followPullRequest && status in setOf("PASSED", "FAILED", "SKIPPED"))
}

/** Durable model-free monitoring. Android workers use bounded polling, not GitHub webhooks. */
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
    private fun decode(row: WorkspaceRecord): GitHubCheckWatch? = runCatching {
        val payload = workspace.json.parseToJsonElement(row.payload).jsonObject
        val decoded = workspace.json.decodeFromJsonElement<GitHubCheckWatch>(payload)
        // Older default-omitting encoders could lose the start time; retain a stable persisted boundary.
        if ("startedAt" in payload) decoded else decoded.copy(startedAt = row.updatedAt, updatedAt = if ("updatedAt" in payload) decoded.updatedAt else row.updatedAt)
    }.getOrNull()
    private suspend fun save(watch: GitHubCheckWatch) {
        val previous = workspace.dao.get(watch.id)?.let(::decode)
        val next = watch.copy(observationGeneration = GitHubWatchObservationFence.nextGeneration(previous))
        workspace.dao.save(WorkspaceRecord(next.id, "github_checks", "${next.owner}/${next.repo} · ${next.status}", workspace.json.encodeToString(next), next.chatId, updatedAt = next.updatedAt))
        if (previous == null || previous.status != next.status || previous.headSha != next.headSha || previous.draft != next.draft) GitHubWatchNotifications.publish(context, next)
    }
    private suspend fun api(watch: GitHubCheckWatch, background: Boolean = true): GitHubEngineeringApi {
        val room = workspace.database.chatRoomDao().getChatRoomsByIds(listOf(watch.chatId)).singleOrNull()
        require(room != null && (!background || !room.isTemporary)) { "Conversation was removed, or background watching requires a saved conversation." }
        val connection = requireNotNull(connections.getConnection(watch.connectionUid)) { "GitHub connection removed." }
        val permission = GitHubAuthorizationIdentity.permission(connection.authType, connection.toolPolicy, connection.approvedReadTools, connection.endpointUrl)
        require(connection.type == "GITHUB" && (if (watch.permissionRevision.isBlank()) connection.updatedAt == watch.connectionUpdatedAt else watch.permissionRevision == permission)) { "GitHub permissions changed; start a new watch." }
        val savedFeatures = settings.getFeatureSettings()
        val scope = savedFeatures.conversationGitHub[watch.chatId]?.takeIf {
            it.connectionUid == watch.connectionUid &&
                it.mainProfileUid == watch.profileUid &&
                it.owner.equals(watch.owner, ignoreCase = true) &&
                it.repo.equals(watch.repo, ignoreCase = true)
        }
        val features = GitHubChatPolicy.features(savedFeatures, watch.profileUid, scope)
        val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == watch.profileUid }
            ?.let { GitHubChatPolicy.platform(it, features, scope) }
        if (profile == null ||
            !profile.enabled ||
            profile.disableAllTools ||
            profile.disableRemoteTools ||
            !features.isToolPluginEnabledForProfile(watch.profileUid, ToolPluginId.GITHUB) ||
            !features.isToolPluginEnabledForProfile(watch.profileUid, ToolPluginId.connection(watch.connectionUid))
        ) {
            throw GitHubReadValidation(
                "PERMISSION_DENIED",
                buildJsonObject {
                    put("message", "GitHub access for this profile is disabled. Review GitHub tool settings before watching checks.")
                }
            )
        }
        return apiFactory.createForEndpoint(connection.endpointUrl) { auth.accessToken(connection) }
    }

    internal suspend fun subscribe(connectionUid: String, profileUid: String, chatId: Int, args: JsonObject, trigger: GitHubWatchTrigger = GitHubWatchTrigger.SUBSCRIBE): JsonObject = withContext(Dispatchers.IO) {
        val waitSeconds = args["wait_timeout_seconds"]?.jsonPrimitive?.intOrNull
        require("wait_timeout_seconds" !in args || waitSeconds != null && waitSeconds in 1..GitHubBoundedCheckWait.MAX_SECONDS) {
            "wait_timeout_seconds must be 1..${GitHubBoundedCheckWait.MAX_SECONDS}; omit it for background watching."
        }
        if (waitSeconds != null) {
            var observed: GitHubCheckWatch? = null
            val history = mutableListOf<kotlinx.serialization.json.JsonElement>()
            val result = GitHubBoundedCheckWait.await(waitSeconds) {
                // Head discovery and access checks consume this same wall-clock deadline.
                val before = observed ?: prepareWatch(connectionUid, profileUid, chatId, args, background = false)
                val after = inspect(before, api(before, background = false))
                if (observed != null && before.headSha != after.headSha) history += watchObservation(before)
                observed = after.copy(updatedAt = System.currentTimeMillis())
                JsonObject(watchObservation(requireNotNull(observed)) + ("revision_history" to JsonArray(history.toList())))
            }
            return@withContext JsonObject(
                result + buildJsonObject {
                    put("background_subscription", false)
                    put("head_binding_verified", observed != null)
                    put("delivery", "Inline observation only. Pending or timed-out checks remain unverified; no background subscription was created.")
                }
            )
        }
        val initial = prepareWatch(connectionUid, profileUid, chatId, args, background = true)
        val id = initial.id
        mutex.withLock {
            val existing = workspace.dao.get(id)?.let(::decode)
            val replace = existing == null || existing.terminal || existing.headSha != initial.headSha
            require(existing?.terminal == false || workspace.dao.forChat(chatId).filter { it.kind == "github_checks" }.mapNotNull(::decode).count { !it.terminal } < 20) { "Stop an existing check watch before adding another (20 active watches per chat)." }
            if (replace) {
                save(initial)
            } else if (requireNotNull(existing).followPullRequest != initial.followPullRequest) {
                save(existing.copy(followPullRequest = initial.followPullRequest))
            }
            val work = OneTimeWorkRequestBuilder<GitHubCheckWatchWorker>()
                .setInputData(workDataOf("watch_id" to id, "watch_trigger" to trigger.name))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag("github-check-watch")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(id, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
            GitHubWatchDiagnostics.record(workspace.dao.get(id)?.let(::decode) ?: initial, "SCHEDULE_REQUESTED", trigger, work.id.toString())
        }
        buildJsonObject {
            put("watch_id", id)
            put("head_sha", initial.headSha)
            initial.pullNumber?.let { put("pr_number", it) }
            initial.runId?.let { put("run_id", it) }
            put("status", "SUBSCRIBED")
            put("background_subscription", true)
            put("monitoring_transport", "ANDROID_BACKGROUND_POLLING")
            put("model_polling_required", false)
            put("delivery", "Native background checks update the chat card automatically. No more model polling calls are needed. Android may defer checks while offline or in battery-saving mode.")
        }
    }

    private fun watchObservation(watch: GitHubCheckWatch): JsonObject = buildJsonObject {
        put("status", watch.status)
        put("head_sha", watch.headSha)
        put("detail", watch.detail)
        put("observed_at_ms", watch.updatedAt)
        watch.runId?.let { put("run_id", it) }
        watch.pullNumber?.let { put("pr_number", it) }
    }

    private suspend fun prepareWatch(connectionUid: String, profileUid: String, chatId: Int, args: JsonObject, background: Boolean): GitHubCheckWatch {
        require(chatId > 0) { "Check watching requires an existing conversation." }
        val owner = args["owner"]?.jsonPrimitive?.content.orEmpty()
        val repo = args["repo"]?.jsonPrimitive?.content.orEmpty()
        val root = GitHubEngineeringApi.root(owner, repo)
        val pull = args["pr_number"]?.jsonPrimitive?.longOrNull
        val run = args["run_id"]?.jsonPrimitive?.longOrNull
        require(pull != null || run != null) { "Supply pr_number or run_id." }
        require((pull == null || pull > 0) && (run == null || run > 0)) { "PR and workflow run IDs must be positive." }
        val connection = requireNotNull(connections.getConnection(connectionUid))
        val id = "github-checks:" + GitHubRepositoryStateStore.digest("$chatId|$connectionUid|$profileUid|$owner/$repo|$pull|$run")
        val follow = args["follow_pr"]?.jsonPrimitive?.booleanOrNull == true
        require(!follow || pull != null && run == null) { "Follow PR requires pr_number without a fixed run_id." }
        val initial = GitHubCheckWatch(id, chatId, connectionUid, profileUid, owner, repo, pull, run, "", connection.updatedAt, followPullRequest = follow, permissionRevision = GitHubAuthorizationIdentity.permission(connection.authType, connection.toolPolicy, connection.approvedReadTools, connection.endpointUrl))
        val api = api(initial, background)
        val pullData = pull?.let { Json.parseToJsonElement(api.request("$root/pulls/$it")).jsonObject }
        val pullHead = pullData?.get("head")?.jsonObject?.get("sha")?.jsonPrimitive?.content
        val branch = pullData?.get("head")?.jsonObject?.get("ref")?.jsonPrimitive?.contentOrNull.orEmpty()
        val draft = pullData?.get("draft")?.jsonPrimitive?.booleanOrNull == true
        val runHead = run?.let { Json.parseToJsonElement(api.request("$root/actions/runs/$it")).jsonObject["head_sha"]?.jsonPrimitive?.content }
        require(pullHead == null || runHead == null || pullHead == runHead) { "The workflow run belongs to a different PR revision." }
        val head = requireNotNull(pullHead ?: runHead).also { require(it.matches(Regex("[a-fA-F0-9]{40}"))) }
        args["commit_sha"]?.jsonPrimitive?.content?.let { expected ->
            require(expected.equals(head, true)) { "The PR or run head differs from commit_sha. Refresh the exact revision before subscribing." }
        }
        return initial.copy(headSha = head, branch = branch, draft = draft)
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
                put("follow_pr", watch.followPullRequest)
            },
            trigger = GitHubWatchTrigger.EXPLICIT_REFRESH
        )
    }

    internal suspend fun disable(connectionUid: String, profileUid: String, chatId: Int, args: JsonObject) = withContext(Dispatchers.IO) {
        val target = prepareWatch(connectionUid, profileUid, chatId, args, background = true)
        mutex.withLock {
            val saved = workspace.dao.get(target.id)?.let(::decode)
            require(saved == null || saved.headSha == target.headSha) { "The watch has moved to a different revision. Refresh the tile." }
            cancelLocked(target.id, saved ?: target)
        }
    }

    internal suspend fun cancel(id: String) = mutex.withLock {
        cancelLocked(id, workspace.dao.get(id)?.let(::decode))
    }

    private suspend fun cancelLocked(id: String, watch: GitHubCheckWatch?) {
        watch?.let {
            val cancelled = it.copy(status = "CANCELLED", detail = "Watching stopped.", updatedAt = System.currentTimeMillis())
            save(cancelled)
            GitHubWatchDiagnostics.record(cancelled, "CANCELLED", GitHubWatchTrigger.USER_CANCEL)
        }
        WorkManager.getInstance(context).cancelUniqueWork(id)
    }

    /** Returns true when the subscription is terminal. */
    internal suspend fun poll(id: String, attempt: Int, workId: String? = null, trigger: GitHubWatchTrigger = GitHubWatchTrigger.SUCCESSOR): Boolean = withContext(Dispatchers.IO) {
        val watch = workspace.dao.get(id)?.let(::decode) ?: return@withContext true
        GitHubWatchDiagnostics.record(watch, "POLL_STARTED", trigger, workId, attempt)
        if (watch.terminal) {
            GitHubWatchDiagnostics.record(watch, "TERMINAL", trigger, workId, attempt)
            return@withContext true
        }
        val now = System.currentTimeMillis()
        if (now < watch.retryAt) {
            GitHubWatchDiagnostics.record(watch, "POLL_DEFERRED", trigger, workId, attempt)
            return@withContext false
        }
        val outcome = if (now - watch.startedAt >= TimeUnit.HOURS.toMillis(24)) {
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
        val failures = if (outcome.status == "RETRYING") watch.failureCount + 1 else 0
        val scheduled = if (!outcome.terminal) outcome.copy(failureCount = failures, retryAt = GitHubWatchBackoff.next(now, (failures - 1).coerceAtLeast(0), outcome.retryAt)) else outcome
        mutex.withLock {
            val current = workspace.dao.get(id)?.let(::decode)
            // Cancellation and a replacement subscription win over an in-flight response.
            if (GitHubWatchObservationFence.accepts(watch, current)) {
                if (scheduled.headSha != watch.headSha) workspace.dao.save(WorkspaceRecord("${watch.id}:revision:${watch.headSha}", "github_check_revision", "${watch.owner}/${watch.repo} · ${watch.headSha.take(12)}", workspace.json.encodeToString(watch), watch.chatId, updatedAt = now))
                save(scheduled.copy(updatedAt = now))
                GitHubWatchDiagnostics.record(workspace.dao.get(id)?.let(::decode) ?: scheduled, if (scheduled.terminal) "TERMINAL" else "POLL_COMPLETED", trigger, workId, attempt)
                scheduled.terminal
            } else {
                GitHubWatchDiagnostics.record(current ?: watch, "STALE_OBSERVATION", trigger, workId, attempt)
                // A stale terminal response must not stop the newer watch's scheduling chain.
                current?.terminal ?: true
            }
        }
    }

    internal suspend fun scheduleNext(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = workspace.dao.get(id)?.let(::decode) ?: return@withLock
            if (current.terminal) return@withLock
            val manager = WorkManager.getInstance(context)
            val workStates = manager.getWorkInfosForUniqueWorkFlow(id).first().map { it.state.name }
            if (!GitHubWatchDiagnostics.shouldAppend(current.terminal, workStates)) {
                GitHubWatchDiagnostics.record(current, "SUCCESSOR_ALREADY_QUEUED", GitHubWatchTrigger.SUCCESSOR)
                return@withLock
            }
            val work = OneTimeWorkRequestBuilder<GitHubCheckWatchWorker>()
                .setInputData(workDataOf("watch_id" to id, "watch_trigger" to GitHubWatchTrigger.SUCCESSOR.name))
                .setInitialDelay((current.retryAt - System.currentTimeMillis()).coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("github-check-watch")
                .build()
            // Wait for the database insert under the scheduling mutex. A second
            // completion must observe this successor before deciding to append one.
            kotlinx.coroutines.runInterruptible {
                manager.enqueueUniqueWork(id, ExistingWorkPolicy.APPEND_OR_REPLACE, work).result.get(10, TimeUnit.SECONDS)
            }
            GitHubWatchDiagnostics.record(current, "SUCCESSOR_ENQUEUED", GitHubWatchTrigger.SUCCESSOR, work.id.toString())
        }
    }

    internal suspend fun recordWorkerRetry(id: String, workId: String, attempt: Int) {
        workspace.dao.get(id)?.let(::decode)?.let { GitHubWatchDiagnostics.record(it, "WORK_RETRY", GitHubWatchTrigger.WORK_MANAGER_RETRY, workId, attempt) }
    }

    internal suspend fun inspect(watch: GitHubCheckWatch, api: GitHubEngineeringApi): GitHubCheckWatch {
        var observed = watch
        val root = GitHubEngineeringApi.root(watch.owner, watch.repo)
        if (watch.pullNumber != null) {
            val pull = Json.parseToJsonElement(api.request("$root/pulls/${watch.pullNumber}")).jsonObject
            observed = watch.copy(
                draft = pull["draft"]?.jsonPrimitive?.booleanOrNull ?: watch.draft,
                branch = pull["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.contentOrNull ?: watch.branch
            )
            val current = pull["head"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
            if (current != watch.headSha) {
                if (watch.followPullRequest && current?.matches(Regex("[a-fA-F0-9]{40}")) == true) {
                    return observed.copy(headSha = current, status = "RUNNING", health = null, retryAt = 0, detail = "Following new revision ${current.take(12)}. Previous results retained; earlier merge approval is invalid for this revision.")
                }
                return observed.copy(status = "STALE", detail = "PR head changed. This watch is attached to ${watch.headSha.take(12)}; start a new watch for the new revision.")
            }
            closedPullRequestWatch(watch, pull)?.let { return it }
        }
        if (watch.runId != null) {
            val run = Json.parseToJsonElement(api.request("$root/actions/runs/${watch.runId}")).jsonObject
            require(run["head_sha"]?.jsonPrimitive?.content == watch.headSha) { "Workflow revision changed." }
            // A paired PR/run subscription must fence the PR on both sides of the run read.
            // The immutable run can pass while a concurrent push changes the mutable PR.
            if (watch.pullNumber != null) {
                val after = Json.parseToJsonElement(api.request("$root/pulls/${watch.pullNumber}")).jsonObject
                if (after["head"]?.jsonObject?.get("sha")?.jsonPrimitive?.content != watch.headSha) {
                    return observed.copy(status = "STALE", detail = "PR head changed while the workflow was loading. Refresh this watch.", health = null)
                }
            }
            val result = workflowWatchStatus(run)
            return observed.copy(status = result, detail = "Workflow ${watch.runId}: ${run["conclusion"]?.jsonPrimitive?.contentOrNull ?: "in progress"} · ${watch.headSha.take(12)}", retryAt = 0)
        }
        val runs = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var total = -1
        for (page in 1..10) {
            val checks = Json.parseToJsonElement(api.request("$root/commits/${watch.headSha}/check-runs?per_page=100&page=$page")).jsonObject
            val pageTotal = checks["total_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            if (pageTotal < 0 || total >= 0 && total != pageTotal) return observed.copy(status = "UNKNOWN", detail = "Check inventory changed during pagination; refreshing.")
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
            return observed.copy(status = "STALE", detail = "PR head changed while checks were loading. Refresh this watch.", health = null)
        }
        closedPullRequestWatch(watch, after)?.let { return it }
        return observed.copy(status = summary, detail = "PR #${watch.pullNumber} · ${watch.headSha.take(12)}", retryAt = 0, health = health)
    }
}

internal fun workflowWatchStatus(run: JsonObject): String = when {
    run["status"]?.jsonPrimitive?.content != "completed" -> "RUNNING"
    run["conclusion"]?.jsonPrimitive?.contentOrNull == "success" -> "PASSED"
    run["conclusion"]?.jsonPrimitive?.contentOrNull in setOf("neutral", "skipped") -> "SKIPPED"
    run["conclusion"]?.jsonPrimitive?.contentOrNull in setOf("failure", "cancelled", "timed_out", "action_required", "startup_failure", "stale") -> "FAILED"
    else -> "UNKNOWN"
}

/** A PR subscription ends when the PR closes; an explicit run subscription remains independent. */
internal fun closedPullRequestWatch(watch: GitHubCheckWatch, pull: JsonObject): GitHubCheckWatch? {
    if (watch.runId != null || pull["state"]?.jsonPrimitive?.contentOrNull != "closed") return null
    val merged = pull["merged"]?.jsonPrimitive?.booleanOrNull == true || pull["merged_at"]?.jsonPrimitive?.contentOrNull != null
    val state = if (merged) "MERGED" else "CLOSED"
    val detail = if (merged) "Pull request merged. Background watching is complete." else "Pull request closed. Background watching stopped."
    return watch.copy(status = state, detail = detail, retryAt = 0, health = GitHubPullRequestHealth(state = state, detail = detail, headSha = watch.headSha))
}
