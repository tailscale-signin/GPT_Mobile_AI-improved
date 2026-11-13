package dev.chungjungsoo.gptmobile.data.network.gateway

import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

internal class DiagnosticSyncFailure(val code: String, val httpStatus: Int? = null) : IOException(code)

internal enum class GatewayDiagnosticRetryState { READY, IN_FLIGHT, BACKOFF, BLOCKED, EXHAUSTED }

internal data class GatewayDiagnosticRetrySnapshot(
    val state: GatewayDiagnosticRetryState,
    val failures: Int,
    val error: String?,
    val retryAfterMillis: Long
)

/** One lease per route. Only an explicit refresh resets a blocked or exhausted failure episode. */
internal class GatewayDiagnosticRetryGate(private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var generation = 0L
    private var inFlight: Long? = null
    private var failures = 0
    private var error: String? = null
    private var terminal: GatewayDiagnosticRetryState? = null
    private var failedAt = 0L
    private var backoffMillis = 0L

    /** The predicate is rechecked before dispatch and every persistence boundary by the operation. */
    suspend fun run(permitted: () -> Boolean, operation: suspend (() -> Boolean) -> Unit): Boolean {
        val lease = acquire(permitted()) ?: return false
        val current = { permitted() && owns(lease) }
        try {
            if (!current()) return false
            operation(current)
            return current() && succeeded(lease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (current()) failed(lease, failure)
            return false
        } finally {
            release(lease)
        }
    }

    /** Privacy/route changes revoke in-flight work without forgiving earlier failures. */
    @Synchronized fun pause() {
        generation++
        inFlight = null
    }

    @Synchronized fun refresh(permitted: Boolean): Boolean {
        if (!permitted) return false
        pause()
        failures = 0
        error = null
        terminal = null
        backoffMillis = 0
        return true
    }

    @Synchronized fun snapshot(): GatewayDiagnosticRetrySnapshot {
        val remaining = (backoffMillis - (monotonicMillis() - failedAt).coerceAtLeast(0)).coerceAtLeast(0)
        return GatewayDiagnosticRetrySnapshot(
            terminal ?: when {
                inFlight != null -> GatewayDiagnosticRetryState.IN_FLIGHT
                remaining > 0 -> GatewayDiagnosticRetryState.BACKOFF
                else -> GatewayDiagnosticRetryState.READY
            },
            failures,
            error,
            if (terminal == null) remaining else 0
        )
    }

    @Synchronized private fun acquire(permitted: Boolean): Long? {
        if (!permitted || snapshot().state != GatewayDiagnosticRetryState.READY) return null
        return (++generation).also { inFlight = it }
    }

    @Synchronized private fun owns(lease: Long): Boolean = inFlight == lease

    @Synchronized private fun succeeded(lease: Long): Boolean {
        if (!owns(lease)) return false
        failures = 0
        error = null
        backoffMillis = 0
        inFlight = null
        return true
    }

    @Synchronized private fun failed(lease: Long, failure: Exception) {
        if (!owns(lease)) return
        val (code, retryable) = classify(failure)
        error = code
        failures++
        failedAt = monotonicMillis()
        terminal = when {
            !retryable -> GatewayDiagnosticRetryState.BLOCKED
            failures >= MAX_ATTEMPTS -> GatewayDiagnosticRetryState.EXHAUSTED
            else -> null
        }
        backoffMillis = if (terminal == null) RETRY_DELAYS[failures - 1] else 0
        inFlight = null
    }

    @Synchronized private fun release(lease: Long) {
        if (owns(lease)) inFlight = null
    }

    private companion object {
        val RETRY_DELAYS = longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000)
        const val MAX_ATTEMPTS = 6
        val TRANSIENT_CODES = setOf("DIAGNOSTIC_TRANSPORT_FAILURE", "DIAGNOSTIC_BACKPRESSURE", "DEBUG_STORAGE_UNAVAILABLE")
        val BLOCKED_CODES = setOf(
            "DIAGNOSTIC_AUTHENTICATION_REQUIRED", "DIAGNOSTIC_ACCESS_DENIED", "DIAGNOSTIC_SOURCE_UNAVAILABLE",
            "DIAGNOSTIC_CAPTURE_BLOCKED", "DIAGNOSTIC_CAPTURE_EXPIRED", "DIAGNOSTIC_REQUEST_REJECTED",
            "DIAGNOSTIC_REDIRECT_REJECTED", "DIAGNOSTIC_BATCH_LIMIT", "DIAGNOSTIC_RESPONSE_LIMIT",
            "DIAGNOSTIC_SOURCE_LIMIT", "DIAGNOSTIC_GAP_LIMIT", "DIAGNOSTIC_METADATA_LIMIT", "DIAGNOSTIC_SPOOL_CORRUPT",
            "INVALID_DIAGNOSTIC_ACK", "INVALID_DIAGNOSTIC_PAGE", "INVALID_DIAGNOSTIC_CURSOR", "INVALID_DIAGNOSTIC_EVENT",
            "INVALID_DIAGNOSTIC_GAP", "DIAGNOSTIC_TLS_FAILURE", "DIAGNOSTIC_PROTOCOL_FAILURE", "DIAGNOSTIC_SYNC_FAILURE"
        )

        fun classify(failure: Exception): Pair<String, Boolean> = when (failure) {
            is DiagnosticSyncFailure -> when (failure.code) {
                in TRANSIENT_CODES -> failure.code to true
                in BLOCKED_CODES -> failure.code to false
                else -> "DIAGNOSTIC_PROTOCOL_FAILURE" to false
            }
            is SSLException -> "DIAGNOSTIC_TLS_FAILURE" to false
            is ProtocolException, is IllegalArgumentException, is IllegalStateException -> "DIAGNOSTIC_PROTOCOL_FAILURE" to false
            is SecurityException -> "DIAGNOSTIC_ACCESS_DENIED" to false
            is IOException -> "DIAGNOSTIC_TRANSPORT_FAILURE" to true
            else -> "DIAGNOSTIC_SYNC_FAILURE" to false
        }
    }
}
