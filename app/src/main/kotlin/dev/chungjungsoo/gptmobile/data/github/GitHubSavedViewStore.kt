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
            // The immutable body also retains only the initially issued cursors.
            // If the mutable index disappears after a crash, old issued handles
            // remain recoverable without inventing a cursor or replaying GitHub.
            val issued = requireNotNull(snapshot["issued_cursors"] as? JsonArray) {
                "Saved view requires an issued cursor array"
            }
            require(issued.isNotEmpty() && issued.size <= 16_384) {
                "Saved view requires bounded initial cursors"
            }
            require(
                issued.all { item ->
                    (item as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.substringBefore(':') == id
                }
            ) { "Saved view contains a cursor not issued for this identity" }
            state.write(
                scope,
                bodyKey,
                JsonObject(
                    snapshot - setOf("issued_cursors", "failed_persistence_cursors") +
                        ("bootstrap_issued_cursors" to issued)
                )
            )
        }
        check(state.containsRetainedBody(scope, bodyKey)) {
            "Saved result body is not durable. Keep the original read receipt; do not publish a continuation."
        }
        // Immutable payloads are written once; only the monotonically growing set
        // of issued handles changes as pages are consumed. If the mutable index
        // disappears after a restart, restore the actually issued bootstrap
        // handles before adding a later page; never invent a cursor.
        val persistedBody = requireNotNull(state.read(scope, bodyKey)) {
            "GITHUB_SAVED_RESULT_BODY_UNREADABLE: preserve the read receipt; do not publish a continuation."
        }
        val bootstrap = (persistedBody["bootstrap_issued_cursors"] as? JsonArray).orEmpty()
        check(
            bootstrap.all { item ->
                (item as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.substringBefore(':') == id
            }
        ) { "GITHUB_SAVED_RESULT_CURSOR_IDENTITY_MISMATCH: saved handle identity differs." }
        state.update(scope, "saved-view-access:$id") { previous ->
            val cursors = (bootstrap + (previous?.get("issued_cursors") as? JsonArray).orEmpty() + (snapshot["issued_cursors"] as? JsonArray).orEmpty()).distinct()
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
                (item as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.substringBefore(':') != id
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
        return JsonObject((body - "bootstrap_issued_cursors") + access)
    }

    class MissingSavedView(val reason: String) : Exception()

    private fun validId(id: String) = runCatching { UUID.fromString(id) }.isSuccess
}
