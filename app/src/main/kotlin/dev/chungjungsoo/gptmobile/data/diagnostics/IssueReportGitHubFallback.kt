package dev.chungjungsoo.gptmobile.data.diagnostics

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A credential-free fallback that opens GitHub's authenticated issue composer. */
internal object IssueReportGitHubFallback {
    const val TRACKER = "https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues"
    private const val BODY_LIMIT = 6_000
    private const val URL_LIMIT = 7_500

    fun url(report: IssueReport): String {
        val title = report.title?.takeIf(String::isNotBlank)
            ?: listOfNotNull(report.feature, report.errorCode).joinToString(": ").ifBlank { "Issue reported from GPT Mobile" }
        val body = buildString {
            report.description?.takeIf(String::isNotBlank)?.let {
                appendLine(it)
                appendLine()
            }
            appendLine("### App details")
            appendLine("- Version: ${report.version} (${report.build})")
            appendLine("- Commit: ${report.commit}")
            appendLine("- Android: ${report.android} (SDK ${report.sdk})")
            appendLine("- Device: ${report.device}")
            appendLine("- Report ID: ${report.reportId}")
            report.feature?.let { appendLine("- Feature: $it") }
            report.errorCode?.let { appendLine("- Error code: $it") }
            report.errorType?.let { appendLine("- Error type: $it") }
            if (report.frames.isNotEmpty()) {
                appendLine()
                appendLine("### Redacted stack frames")
                report.frames.forEach { appendLine("- `${it.replace('`', '\'').take(240)}`") }
            }
            if (report.events.isNotEmpty()) {
                appendLine()
                appendLine("### Recent redacted events")
                report.events.forEach { appendLine("- `${it.replace('`', '\'').take(300)}`") }
            }
        }.take(BODY_LIMIT)
        val prefix = "$TRACKER/new?title=${encode(title.take(IssueReport.TITLE_LIMIT))}&body="
        var boundedBody = body
        var encodedBody = encode(boundedBody)
        while (prefix.length + encodedBody.length > URL_LIMIT && boundedBody.isNotEmpty()) {
            var end = (boundedBody.length * 3 / 4).coerceAtLeast(1)
            if (boundedBody[end - 1].isHighSurrogate()) end--
            boundedBody = boundedBody.take(end)
            encodedBody = encode(boundedBody)
        }
        return prefix + encodedBody
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
