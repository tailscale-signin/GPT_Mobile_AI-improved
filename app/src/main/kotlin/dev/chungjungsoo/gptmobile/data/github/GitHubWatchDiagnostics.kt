package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class GitHubWatchTrigger { SUBSCRIBE, EXPLICIT_REFRESH, SUCCESSOR, WORK_MANAGER_RETRY, USER_CANCEL }

/** Fixed states and hashed identities only; repository names, refs and error bodies stay private. */
internal object GitHubWatchDiagnostics {
    fun metadata(watch: GitHubCheckWatch, event: String, trigger: GitHubWatchTrigger, workId: String? = null, attempt: Int? = null): JsonObject = buildJsonObject {
        put("schema", "github.watch.v1")
        put("event", event.takeIf { it in events } ?: "UNKNOWN")
        put("watch_id", watch.id.takeIf { it.matches(watchIdentity) } ?: GitHubRepositoryStateStore.digest(watch.id))
        put("target_hash", GitHubRepositoryStateStore.digest("${watch.owner.lowercase(Locale.ROOT)}/${watch.repo.lowercase(Locale.ROOT)}|${watch.pullNumber}|${watch.runId}"))
        put("ref_hash", GitHubRepositoryStateStore.digest("${watch.branch}|${watch.headSha}"))
        put("configuration_revision", GitHubRepositoryStateStore.digest("${watch.connectionUid}|${watch.profileUid}|${watch.permissionRevision.ifBlank { watch.connectionUpdatedAt.toString() }}|${watch.followPullRequest}"))
        put("observation_generation", watch.observationGeneration)
        put("trigger", trigger.name)
        put("status", watch.status.takeIf { it in statuses } ?: "UNKNOWN")
        put("terminal", watch.terminal)
        put("terminal_condition", if (watch.terminal) watch.status.takeIf { it in statuses } ?: "UNKNOWN" else "NONE")
        put("next_poll_at_ms", watch.retryAt)
        put("failure_count", watch.failureCount)
        workId?.takeIf { it.matches(workIdentity) }?.let { put("work_id", it) }
        attempt?.takeIf { it >= 0 }?.let { put("work_attempt", it) }
    }

    fun record(watch: GitHubCheckWatch, event: String, trigger: GitHubWatchTrigger, workId: String? = null, attempt: Int? = null) {
        if (AppLogRecorder.enabled.value && !AppLogRecorder.privateSessionActive) {
            AppLogRecorder.record("GitHubCheckWatch", metadata(watch, event, trigger, workId, attempt).toString())
        }
    }

    /** The current worker is RUNNING; one queued or blocked successor already owns the next poll. */
    fun shouldAppend(terminal: Boolean, workStates: Iterable<String>): Boolean =
        !terminal && workStates.none { it == "ENQUEUED" || it == "BLOCKED" }

    private val watchIdentity = Regex("github-checks:[a-f0-9]{64}")
    private val workIdentity = Regex("[a-fA-F0-9-]{36}")
    private val events = setOf("SCHEDULE_REQUESTED", "SCHEDULE_KEPT", "SUCCESSOR_ENQUEUED", "SUCCESSOR_ALREADY_QUEUED", "POLL_STARTED", "POLL_DEFERRED", "POLL_COMPLETED", "STALE_OBSERVATION", "TERMINAL", "CANCELLED", "WORK_RETRY")
    private val statuses = setOf("RUNNING", "UNKNOWN", "NONE", "RETRYING", "PASSED", "FAILED", "SKIPPED", "CANCELLED", "EXPIRED", "BLOCKED", "STALE", "MERGED", "CLOSED")
}
