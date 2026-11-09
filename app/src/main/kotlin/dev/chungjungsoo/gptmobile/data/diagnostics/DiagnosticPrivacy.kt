package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// These run before enqueueing to protect privacy even if the process stops.
// Compile once: logging from text input or frame callbacks must not rebuild patterns.
private val sensitivePayload = Regex("""(?i)(?:\\?["'](?:[a-z_]+_)?(?:query|prompt|arguments|result|document|content|text|error_body)\\?["']\s*:|\b(?:[a-z_]+_)?(?:query|prompt|arguments|result|document|error_body)\s*=)""")
private val privateJsonKey = Regex("(?:^|_)(?:query|prompt|arguments|result|document|content|text|error_body)$")
private val secretJsonKey = Regex("^(?:authorization|proxy_authorization|cookie|set_cookie|x_api_key|x_goog_api_key|x_subscription_token|mcp_session_id|anthropic_organization_id|anthropic_workspace_id|openai_organization|openai_project|x_organization_id|x_workspace_id|api_?key|access_?token|refresh_?token|password|client_?secret)$")
private val queryString = Regex("""(https?://[^\s?"']+)\?[^\s"']+""")
private val privatePath = Regex("""(?:/data/|/storage/|/sdcard/|/home/)[^\s,;]+""")
private val privateAddress = Regex("""\b(?:10\.(?:\d{1,3}\.){2}\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3})\b""")
private val quotedSecret = Regex("""(?i)("(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-goog-api-key|x-subscription-token|mcp-session-id|anthropic-organization-id|anthropic-workspace-id|openai-organization|openai-project|x-organization-id|x-workspace-id|api_?key|access_?token|refresh_?token|password|client_?secret)"\s*:\s*)"(?:\\.|[^"\\])*"""")
private val privateHeader = Regex("(?i)(?<![A-Za-z0-9_-])(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-goog-api-key|x-subscription-token|mcp-session-id|anthropic-organization-id|anthropic-workspace-id|openai-organization|openai-project|x-organization-id|x-workspace-id)\\s*[:=]\\s*[^\\r\\n]+")
private val authorizationValue = Regex("(?i)(bearer|basic)\\s+[a-z0-9._~+/=-]+")
private val secretValue = Regex("(?i)([\\\"]?(?:api_?key|access_?token|refresh_?token|password|client_?secret)[\\\"]?\\s*[:=]\\s*[\\\"]?)[^\\\"\\s,}]+")
private val tokenValue = Regex("\\b(?:sk-|hf_|ghp_|github_pat_)[A-Za-z0-9_-]{8,}")
private val chatCollection = Regex("""(?i)(?:^|\s)D/chats\b|\bchats\s*[:=]""")
private val privateDataClasses = listOf("ChatRoomV2", "MessageV2", "PlatformV2").map { it to Regex("""\b$it\([^\r\n)]*\)""") }

/** Redact values in complete JSON trees so safe execution receipts keep their structure. */
internal fun redactDiagnosticPayload(message: String): String = redactDiagnosticSegments(message, credentials = false)

private fun redactDiagnosticSegments(message: String, credentials: Boolean): String = message.lineSequence().joinToString("\n") { line ->
    buildString {
        var offset = 0
        while (offset < line.length) {
            val start = line.indexOfAny(charArrayOf('{', '['), offset)
            val prefixEnd = if (start < 0) line.length else start
            val prefix = line.substring(offset, prefixEnd)
            append(redactDiagnosticText(prefix, credentials))
            // An unstructured payload has no trustworthy end boundary.
            if (start < 0 || sensitivePayload.containsMatchIn(prefix)) break
            val end = diagnosticJsonEnd(line, start)
            val value = end?.let { runCatching { Json.parseToJsonElement(line.substring(start, it)) }.getOrNull() }
            if (value == null) {
                append(redactDiagnosticText(line.substring(start), credentials))
                break
            }
            append(redactDiagnosticTree(value, credentials))
            offset = requireNotNull(end)
        }
    }
}

/** Bound scanning before parsing, including malformed or deeply nested logcat payloads. */
private fun diagnosticJsonEnd(line: String, start: Int): Int? {
    var depth = 0
    var quoted = false
    var escaped = false
    for (index in start until minOf(line.length, start + 16 * 1024)) {
        val character = line[index]
        if (quoted) {
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '"') {
                quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 32) return null
                '}', ']' -> if (--depth == 0) return index + 1
            }
        }
    }
    return null
}

private fun redactDiagnosticTree(value: JsonElement, credentials: Boolean): JsonElement = when (value) {
    is JsonObject -> JsonObject(
        value.mapValues { (key, child) ->
            val normalized = key.lowercase(Locale.ROOT).replace('-', '_')
            when {
                privateJsonKey.containsMatchIn(normalized) -> JsonPrimitive("[private payload redacted]")
                credentials && secretJsonKey.matches(normalized) -> JsonPrimitive("[redacted]")
                else -> redactDiagnosticTree(child, credentials)
            }
        }
    )
    is JsonArray -> JsonArray(value.map { redactDiagnosticTree(it, credentials) })
    is JsonPrimitive -> if (value.isString) JsonPrimitive(redactDiagnosticText(value.content, credentials)) else diagnosticJsonValue(value)
}

private fun redactDiagnosticText(message: String, credentials: Boolean): String {
    val start = sensitivePayload.find(message)?.range?.first
    val safe = if (start == null) message else message.take(start) + "[private payload redacted]"
    val payloadSafe = safe.replace(queryString, "$1?[redacted]")
    return if (credentials) redactDiagnosticCredentials(payloadSafe) else payloadSafe
}

internal fun diagnosticMessageForStorage(message: String): String {
    // Redact complete lines only. Cutting a raw header/key/token in half before redaction
    // could expose a secret fragment; running every regex over a huge input exhausts heap.
    val source = if (message.length <= 16 * 1024) {
        message
    } else {
        val boundary = message.lastIndexOf('\n', 16 * 1024 - 1)
        if (boundary >= 0) message.substring(0, boundary) else "[oversized diagnostic message omitted]"
    }
    val safe = redactLogMessage(source)
    if (source !== message || safe.length > 7800) {
        val retained = safe.take(7800)
        return "$retained · truncatedAtSource=true · originalLength=${message.length} · retainedLength=${retained.length}"
    }
    return safe
}

internal fun redactLogMessage(message: String): String {
    if (
        "ChatRoomV2(" in message &&
        chatCollection.containsMatchIn(message)
    ) {
        return "D/chats: [redacted chat collection]"
    }
    return redactDiagnosticSegments(message, credentials = true)
}

private fun redactDiagnosticCredentials(message: String): String {
    var text = DiagnosticRedactor.redact(message)
    text = text.replace(privatePath, "[private-path]")
    text = text.replace(privateAddress, "[private-address]")
    // Android Log.d calls from legacy/UI code may stringify entire Room/domain objects.
    // Keep diagnostics useful without exporting conversation titles, profile UUIDs,
    // message text or timestamps embedded in those data-class dumps.
    privateDataClasses.forEach { (type, pattern) ->
        text = text.replace(pattern, "$type([redacted])")
    }
    // Match complete quoted keys as well as raw headers; keep JSON siblings and
    // escaping valid so safe provider records remain structurally exportable.
    text = text.replace(quotedSecret) { match -> "${match.groupValues[1]}\"[redacted]\"" }
    text = text.replace(privateHeader, "$1: [redacted]")
    text = text.replace(authorizationValue, "$1 [redacted]")
    text = text.replace(secretValue, "$1[redacted]")
    text = text.replace(tokenValue, "[redacted]")
    return text
}
