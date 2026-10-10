package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep every record and stack continuation while exposing correlation and measured/estimated fields. */
internal fun structuredDiagnosticLine(line: String): String {
    val safe = redactLogMessage(line)
    val match = Regex("^(\\S+) ([VDIWEF])/([^:]+): (.*)$").matchEntire(safe)
    return buildJsonObject {
        put("schema", 1)
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
                    message.split(" · ").drop(1).forEach { field ->
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
