package dev.chungjungsoo.gptmobile.data.agent.tool

import java.io.EOFException
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal class McpIncompleteResponseException(method: String, val receivedBytes: Int) :
    EOFException(
        "MCP_STREAM_INTERRUPTED: $method response ended before its complete envelope; receivedBytes=$receivedBytes; " +
            if (method in MCP_REPLAYABLE_READS) "read may be retried" else "UNKNOWN_OUTCOME: reconcile before retrying"
    )

internal class McpRequestTimeoutException(method: String, timeout: Long) :
    IOException(
        "MCP_REQUEST_TIMEOUT: $method exceeded $timeout ms; " +
            if (method in MCP_REPLAYABLE_READS) "read incomplete" else "UNKNOWN_OUTCOME: reconcile before retrying"
    )

private val MCP_REPLAYABLE_READS = setOf("server/discover", "tools/list", "resources/list", "prompts/list", "resources/read", "prompts/get", "tasks/get")

/** One bounded read retry keeps previously completed catalog pages. Tool calls are never replayed. */
internal suspend fun <T> withMcpReadRecovery(method: String, pause: suspend (Long) -> Unit = { delay(it) }, action: suspend () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    val causes = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    val retryable = failure.isMcpInterruptedRead() || causes.any { it is UnknownHostException }
    if (method !in MCP_REPLAYABLE_READS || !retryable || causes.any { it is javax.net.ssl.SSLException || it is ModernMcpError || it is io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError }) throw failure
    currentCoroutineContext().ensureActive()
    val category = if (causes.any { it is UnknownHostException }) "DNS_FAILED" else "CONNECTION_ABORTED"
    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("MCP", "READ_RECOVERY · method=$method · originalFailure=$category · failedAttempt=1 · nextAttempt=2 · policy=IDEMPOTENT_READ_ONLY", "W")
    pause(250L + kotlin.random.Random.nextLong(0, 251))
    try {
        action().also {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("MCP", "READ_RECOVERED · method=$method · originalFailure=$category · completedAttempt=2 · priorFailureRetained=true")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (second: Exception) {
        if (second !== failure) second.addSuppressed(failure)
        throw second
    }
}

/** A request deadline is transport failure; cancelling its parent remains intentional cancellation. */
internal suspend fun <T> withMcpRequestDeadline(timeout: Long, method: String, action: suspend () -> T): T = try {
    withTimeout(timeout) { action() }
} catch (deadline: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw McpRequestTimeoutException(method, timeout)
}
