package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity
import dev.chungjungsoo.gptmobile.data.github.GitHubCiObservationReport
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubSemanticSummary
import dev.chungjungsoo.gptmobile.data.github.normalizeGitHubRepository
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A small read plan for a stalled CI diagnosis, using only admitted tools and observed identities. */
internal class GitHubCiInvestigationRecovery(task: String?) {
    private val enabled = task != null && GitHubCiObservationReport.matches(task)
    private var active = false
    private val observations = linkedMapOf<String, Observation>()
    private val automaticCalls = linkedSetOf<String>()

    private data class Observation(val call: ProviderEvent.ToolCall, val successful: Boolean, val scope: JsonObject, val data: JsonObject, val requestedRepository: Pair<String?, String?>?)

    /** Capture bounded identities before the runner discards full retained payloads for replay. */
    fun observe(exchange: AgentToolExchange) {
        if (!enabled) return
        for (call in exchange.calls) {
            val operation = operationIdentity(call.name) ?: continue
            val result = exchange.results.firstOrNull { it.callId == call.callId } ?: continue
            if (call.callId.startsWith(PREFIX)) automaticCalls += call.callId
            val arguments = GitHubActionIdentity.canonicalArguments(operation, call.arguments)
            if ("view_cursor" in arguments) continue
            val source = ToolResultEnvelope.element(result.retainedContent ?: result.content) as? JsonObject
            // Reused receipts cannot replace the original source with an empty display wrapper.
            if (source?.get("schema") == JsonPrimitive("github.repeat.v1")) continue
            val data = source?.let(GitHubSemanticSummary::data).orEmpty()
            val summary = result.executionReceipt?.get("semantic_summary") as? JsonObject
            val scope = result.executionReceipt?.get("resource_context") as? JsonObject
            val resolved = (result.executionReceipt?.get("corrected_arguments") ?: result.executionReceipt?.get("canonical_arguments") ?: result.executionReceipt?.get("resolved_arguments")) as? JsonObject
            val bounded = buildJsonObject {
                for (key in listOf("commit_sha", "head_sha", "branch", "run_id", "run_attempt", "job_id", "status", "conclusion", "total_lines")) {
                    (data[key] ?: summary?.get(key))?.let { put(key, it) }
                }
                if (arguments.text("action") in setOf("logs", "failure")) {
                    (summary?.get("apk_size_failure") as? JsonObject ?: dev.chungjungsoo.gptmobile.data.github.GitHubCiSizeGate.observation(arguments, JsonObject(data)))?.let { put("apk_size_failure", it) }
                }
                for (collection in listOf("workflow_runs", "jobs")) {
                    (data[collection] as? JsonArray)?.let { rows ->
                        put(
                            collection,
                            JsonArray(
                                rows.take(100).filterIsInstance<JsonObject>().map { row ->
                                    JsonObject(row.filterKeys { it in setOf("id", "head_sha", "run_id", "run_attempt", "status", "conclusion") })
                                }
                            )
                        )
                    }
                }
            }
            observations[call.callId] = Observation(
                call.copy(name = operation, arguments = arguments),
                !result.isError && result.errorCode !in ToolResultEnvelope.unknownOutcomeCodes,
                buildJsonObject {
                    for (key in listOf("owner", "repo", "ref", "branch", "selected_ref", "commit_sha", "head_sha", "expected_head_sha")) {
                        (scope?.get(key) ?: resolved?.get(key) ?: arguments[key])?.let { put(key, it) }
                    }
                },
                bounded,
                repository(arguments)?.let { requested ->
                    val bound = resolved?.let(::repository)
                    (requested.first ?: bound?.first) to (requested.second ?: bound?.second)
                }
            )
            while (observations.size > 128) observations.remove(observations.keys.first())
        }
    }

    fun activate(tools: Collection<AgentToolDefinition>): Boolean {
        if (!enabled || next(tools) == null) return false
        active = true
        return true
    }

    fun nextCall(tools: Collection<AgentToolDefinition>): ProviderEvent.ToolCall? = if (active) next(tools) else null

