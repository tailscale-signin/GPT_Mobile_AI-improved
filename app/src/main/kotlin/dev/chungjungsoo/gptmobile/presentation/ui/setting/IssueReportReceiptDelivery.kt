package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.presentation.common.Route
import kotlinx.coroutines.CancellationException

/** Navigation reports destination patterns; account setup also owns these child screens. */
internal fun shouldCompleteIssueReportSetup(route: String?): Boolean = !route.isNullOrBlank() &&
    route !in setOf(
        Route.TOOL_CONNECTIONS,
        Route.ADD_TOOL_CONNECTION,
        Route.EDIT_TOOL_CONNECTION,
        Route.AUDIT_CONNECTION_REPAIR,
        Route.AUDIT_CONNECTION_EDIT,
        Route.MCP_MARKETPLACE
    )

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
