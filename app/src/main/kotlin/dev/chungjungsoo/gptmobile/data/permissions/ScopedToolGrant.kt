package dev.chungjungsoo.gptmobile.data.permissions

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ScopedToolGrant(
    val chatId: Int,
    val schemaHash: String,
    val resource: String,
    val action: String
) {
    fun key(): String = canonicalHash(JsonArray(listOf(JsonPrimitive(chatId), JsonPrimitive(schemaHash), JsonPrimitive(resource), JsonPrimitive(action))))

    companion object {
        fun from(chatId: Int, schema: JsonObject, arguments: JsonObject): ScopedToolGrant {
            fun text(key: String) = (arguments[key] as? JsonPrimitive)?.content.orEmpty()
            val owner = text("owner").lowercase()
            val repo = text("repo").ifBlank { text("repository") }.lowercase()
            val destination = listOf("branch", "ref", "path", "file_path", "directory", "target", "destination", "url", "uri", "issue_number", "pull_number", "project_id").mapNotNull { key -> arguments[key]?.let { "$key=$it" } }
            val resource = listOf(if (owner.isNotBlank() && repo.isNotBlank()) "$owner/$repo" else repo).plus(destination).filter(String::isNotBlank).joinToString(" | ")
            return ScopedToolGrant(chatId, canonicalHash(schema), resource, text("action"))
        }

        fun canonicalHash(value: JsonElement): String {
            fun canonical(element: JsonElement): JsonElement = when (element) {
                is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
                is JsonArray -> JsonArray(element.map(::canonical))
                else -> element
            }
            return MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().encodeToByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
