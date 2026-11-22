package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Query-independent, bounded retry memory; each query still owns its exact coverage and cursor. */
internal object GitHubSearchFileFailures {
    const val STORE_KEY = "search-file-deadlines-v1"
    const val MAX_ENTRIES = 64
    const val MAX_ATTEMPTS = 2
    private val identityPattern = Regex("[a-f0-9]{64}")

    fun identity(context: GitHubTaskSnapshot, path: String, blobSha: String): String = GitHubRepositoryStateStore.digest(
        JsonArray(
            listOf(
                context.accountId,
                context.connectionUid,
                context.conversationId,
                context.taskId,
                context.owner.lowercase(Locale.ROOT),
                context.repo.lowercase(Locale.ROOT),
                context.commitSha.lowercase(Locale.ROOT),
                blobSha.lowercase(Locale.ROOT),
                path
            ).map(::JsonPrimitive)
        ).toString()
    )

    fun entries(value: JsonObject?): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        (value?.get("entries") as? JsonObject)?.forEach { (identity, raw) ->
            val attempts = (raw as? JsonPrimitive)?.intOrNull
            if (identityPattern.matches(identity) && attempts != null && attempts in 1..MAX_ATTEMPTS) {
                result[identity] = attempts
                if (result.size > MAX_ENTRIES) result.remove(result.keys.first())
            }
        }
        return result
    }

    fun update(value: JsonObject?, completed: Set<String>, stalled: String?): JsonObject {
        val retained = entries(value).toMutableMap()
        completed.forEach { retained.remove(it) }
        if (stalled != null) {
            require(identityPattern.matches(stalled))
            val previous = retained.remove(stalled) ?: 0
            retained[stalled] = (previous + 1).coerceAtMost(MAX_ATTEMPTS)
        }
        return buildJsonObject {
            put("schema", "github.search_file_failures.v1")
            put("entries", buildJsonObject { retained.entries.toList().takeLast(MAX_ENTRIES).forEach { (identity, attempts) -> put(identity, attempts) } })
        }
    }
}
