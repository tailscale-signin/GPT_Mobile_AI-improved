package dev.chungjungsoo.gptmobile.data.github

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
        val bootstrap = (persistedBody["bootstrap_issued_cursors"] as? JsonArray).orEmpty()
        check(
            bootstrap.all { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.startsWith("$id:") == true
            }
        ) { "GITHUB_SAVED_RESULT_CURSOR_IDENTITY_MISMATCH: saved handle identity differs." }
        state.update(scope, "saved-view-access:$id") { previous ->
            val cursors = (bootstrap + (previous?.get("issued_cursors") as? JsonArray).orEmpty() + issued).distinct()
            val failed = ((previous?.get("failed_persistence_cursors") as? JsonArray).orEmpty() + (snapshot["failed_persistence_cursors"] as? JsonArray).orEmpty()).distinct()
            require(cursors.size <= 16_384) { "Saved view handle allowance reached" }
            val authorized = cursors.toSet()
            require(failed.all { it in authorized }) { "Unissued saved handle cannot become recovery proof" }
            buildJsonObject {
                put("issued_cursors", JsonArray(cursors))
                put("failed_persistence_cursors", JsonArray(failed))
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
        val body = state.read(scope, bodyKey) ?: throw MissingSavedView(
            when {
                state.containsRetainedBody(scope, bodyKey) -> "BODY_UNREADABLE"
                state.containsRetainedBody(scope, accessKey) -> "BODY_MISSING_INDEX_PRESENT"
                else -> "BODY_MISSING"
            }
        )
        val access = state.read(scope, accessKey)
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

    class MissingSavedView(val reason: String) : Exception()

    private fun validId(id: String) = runCatching { UUID.fromString(id) }.isSuccess
}
