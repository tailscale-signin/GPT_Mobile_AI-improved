package dev.chungjungsoo.gptmobile.data.network.error

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CircuitBreakerOpenException(
    val cooldownRemainingMs: Long,
    val circuitBreakerName: String = "default",
    message: String = "Circuit breaker '$circuitBreakerName' is OPEN. Cooldown remaining: ${cooldownRemainingMs}ms"
) : RuntimeException(message) {
    constructor(circuitBreakerName: String, cooldownRemainingMs: Long) : this(
        cooldownRemainingMs = cooldownRemainingMs,
        circuitBreakerName = circuitBreakerName
    )
}

/**
 * Circuit breaker states according to docs/adr/002-circuit-breaker-implementation.md.
 */
typealias CircuitState = CircuitBreaker.State

/**
 * Thread-safe Circuit Breaker implementing the state machine in ADR-002:
 * - CLOSED: Normal operation. Success resets failure count. Consecutive failures >= threshold trips to OPEN.
 * - OPEN: Immediately rejects calls with [CircuitBreakerOpenException]. After cooldownMs, transitions to HALF_OPEN.
 * - HALF_OPEN: Allows trial requests. If successful >= halfOpenSuccessThreshold, resets to CLOSED. Any failure returns to OPEN.
 */
class CircuitBreaker(
    val name: String = "default",
    private val failureThreshold: Int = 5,
    private val cooldownMs: Long = 30_000L,
    private val halfOpenSuccessThreshold: Int = 2,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    enum class State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    data class Config(
        val failureThreshold: Int = 5,
        val resetTimeoutMs: Long = 30_000L,
        val halfOpenSuccessThreshold: Int = 2
    )

    constructor(
        name: String = "default",
        config: Config,
        timeProvider: () -> Long = { System.currentTimeMillis() }
    ) : this(
        name = name,
        failureThreshold = config.failureThreshold,
        cooldownMs = config.resetTimeoutMs,
        halfOpenSuccessThreshold = config.halfOpenSuccessThreshold,
        timeProvider = timeProvider
    )

    private val stateRef = AtomicReference(State.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val halfOpenSuccesses = AtomicInteger(0)
    private val lastOpenedTimestamp = AtomicLong(0L)

    val state: State
        get() = currentState()

    val failureCount: Int
        get() = consecutiveFailures.get()

    fun currentState(): State {
        checkCooldownTransition()
        return stateRef.get()
    }

    private fun checkCooldownTransition() {
        if (stateRef.get() == State.OPEN) {
            val elapsed = timeProvider() - lastOpenedTimestamp.get()
            if (elapsed >= cooldownMs) {
                if (stateRef.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenSuccesses.set(0)
                }
            }
        }
    }

    /**
     * Executes the given [block] protected by the circuit breaker.
     * Throws [CircuitBreakerOpenException] immediately if OPEN.
     */
    fun <T> execute(block: () -> T): T {
        checkCooldownTransition()

        val current = stateRef.get()
        if (current == State.OPEN) {
            val remaining = (cooldownMs - (timeProvider() - lastOpenedTimestamp.get())).coerceAtLeast(0L)
            throw CircuitBreakerOpenException(remaining, name)
        }

        return try {
            val result = block()
            onSuccess()
            result
        } catch (t: Throwable) {
            onFailure(t)
            throw t
        }
    }

    /**
     * Suspending version of [execute].
     */
    suspend fun <T> executeSuspend(block: suspend () -> T): T {
        checkCooldownTransition()

        val current = stateRef.get()
        if (current == State.OPEN) {
            val remaining = (cooldownMs - (timeProvider() - lastOpenedTimestamp.get())).coerceAtLeast(0L)
            throw CircuitBreakerOpenException(remaining, name)
        }

        return try {
            val result = block()
            onSuccess()
            result
        } catch (t: Throwable) {
            onFailure(t)
            throw t
        }
    }

    fun onSuccess() {
        when (stateRef.get()) {
            State.HALF_OPEN -> {
                if (halfOpenSuccesses.incrementAndGet() >= halfOpenSuccessThreshold) {
                    stateRef.set(State.CLOSED)
                    consecutiveFailures.set(0)
                    halfOpenSuccesses.set(0)
                }
            }
            State.CLOSED -> {
                consecutiveFailures.set(0)
            }
            State.OPEN -> {
                // Was open, transition to half-open success
            }
        }
    }

    fun onFailure(throwable: Throwable) {
        // Do not count client cancellations towards breaker failures
        if (throwable is kotlinx.coroutines.CancellationException) return

        when (stateRef.get()) {
            State.HALF_OPEN -> {
                tripToOpen()
            }
            State.CLOSED -> {
                if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
                    tripToOpen()
                }
            }
            State.OPEN -> {
                lastOpenedTimestamp.set(timeProvider())
            }
        }
    }

    private fun tripToOpen() {
        stateRef.set(State.OPEN)
        lastOpenedTimestamp.set(timeProvider())
        halfOpenSuccesses.set(0)
    }

    fun reset() {
        stateRef.set(State.CLOSED)
        consecutiveFailures.set(0)
        halfOpenSuccesses.set(0)
        lastOpenedTimestamp.set(0L)
    }
}
