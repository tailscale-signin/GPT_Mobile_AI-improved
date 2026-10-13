package dev.chungjungsoo.gptmobile.data.diagnostics

/** Redact before truncating; incomplete nested JSON is deliberately not treated as safe text. */
internal fun redactDiagnosticPayload(message: String): String = message.lineSequence().joinToString("\n") { line ->
    val sensitive = Regex("""(?i)(?:\\?["'](?:query|prompt|arguments|result|document|content|text|error_body)\\?["']\s*:|\b(?:query|prompt|arguments|result|document|error_body)\s*=)""")
    val start = sensitive.find(line)?.range?.first
    val safe = if (start == null) line else line.take(start) + "[private payload redacted]"
    safe.replace(Regex("""(https?://[^\s?"']+)\?[^\s"']+"""), "$1?[redacted]")
}

internal fun diagnosticMessageForStorage(message: String): String {
    val safe = redactLogMessage(message)
    return if (safe.length > 7800) safe.take(7800) + " · truncatedAtSource=true · originalLength=${message.length} · retainedLength=7800" else safe
}
