package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Bounded verdict arrays leave room for visible JSON after any permitted reasoning. */
internal object GitHubReviewSchema {
    const val VERSION = 1
    const val MAX_OUTPUT_CHARACTERS = 16_384
    const val OUTPUT_TOKENS = 1536

    enum class Outcome { PASS, FAIL, INDETERMINATE }

    data class Verdict(val outcome: Outcome, val reason: String, val issues: List<String>, val claims: List<String>) {
        val approved: Boolean get() = outcome == Outcome.PASS
    }

    /** Permit a single presentation fence, never prose, multiple candidates or coerced values. */
    fun parseVerdict(text: String): JsonObject {
        require(text.length <= MAX_OUTPUT_CHARACTERS) { "Review verdict exceeds the format allowance." }
        val trimmed = text.trim()
        val value = Regex("\\A```(?:json)?\\s*\\n([\\s\\S]*?)\\n```\\z", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
        val root = Json.parseToJsonElement(value) as? JsonObject ?: error("The review did not return a structured verdict.")
        // JsonObject alone would silently accept repeated fields with last-value-wins semantics.
        val keys = mutableSetOf<String>()
        var depth = 0
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    val start = index++
                    while (index < value.length && value[index] != '"') {
                        if (value[index] == '\\') index++
                        index++
                    }
                    val end = index + 1
                    var next = end
                    while (next < value.length && value[next].isWhitespace()) next++
                    if (depth == 1 && next < value.length && value[next] == ':') {
                        val key = (Json.parseToJsonElement(value.substring(start, end)) as JsonPrimitive).content
                        require(keys.add(key)) { "Review verdict repeats a field." }
                    }
                }
            }
            index++
        }
        return root
    }

    fun validate(text: String, expectedIds: List<String>): Verdict {
        val root = parseVerdict(text)
        require(root.keys == setOf("schema_version", "verdict", "reason", "reviewed_ids", "issues", "verified_claims")) { "Review fields do not match verdict v$VERSION." }
        require((root["schema_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull == VERSION) { "Unsupported review verdict version." }
        fun string(key: String, limit: Int): String = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= limit } ?: error("Review $key must be a bounded nonempty string.")
        fun strings(key: String, count: Int, length: Int): List<String> = (root[key] as? JsonArray)?.takeIf { it.size <= count }?.map {
            (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull?.takeIf { item -> item.isNotBlank() && item.length <= length }
                ?: error("Review $key contains an invalid entry.")
        } ?: error("Review $key is missing or exceeds its allowance.")
        val outcome = Outcome.entries.singleOrNull { it.name == string("verdict", 16) } ?: error("Unknown review verdict.")
        val checked = strings("reviewed_ids", 16, 160)
        require(checked.size == expectedIds.size && checked.toSet() == expectedIds.toSet()) { "Review must acknowledge every supplied evidence ID exactly once." }
        val reason = string("reason", 600)
        val issues = strings("issues", 4, 400)
        val claims = strings("verified_claims", 4, 400)
        require(outcome != Outcome.PASS || issues.isEmpty()) { "PASS contradicts the reported issues." }
        require(outcome != Outcome.PASS || expectedIds.isEmpty() || claims.isNotEmpty()) { "PASS requires checked claims for the supplied evidence." }
        require(outcome != Outcome.FAIL || issues.isNotEmpty()) { "FAIL requires an actionable evidence finding." }
        return Verdict(outcome, reason, issues, claims)
    }

    val format = Json.parseToJsonElement("""{"type":"json_schema","json_schema":{"name":"github_review_v1","strict":true,"schema":{"type":"object","properties":{"schema_version":{"type":"integer","enum":[1]},"verdict":{"type":"string","enum":["PASS","FAIL","INDETERMINATE"]},"reason":{"type":"string","minLength":1,"maxLength":600},"reviewed_ids":{"type":"array","maxItems":16,"items":{"type":"string","minLength":1,"maxLength":160}},"issues":{"type":"array","maxItems":4,"items":{"type":"string","minLength":1,"maxLength":400}},"verified_claims":{"type":"array","maxItems":4,"items":{"type":"string","minLength":1,"maxLength":400}}},"required":["schema_version","verdict","reason","reviewed_ids","issues","verified_claims"],"additionalProperties":false}}}""") as JsonObject
    val probeFormat = Json.parseToJsonElement("""{"type":"json_schema","json_schema":{"name":"schema_probe","strict":true,"schema":{"type":"object","properties":{"ok":{"type":"boolean","enum":[true]}},"required":["ok"],"additionalProperties":false}}}""") as JsonObject
}
