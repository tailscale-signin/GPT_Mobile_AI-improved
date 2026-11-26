package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** GitHub owns remote execution; the app retains the publication receipt across turns and restarts. */
internal class GitHubCopilotTasks(
    private val store: GitHubRepositoryStateStore,
    private val scope: String,
    private val read: suspend (String) -> JsonObject,
    private val write: suspend (String, JsonObject) -> JsonObject
) {
    class AdmissionRejected(val code: String, message: String) : IllegalArgumentException(message)

    companion object {
        val reads = setOf("copilot_task_status", "copilot_tasks")
        val writes = setOf("copilot_create_task")
        val actions = reads + writes
        private val states = setOf("queued", "in_progress", "completed", "failed", "idle", "waiting_for_user", "timed_out", "cancelled")

        /** The API payload, not the invocation ID or display arguments, fences an uncertain POST. */
        fun creationIdentity(args: JsonObject): JsonObject = buildJsonObject {
            put("repository", "${text(args, "owner")}/${text(args, "repo")}".lowercase(Locale.ROOT))
            put("body", creationBody(args))
        }

        fun creationBody(args: JsonObject): JsonObject = buildJsonObject {
            val prompt = text(args, "prompt")
            require(prompt.isNotBlank() && prompt.codePointCount(0, prompt.length) <= 24_000 && '\u0000' !in prompt && Charsets.UTF_8.newEncoder().canEncode(prompt)) {
                "The prompt must contain 1 to 24000 Unicode characters."
            }
            put("prompt", prompt)
            val base = copilotBranch(text(args, "base_ref"))
            put("base_ref", base)
            listOf("head_ref", "model", "custom_agent").forEach { key ->
                if (key in args) {
                    val value = text(args, key)
                    if (key == "head_ref") {
                        val head = copilotBranch(value)
                        require(head != base) { "The head and base must be distinct branches." }
                        put(key, head)
                    } else {
                        require(value.matches(Regex("[A-Za-z0-9_.:/-]{1,128}"))) { "Use a supported $key identifier." }
                        put(key, value)
                    }
                }
            }
            val createPr = args["create_pull_request"] ?: JsonPrimitive(false)
            require(createPr == JsonPrimitive(true) || createPr == JsonPrimitive(false)) { "create_pull_request must be boolean." }
            require("head_ref" !in args || createPr != JsonPrimitive(true)) { "An existing head_ref cannot request a new pull request; use the existing branch/PR context." }
            put("create_pull_request", createPr)
        }

        private fun copilotBranch(value: String): String = normalizeGitHubBranch(value).also { ref ->
            require(ref.matches(Regex("[A-Za-z0-9][A-Za-z0-9_./@-]{0,199}")) && ".." !in ref && !ref.endsWith('.') && ref.split('/').all { it.isNotEmpty() && it != "." && !it.endsWith(".lock") }) {
                "Use an exact Copilot branch name."
            }
        }

        private fun text(args: JsonObject, key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        private fun validId(value: String) = value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
    }

    suspend fun execute(root: String, action: String, args: JsonObject): JsonObject {
        val endpoint = "/agents$root/tasks"
        return when (action) {
            "copilot_create_task" -> create(root, endpoint, args)
            "copilot_task_status" -> status(root, endpoint, args)
            "copilot_tasks" -> {
                val page = (args["page"] as? JsonPrimitive)?.intOrNull ?: 1
                val perPage = (args["per_page"] as? JsonPrimitive)?.intOrNull ?: 30
                require(page in 1..1000 && perPage in 1..100)
                val result = read("$endpoint?per_page=$perPage&page=$page&sort=updated_at&direction=desc")
                val tasks = result["tasks"] as? JsonArray ?: error("GitHub did not return a task list. No creation was attempted.")
                metadata {
                    put("tasks", JsonArray(tasks.map { taskSummary(it as? JsonObject ?: error("Invalid GitHub task entry.")) }))
                    put("page", page)
                    put("per_page", perPage)
                    put("has_more", tasks.size == perPage)
                    put("note", "Discovery is read-only. A similar task name or timestamp does not prove the outcome of an uncertain creation.")
                }
            }
            else -> error("Unsupported Copilot task action.")
        }
    }

    private suspend fun create(root: String, endpoint: String, args: JsonObject): JsonObject {
        val requestId = text(args, "request_id")
        require(requestId.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))) { "Supply a stable request_id; keep it unchanged during recovery." }
        val body = creationBody(args)
        val payloadHash = GitHubRepositoryStateStore.digest(GitHubExecutionJournal.canonical(creationIdentity(args)).toString())
        val identity = buildJsonObject { put("payload_hash", payloadHash) }
        val executionKey = "execution:" + GitHubRepositoryStateStore.digest("$scope|copilot.create|${GitHubExecutionJournal.canonical(identity)}")
        store.update(scope, requestKey(requestId)) { previous ->
            require(previous == null || previous["payload_hash"] == JsonPrimitive(payloadHash)) {
                "request_id already belongs to another Copilot request. Resume its saved receipt before creating different work."
            }
            previous ?: buildJsonObject {
                put("status", "PROTECTED_GUARD")
                put("repository", root.lowercase(Locale.ROOT))
                put("payload_hash", payloadHash)
                put("execution_key", executionKey)
            }
        }
        var beforeWriteFailure: Exception? = null
        return try {
            GitHubExecutionJournal(store, scope).run("copilot.create", identity, singleHttpRequest = true) {
                try {
                    verifyRef(root, text(body, "base_ref"), text(args, "expected_base_sha"), "base")
                    if ("head_ref" in body) verifyRef(root, text(body, "head_ref"), text(args, "expected_head_sha"), "head")
                    val accepted = write(endpoint, body)
                    val task = taskSummary(accepted)
                    metadata {
                        put("request_id", requestId)
                        put("task_id", task.getValue("id"))
                        put("task_url", "https://github.com/${root.removePrefix("/repos/")}/copilot/tasks/${GitHubWorkspaceClient.segment(text(task, "id"))}")
                        put("purpose", "Implement the submitted repository task")
                        put("task", task)
                        put("status", "REMOTE_ACCEPTED")
                        put("remote_continues_without_app", true)
                        put("expected_base_sha", text(args, "expected_base_sha"))
                        args["expected_head_sha"]?.let { put("expected_head_sha", it) }
                        put("revision_guard", "PREFLIGHT_ONLY")
                        put("note", "GitHub accepted the cloud task. Resume with copilot_task_status and this request_id or task_id. The preview API has no atomic SHA precondition; inspect the resulting PR, exact head and required checks before acceptance or merge.")
                    }
                } catch (failure: Exception) {
                    if (failure !is CancellationException && currentCoroutineContext()[GitHubMutationAttempts]?.uncertainOrAccepted != true) {
                        beforeWriteFailure = failure
                        throw GitHubRequestRejected(0, "Copilot creation stopped before any write was accepted or became uncertain.")
                    }
                    throw failure
                }
            } as JsonObject
        } catch (rejected: GitHubRequestRejected) {
            throw (beforeWriteFailure ?: rejected)
        }
    }

    private suspend fun verifyRef(root: String, ref: String, expected: String, label: String) {
        require(expected.matches(Regex("[a-fA-F0-9]{40}")) && expected.any { it != '0' }) { "Discover the current $label branch and supply its exact expected_${label}_sha." }
        val observed = read("$root/git/ref/heads/${GitHubWorkspaceClient.segment(ref)}")
        val commit = observed["object"] as? JsonObject
        require(commit?.get("type") == JsonPrimitive("commit") && (commit?.get("sha") as? JsonPrimitive)?.content.equals(expected, true)) {
            "The Copilot $label branch changed. Refresh its exact head before submitting cloud work."
        }
    }

    private suspend fun status(root: String, endpoint: String, args: JsonObject): JsonObject {
        require("gateway_job_id" !in args) { "A Gateway job must be resumed through its original Gateway execution owner." }
        val supplied = text(args, "task_id")
        val requestId = text(args, "request_id")
        require(supplied.isBlank() != requestId.isBlank()) { "Supply exactly one observed task_id or saved request_id." }
        val id = if (supplied.isNotBlank()) {
            supplied
        } else {
            require(requestId.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))) { "Use the request_id from the saved creation receipt." }
            val binding = store.read(scope, requestKey(requestId))
            require(binding?.get("repository") == JsonPrimitive(root.lowercase(Locale.ROOT))) { "No saved Copilot request exists for this account, chat and repository." }
            val row = store.read(scope, text(requireNotNull(binding), "execution_key"))
            val receipt = row?.get("result") as? JsonObject
            val retainedId = receipt?.let { text(it, "task_id") }.orEmpty()
            if (row?.get("status") != JsonPrimitive("COMPLETED") || retainedId.isBlank()) {
                return metadata {
                    put("request_id", requestId)
                    put("status", if (row?.get("status") == JsonPrimitive("REJECTED")) "NOT_STARTED" else "RECONCILE_REQUIRED")
                    put("dispatched", false)
                    put("message", "No confirmed remote task ID is retained. Inspect Copilot task discovery and GitHub directly; matching names or timestamps cannot prove association. This resume performed no creation and never releases an uncertain publication claim.")
                }
            }
            retainedId
        }
        require(validId(id)) { "Use the opaque task_id returned by GitHub." }
        val task = taskSummary(read("$endpoint/${GitHubWorkspaceClient.segment(id)}"))
        require(task["id"] == JsonPrimitive(id)) { "GitHub returned a different task identity. No task was created or retried." }
        val state = text(task, "state")
        return metadata {
            put("task_id", id)
            put("task_url", "https://github.com/${root.removePrefix("/repos/")}/copilot/tasks/${GitHubWorkspaceClient.segment(id)}")
            put("purpose", "Observe the submitted repository task")
            requestId.takeIf(String::isNotBlank)?.let { put("request_id", it) }
            put("task", task)
            put(
                "status",
                when (state) {
                    "queued" -> "REMOTE_QUEUED"
                    "in_progress" -> "REMOTE_RUNNING"
                    "completed" -> "REMOTE_COMPLETED"
                    "idle", "waiting_for_user" -> "HUMAN_ACTION_REQUIRED"
                    "failed", "timed_out", "cancelled" -> "REMOTE_FAILED"
                    else -> "RECONCILE_REQUIRED"
                }
            )
            put("requires_reconciliation", state !in states)
            if (state !in states) put("reconciliation_reason", "UNKNOWN_REMOTE_STATE")
            put("user_input_required", state in setOf("idle", "waiting_for_user"))
            put("remote_continues_without_app", state in setOf("queued", "in_progress"))
            put("note", "This is a fresh read of the existing cloud task. Remote completion is not CI, exact-head acceptance or merge authorization.")
        }
    }

    private fun taskSummary(task: JsonObject): JsonObject {
        require(validId(text(task, "id"))) { "GitHub returned no usable task ID. Reconcile remote tasks; do not repeat creation." }
        return buildJsonObject {
            listOf("id", "url", "html_url", "name", "state", "creator_type", "session_count", "created_at", "updated_at", "archived_at").forEach { key ->
                task[key]?.let { put(key, it) }
            }
            (task["artifacts"] as? JsonArray)?.let { put("artifacts", JsonArray(it.take(30))) }
            (task["sessions"] as? JsonArray)?.let { sessions ->
                put(
                    "sessions",
                    JsonArray(
                        sessions.take(20).mapNotNull { value ->
                            val session = value as? JsonObject ?: return@mapNotNull null
                            JsonObject(session.filterKeys { it in setOf("id", "task_id", "state", "head_ref", "base_ref", "model", "created_at", "updated_at", "completed_at") })
                        }
                    )
                )
                put("sessions_truncated", sessions.size > 20)
            }
        }
    }

    private fun requestKey(requestId: String) = "execution:" + GitHubRepositoryStateStore.digest("$scope|copilot.request|$requestId")

    private fun metadata(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("schema", "github.copilot-task.v1")
        put("execution_owner", "GITHUB_CLOUD")
        put("orchestration_owner", "ANDROID_DIRECT")
        put("gateway_required", false)
        put("api_preview", true)
        put("creation_access", "COPILOT_BUSINESS_OR_ENTERPRISE")
        put("required_repository_permission", "Agent tasks: read for status, write for creation")
        put("supported_authentication", "Fine-grained personal access token or GitHub App user access token; installation tokens are unsupported")
        put("acceptance_verified", false)
        put("ci_verified", false)
        put("merge_authorized", false)
        block()
    }
}
