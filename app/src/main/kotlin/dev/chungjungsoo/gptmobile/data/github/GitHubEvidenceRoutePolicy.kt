package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A run-local comparison between already admitted ways to advance one typed proof obligation. */
internal class GitHubEvidenceRoutePolicy(
    private val measuredRunTokens: suspend () -> Int? = { null },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    enum class Route { CACHED_RANGE, LIVE_LOOKUP, REVIEWER }

    private data class Key(val producer: String, val route: Route)
    private data class Attempt(val key: Key, val criterion: String, val started: Long, val startTokens: Int?)
    private data class Measurement(val samples: Int = 0, val verified: Int = 0, val elapsed: Long = 0, val tokens: Long = 0, val metered: Int = 0) {
        val meanTokens: Double? get() = if (metered > 0) tokens.toDouble() / metered else null
    }

    private val history = mutableMapOf<Key, Measurement>()
    private var attempt: Attempt? = null
    private val pendingKeys = mutableSetOf<Key>()
    private val outcomes = mutableMapOf<Key, MutableMap<String, Int>>()
    private val shadow = mutableMapOf<String, Pair<Route, Route>>()

    private fun record(key: Key, outcome: String) {
        val counts = outcomes.getOrPut(key) { mutableMapOf() }
        counts[outcome] = (counts[outcome] ?: 0) + 1
    }

    fun diagnostics(): JsonObject = buildJsonObject {
        put("scope", "CURRENT_BOUND_RUN")
        put("shadow_only", true)
        put(
            "observations",
            JsonArray(
                outcomes.map { (key, counts) ->
                    buildJsonObject {
                        put("family", key.producer)
                        put("route", key.route.name)
                        counts.forEach { (outcome, count) -> put(outcome, count) }
                    }
                }
            )
        )
        put(
            "comparisons",
            JsonArray(
                shadow.map { (context, routes) ->
                    buildJsonObject {
                        put("family_and_admitted_capabilities", context)
                        put("selected", routes.first.name)
                        put("shadow", routes.second.name)
                    }
                }
            )
        )
    }

    fun choose(producer: String, admitted: Set<Route>): List<Route> {
        val options = when (producer) {
            "SOURCE_OBSERVATION" -> listOf(Route.CACHED_RANGE, Route.LIVE_LOOKUP)
            "DEFECT_ANALYSIS", "DIFF_REVIEW" -> Route.entries
            else -> listOf(Route.LIVE_LOOKUP)
        }
        val available = options.filter { it in admitted }
        val knownCosts = available.mapNotNull { history[Key(producer, it)]?.meanTokens }
        val meanCost = knownCosts.takeIf { it.isNotEmpty() }?.average()
        val selected = available.sortedByDescending { route ->
            // Smoothed verified criteria per second. An unseen route retains an exploration
            // prior. Wall time includes verification and is never presented as API latency.
            val m = history[Key(producer, route)] ?: Measurement()
            // Unknown token costs receive the admitted routes' observed mean. Only actual
            // complete provider totals contribute to that mean, including measured zero.
            val tokenCost = if (meanCost == null) 1.0 else 1.0 + (m.meanTokens ?: meanCost) / (1.0 + meanCost)
            (m.verified + 1.0) / (m.samples + 2.0) / (1.0 + m.elapsed / (m.samples + 1.0) / 1000.0) / tokenCost
        }
        if (selected.isNotEmpty()) {
            val alternative = available.maxBy { route ->
                val counts = outcomes[Key(producer, route)].orEmpty()
                val pass = counts["verified"] ?: 0
                val fail = counts["failed"] ?: 0
                (pass + 1.0) / (pass + fail + 2.0)
            }
            shadow[producer + "/" + available.joinToString("+") { it.name }] = selected.first() to alternative
        }
        return selected
    }

    suspend fun started(producer: String, route: Route, criterion: String) = begin(producer, route, criterion, monotonicMillis(), measuredRunTokens())

    private fun begin(producer: String, route: Route, criterion: String, startMillis: Long, tokens: Int?) {
        val key = Key(producer, route)
        attempt?.let { pendingKeys.add(it.key) }
        if (attempt != null) pendingKeys.add(key)
        attempt = Attempt(key, criterion, startMillis, tokens)
    }

    suspend fun <T> review(producer: String, criterion: String, open: suspend () -> T?): T? {
        val start = monotonicMillis()
        val tokens = measuredRunTokens()
        val result = open() ?: run {
            record(Key(producer, Route.REVIEWER), "unavailable")
            return null
        }
        begin(producer, Route.REVIEWER, criterion, start, tokens)
        return result
    }

    /** Only a newly computed verifier checkpoint provides a yield observation. */
    suspend fun observed(report: JsonObject) {
        val pending = attempt ?: return
        attempt = null
        if (pendingKeys.isNotEmpty()) {
            pendingKeys.forEach { record(it, "joint") }
            pendingKeys.clear()
            return
        }
        val rows = (report["acceptance_ledger"] as? JsonObject)?.get("requirements") as? JsonArray
        val row = rows?.filterIsInstance<JsonObject>()?.singleOrNull { it["id"] == JsonPrimitive(pending.criterion) && it["required"] == JsonPrimitive(true) }
        val state = row?.get("state") as? JsonPrimitive
        if (state == null) {
            record(pending.key, "unobserved")
            return
        }
        record(
            pending.key,
            when (state.content) {
                "PASS" -> "verified"
                "FAIL", "FAILED", "CONTRADICTED" -> "failed"
                else -> "pending"
            }
        )
        val old = history[pending.key] ?: Measurement()
        val delta = pending.startTokens?.let { before -> measuredRunTokens()?.takeIf { it >= before && it < Int.MAX_VALUE }?.minus(before) }
        if (delta == null) record(pending.key, "unmetered")
        history[pending.key] = Measurement(
            old.samples + 1,
            old.verified + if (state.content == "PASS") 1 else 0,
            old.elapsed + (monotonicMillis() - pending.started).coerceAtLeast(0),
            old.tokens + (delta ?: 0),
            old.metered + if (delta != null) 1 else 0
        )
    }

    companion object {
        private val sha = Regex("[a-fA-F0-9]{40}")

        fun relevantLookup(producer: String, nativeAction: String?): Boolean = when (nativeAction) {
            "read_ranges", "search_repository" -> true
            "changed_since", "pr_context", "get_commit_detail" -> producer in setOf("DIFF_REVIEW", "DEFECT_ANALYSIS")
            "issue_context", "ci_failure_digest" -> producer == "DEFECT_ANALYSIS"
            else -> false
        }

        fun cachedRange(receipt: JsonObject?, epoch: Long, head: String, authorized: Set<String>): Boolean {
            if (receipt == null || !sha.matches(head)) return false
            val tool = (receipt["tool"] as? JsonPrimitive)?.content ?: return false
            val args = receipt["arguments"] as? JsonObject ?: return false
            return tool in authorized &&
                tool.githubOperationFamily() == "github_read" &&
                receipt["trusted"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] == JsonPrimitive(true) &&
                receipt["success"] == JsonPrimitive(true) &&
                receipt["immutable"] == JsonPrimitive(true) &&
                receipt["repository_role"] == JsonPrimitive("WRITE_DESTINATION") &&
                receipt["epoch"] == JsonPrimitive(epoch) &&
                args["commit_sha"] == JsonPrimitive(head) &&
                (args["ranges"] as? JsonArray)?.isNotEmpty() == true
        }
    }
}
