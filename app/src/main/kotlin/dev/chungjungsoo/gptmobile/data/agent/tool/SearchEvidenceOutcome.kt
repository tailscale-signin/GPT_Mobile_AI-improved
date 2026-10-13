package dev.chungjungsoo.gptmobile.data.agent.tool

import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class SearchEvidenceOutcome { SUCCESS, EMPTY_VALID, PARTIAL, AUTH_REQUIRED, RATE_LIMITED, UNAVAILABLE, PARSE_FAILED, CANCELLED }

internal fun evidenceOutcome(legacy: String, hasSources: Boolean, partial: Boolean): SearchEvidenceOutcome = when {
    legacy == "authentication_failed" -> SearchEvidenceOutcome.AUTH_REQUIRED
    legacy == "rate_limited" -> SearchEvidenceOutcome.RATE_LIMITED
    legacy == "cancelled" -> SearchEvidenceOutcome.CANCELLED
    hasSources -> if (partial || legacy != "success") SearchEvidenceOutcome.PARTIAL else SearchEvidenceOutcome.SUCCESS
    legacy == "empty" -> SearchEvidenceOutcome.EMPTY_VALID
    legacy in setOf("malformed_response", "unstructured") -> SearchEvidenceOutcome.PARSE_FAILED
    else -> SearchEvidenceOutcome.UNAVAILABLE
}

/** A legacy false flag cannot negate an explicit loss marker. No claim of full examination. */
internal fun evidenceHasTruncation(value: JsonElement?, depth: Int = 0): Boolean {
    if (depth > 16) return true
    return when (value) {
        is JsonPrimitive -> value.isString && Regex("(?i)\\[(?:compacted evidence|result truncated|truncated)|\\[\\.\\.\\.truncated").containsMatchIn(value.content)
        is JsonArray -> value.any { evidenceHasTruncation(it, depth + 1) }
        is JsonObject -> value["truncated"] == JsonPrimitive(true) || value["partial"] == JsonPrimitive(true) || value["outputBudgetExhausted"] == JsonPrimitive(true) || value.values.any { evidenceHasTruncation(it, depth + 1) }
        else -> false
    }
}

/** Decode only recognized search wrappers; downstream acquisition retains its own permission checks. */
internal fun resolveSearchRedirect(value: String): String? = runCatching {
    var current = value.trim()
    repeat(4) {
        if (current.startsWith("//duckduckgo.com/")) current = "https:$current"
        val uri = URI(current)
        val key = when {
            uri.host in setOf("duckduckgo.com", "www.duckduckgo.com") && uri.path == "/l/" -> "uddg"
            uri.host in setOf("google.com", "www.google.com") && uri.path == "/url" -> if (uri.rawQuery.orEmpty().startsWith("url=")) "url" else "q"
            else -> return@runCatching SearchUrlIdentity.parse(current)?.navigationUrl
        }
        val encoded = uri.rawQuery.orEmpty().split('&').singleOrNull { it.substringBefore('=') == key }?.substringAfter('=') ?: return@runCatching null
        val next = URLDecoder.decode(encoded, Charsets.UTF_8.name())
        require(next != current && next.length <= 65_536)
        // An encoded javascript, credential-bearing, opaque or invalid destination never becomes a source.
        require(SearchUrlIdentity.parse(next) != null)
        current = next
    }
    null
}.getOrNull()
