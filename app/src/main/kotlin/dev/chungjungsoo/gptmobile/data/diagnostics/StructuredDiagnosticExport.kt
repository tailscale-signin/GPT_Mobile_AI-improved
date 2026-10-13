package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val eventIdPattern = Regex("(?:^|: | · )eventId=([a-zA-Z0-9-]+)")
private val recordPattern = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$")
private val fieldPattern = Regex("(?:^| · |(?<=\\s))([A-Za-z][A-Za-z0-9_]*)=")
private val numericFieldPattern = Regex("(?:attempt|attempts|.*(?:Ms|Bytes|Tokens|Count)|input|output|limit|budget|entries|sequence|round|calls|records)", RegexOption.IGNORE_CASE)

/** Keep every record and continuation. A duplicate is not the same thing as a non-issue. */
internal fun diagnosticEventIdentity(line: String): String = eventIdPattern.find(line)?.groupValues?.get(1)
    ?: java.security.MessageDigest.getInstance("SHA-256").digest(line.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun structuredDiagnosticLine(line: String, captureSource: String = "unknown", duplicate: Boolean = false, parentEventId: String? = null, sequence: Int = 0, captureIdentity: String? = null): String {
    val safe = redactLogMessage(line).take(8000)
    val match = recordPattern.matchEntire(safe)
    val message = match?.groupValues?.get(4) ?: safe
    val classification = classifyDiagnostic(match?.groupValues?.get(2), match?.groupValues?.get(3), message)
    val fields = fieldPattern.findAll(message).take(64).toList()
    return buildJsonObject {
        put("schema", 4)
        put("eventId", captureIdentity ?: diagnosticEventIdentity(line))
        parentEventId?.let { put("parentEventId", it) }
        put("sequence", sequence)
        put("captureSource", captureSource)
        put("duplicate", duplicate)
        put("countAsIssue", !duplicate && match != null && classification.actionable)
        put("category", classification.category)
        put("severity", classification.severity)
        put("recovery", classification.recovery)
        put("truncated", line.length > 8000 || "truncatedAtSource=true" in line)
        put("truncatedAtSource", "truncatedAtSource=true" in line)
        put("truncatedAtExport", line.length > 8000)
        put("originalLength", line.length)
        put("retainedLength", safe.length)
        put("kind", if (match == null) "continuation" else "event")
        put("message", message)
        if (match != null) {
            put("timestamp", match.groupValues[1])
            put("level", match.groupValues[2])
            put("tag", match.groupValues[3])
            put(
                "fields",
                buildJsonObject {
                    fields.forEachIndexed { index, field ->
                        val key = field.groupValues[1]
                        val end = fields.getOrNull(index + 1)?.range?.first ?: message.length
                        val value = message.substring(field.range.last + 1, end).trim().trimEnd('·').trim()
                        when {
                            value == "true" || value == "false" -> put(key, value.toBooleanStrict())
                            numericFieldPattern.matches(key) && value.toLongOrNull() != null -> put(key, value.toLong())
                            else -> put(key, JsonPrimitive(value))
                        }
                    }
                }
            )
        }
    }.toString()
}
