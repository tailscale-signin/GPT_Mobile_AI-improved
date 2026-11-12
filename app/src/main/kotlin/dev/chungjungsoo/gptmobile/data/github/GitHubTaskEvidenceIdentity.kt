package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** The active conversation pointer is a hint; the saved task contract owns evidence identity. */
internal object GitHubTaskEvidenceIdentity {
    data class Restored(val binding: String, val acceptedTask: String)

    fun sourceKey(activeKey: String, sourceMessageId: Int): String = "$activeKey:source:$sourceMessageId"

    suspend fun restore(
        repository: String,
        sourceMessageId: Int,
        derivedBinding: String,
        sourcePointer: JsonObject?,
        activePointer: JsonObject?,
        loadContract: suspend (String) -> JsonObject?
    ): Restored? {
        val candidates = listOfNotNull(sourcePointer?.text("binding"), activePointer?.text("binding"), derivedBinding)
            .filter { it.matches(Regex("[a-f0-9]{64}")) }.distinct()
        for (binding in candidates) {
            val contract = loadContract(binding) ?: continue
            if (contract.text("schema") != "github.task-contract.v1" ||
                contract.text("binding") != binding ||
                !contract.text("repository").equals(repository, true) ||
                (contract["source_message_id"] as? JsonPrimitive)?.intOrNull != sourceMessageId
            ) {
                continue
            }
            val task = contract.text("requested_actions").takeIf(String::isNotBlank) ?: continue
            return Restored(binding, task)
        }
        return null
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
