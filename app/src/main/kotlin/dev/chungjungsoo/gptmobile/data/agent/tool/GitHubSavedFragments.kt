package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Pack adjacent text from one saved value without changing its UTF-16 offsets or cursor. */
internal object GitHubSavedFragments {
    private val textKeys = setOf("pointer", "value", "offset", "total_characters")

    fun combine(previous: JsonObject?, next: JsonObject): JsonObject? {
        if (previous == null || previous.keys != textKeys || next.keys != textKeys) return null
        val pointer = previous["pointer"] as? JsonPrimitive ?: return null
        if (!pointer.isString || pointer != next["pointer"]) return null
        val first = previous["value"] as? JsonPrimitive ?: return null
        val second = next["value"] as? JsonPrimitive ?: return null
        if (!first.isString || !second.isString) return null
        val start = (previous["offset"] as? JsonPrimitive)?.intOrNull ?: return null
        val following = (next["offset"] as? JsonPrimitive)?.intOrNull ?: return null
        val total = (previous["total_characters"] as? JsonPrimitive)?.intOrNull ?: return null
        if (start < 0 ||
            total < 0 ||
            previous["total_characters"] != next["total_characters"] ||
            start.toLong() + first.content.length != following.toLong() ||
            following.toLong() + second.content.length > total.toLong()
        ) {
            return null
        }
        return JsonObject(previous + ("value" to JsonPrimitive(first.content + second.content)))
    }
}
