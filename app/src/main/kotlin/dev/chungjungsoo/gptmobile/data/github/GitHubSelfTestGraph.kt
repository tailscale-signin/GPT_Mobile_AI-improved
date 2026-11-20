package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Revision-bound graph preparation. Index calls retain the repository's durable checkpoint gate. */
internal object GitHubSelfTestGraph {
    private data class Evidence(val sha: String, val observations: List<JsonObject>, val graph: List<JsonObject>) {
        val preparation = graph.lastOrNull { it.action() in setOf("status", "index") }
        val state = preparation?.takeIf { it["success"] == JsonPrimitive(true) }?.let(GitHubInventoryObservation::planningRoot)
        val job = state?.get("index_job") as? JsonObject
        val indexed = state?.get("has_more") == JsonPrimitive(false) && (job == null || job.text("state") == "COMPLETED")
    }

    private val pendingJobStates = setOf("QUEUED", "RUNNING", "PAUSED", "INTERRUPTED")

    private fun evidence(receipts: List<JsonObject>): Evidence? {
        val trusted = receipts.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE")
        }
        val source = trusted.lastOrNull {
            it["success"] == JsonPrimitive(true) && it.text("tool").githubOperationFamily() == "github_context" && it.action() == "status"
        } ?: return null
        val selected = GitHubInventoryObservation.planningRoot(source) ?: return null
        val sha = selected.text("commit_sha").takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return null
        val repository = source.text("repository").takeIf { it.isNotBlank() } ?: return null
        if (source.text("binding").isBlank()) return null
        val observations = trusted.filter { it["binding"] == source["binding"] && it["repository"] == source["repository"] }
        val graph = observations.filter { receipt ->
            if (receipt.text("tool").githubOperationFamily() != "repository_graph") return@filter false
            if (receipt["success"] != JsonPrimitive(true)) return@filter true
            val root = GitHubInventoryObservation.planningRoot(receipt) ?: return@filter false
            val metadata = (root["graph"] as? JsonObject) ?: root
            metadata.text("repository").equals(repository, ignoreCase = true) &&
                metadata.text("commit_sha").equals(sha, ignoreCase = true) &&
                (metadata["branch"] == null || selected["branch"] == null || metadata["branch"] == selected["branch"])
        }
        val selectedTool = graph.lastOrNull { it.action() in setOf("status", "index") }?.text("tool")
        return Evidence(sha, observations, graph.filter { it.text("tool") == selectedTool })
    }

    /** A fresh Continue can observe the same attempt again. A single request cannot poll indefinitely. */
    fun statusForPendingIndex(pending: JsonObject, tools: List<AgentToolDefinition>, receipts: Collection<JsonObject>, requestKey: String): ProviderEvent.ToolCall? {
        val attempt = (pending["execution"] as? JsonObject)?.text("graph_index_attempt_hash") ?: return null
        if (!attempt.matches(Regex("[a-f0-9]{64}"))) return null
        val args = buildJsonObject {
            put("action", "status")
            put("options", buildJsonObject { put("expected_index_attempt_hash", attempt) })
        }
        val tool = tools.singleOrNull { it.modelVisible && it.name == pending.text("tool") && it.name.githubOperationFamily() == "repository_graph" } ?: return null
        if (ToolArgumentValidator.errors(args, tool.inputSchema).isNotEmpty()) return null
        val id = "graph-status-" + GitHubEvidenceBook.digest("${pending.text("binding")}:$attempt:$requestKey").take(24)
        if (receipts.any { it["call_id"] == JsonPrimitive(id) }) return null
        return ProviderEvent.ToolCall(id, tool.name, args)
    }

    /** Acknowledged jobs keep running on the host. Each request observes them once without re-indexing. */
    fun statusForSavedIndex(tools: List<AgentToolDefinition>, receipts: List<JsonObject>, requestKey: String): ProviderEvent.ToolCall? {
        val evidence = evidence(receipts) ?: return null
        val observed = evidence.graph.lastOrNull { it["success"] == JsonPrimitive(true) && it.action() in setOf("status", "index") } ?: return null
        val job = GitHubInventoryObservation.planningRoot(observed)?.get("index_job") as? JsonObject ?: return null
        val needsSnapshot = observed.action() == "index" && job.text("state") == "COMPLETED" && evidence.state?.get("has_more") != JsonPrimitive(false)
        if ((job.text("state") !in pendingJobStates && !needsSnapshot) || job.text("id").isBlank()) return null
        val generation = (job["generation"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return null
        val args = buildJsonObject { put("action", "status") }
        val tool = tools.singleOrNull {
            it.modelVisible && it.name == observed.text("tool") && ToolArgumentValidator.errors(args, it.inputSchema).isEmpty()
        } ?: return null
        val id = "graph-job-status-" + GitHubEvidenceBook.digest("${observed.text("binding")}:${evidence.sha}:${tool.name}:${job.text("id")}:$generation:$requestKey").take(24)
        if (receipts.any { it["call_id"] == JsonPrimitive(id) }) return null
        return ProviderEvent.ToolCall(id, tool.name, args)
    }

    fun available(tools: List<AgentToolDefinition>, receipts: List<JsonObject>): List<ProviderEvent.ToolCall> {
        val evidence = evidence(receipts) ?: return emptyList()
        // A failed index can have changed remote state. Only an independently
        // successful status reconciliation may unlock a subsequent preparation.
        // Read status once; never turn a missing mutation response into a replay.
        if (evidence.preparation?.action() == "index" && evidence.preparation["success"] != JsonPrimitive(true)) {
            val args = buildJsonObject { put("action", "status") }
            val tool = tools.singleOrNull {
                it.modelVisible && it.name == evidence.preparation.text("tool") && ToolArgumentValidator.errors(args, it.inputSchema).isEmpty()
            } ?: return emptyList()
            return listOf(ProviderEvent.ToolCall("graph-reconciliation-" + GitHubEvidenceBook.digest("${evidence.sha}:${tool.name}:${evidence.preparation}").take(24), tool.name, args))
        }
        val state = evidence.state ?: return emptyList()
        if (evidence.preparation?.get("success") != JsonPrimitive(true)) return emptyList()
        // An accepted background job owns its cursor. A status read, not another
        // index mutation, observes progress; stopped jobs require explicit action.
        if (evidence.job != null && !evidence.indexed) return emptyList()
        val recipes = buildList {
            fun recipe(action: String, vararg fields: Pair<String, JsonElement>) = add(
                buildJsonObject {
                    put("action", action)
                    fields.forEach { (key, value) -> put(key, value) }
                }
            )
            if (state.text("status") == "NOT_INDEXED") {
                recipe("index")
                return@buildList
            }
            if (state["has_more"] == JsonPrimitive(true)) {
                state.text("index_cursor").takeIf { it.isNotBlank() }?.let { cursor ->
                    recipe("index", "options" to buildJsonObject { put("index_cursor", cursor) })
                }
                return@buildList
            }
            if (!evidence.indexed) return@buildList
            listOf("architecture", "notes", "requirements", "wiki", "metrics", "prs").forEach { recipe(it) }
            val observations = evidence.graph.filter { it["success"] == JsonPrimitive(true) }.mapNotNull { receipt ->
                GitHubInventoryObservation.planningRoot(receipt)?.let { receipt.action() to it }
            }
            fun sourceNodes(root: JsonObject) = (root["nodes"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { node ->
                    listOf("id", "path").all { key -> (node[key] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true } &&
                        (node["commit_sha"] == null || node.text("commit_sha").equals(evidence.sha, ignoreCase = true))
                }
            val roots = observations.map { it.second }
            val architecture = observations.lastOrNull { it.first == "architecture" }?.second
            val nodes = (observations.filter { it.first == "query" }.flatMap { sourceNodes(it.second) } + roots.flatMap(::sourceNodes))
                .distinctBy { it["id"] }.sortedBy { it.text("kind") == "file" }
            val legacyPaths = (architecture?.get("communities") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .flatMap { (it["files"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>() }.filter { it.isString && it.content.isNotBlank() }
            // Paged architecture returns file nodes; their immutable revision is
            // carried by the validated graph envelope, with optional node checks.
            val paths = (legacyPaths + nodes.map { it.getValue("path") }).distinct()
            paths.firstOrNull()?.let { path ->
                recipe(
                    "query",
                    "query" to path,
                    "options" to buildJsonObject {
                        put("limit", 8)
                        put("depth", 1)
                    }
                )
            }
            nodes.firstOrNull()?.get("id")?.let { symbol ->
                listOf("node", "callers", "callees", "impact").forEach { recipe(it, "symbol" to symbol) }
            }
            val ids = nodes.mapNotNull { it["id"] }.toSet()
            // Architecture depends_on edges aggregate file relationships. Only
            // actual query/traversal relationships can supply a direct path probe.
            observations.filter { it.first in setOf("query", "node", "callers", "callees", "impact", "path") }
                .sortedBy { it.first != "query" }.flatMap { (_, root) -> (root["edges"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
                .filter { it.text("relation") != "depends_on" }.sortedBy { it.text("relation") == "contains" }
                .firstOrNull { it["source"] in ids && it["target"] in ids && it["source"] != it["target"] }?.let { edge ->
                    recipe(
                        "path",
                        "options" to buildJsonObject {
                            put("source", edge.getValue("source"))
                            put("target", edge.getValue("target"))
                            put("depth", 2)
                        }
                    )
                }
            // A real source dependency supplies an inspected contract pair. The
            // result remains a source-level candidate, never protocol/test proof.
            val knownPaths = paths.toSet()
            val legacyContract = (architecture?.get("dependencies") as? JsonObject).orEmpty().entries.firstNotNullOfOrNull { (consumer, targets) ->
                if (JsonPrimitive(consumer) !in knownPaths) return@firstNotNullOfOrNull null
                (targets as? JsonArray).orEmpty().firstOrNull { it in knownPaths && it != JsonPrimitive(consumer) }?.let { producer ->
                    buildJsonObject {
                        put("producer", producer)
                        put("consumer", consumer)
                    }
                }
            }
            val filePaths = architecture?.let(::sourceNodes).orEmpty().associate { it.getValue("id") to it.getValue("path") }
            val pagedContract = (architecture?.get("edges") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstNotNullOfOrNull { edge ->
                if (edge.text("relation") != "depends_on") return@firstNotNullOfOrNull null
                val consumer = filePaths[edge["source"]] ?: return@firstNotNullOfOrNull null
                val producer = filePaths[edge["target"]]?.takeIf { it != consumer } ?: return@firstNotNullOfOrNull null
                buildJsonObject {
                    put("producer", producer)
                    put("consumer", consumer)
                }
            }
            (legacyContract ?: pagedContract)?.let { recipe("contracts", "options" to it) }
            evidence.observations.asReversed().firstNotNullOfOrNull { receipt ->
                val args = receipt["arguments"] as? JsonObject ?: return@firstNotNullOfOrNull null
                if (receipt["success"] != JsonPrimitive(true) || receipt.text("tool").githubOperationFamily() != "github_ci" || args.text("action") != "logs" || args.text("expected_head_sha") != evidence.sha) return@firstNotNullOfOrNull null
                val root = GitHubInventoryObservation.planningRoot(receipt) ?: return@firstNotNullOfOrNull null
                (root["excerpts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { it.text("text") }.joinToString("\n")
                    .takeIf { it.isNotBlank() }?.take(24000)
            }?.let { recipe("diagnose", "options" to buildJsonObject { put("log", it) }) }
        }
        return recipes.mapNotNull { args ->
            val tool = tools.firstOrNull {
                it.modelVisible && it.name == evidence.preparation.text("tool") && ToolArgumentValidator.errors(args, it.inputSchema).isEmpty()
            } ?: return@mapNotNull null
            val prior = evidence.graph.filter { it.text("tool") == tool.name && it.action() == args.text("action") }
            val continuation = args.text("action") == "index" && (args["options"] as? JsonObject)?.get("index_cursor") != null
            if (prior.any { it["arguments"] == args } || !continuation && prior.isNotEmpty()) return@mapNotNull null
            ProviderEvent.ToolCall("graph-preparation-" + GitHubEvidenceBook.digest("${evidence.sha}:${tool.name}:$args").take(24), tool.name, args)
        }
    }

    fun incomplete(action: String, receipt: JsonObject): Boolean = incomplete(action, receipt, emptyList())

    fun incomplete(action: String, receipt: JsonObject, receipts: List<JsonObject>): Boolean {
        if (action == "status" || receipt["success"] != JsonPrimitive(true)) return false
        val root = GitHubInventoryObservation.planningRoot(receipt) ?: return false
        val job = root["index_job"] as? JsonObject
        val incomplete = root.text("status") == "NOT_INDEXED" ||
            action == "index" &&
            (root["has_more"] != JsonPrimitive(false) || job != null && job.text("state") != "COMPLETED")
        if (!incomplete || action != "index" || job == null || job.text("id").isBlank()) return incomplete
        val generation = (job["generation"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return true
        val graph = evidence(receipts)?.graph ?: return true
        val original = graph.indexOf(receipt).takeIf { it >= 0 } ?: return true
        val observed = graph.drop(original + 1).lastOrNull { it.action() == "status" && it["success"] == JsonPrimitive(true) }
            ?.let(GitHubInventoryObservation::planningRoot) ?: return true
        val completed = observed["index_job"] as? JsonObject ?: return true
        return completed["id"] != job["id"] ||
            completed["generation"] != JsonPrimitive(generation) ||
            completed["state"] != JsonPrimitive("COMPLETED") ||
            observed["has_more"] != JsonPrimitive(false)
    }

    /** A selected repository is already known; distinguish graph preparation from missing source selectors. */
    fun blocker(action: String, receipts: List<JsonObject>): GitHubCriterionBlocker? {
        if (action == "status") return null
        val evidence = evidence(receipts) ?: return GitHubCriterionBlocker.REPOSITORY_INPUT_REQUIRED
        if (evidence.preparation == null) return GitHubCriterionBlocker.GRAPH_INDEX_REQUIRED
        if (evidence.preparation["success"] != JsonPrimitive(true)) return GitHubCriterionBlocker.GRAPH_INDEX_UNCONFIRMED
        if (evidence.job != null && !evidence.indexed) return GitHubCriterionBlocker.CONTINUATION_REQUIRED
        if (evidence.state?.text("status") == "NOT_INDEXED") {
            return if (evidence.graph.any { it.action() == "index" }) GitHubCriterionBlocker.GRAPH_INDEX_UNCONFIRMED else GitHubCriterionBlocker.GRAPH_INDEX_REQUIRED
        }
        if (!evidence.indexed) return GitHubCriterionBlocker.CONTINUATION_REQUIRED
        return if (action in setOf("query", "node", "callers", "callees", "path", "impact", "contracts", "diff", "diagnose")) GitHubCriterionBlocker.GRAPH_INPUT_REQUIRED else null
    }

    fun requiredInputs(action: String, receipts: List<JsonObject>): List<String> {
        val evidence = evidence(receipts) ?: return listOf("Trusted selected repository status with the exact immutable commit SHA and task binding")
        if (action == "status") return listOf("Authorized graph status for the selected repository, branch and immutable revision")
        if (evidence.preparation == null) return listOf("Successful repository_graph status for the selected revision before preparing its index")
        if (evidence.preparation["success"] != JsonPrimitive(true)) return listOf("Resolve the recorded graph admission or transport failure; reconcile an uncertain index with status on the same gateway before continuing")
        if (evidence.job != null && !evidence.indexed) {
            return listOf("Observe the saved indexing job on the same gateway and revision; let active work finish, or explicitly resume a paused job. Do not send another index batch while that job owns the cursor.")
        }
        if (evidence.state?.text("status") == "NOT_INDEXED") {
            return if (evidence.graph.any { it.action() == "index" }) {
                listOf("The index attempt is unconfirmed and status has not proved progress; reconcile the retained checkpoint on the original gateway before retrying")
            } else {
                listOf("Build the selected revision's graph with repository_graph action=index through its checkpoint gate")
            }
        }
        if (!evidence.indexed) return listOf("Continue the exact observed index_cursor until has_more=false, within the retained call budget and cleanup reserve")
        return when (action) {
            "diff" -> listOf("A distinct exact base_sha whose graph is already indexed under the same repository, branch and gateway principal; selecting a different revision is not an automatic smoke-test action")
            "diagnose" -> listOf("A non-empty bounded log excerpt from the selected revision's observed job; synthetic failure text cannot establish diagnostic coverage")
            "contracts" -> listOf("Two indexed source paths with an observed dependency to inspect as the producer and consumer")
            "query" -> listOf("An indexed source path observed in graph architecture")
            "node", "callers", "callees", "impact" -> listOf("An exact node ID observed in a successful query of this repository revision")
            "path" -> listOf("Two observed node IDs joined by an edge in this repository revision")
            "note", "federated", "provider" -> listOf("Separately authorized graph write or configuration inputs; the integration read planner does not infer these")
            else -> emptyList()
        }
    }

    private fun JsonObject.action() = (get("arguments") as? JsonObject)?.text("action").orEmpty()
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
