package dev.chungjungsoo.gptmobile.data.github

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Captured responses are independent of the page cache and never require replaying their operation. */
internal class GitHubSavedViewStore(private val state: GitHubRepositoryStateStore, private val scope: String) {
    suspend fun save(id: String, snapshot: JsonObject) {
        require(validId(id))
        val bodyKey = "saved-view-body:$id"
        if (!state.containsRetainedBody(scope, bodyKey)) {
            state.write(scope, bodyKey, JsonObject(snapshot - setOf("issued_cursors", "failed_persistence_cursors")))
        }
        // Immutable payloads are written once; only the monotonically growing set
        // of issued handles changes as pages are consumed.
        state.update(scope, "saved-view-access:$id") { previous ->
            val cursors = ((previous?.get("issued_cursors") as? JsonArray).orEmpty() + (snapshot["issued_cursors"] as? JsonArray).orEmpty()).distinct()
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
        val body = state.read(scope, "saved-view-body:$id") ?: throw MissingSavedView("BODY_MISSING")
        val access = state.read(scope, "saved-view-access:$id") ?: throw MissingSavedView("CURSOR_INDEX_MISSING")
        return JsonObject(body + access)
    }

    class MissingSavedView(val reason: String) : Exception()

    private fun validId(id: String) = runCatching { UUID.fromString(id) }.isSuccess
}