    private fun next(tools: Collection<AgentToolDefinition>): ProviderEvent.ToolCall? {
        if (!enabled || automaticCalls.size >= MAX_AUTOMATIC_READS) return null
        val seed = observations.values.firstOrNull { it.successful && it.call.name.substringBefore("__") == "github_ci" && it.call.arguments.text("action") == "workflows" }
            ?: observations.values.firstOrNull { observation ->
                observation.call.name.substringBefore("__") == "github_ci" &&
                    observation.call.arguments.text("action") in setOf("runs", "jobs", "job", "logs", "status") &&
                    observation.requestedRepository?.let { requested ->
                        listOf("owner" to requested.first, "repo" to requested.second).all { (key, expected) ->
                            expected == null || observation.scope.text(key).equals(expected, ignoreCase = true)
                        }
                    } == true
            } ?: return null
        val ciName = seed.call.name
        if (tools.none { it.name == ciName && it.modelVisible }) return null
        val contextName = "github_context" + ciName.removePrefix("github_ci")
        // A failed ID lookup is enough to start bounded discovery, but never supplies
        // a run/job identity. An explicitly requested commit remains the target.
        val selectedRef = if (seed.call.arguments.text("action") == "workflows") {
            seed.scope.reference()
        } else {
            listOf("commit_sha", "head_sha", "expected_head_sha").firstNotNullOfOrNull { key -> seed.call.arguments.text(key).takeIf(SHA::matches) }
                ?: seed.scope.reference()
        }
        val scoped = observations.values.filter { observation ->
            observation.call.name in setOf(ciName, contextName) &&
                listOf("owner", "repo").all { key ->
                    val expected = seed.scope.text(key)
                    val actual = observation.scope.text(key)
                    expected.isBlank() || actual.isBlank() || expected.equals(actual, ignoreCase = true)
                } &&
                (
                    selectedRef.isBlank() ||
                        observation.scope.reference().let { it.isBlank() || it == selectedRef } ||
                        SHA.matches(selectedRef) &&
                        (
                            observation.data.text("commit_sha").equals(selectedRef, ignoreCase = true) ||
                                listOf("commit_sha", "head_sha", "expected_head_sha").any { observation.call.arguments.text(it).equals(selectedRef, ignoreCase = true) }
                            )
                    ) &&
                (observation.call.name != contextName || selectedRef.isBlank() || SHA.matches(selectedRef) || observation.data.text("branch").let { it.isBlank() || it == selectedRef })
        }
        fun call(name: String, values: JsonObject): ProviderEvent.ToolCall? {
            val arguments = JsonObject(seed.scope.filterKeys { it in setOf("owner", "repo") } + values)
            val action = arguments.text("action")
            val selectedRepository = repository(arguments) ?: return null
            // A rejected, failed or previously completed read is not retried automatically.
            // Compare the request's target, not its response target: a mismatched
            // receipt must not reopen the same request or suppress another repository.
            if (observations.values.any { observation ->
                    observation.call.name == name &&
                        observation.requestedRepository?.let { requested ->
                            (requested.first ?: selectedRepository.first) == selectedRepository.first &&
                                (requested.second ?: selectedRepository.second) == selectedRepository.second
                        } == true &&
                        observation.call.arguments.text("action") == action &&
                        values.filterKeys { it !in setOf("per_page", "page") }.all { (key, value) ->
                            val actual = observation.call.arguments[key]
                            actual == value || key == "commit_sha" && observation.call.arguments["head_sha"] == value
                        }
                }
            ) {
                return null
            }
            val tool = tools.firstOrNull {
                it.modelVisible && operationIdentity(it.name) == name && ToolArgumentValidator.errors(arguments, it.inputSchema).isEmpty()
            } ?: return null
            return ProviderEvent.ToolCall(PREFIX + GitHubEvidenceBook.digest(name + arguments.toString()).take(24), tool.name, arguments)
        }
        // Workflow discovery ignores SHA filters; echoed request fields are not a
        // revision observation. Only a matching successful status supplies the head.
        val head = scoped.lastOrNull {
            it.successful &&
                it.call.name == contextName &&
                it.call.arguments.text("action") == "status" &&
                (!SHA.matches(selectedRef) || it.data.text("commit_sha").equals(selectedRef, ignoreCase = true))
        }
            ?.data?.text("commit_sha")?.takeIf(SHA::matches)
        if (head == null) {
            return call(
                contextName,
                buildJsonObject {
                    put("action", "status")
                    selectedRef.takeIf(String::isNotBlank)?.let { put("ref", it) }
                }
            )
        }
        call(
            ciName,
            buildJsonObject {
                put("action", "runs")
                put("commit_sha", head)
                put("per_page", 100)
                put("page", 1)
            }
        )?.let { return it }
        val runs = scoped.filter { it.successful && it.call.name == ciName && it.call.arguments.text("action") == "runs" }
            .flatMap { (it.data["workflow_runs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
            .filter { it.text("head_sha").equals(head, ignoreCase = true) && it.positiveLong("id") != null && it.positiveInt("run_attempt") != null }
            .groupBy { it.positiveLong("id") }
            .values.mapNotNull { attempts -> attempts.maxByOrNull { requireNotNull(it.positiveInt("run_attempt")) } }
            .filter { it.text("status") == "completed" && it.text("conclusion") in FAILED_CONCLUSIONS }
        for (run in runs.take(2)) {
            val runId = requireNotNull(run.positiveLong("id"))
            val attempt = requireNotNull(run.positiveInt("run_attempt"))
            val identity = buildJsonObject {
                put("run_id", runId)
                put("run_attempt", attempt)
                put("expected_head_sha", head)
            }
            call(
                ciName,
                JsonObject(
                    identity + buildJsonObject {
                        put("action", "jobs")
                        put("per_page", 100)
                        put("page", 1)
                    }
                )
            )?.let { return it }
            val jobs = scoped.filter {
                it.successful &&
                    it.call.name == ciName &&
                    it.call.arguments.text("action") == "jobs" &&
                    it.data.positiveLong("run_id") == runId &&
                    it.data.positiveInt("run_attempt") == attempt &&
                    it.data.text("head_sha").equals(head, ignoreCase = true)
            }.flatMap { (it.data["jobs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
                .filter {
                    it.positiveLong("id") != null &&
                        it.text("status") == "completed" &&
                        it.text("conclusion") in FAILED_CONCLUSIONS &&
                        (it["run_id"] == null || it.positiveLong("run_id") == runId) &&
                        (it["run_attempt"] == null || it.positiveInt("run_attempt") == attempt) &&
                        (it["head_sha"] == null || it.text("head_sha").equals(head, ignoreCase = true))
                }.distinctBy { it.positiveLong("id") }
            for (job in jobs.take(2)) {
                val jobId = requireNotNull(job.positiveLong("id"))
                if (scoped.any { observation ->
                        val gate = observation.data["apk_size_failure"] as? JsonObject
                        observation.successful &&
                            observation.call.name == ciName &&
                            gate?.get("run_id") == JsonPrimitive(runId) &&
                            gate["run_attempt"] == JsonPrimitive(attempt) &&
                            gate["job_id"] == JsonPrimitive(jobId) &&
                            gate.text("head_sha") == head
                    }
                ) {
                    continue
                }
                val logIdentity = JsonObject(
                    identity + buildJsonObject {
                        put("action", "logs")
                        put("job_id", jobId)
                    }
                )
                call(
                    ciName,
                    JsonObject(
                        logIdentity + buildJsonObject {
                            put("start_line", 1)
                            put("end_line", 200)
                        }
                    )
                )?.let { return it }
                val lines = scoped.lastOrNull {
                    it.successful && it.call.name == ciName && logIdentity.all { (key, value) -> it.call.arguments[key] == value }
                }?.data?.positiveInt("total_lines") ?: continue
                if (lines > 200) {
                    call(
                        ciName,
                        JsonObject(
                            logIdentity + buildJsonObject {
                                put("start_line", maxOf(201, lines - 199))
                                put("end_line", lines)
                            }
                        )
                    )?.let { return it }
                }
            }
        }
        return null
    }

    private fun operationIdentity(name: String): String? {
        val facade = name.substringBefore("__")
        val operation = when (facade) {
            "github_context", "github_context_inspect" -> "github_context"
            "github_ci" -> "github_ci"
            else -> return null
        }
        // The facade can change, but the complete connection suffix remains part
        // of read identity so aliases cannot reopen a consumed request.
        return operation + name.removePrefix(facade)
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun repository(arguments: JsonObject): Pair<String?, String?>? = runCatching {
        val (owner, repo) = normalizeGitHubRepository(arguments.text("owner").takeIf(String::isNotBlank), arguments.text("repo").takeIf(String::isNotBlank))
        owner?.lowercase(Locale.ROOT) to repo?.lowercase(Locale.ROOT)
    }.getOrNull()
    private fun JsonObject.reference() = text("ref").ifBlank { text("branch") }.ifBlank { text("selected_ref") }
    private fun JsonObject.positiveLong(key: String) = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    private fun JsonObject.positiveInt(key: String) = (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    companion object {
        private const val PREFIX = "ci-investigation-read-"
        private const val MAX_AUTOMATIC_READS = 12
        private val SHA = Regex("[a-fA-F0-9]{40}")
        private val FAILED_CONCLUSIONS = setOf("failure", "timed_out", "action_required", "startup_failure")
    }
}
