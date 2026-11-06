package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor

// These run before enqueueing to protect privacy even if the process stops.
// Compile once: logging from text input or frame callbacks must not rebuild patterns.
private val sensitivePayload = Regex("""(?i)(?:\\?["'](?:query|prompt|arguments|result|document|content|text|error_body)\\?["']\s*:|\b(?:query|prompt|arguments|result|document|error_body)\s*=)""")
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

/** Redact before truncating; incomplete nested JSON is deliberately not treated as safe text. */
internal fun redactDiagnosticPayload(message: String): String = message.lineSequence().joinToString("\n") { line ->
    val start = sensitivePayload.find(line)?.range?.first
    val safe = if (start == null) line else line.take(start) + "[private payload redacted]"
    safe.replace(queryString, "$1?[redacted]")
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
    var text = DiagnosticRedactor.redact(redactDiagnosticPayload(message))
    text = text.replace(privatePath, "[private-path]")
    text = text.replace(privateAddress, "[private-address]")
    if (
        "ChatRoomV2(" in text &&
        chatCollection.containsMatchIn(text)
    ) {
        return "D/chats: [redacted chat collection]"
    }
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
