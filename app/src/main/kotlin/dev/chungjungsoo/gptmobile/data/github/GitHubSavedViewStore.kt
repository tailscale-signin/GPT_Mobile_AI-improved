package dev.chungjungsoo.gptmobile.data.github

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Captured responses are independent of the page cache and never require replaying their operation. */
internal class GitHubSavedViewStore(private val state: GitHubRepositoryStateStore, private val scope: String) {
    suspend fun save(id: String, snapshot: JsonObject) {
        require(validId(id))
        val bodyKey = "saved-view-body:$id"
        val issued = requireNotNull(snapshot["issued_cursors"] as? JsonArray) { "Saved view requires an issued cursor array" }
        require(
            issued.size <= 16_384 &&
                issued.all { cursor ->
                    (cursor as? JsonPrimitive)?.takeIf { it.isString }?.content?.startsWith("$id:") == true
                }
        ) { "Saved view requires bounded cursors issued for this identity" }
        // Recover the first immutable capture before appending subsequently issued handles.
        val persistedBody = state.retainSavedViewBody(scope, id) {
            require(issued.isNotEmpty()) { "Saved view requires an initial cursor" }
            JsonObject(
                snapshot - setOf("issued_cursors", "failed_persistence_cursors") +
                    ("bootstrap_issued_cursors" to issued)
            )
        }
        for (key in listOf("scope", "operation", "model_name", "source_sha256", "source_call_id")) {
            check(snapshot[key] == null || persistedBody[key] == snapshot[key]) { "Saved view source identity changed." }
        }
        val pinnedSource = persistedBody["source_recovery"] as? JsonObject
        val recovery = if (persistedBody["operation"] == JsonPrimitive("github_read") &&
            persistedBody["original_error"] == JsonPrimitive(false) &&
            pinnedSource != null
        ) {
            JsonObject(persistedBody.filterKeys { it in setOf("scope", "operation", "model_name", "source_sha256", "source_recovery", "source_call_id") })
        } else {
            null
        }
        val bootstrap = (persistedBody["bootstrap_issued_cursors"] as? JsonArray).orEmpty()
        check(
            bootstrap.all { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.startsWith("$id:") == true
            }
        ) { "GITHUB_SAVED_RESULT_CURSOR_IDENTITY_MISMATCH: saved handle identity differs." }
        val failedProofs = (snapshot["failed_persistence_cursors"] as? JsonArray).orEmpty()
        // The immutable body already proves its initial cursors. Only additional grants and failure proofs need an index.
        val initial = bootstrap.toSet()
        if (recovery == null && issued.all { it in initial } && failedProofs.isEmpty()) {
            check(state.containsRetainedBody(scope, bodyKey)) { "Saved result body was not retained." }
            return
        }
        state.update(scope, "saved-view-access:$id") { previous ->
            val cursors = (bootstrap + (previous?.get("issued_cursors") as? JsonArray).orEmpty() + issued).distinct()
            val failed = ((previous?.get("failed_persistence_cursors") as? JsonArray).orEmpty() + failedProofs).distinct()
            require(cursors.size <= 16_384) { "Saved view handle allowance reached" }
            val authorized = cursors.toSet()
            require(failed.all { it in authorized }) { "Unissued saved handle cannot become recovery proof" }
            buildJsonObject {
                put("issued_cursors", JsonArray(cursors))
                put("failed_persistence_cursors", JsonArray(failed))
                recovery?.let {
                    put("immutable_source", it)
                    persistedBody["page_bytes"]?.let { size -> put("original_page_bytes", size) }
                    (persistedBody["context"] as? JsonObject)?.takeIf { selectors -> selectors.toString().encodeToByteArray().size <= 64 * 1024 }?.let { selectors -> put("original_context", selectors) }
                    (persistedBody["captured_source_coverage"] as? JsonArray)?.let { coverage -> put("captured_source_coverage", coverage) }
                    put("layout_sha256", previous?.get("layout_sha256") ?: JsonPrimitive(layoutDigest(persistedBody)))
                }
            }
        }
        check(state.containsRetainedBody(scope, bodyKey) && state.containsRetainedBody(scope, "saved-view-access:$id")) {
            "Saved result persistence is incomplete. Keep the accepted operation receipt; do not repeat its mutation."
        }
    }

    suspend fun load(id: String): JsonObject? = try {
        loadWithDiagnostics(id)
    } catch (_: MissingSavedView) {
        null
    }

    suspend fun loadWithDiagnostics(id: String): JsonObject {
        if (!validId(id)) throw MissingSavedView("INVALID_VIEW_ID")
        // Observe separately whether an issued cursor index survived the source body.
        // Neither state authorizes replaying a remote mutation.
        val bodyKey = "saved-view-body:$id"
        val accessKey = "saved-view-access:$id"
        val body = state.read(scope, bodyKey)
        val access = state.read(scope, accessKey)
        if (body == null) {
            val reason = when {
                state.containsRetainedBody(scope, bodyKey) -> "BODY_UNREADABLE"
                state.containsRetainedBody(scope, accessKey) -> "BODY_MISSING_INDEX_PRESENT"
                else -> "BODY_MISSING"
            }
            throw MissingSavedView(reason, access)
        }
        if (access == null) {
            val initial = body["bootstrap_issued_cursors"] as? JsonArray
            if (initial.isNullOrEmpty() || initial.size > 16_384) {
                throw MissingSavedView("CURSOR_INDEX_MISSING")
            }
            val altered = initial.any { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.startsWith("$id:") != true
            }
            if (altered) throw MissingSavedView("CURSOR_INDEX_MISSING")
            // Initial handle proof is encrypted with the source body. Do not
            // invent later-issued continuations or retry an accepted mutation.
            return JsonObject(
                (body - "bootstrap_issued_cursors") +
                    ("issued_cursors" to initial) +
                    ("failed_persistence_cursors" to JsonArray(emptyList()))
            )
        }
        val issued = access["issued_cursors"] as? JsonArray ?: throw MissingSavedView("CURSOR_INDEX_INVALID")
        val failed = when (val value = access["failed_persistence_cursors"]) {
            null -> JsonArray(emptyList())
            is JsonArray -> value
            else -> throw MissingSavedView("CURSOR_INDEX_INVALID")
        }
        if (issued.isEmpty() ||
            issued.size > 16_384 ||
            failed.size > issued.size ||
            issued.any { it !is JsonPrimitive || !it.isString || !it.content.startsWith("$id:") }
        ) {
            throw MissingSavedView("CURSOR_INDEX_INVALID")
        }
        val authorized = issued.toSet()
        if (failed.any { it !in authorized }) throw MissingSavedView("CURSOR_INDEX_INVALID")
        return JsonObject((body - "bootstrap_issued_cursors") + mapOf("issued_cursors" to issued, "failed_persistence_cursors" to failed))
    }

