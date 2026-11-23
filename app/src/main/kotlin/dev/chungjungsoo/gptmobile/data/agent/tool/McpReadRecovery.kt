package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.MetadataResponseObservation
import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
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

internal fun Throwable.mcpCancellationCause(): CancellationException? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(this) { it.cause }.takeWhile(seen::add).take(16).filterIsInstance<CancellationException>().firstOrNull()
}

/** The invocation owns this fact even when a retry replaces the session or exception. */
internal suspend fun recordMcpInterruptedRead(failure: Throwable) {
    if (failure.isLocalPrivacyPolicyRejection()) return
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence(failure) { it.cause }.takeWhile(seen::add).take(16).toList()
    // Known local, DNS, connection and TLS admission failures do not establish
    // an uncertain HTTP exchange. A general SSLException can instead report an
    // interrupted response after dispatch; retain that acquisition's uncertainty.
    if (causes.none { it is CancellationException || it is UnknownHostException || it is ConnectException || it is javax.net.ssl.SSLHandshakeException || it is javax.net.ssl.SSLPeerUnverifiedException || it is java.security.cert.CertificateException } &&
        causes.any { it is IOException }
    ) {
        currentCoroutineContext()[MetadataResponseObservation]?.readInterrupted()
    }
}

/** One bounded read retry keeps previously completed catalog pages. Tool calls are never replayed. */
internal suspend fun <T> withMcpReadRecovery(method: String, pause: suspend (Long) -> Unit = { delay(it) }, action: suspend () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    failure.mcpCancellationCause()?.let { throw it }
    val causes = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    val retryable = failure.isMcpInterruptedRead() || causes.any { it is UnknownHostException }
    if (failure.isLocalPrivacyPolicyRejection() || method !in MCP_REPLAYABLE_READS || !retryable || causes.any { it is javax.net.ssl.SSLException || it is ModernMcpError || it is io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError }) throw failure
    currentCoroutineContext().ensureActive()
    recordMcpInterruptedRead(failure)
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
        second.mcpCancellationCause()?.let { throw it }
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
