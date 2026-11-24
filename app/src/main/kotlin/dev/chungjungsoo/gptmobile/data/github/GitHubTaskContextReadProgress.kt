package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class GitHubContextReadState {
    SOURCE_FETCHED,
    SOURCE_REUSED,
    SOURCE_UNAVAILABLE,
    PREPARATION_DEADLINE,
    BLOB_READ_DEADLINE,
    NO_PROGRESS_LIMIT_REACHED,
    SOURCE_READ_LIMIT
}

/** A task/revision/configuration checkpoint. It cannot issue a call or change any read authority. */
internal class GitHubTaskContextReadProgress(saved: JsonObject?) {
    private val failures = saved.orEmpty().mapNotNull { (blob, value) ->
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?.takeIf { blob.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) && it in 1..MAX_NO_PROGRESS_ATTEMPTS }
            ?.let { blob to it }
    }.take(20).toMap().toMutableMap()
    private val observations = linkedMapOf<String, GitHubContextReadState>()

    fun exhausted(blob: String): Boolean = (failures[blob] ?: 0) >= MAX_NO_PROGRESS_ATTEMPTS

    fun record(path: String, blob: String, state: GitHubContextReadState, attempted: Boolean = false) {
        observations[path] = state
        when (state) {
            GitHubContextReadState.BLOB_READ_DEADLINE, GitHubContextReadState.PREPARATION_DEADLINE -> if (attempted) {
                failures[blob] = ((failures[blob] ?: 0) + 1).coerceAtMost(MAX_NO_PROGRESS_ATTEMPTS)
            }
            GitHubContextReadState.SOURCE_FETCHED, GitHubContextReadState.SOURCE_REUSED -> failures.remove(blob)
            else -> Unit
        }
    }

    fun state(path: String): GitHubContextReadState? = observations[path]
    fun checkpoint(): JsonObject = buildJsonObject { failures.entries.take(20).forEach { (blob, attempts) -> put(blob, attempts) } }

    companion object {
        const val MAX_NO_PROGRESS_ATTEMPTS = 2
    }
}
