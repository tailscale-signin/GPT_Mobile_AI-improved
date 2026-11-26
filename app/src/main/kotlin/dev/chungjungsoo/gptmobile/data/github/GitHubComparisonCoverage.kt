package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Monotonic coverage of one immutable comparison; a deliberate live first page starts a new generation. */
internal object GitHubComparisonCoverage {
    data class Coverage(val receipts: Map<String, JsonObject>, val pending: List<JsonObject>, val complete: Boolean, val identity: String, val generation: String)

    fun observe(receipts: Map<String, JsonObject>, root: (JsonObject) -> Boolean): Coverage? {
        val observations = receipts.filterValues { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
        val roots = observations.filterValues { it["success"] == JsonPrimitive(true) && root(it) }
        val anchor = roots.values.lastOrNull { page(it) == 1 } ?: roots.values.firstOrNull() ?: return null
        val identity = identity(anchor)
        val entries = observations.entries.toList()
        val refresh = entries.indexOfLast { (_, row) ->
            root(row) &&
                identity(row) == identity &&
                page(row) == 1 &&
                (arguments(row)["verify_live"] == JsonPrimitive(true) || arguments(row)["refresh"] == JsonPrimitive(true))
        }
        fun direct(row: JsonObject) = row["shared_result"] != JsonPrimitive(true) && row["source_observation_receipt_id"] == null
        fun generation(row: JsonObject) = GitHubComparisonPage.generation(row["execution"] as? JsonObject)
        val issued = entries.mapIndexedNotNull { index, (_, row) ->
            if (!root(row) || identity(row) != identity || !direct(row)) return@mapIndexedNotNull null
            generation(row)?.let { index to it }
        }
        val firstIssued = linkedMapOf<String, Int>()
        val namespaces = linkedMapOf<String, Int>()
        issued.forEach { (index, generation) ->
            firstIssued.putIfAbsent(generation.identity, index)
            namespaces.putIfAbsent(generation.namespace, index)
        }
        val legacyRefresh = entries.indexOfLast { (_, row) ->
            root(row) &&
                identity(row) == identity &&
                direct(row) &&
                generation(row) == null &&
                page(row) == 1 &&
                (arguments(row)["verify_live"] == JsonPrimitive(true) || arguments(row)["refresh"] == JsonPrimitive(true))
        }
        val refreshes = issued.map { it.second }.filter { it.startsLiveRefresh && firstIssued.getValue(it.identity) > legacyRefresh }
        val currentNamespace = refreshes.maxByOrNull { namespaces.getValue(it.namespace) }?.namespace
        val currentGeneration = refreshes.filter { it.namespace == currentNamespace }.maxByOrNull { it.ordinal }
        val generationRows = if (currentGeneration == null) {
            // Persisted legacy observations cannot establish dispatch generation ownership.
            entries.drop(refresh.coerceAtLeast(0))
                .filter { (_, row) -> arguments(row)["action"] != JsonPrimitive("changes") || identity(row) == identity }
        } else {
            val first = firstIssued.getValue(currentGeneration.identity)
            val namespaceStart = namespaces.getValue(currentGeneration.namespace)
            entries.filterIndexed { index, (_, row) ->
                if (arguments(row)["action"] != JsonPrimitive("changes")) return@filterIndexed true
                if (identity(row) != identity) return@filterIndexed false
                val observed = generation(row)
                when {
                    observed == null -> index >= first && index > legacyRefresh && direct(row)
                    observed.namespace == currentGeneration.namespace -> observed.ordinal >= currentGeneration.ordinal
                    else -> namespaces[observed.namespace]?.let { it > namespaceStart } == true
                }
            }
        }.associate { it.toPair() }
        val chain = GitHubTestChecklist.paginationReceipts(generationRows) { root(it) && identity(it) == identity }
        val observedPages = chain.values.filter { row ->
            row["success"] == JsonPrimitive(true) ||
                row["error_code"] == JsonPrimitive("GITHUB_PAGINATION_INCOMPLETE") &&
                data(row)["reason"] == JsonPrimitive("INCOMPLETE_REMOTE_COVERAGE") &&
                (data(row)["files"] as? kotlinx.serialization.json.JsonArray) != null
        }
        val completed = observedPages.filter { it["success"] == JsonPrimitive(true) }
        val numbered = observedPages.filter(root).map(::page).toSet()
        val consistent = observedPages.none {
            val result = data(it)
            (result["pagination_progress"] as? JsonObject)?.get("upstream_changed_during_traversal") == JsonPrimitive(true) ||
                result["pagination_inconsistent"] == JsonPrimitive(true) ||
                result["tree_complete"] == JsonPrimitive(false) ||
                root(it) &&
                GitHubComparisonPage.observedSize(result) == null
        }
        val terminal = completed.any { row ->
            val result = data(row)
            val progress = result["pagination_progress"] as? JsonObject
            val lastPage = (progress?.get("terminal_page") as? JsonPrimitive)?.intOrNull ?: page(row)
            result["has_more"] == JsonPrimitive(false) &&
                result["complete"] != JsonPrimitive(false) &&
                result["file_inventory_complete"] != JsonPrimitive(false) &&
                (result["changed_file_coverage"] as? JsonObject)?.get("complete") != JsonPrimitive(false) &&
                progress?.get("complete") != JsonPrimitive(false) &&
                progress?.get("upstream_changed_during_traversal") != JsonPrimitive(true) &&
                lastPage in 1..1000 &&
                (1..lastPage).all { it in numbered }
        }
        // A late interior page can establish aggregate completion after a terminal
        // page reported a missing gap. Its retained rows satisfy that exact remote
        // page only after the same generation proves complete, consistent coverage.
        // Local fragments and the original failed receipt remain unchanged.
        val pending = GitHubTestChecklist.pendingPages(chain).filterNot { recipe ->
            terminal && consistent && observedPages.any { root(it) && GitHubTestChecklist.sameCall(it, recipe, anchor) }
        }
        val generation = currentGeneration?.identity?.let(GitHubEvidenceBook::digest)
            ?: if (refresh < 0) "RETAINED" else GitHubEvidenceBook.digest(entries[refresh].key)
        return Coverage(chain, pending, terminal && consistent && pending.isEmpty(), identity, generation)
    }

    private fun identity(row: JsonObject): String {
        val query = GitHubTestChecklist.comparisonPageArguments(row.text("tool"), arguments(row), row.text("repository"), GitHubComparisonPage.observedSize(data(row)) ?: -1)
            .filterKeys { it !in setOf("page", "verify_live", "refresh", "response_detail") }
        return GitHubEvidenceBook.digest(
            buildJsonObject {
                put("binding", row["binding"] ?: JsonPrimitive(""))
                put("repository", row["repository"] ?: JsonPrimitive(""))
                put("tool", row.text("tool"))
                put("base_sha", data(row).text("base_sha"))
                put("head_sha", data(row).text("head_sha"))
                put("query", JsonObject(query.toSortedMap()))
            }.toString()
        )
    }

    private fun arguments(row: JsonObject) = row["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    private fun data(row: JsonObject) = GitHubSemanticSummary.data(row["result"] as? JsonObject ?: JsonObject(emptyMap()))
    private fun page(row: JsonObject) = ((arguments(row)["page"] ?: data(row)["page"]) as? JsonPrimitive)?.intOrNull ?: 1
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
