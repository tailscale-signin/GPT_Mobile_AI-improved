package dev.chungjungsoo.gptmobile.data.github

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One filter contract for PR admission, remote queries and their continuations. */
internal object GitHubPullRequestQuery {
    fun defaultDirection(sort: String) = if (sort == "created") "desc" else "asc"

    fun parameters(arguments: JsonObject): JsonObject {
        fun text(key: String, default: String? = null): String? {
            val value = arguments[key] ?: return default
            require(value is JsonPrimitive && value.isString) { "$key must be a string." }
            return value.content
        }
        fun number(key: String, default: Int, maximum: Int): Int {
            val value = arguments[key] ?: return default
            val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            require(number != null && number in 1..maximum) { "$key must be an integer from 1 to $maximum." }
            return number
        }
        val state = text("state", "all")
        require(state in setOf("open", "closed", "all")) { "state must be open, closed or all." }
        val sort = requireNotNull(text("sort", "created"))
        require(sort in setOf("created", "updated", "popularity", "long-running")) { "sort must be created, updated, popularity or long-running." }
        val direction = text("direction", defaultDirection(sort))
        require(direction in setOf("asc", "desc")) { "direction must be asc or desc." }
        return buildJsonObject {
            put("state", state)
            put("sort", sort)
            put("direction", direction)
            for (key in listOf("head", "base")) {
                text(key)?.let { value ->
                    require(value.length in 1..256 && value.none { it.isWhitespace() || it.isISOControl() }) {
                        "$key must be a nonblank branch filter of at most 256 characters without whitespace."
                    }
                    put(key, value)
                }
            }
            put("per_page", number("per_page", 20, 100))
            put("page", number("page", 1, 1000))
        }
    }

    fun encode(parameters: JsonObject): String = parameters.entries.joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode((value as JsonPrimitive).content, StandardCharsets.UTF_8.name())}"
    }
}
