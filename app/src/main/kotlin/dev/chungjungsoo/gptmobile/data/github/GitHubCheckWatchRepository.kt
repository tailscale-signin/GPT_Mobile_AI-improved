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
import dev.chungjungsoo.gptmobile.data.agent.ToolRunContext
import dev.chungjungsoo.gptmobile.data.agent.tool.EngineeringApiException
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApiFactory
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
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
import kotlinx.serialization.json.JsonPrimitive
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
    val observationGeneration: Long = 0,
    val runAttempt: Int? = null,
    val accountScopeHash: String = "",
    val allChecks: Boolean = false,
    val waitingTasks: List<GitHubCiWatchTask> = emptyList()
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
        if (room == null) throw GitHubReadValidation("PERMISSION_DENIED", buildJsonObject { put("message", "This conversation no longer exists.") })
        if (background && room.isTemporary) {
            throw GitHubReadValidation(
                "USER_INPUT_REQUIRED",
                buildJsonObject {
                    put("message", "Save this conversation for background check notifications, or request an inline observation with wait_timeout_seconds between 1 and 30.")
                }
            )
        }
        val connection = connections.getConnection(watch.connectionUid)
            ?: throw GitHubReadValidation("PERMISSION_DENIED", buildJsonObject { put("message", "Reconnect GitHub before starting a check watch.") })
        val permission = GitHubAuthorizationIdentity.permission(connection.authType, connection.toolPolicy, connection.approvedReadTools, connection.endpointUrl)
        require(connection.type == "GITHUB" && (if (watch.permissionRevision.isBlank()) connection.updatedAt == watch.connectionUpdatedAt else watch.permissionRevision == permission)) { "GitHub permissions changed; start a new watch." }
        if (watch.accountScopeHash.isNotEmpty()) {
            require(watch.accountScopeHash == GitHubRepositoryStateStore.digest(auth.durableIdentity(watch.connectionUid))) { "The GitHub account changed. Open a new check watch for the current account." }
        }
        val savedFeatures = settings.getFeatureSettings()
        val selected = savedFeatures.conversationGitHub[watch.chatId]
        val scope = selected?.takeIf {
            it.connectionUid == watch.connectionUid &&
                it.mainProfileUid == watch.profileUid &&
                it.owner.equals(watch.owner, ignoreCase = true) &&
                it.repo.equals(watch.repo, ignoreCase = true)
        }
        if (selected != null && scope == null) {
            throw GitHubReadValidation("PERMISSION_DENIED", buildJsonObject { put("message", "This conversation now uses a different repository, profile or GitHub connection. Reopen its current check card.") })
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
        var durableWatch = initial
        var reattached = false
        mutex.withLock {
            val existing = workspace.dao.get(id)?.let(::decode)
            val sameAuthority = existing != null && existing.headSha == initial.headSha && existing.permissionRevision == initial.permissionRevision && existing.accountScopeHash == initial.accountScopeHash
            val replace = !sameAuthority || requireNotNull(existing).terminal || !initial.allChecks && existing.runAttempt != initial.runAttempt
            val tasks = ((existing?.waitingTasks.orEmpty().takeIf { sameAuthority && existing?.terminal == false }).orEmpty() + initial.waitingTasks).distinctBy { it.sourceMessageId }
            require(tasks.size <= 20) { "This watch already has 20 waiting tasks." }
            require(existing?.terminal == false || workspace.dao.forChat(chatId).filter { it.kind == "github_checks" }.mapNotNull(::decode).count { !it.terminal } < 20) { "Stop an existing check watch before adding another (20 active watches per chat)." }
            if (replace) {
                durableWatch = initial.copy(waitingTasks = tasks)
                save(durableWatch)
            } else if (requireNotNull(existing).followPullRequest != initial.followPullRequest || existing.waitingTasks != tasks) {
                durableWatch = existing.copy(followPullRequest = initial.followPullRequest, waitingTasks = tasks)
                save(durableWatch)
                reattached = true
            } else {
                durableWatch = requireNotNull(existing)
                reattached = true
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
            put("subscription_id", id)
            put("subscription_state", if (reattached) "REATTACHED" else "SUBSCRIBED")
            put("lifecycle_state", durableWatch.status)
            put("observation_state", "WAITING_FOR_FIRST_LIVE_CHECK_OBSERVATION")
            put("head_sha", initial.headSha)
            initial.pullNumber?.let { put("pr_number", it) }
            initial.runId?.let { put("run_id", it) }
            initial.runAttempt?.let { put("run_attempt", it) }
            if (initial.allChecks) put("all_checks", true)
            put("status", "SUBSCRIBED")
            put("background_subscription", true)
            put("monitoring_transport", "ANDROID_BACKGROUND_POLLING")
            put("model_polling_required", false)
            put("delivery", "Native background checks update the chat card automatically. No more model polling calls are needed. Android may defer checks while offline or in battery-saving mode.")
        }
    }

    private fun watchObservation(watch: GitHubCheckWatch): JsonObject = buildJsonObject {
        put("status", watch.status)
        put("subscription_id", watch.id)
        put("subscription_state", if (watch.terminal) "TERMINAL" else "ACTIVE")
        put("lifecycle_state", watch.status)
        put("observation_state", "LIVE_CHECK_OBSERVATION")
        put("head_sha", watch.headSha)
        put("detail", watch.detail)
        put("observed_at_ms", watch.updatedAt)
        watch.runId?.let { put("run_id", it) }
        if (watch.allChecks) put("all_checks", true)
        watch.pullNumber?.let { put("pr_number", it) }
    }

    private suspend fun prepareWatch(connectionUid: String, profileUid: String, chatId: Int, args: JsonObject, background: Boolean): GitHubCheckWatch {
        require(chatId > 0) { "Check watching requires an existing conversation." }
        val owner = args["owner"]?.jsonPrimitive?.content.orEmpty()
        val repo = args["repo"]?.jsonPrimitive?.content.orEmpty()
        val root = GitHubEngineeringApi.root(owner, repo)
        val pull = args["pr_number"]?.jsonPrimitive?.longOrNull
        val run = args["run_id"]?.jsonPrimitive?.longOrNull
        val allChecks = args["all_checks"] == JsonPrimitive(true)
        val requestedHead = args["commit_sha"]?.jsonPrimitive?.contentOrNull
        require(pull != null || run != null || allChecks) { "Supply pr_number, run_id, or all_checks=true with an exact commit_sha." }
        require(!allChecks || dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.wholeCommitWatch(args)) { "All-check monitoring requires an exact commit_sha." }
        require((pull == null || pull > 0) && (run == null || run > 0)) { "PR and workflow run IDs must be positive." }
        val connection = connections.getConnection(connectionUid)
            ?: throw GitHubReadValidation("PERMISSION_DENIED", buildJsonObject { put("message", "Reconnect GitHub before starting a check watch.") })
        val target = if (allChecks) "all|$pull|${requestedHead?.lowercase()}" else "$pull|$run"
        val id = "github-checks:" + GitHubRepositoryStateStore.digest("$chatId|$connectionUid|$profileUid|$owner/$repo|$target")
        val follow = args["follow_pr"]?.jsonPrimitive?.booleanOrNull == true
        require(!follow || pull != null && run == null) { "Follow PR requires pr_number without a fixed run_id." }
        val provisional = GitHubCheckWatch(id, chatId, connectionUid, profileUid, owner, repo, pull, run, "", connection.updatedAt, followPullRequest = follow, permissionRevision = GitHubAuthorizationIdentity.permission(connection.authType, connection.toolPolicy, connection.approvedReadTools, connection.endpointUrl))
        val initial = if (background) {
            api(provisional, true)
            auth.accessToken(connection)
            provisional.copy(accountScopeHash = GitHubRepositoryStateStore.digest(auth.durableIdentity(connectionUid)))
        } else {
            provisional
        }
        val api = api(initial, background)
        val pullData = pull?.let { Json.parseToJsonElement(api.request("$root/pulls/$it")).jsonObject }
        val pullHead = pullData?.get("head")?.jsonObject?.get("sha")?.jsonPrimitive?.content
        val branch = pullData?.get("head")?.jsonObject?.get("ref")?.jsonPrimitive?.contentOrNull.orEmpty()
        val draft = pullData?.get("draft")?.jsonPrimitive?.booleanOrNull == true
        val runData = run?.let { Json.parseToJsonElement(api.request("$root/actions/runs/$it")).jsonObject }
        val runHead = runData?.get("head_sha")?.jsonPrimitive?.content
        val attempt = runData?.get("run_attempt")?.jsonPrimitive?.intOrNull
        if ("run_attempt" in args) {
            val expected = args["run_attempt"]?.jsonPrimitive?.intOrNull
            require(run != null && expected != null && expected > 0 && attempt == expected) { "The observed workflow attempt changed. Refresh this run before subscribing." }
        }
        require(pullHead == null || runHead == null || pullHead == runHead) { "The workflow run belongs to a different PR revision." }
        // A SHA-only watch needs an authenticated observation of this repository's commit.
        val commitHead = if (pull == null && run == null) {
            Json.parseToJsonElement(api.request("$root/git/commits/${requireNotNull(requestedHead)}")).jsonObject["sha"]?.jsonPrimitive?.contentOrNull
        } else {
            null
        }
        val head = (pullHead ?: runHead ?: commitHead)?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
            ?: throw GitHubReadValidation("GITHUB_EVIDENCE_REQUIRED", buildJsonObject { put("message", "GitHub did not return the head SHA for this check watch. Refresh the commit, pull request or workflow run.") })
        args["commit_sha"]?.jsonPrimitive?.content?.let { expected ->
            require(expected.equals(head, true)) { "The PR or run head differs from commit_sha. Refresh the exact revision before subscribing." }
        }
        if (background) api(initial, true)
        val task = if (background && allChecks) {
            kotlin.coroutines.coroutineContext[ToolRunContext]?.runId?.let { GitHubCiWatchTask.capture(it, chatId, profileUid, owner, repo, workspace.database, settings) }
        } else {
            null
        }
        return initial.copy(headSha = head, branch = branch, draft = draft, runAttempt = attempt, allChecks = allChecks, waitingTasks = listOfNotNull(task))
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
                if (watch.allChecks) {
                    put("all_checks", true)
                    put("commit_sha", watch.headSha)
                }
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
                inspect(watch, api(watch)).also { if (watch.accountScopeHash.isNotEmpty()) api(watch) }
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

    /** Recheck the captured account and scope after waiting in the durable queue. */
    internal suspend fun validateQueuedContinuation(prompt: dev.chungjungsoo.gptmobile.data.queue.PendingPrompt): Boolean {
        val payload = prompt.details()
        val continuation = payload.githubCiWatch ?: return false
        val watch = workspace.dao.get(continuation.watchId)?.let(::decode) ?: return false
        val descriptor = payload.recovery ?: return false
        val prior = workspace.database.agentRunDao().getById(descriptor.generationId) ?: return false
        val original = workspace.database.agentPersistenceDao().rawRecoveryMessage(descriptor.sourceMessageId) ?: return false
        val room = workspace.database.agentPersistenceDao().getChatRoom(prompt.chatId) ?: return false
        if (!continuation.matches(prompt, watch, prior, original, room)) return false
        val current = GitHubCiWatchTask.capture(prior.runId, watch.chatId, watch.profileUid, watch.owner, watch.repo, workspace.database, settings)
        if (current != continuation.task(payload)) return false
        val selected = settings.getFeatureSettings().conversationGitHub[watch.chatId] ?: return false
        if (selected.copy(commitSha = watch.headSha) != payload.tools.github) return false
        try {
            api(watch)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return true
    }

    /** A persistent queue key fences delivery if the worker restarts after inserting the continuation. */
    internal suspend fun deliverTerminalTasks(id: String, queue: dagger.Lazy<DurablePromptQueue>): Boolean {
        val watch = mutex.withLock { workspace.dao.get(id)?.let(::decode) } ?: return true
        if (!watch.allChecks || watch.waitingTasks.isEmpty() || watch.status !in setOf("PASSED", "FAILED", "SKIPPED")) return true
        val initialApi = try {
            api(watch)
        } catch (_: IllegalArgumentException) {
            return true
        }
        val prepared = watch.waitingTasks.map { it to it.prepare(watch, workspace.database, settings, initialApi) }
        return mutex.withLock {
            val current = workspace.dao.get(id)?.let(::decode)
            // Equality includes the head, authority, generation, status and exact task membership.
            // Cancellation or a replacement wins over the suspended remote read.
            if (current != watch) return@withLock terminalDeliverySettled(watch, current)
            val remaining = mutableListOf<GitHubCiWatchTask>()
            for ((task, delivery) in prepared) {
                when (delivery) {
                    GitHubCiWatchTask.Delivery.Waiting -> remaining += task
                    GitHubCiWatchTask.Delivery.Discarded -> Unit
                    is GitHubCiWatchTask.Delivery.Ready -> {
                        val ready = task.currentDelivery(watch, workspace.database, settings)
                        if (ready is GitHubCiWatchTask.Delivery.Ready) {
                            try {
                                api(watch)
                            } catch (_: IllegalArgumentException) {
                                return@withLock true
                            }
                            val latest = workspace.dao.get(id)?.let(::decode)
                            if (latest != watch) return@withLock terminalDeliverySettled(watch, latest)
                            val key = "github-ci-task:${watch.id}:${watch.headSha}:${task.sourceMessageId}"
                            queue.get().enqueue(watch.chatId, "Continue", ready.payload, key)
                        } else if (ready == GitHubCiWatchTask.Delivery.Waiting) {
                            remaining += task
                        }
                    }
                }
            }
            if (remaining.size != watch.waitingTasks.size) save(watch.copy(waitingTasks = remaining))
            remaining.isEmpty()
        }
    }

    private fun terminalDeliverySettled(observed: GitHubCheckWatch, current: GitHubCheckWatch?): Boolean {
        if (current == null) return true
        // An active/replaced watch owns its own polling chain. A changed task set on
        // this same terminal snapshot must be retried rather than silently consumed.
        val sameTerminal = current.copy(observationGeneration = observed.observationGeneration, waitingTasks = observed.waitingTasks) == observed
        return !sameTerminal || current.waitingTasks.isEmpty()
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
        if (watch.runId != null && !watch.allChecks) {
            val run = Json.parseToJsonElement(api.request("$root/actions/runs/${watch.runId}")).jsonObject
            require(run["head_sha"]?.jsonPrimitive?.content == watch.headSha) { "Workflow revision changed." }
            if (watch.runAttempt != null && run["run_attempt"]?.jsonPrimitive?.intOrNull != watch.runAttempt) {
                return observed.copy(status = "STALE", detail = "A new workflow attempt started. Refresh this watch to observe the current attempt.", health = null)
            }
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
        val statusPath = "$root/commits/${watch.headSha}/status?per_page=100"
        var statuses = Json.parseToJsonElement(api.request(statusPath)).jsonObject
        if (watch.allChecks && (statuses["total_count"]?.jsonPrimitive?.intOrNull ?: 0) > 100) {
            val totalStatuses = statuses["total_count"]?.jsonPrimitive?.intOrNull
            val contexts = statuses["statuses"]?.jsonArray.orEmpty().toMutableList()
            for (page in 2..10) {
                if (contexts.size >= requireNotNull(totalStatuses)) break
                val next = Json.parseToJsonElement(api.request("$statusPath&page=$page")).jsonObject
                if (next["total_count"] != statuses["total_count"] || next["state"] != statuses["state"]) return observed.copy(status = "UNKNOWN", detail = "Commit status inventory changed during pagination.")
                val rows = next["statuses"]?.jsonArray.orEmpty()
                contexts += rows
                if (rows.size < 100) break
            }
            statuses = JsonObject(statuses + ("statuses" to JsonArray(contexts)))
        }
        val summary = gitHubCheckSummary(
            buildJsonObject {
                put("total_count", total)
                put("check_runs", JsonArray(runs))
            },
            statuses,
            waitForAll = watch.allChecks
        )
        if (watch.pullNumber == null) {
            return observed.copy(status = summary, detail = "Checks at ${watch.headSha.take(12)}", retryAt = 0)
        }
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
