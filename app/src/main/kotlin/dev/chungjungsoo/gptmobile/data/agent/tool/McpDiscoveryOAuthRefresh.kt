package dev.chungjungsoo.gptmobile.data.agent.tool

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only catalogue discovery uses this retry; uncertain tools/call outcomes never enter it. */
internal suspend fun <T> withMcpDiscoveryOAuthRefresh(
    initial: McpConnectionConfig,
    oauth: Boolean,
    refresh: suspend (rejectedAuthorizationHeader: String?) -> McpConnectionConfig,
    discover: suspend (config: McpConnectionConfig, refreshed: Boolean) -> T
): T = try {
    discover(initial, false)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    val causes = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    val rejected = causes.any { (it is StreamableHttpError && it.code == 401) || (it is ModernMcpError && it.status == 401) }
    // A permission denial, local auth gate, or interrupted request is not proof of an expired token.
    if (!oauth || !rejected || causes.any { (it is StreamableHttpError && it.code == 403) || (it is ModernMcpError && it.status == 403) }) throw failure
    currentCoroutineContext().ensureActive()
    val replacement = try {
        refresh(initial.authorizationHeader)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refreshFailure: Exception) {
        val refreshCauses = generateSequence<Throwable>(refreshFailure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
        refreshCauses.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
        // The first discovery already received 401. Do not replace that dispatch evidence
        // with a later credential-preparation rejection or expose OAuth response prose.
        throw McpDiscoveryOAuthRefreshException(refreshCauses.filterIsInstance<McpLocalValidationException>().firstOrNull()?.rule)
    }
    currentCoroutineContext().ensureActive()
    // This call deliberately sits outside the try/catch: a second 401 is terminal.
    discover(replacement, true)
}

internal class McpDiscoveryOAuthRefreshException(localRule: McpLocalValidationRule? = null) :
    IllegalStateException(
        "MCP_AUTHENTICATION_REQUIRED: Discovery received HTTP 401, but OAuth refresh was unavailable. No second discovery request was sent. Reconnect this service in Plugins/Tools."
    ) {
    val httpStatus: Int get() = 401
    val validationRule: String = localRule?.name ?: "OAUTH_REFRESH_UNAVAILABLE"
    val validationField: String = localRule?.field ?: "credential"
}
