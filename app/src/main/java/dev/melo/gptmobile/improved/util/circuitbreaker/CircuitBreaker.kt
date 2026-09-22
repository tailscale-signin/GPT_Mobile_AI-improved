package dev.melo.gptmobile.improved.util.circuitbreaker

import kotlinx.coroutines.delay

/**
 * Circuit breaker for protecting LLM API calls from cascading failures.
 * Implements three states: CLOSED, OPEN, HALF_OPEN with automatic recovery.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val recoveryTimeoutSeconds: Long = 30L,
    private val halfOpenRequests: Int = 3,
    private val stateCheckIntervalMs: Long = 1000L
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var currentState: State = State.CLOSED
    private var failureCount = 0
    private var halfOpenRequestCount = 0
    private var lastFailureTime: Long = 0
    private val lock = Any()

    /**
     * Check if circuit is closed (normal operation).
     */
    fun isClosed(): Boolean = currentState == State.CLOSED

    /**
     * Check if circuit is open (blocking all requests).
     */
    fun isOpen(): Boolean = currentState == State.OPEN

    /**
     * Check if circuit is half-open (allowing test requests).
     */
    fun isHalfOpen(): Boolean = currentState == State.HALF_OPEN

    /**
     * Execute an action with circuit breaker protection.
     * Returns true on success, false on failure.
     */
    suspend fun execute(action: () -> Result<Unit>): Boolean {
        return with(lock) {
            when (currentState) {
                State.CLOSED -> {
                    try {
                        action()
                        failureCount = 0
                        true
                    } catch (e: Exception) {
                        lastFailureTime = System.currentTimeMillis()
                        failureCount++
                        if (failureCount >= failureThreshold) {
                            currentState = State.OPEN
                        }
                        false
                    }
                }
                State.OPEN -> {
                    // Check if recovery timeout has passed
                    if (System.currentTimeMillis() - lastFailureTime >= recoveryTimeoutSeconds * 1000L) {
                        currentState = State.HALF_OPEN
                        halfOpenRequestCount = 0
                    }
                    false
                }
                State.HALF_OPEN -> {
                    halfOpenRequestCount++
                    if (halfOpenRequestCount >= halfOpenRequests) {
                        // All test requests failed, open the circuit
                        currentState = State.OPEN
                        failureCount = halfOpenRequests
                    } else {
                        try {
                            action()
                            currentState = State.CLOSED
                            failureCount = 0
                            true
                        } catch (e: Exception) {
                            // Test request failed, stay in half-open
                            false
                        }
                    }
                }
            }
        }
    }

    /**
     * Manually transition to a specific state.
     */
    fun transition(newState: State) {
        currentState = newState
        if (newState == State.CLOSED) {
            failureCount = 0
        }
    }

    /**
     * Get current state for monitoring.
     */
    fun getState(): State = currentState

    /**
     * Get failure count for monitoring.
     */
    fun getFailureCount(): Int = failureCount

    /**
     * Reset circuit breaker to initial state.
     */
    fun reset() {
        currentState = State.CLOSED
        failureCount = 0
        halfOpenRequestCount = 0
        lastFailureTime = 0
    }
}