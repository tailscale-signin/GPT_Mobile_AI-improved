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
        val metadata = buildString {
            appendLine("### App details")
            appendLine("- Version: ${report.version} (${report.build})")
            appendLine("- Commit: ${report.commit}")
            appendLine("- Android: ${report.android} (SDK ${report.sdk})")
            appendLine("- Device: ${report.device}")
            appendLine("- Report ID: ${report.reportId}")
            report.feature?.let { appendLine("- Feature: $it") }
            report.errorCode?.let { appendLine("- Error code: $it") }
            report.errorType?.let { appendLine("- Error type: $it") }
        }
        val details = buildString {
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
            report.description?.takeIf(String::isNotBlank)?.let {
                appendLine()
                appendLine("### What happened")
                appendLine()
                appendLine(it)
            }
        }
        val prefix = "$TRACKER/new?title=${encode(title.issueReportPrefix(IssueReport.TITLE_LIMIT))}&body="
        fun fits(optional: String): Boolean = metadata.length + optional.length <= BODY_LIMIT &&
            prefix.length + encode(metadata + optional).length <= URL_LIMIT
        if (fits(details)) return prefix + encode(metadata + details)

        // Keep build, report identity and failure evidence even when user text expands during URL encoding.
        val shortened = "\n\n[Additional report text shortened to fit the issue form.]"
        var low = 0
        var high = details.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (fits(details.issueReportPrefix(middle) + shortened)) low = middle else high = middle - 1
        }
        return prefix + encode(metadata + details.issueReportPrefix(low) + shortened)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
