package dev.chungjungsoo.gptmobile.data.github

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Protected execution state survives cache pruning and closing the map. It never authorizes a replay. */
internal class GatewayGraphIndexCheckpoint(private val store: GitHubRepositoryStateStore, private val scope: String) {
    private val key = "execution:" + GitHubRepositoryStateStore.digest("repository-graph-index-v1")

    data class Observation(val unresolved: Boolean, val attemptHash: String? = null, val confirmedAttemptHash: String? = null)

    suspend fun unresolved(): Boolean = pending() != null

    suspend fun observation(): Observation {
        val retained = pending()
        return Observation(retained != null, retained?.text("attempt")?.takeIf(String::isNotBlank)?.let(::attemptHash))
    }

    /** A local join token, never a remote job identifier or evidence that an HTTP request ran. */
    fun attemptHash(attempt: String): String = GitHubRepositoryStateStore.digest("gptmobile.graph-index-attempt.v1:$scope:$attempt")

    suspend fun begin(cursor: String?, previousStatus: JsonObject? = null): String {
        val attempt = UUID.randomUUID().toString()
        store.update(scope, key) { previous ->
            check(!isPending(previous)) { "The previous index request is unconfirmed. Check the saved index before continuing." }
            buildJsonObject {
                put("status", "RUNNING")
                put("attempt", attempt)
                cursor?.let { put("index_cursor", it) }
                if (previousStatus?.get("has_more") == JsonPrimitive(false)) {
                    put("previously_complete", true)
                    parsedFiles(previousStatus)?.let { put("previous_parsed_files", it) }
                }
            }
        }
        return attempt
    }

    suspend fun confirm(attempt: String): String? {
        var confirmed: String? = null
        store.update(scope, key) { previous ->
            if (previous?.text("attempt") == attempt) {
                if (isPending(previous)) confirmed = attemptHash(attempt)
                buildJsonObject { put("status", "CONFIRMED") }
            } else {
                requireNotNull(previous)
            }
        }
        return confirmed
    }

    /** An absent or unchanged snapshot cannot prove that a timed-out first batch was not accepted. */
    suspend fun reconcile(result: JsonObject): Boolean = reconcileObserved(result).unresolved

    /** Observe the existing transition; no payload-supplied identifier can manufacture its proof. */
    suspend fun reconcileObserved(result: JsonObject): Observation {
        val previous = pending() ?: return Observation(false)
        val observed = Observation(true, previous.text("attempt")?.takeIf(String::isNotBlank)?.let(::attemptHash))
        val job = result["index_job"] as? JsonObject
        val accepted = (job?.get("accepted_request_ids") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (previous.text("attempt")?.let { it == job?.text("request_id") || it in accepted } == true) {
            val confirmed = confirm(requireNotNull(previous.text("attempt")))
            return observation().copy(confirmedAttemptHash = confirmed)
        }
        if (result["graph"] !is JsonObject) return observed
        val more = result["has_more"]
        val next = result.text("index_cursor")?.takeIf(String::isNotBlank)
        val previousCursor = previous.text("index_cursor")
        val knownAttempt = previous.text("attempt") != null
        val progressed = more == JsonPrimitive(true) && next != null && next != previousCursor
        val before = (previous["previous_parsed_files"] as? JsonPrimitive)?.nonnegativeCount()
        val rebuilt = before != null && parsedFiles(result)?.let { it > before } == true
        val completed = more == JsonPrimitive(false) && (previous["previously_complete"] != JsonPrimitive(true) || rebuilt)
        var confirmed: String? = null
        if (knownAttempt && (completed || progressed)) {
            store.update(scope, key) { current ->
                if (current == previous) {
                    confirmed = observed.attemptHash
                    buildJsonObject { put("status", "CONFIRMED") }
                } else {
                    requireNotNull(current)
                }
            }
        }
        return observation().copy(confirmedAttemptHash = confirmed)
    }

    private suspend fun pending(): JsonObject? = store.read(scope, key)?.takeIf(::isPending)
    private fun parsedFiles(value: JsonObject): Int? = (((value["graph"] as? JsonObject)?.get("index_metrics") as? JsonObject)?.get("parsed_files") as? JsonPrimitive)?.nonnegativeCount()
    private fun JsonPrimitive.nonnegativeCount(): Int? = takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
    private fun isPending(value: JsonObject?): Boolean = value != null && value.text("status") != "CONFIRMED"
    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
}
