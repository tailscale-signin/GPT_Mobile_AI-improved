package dev.chungjungsoo.gptmobile.presentation.ui.setting

import kotlinx.coroutines.CancellationException

/** An explicit Retry reuses the approved outbox entry; cancellation leaves its receipt pending. */
internal suspend fun offerIssueReportRetry(
    reportId: String,
    prompt: suspend (retryUnavailable: Boolean) -> Boolean,
    retry: suspend (String) -> Boolean
) {
    var retryUnavailable = false
    while (prompt(retryUnavailable)) {
        try {
            if (retry(reportId)) return
            retryUnavailable = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryUnavailable = true
        }
    }
}
