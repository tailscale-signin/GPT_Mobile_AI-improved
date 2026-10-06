package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class DebugActivityContent(
    val title: String,
    val body: String,
    val score: Int? = null,
    val verdict: String? = null
)

/** Render machine output as readable activity; incomplete streaming JSON never leaks punctuation. */
internal fun debugActivityContent(raw: String, reviewer: Boolean = false): DebugActivityContent {
    val content = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    // Some providers concatenate their plan and answer. Preserve the answer while
    // converting the leading machine object rather than hiding the entire message.
    leadingJsonEnd(content)?.takeIf { it < content.length }?.let { end ->
        val structured = debugActivityContent(content.take(end), reviewer)
        val remainder = debugActivityContent(content.drop(end), reviewer)
        return structured.copy(body = listOf(structured.body, remainder.body).filter(String::isNotBlank).joinToString("\n\n"))
    }
    val json = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull()
    fun strings(name: String): List<String> = (json?.get(name) as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }.distinct()
    if (json != null) {
        if (reviewer || "review_score" in json || "verdict" in json) {
            val score = (json["review_score"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..100 }
            val verdict = (json["verdict"] as? JsonPrimitive)?.contentOrNull?.uppercase()
                ?.takeIf { it in setOf("PASS", "CORRECTED", "REJECT") }
            val issues = strings("issues")
            val correction = (json["corrections"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }
            return DebugActivityContent(
                "Independent review",
                buildString {
                    append(
                        when (verdict) {
                            "PASS" -> "The reviewer accepted the supplied evidence."
                            "CORRECTED" -> "The reviewer supplied corrections."
                            "REJECT" -> "The evidence needs correction before the handoff."
                            else -> "The reviewer assessment is incomplete."
                        }
                    )
                    issues.forEach { append("\n• $it") }
                    correction?.takeIf(String::isNotBlank)?.let { append("\n\n$it") }
                },
                score,
                verdict
            )
        }
        if ("queries" in json || "urls" in json) {
            val queries = strings("queries")
            val urls = strings("urls")
            return DebugActivityContent(
                "Research plan",
                buildString {
                    if (queries.isEmpty() && urls.isEmpty()) append("No external research needed. Preparing the task directly.")
                    if (queries.isNotEmpty()) {
                        append("${queries.size} search ${if (queries.size == 1) "query" else "queries"}")
                        queries.forEach { append("\n• $it") }
                    }
                    if (urls.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Pages to read")
                        urls.forEach { append("\n• $it") }
                    }
                }
            )
        }
        // Other structured worker output stays useful without exposing braces or null fields.
        return DebugActivityContent(
            "Delegate result",
            json.entries.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" || it.isBlank() }
                    ?.let { "${key.replace('_', ' ')}: $it" }
            }.joinToString("\n").ifBlank { "Structured result received." }
        )
    }
    val structuredFragment = content.startsWith('{') ||
        content.startsWith("\"queries\"") ||
        content.startsWith(",\"verdict\"") ||
        content.startsWith("\"review_score\"")
    if (content.isBlank() || structuredFragment) {
        return DebugActivityContent(if (reviewer) "Independent review" else "Delegate activity", if (reviewer) "Preparing the review…" else "Preparing the result…")
    }
    val score = Regex("Reviewer Score:\\s*(\\d{1,3})/100", RegexOption.IGNORE_CASE).find(content)
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    val verdict = Regex("\\b(PASS|CORRECTED|REJECT)\\b").find(content)?.value
    val body = content.lines().filterNot {
        it.trim().equals("Reviewer", true) ||
            it.trim().equals("Delegation", true) ||
            Regex("^\\s*,?\\s*\"(?:review_score|verdict|issues|corrections|queries|urls)\"\\s*:").containsMatchIn(it)
    }
        .joinToString("\n").replace(Regex("\\[Reviewer Score:[^\\]]+]\\s*"), "").trim()
    return DebugActivityContent(if (reviewer) "Independent review" else "Delegate result", body, score, verdict)
}

private fun leadingJsonEnd(content: String): Int? {
    if (!content.startsWith('{')) return null
    var depth = 0
    var quoted = false
    var escaped = false
    content.forEachIndexed { index, char ->
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
        } else {
            when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return index + 1
            }
        }
    }
    return null
}
