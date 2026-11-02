package dev.chungjungsoo.gptmobile.data.agent.tool

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal class McpStreamIdleTimeoutException(timeout: Long) : IOException("MCP_STREAM_IDLE_TIMEOUT: no stream line or heartbeat within $timeout ms; reconcile uncertain tool outcomes before retrying.")

/** SSE comments count as heartbeats. Parent/lifecycle cancellation remains cancellation. */
internal suspend fun <T> withMcpStreamIdleDeadline(timeout: Long, read: suspend () -> T): T = try {
    withTimeout(timeout) { read() }
} catch (deadline: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw McpStreamIdleTimeoutException(timeout)
}
