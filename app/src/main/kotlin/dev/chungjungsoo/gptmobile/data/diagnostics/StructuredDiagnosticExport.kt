package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep every record and stack continuation while exposing correlation and measured/estimated fields. */
internal fun diagnosticEventIdentity(line: String): String = Regex("(?:^| · )eventId=([a-zA-Z0-9-]+)").find(line)?.groupValues?.get(1)
    ?: java.security.MessageDigest.getInstance("SHA-256").digest(line.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun structuredDiagnosticLine(line: String, captureSource: String = "unknown", duplicate: Boolean = false): String {
    val safe = redactLogMessage(line.take(16_000)).take(8000)
    val match = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$").matchEntire(safe)
    return buildJsonObject {
        put("schema", 2)
        put("eventId", diagnosticEventIdentity(line))
        put("captureSource", captureSource)
        put("duplicate", duplicate)
        put("countAsIssue", !duplicate)
        put("truncated", line.length > 8000)
        put("kind", if (match == null) "continuation" else "event")
        if (match == null) {
            put("message", safe)
        } else {
            put("timestamp", match.groupValues[1])
            put("level", match.groupValues[2])
            put("tag", match.groupValues[3])
            val message = match.groupValues[4]
            put("message", message)
            put(
                "fields",
                buildJsonObject {
                    message.split(" · ", limit = 65).drop(1).forEach { field ->
                        val separator = field.indexOf('=')
                        if (separator > 0) {
                            val key = field.substring(0, separator).trim()
                            if (key.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
                                put(key, field.substring(separator + 1).trim())
                            }
                        }
                    }
                }
            )
        }
    }.toString()
}
