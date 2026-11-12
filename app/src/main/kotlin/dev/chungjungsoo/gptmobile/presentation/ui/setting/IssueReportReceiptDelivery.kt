package dev.chungjungsoo.gptmobile.presentation.ui.setting

import kotlinx.coroutines.CancellationException

/** Return only after an opened draft or explicit dismissal; cancellation leaves the receipt pending. */
internal suspend fun offerIssueReportFallback(
    url: String,
    prompt: suspend (browserUnavailable: Boolean) -> Boolean,
    openUri: (String) -> Unit
) {
    var browserUnavailable = false
    while (prompt(browserUnavailable)) {
        try {
            openUri(url)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            browserUnavailable = true
        }
    }
}