    /** A separate authorized read can repair the original view only if both source bytes and pagination layout match. */
    suspend fun restorePinnedSource(cursor: String, request: JsonObject, fresh: JsonObject, rebuildPage: ((JsonArray?, Int) -> JsonObject?)? = null): Boolean {
        val id = cursor.substringBefore(':')
        if (!validId(id)) return false
        val access = state.read(scope, "saved-view-access:$id") ?: return false
        fun field(name: String) = (fresh[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val pin = MissingSavedView("", access).pinnedSource(field("scope") ?: return false, field("model_name") ?: return false, cursor) ?: return false
        if (pin.recipe["arguments"] != request) return false
        val expectedSource = JsonPrimitive(pin.sourceDigest)
        val size = (access["original_page_bytes"] as? JsonPrimitive)?.intOrNull
        val rebuilt = if (fresh["source_sha256"] == expectedSource && (size == null || fresh["page_bytes"] == JsonPrimitive(size))) {
            fresh
        } else {
            // Range history changes on a reread of the same blob. Restore only its
            // captured historical fields and original page size, then verify all bytes.
            if (size == null || size !in 2048..8192) return false
            val coverage = access["captured_source_coverage"] as? JsonArray
            if (coverage == null && fresh["source_sha256"] != expectedSource) return false
            rebuildPage?.invoke(coverage, size) ?: return false
        }
        // An implicit ref resolved before the original read. A recovery request
        // carries that exact SHA, while its historical cursor retains its own selectors.
        val context = access["original_context"] as? JsonObject
        if (rebuilt["source_recovery"] != pin.recipe) return false
        // Normalization can reorder equivalent owner/repo selectors on the new
        // call. Its historical recipe keeps its exact original JSON serialization.
        val historicalSelectors = mapOf("source_recovery" to pin.recipe) + listOfNotNull(context?.let { "context" to it }).toMap()
        val recovered = JsonObject(rebuilt + historicalSelectors)
        val layout = layoutDigest(recovered)
        if (recovered["source_sha256"] != expectedSource || access["layout_sha256"] != JsonPrimitive(layout)) return false
        val index = access["immutable_source"] as? JsonObject ?: return false
        val issued = access["issued_cursors"] as? JsonArray ?: return false
        if (issued.size > 16_384 || issued.any { it !is JsonPrimitive || !it.isString || !it.content.startsWith("$id:") }) return false
        val original = state.retainSavedViewBody(scope, id) {
            JsonObject(
                recovered - setOf("issued_cursors", "failed_persistence_cursors", "source_call_id") +
                    index.filterKeys { it == "source_call_id" } + ("bootstrap_issued_cursors" to issued)
            )
        }
        return original["source_sha256"] == expectedSource && layoutDigest(original) == layout
    }

    private fun layoutDigest(body: JsonObject): String = GitHubRepositoryStateStore.digest(
        JsonObject(body.filterKeys { it !in setOf("issued_cursors", "failed_persistence_cursors", "bootstrap_issued_cursors", "source_call_id", "captured_source_coverage") }).toString()
    )

    data class PinnedSource(val recipe: JsonObject, val sourceDigest: String)

    class MissingSavedView(val reason: String, private val access: JsonObject? = null) : Exception() {
        fun pinnedSource(scope: String, name: String, cursor: String): PinnedSource? {
            if (JsonPrimitive(cursor) !in (access?.get("issued_cursors") as? JsonArray).orEmpty()) return null
            val proof = access?.get("immutable_source") as? JsonObject ?: return null
            if (proof["scope"] != JsonPrimitive(scope) || proof["operation"] != JsonPrimitive("github_read") || proof["model_name"] != JsonPrimitive(name)) return null
            val digest = (proof["source_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!digest.matches(Regex("[a-f0-9]{64}"))) return null
            val recipe = proof["source_recovery"] as? JsonObject ?: return null
            val args = recipe["arguments"] as? JsonObject ?: return null
            fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (recipe["tool"] != JsonPrimitive(name) ||
                text("owner").isNullOrBlank() ||
                text("repo").isNullOrBlank() ||
                !text("commit_sha").orEmpty().matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) ||
                (args["ranges"] as? JsonArray).isNullOrEmpty() ||
                args.keys.any { it in setOf("view_cursor", "evidence_id", "branch", "ref") }
            ) {
                return null
            }
            return PinnedSource(recipe, digest)
        }
    }

    private fun validId(id: String) = runCatching { UUID.fromString(id) }.isSuccess
}
