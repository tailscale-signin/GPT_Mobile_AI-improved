package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Credential-local transient-failure admission; one recovery probe after a bounded cooldown. */
internal class GitHubRequestCircuit(
    private val threshold: Int = 3,
    private val cooldownMillis: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis
) {
    init {
        require(threshold > 0 && cooldownMillis > 0)
    }
    data class Permit(val generation: Long, val probe: Boolean)
    class Open(val retryAfterMs: Long) : IllegalStateException("GITHUB_CIRCUIT_OPEN: GitHub transport is cooling down; retryAfterMs=$retryAfterMs. No request was dispatched.")

    private var generation = 0L
    private var failures = 0
    private var openUntil = 0L
    private var probing = false

    @Synchronized fun admit(): Permit {
        if (openUntil == 0L) return Permit(generation, false)
        val remaining = openUntil - now()
        if (remaining > 0 || probing) throw Open(maxOf(1000, remaining))
        probing = true
        return Permit(generation, true)
    }

    @Synchronized fun succeeded(permit: Permit) {
        if (permit.generation != generation) return
        failures = 0
        openUntil = 0
        probing = false
    }

    @Synchronized fun failed(permit: Permit, failure: Throwable) {
        if (permit.generation != generation) return
        if (failure is CancellationException) {
            if (permit.probe) probing = false
            return
        }
        val transient = failure is IOException &&
            failure !is SSLException &&
            (failure !is GitHubReadRetry.HttpFailure || GitHubReadRetry.transientStatus(failure.status))
        if (!transient) {
            // Permissions, validation and TLS failures keep their own explicit diagnostics.
            // An observed server rejection is evidence that the service is reachable.
            if (permit.probe) succeeded(permit)
            return
        }
        failures++
        if (permit.probe || failures >= threshold) {
            openUntil = now() + cooldownMillis
            probing = false
            generation++
        }
    }

    @Synchronized fun metrics() = buildJsonObject {
        put(
            "state",
            if (openUntil == 0L) {
                "CLOSED"
            } else if (probing) {
                "HALF_OPEN"
            } else {
                "OPEN"
            }
        )
        put("consecutive_transient_failures", failures)
        put("retry_after_ms", if (openUntil == 0L) 0 else (openUntil - now()).coerceAtLeast(0))
        put("probe_in_flight", probing)
    }
}
