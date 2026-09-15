# 002: Circuit Breaker Implementation

**Status**: Accepted  
**Author**: tailscale-signin  
**Created**: 2026-09-15

## Context

The LLM API is an external dependency that can fail unpredictably due to:
- Network issues
- Service maintenance
- Rate limiting
- Model unavailability

Without protection, repeated failures can:
1. Exhaust resources (threads, connections)
2. Cascade to other parts of the system
3. Provide poor user experience

## Decision

Implement a **circuit breaker** with three states:

1. **CLOSED**: Normal operation, requests flow through
2. **OPEN**: Fail fast, no requests sent
3. **HALF_OPEN**: Testing recovery, limited requests allowed

### Configuration

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Failure Threshold | 5 | Fail after 5 consecutive failures |
| Recovery Timeout | 30s | Wait before trying again |
| Half-Open Requests | 1 | Test with single request |
| State Check Interval | 1s | Poll for recovery |

## Consequences

### Positive
- Prevents cascading failures
- Reduces load on failing LLM service
- Provides fast failure (milliseconds instead of timeouts)
- Automatic recovery when service returns
- Clear visibility into service health

### Negative
- May reject valid requests during brief outages
- State must be persisted across restarts (considered)
- Adds complexity to request flow

## Implementation

```java
public class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }
    
    private final int failureThreshold;
    private final long recoveryTimeoutSeconds;
    private final AtomicInteger failureCount;
    private final AtomicReference<State> state;
    private long lastFailureTime;
    
    public synchronized boolean isClosed() { return state.get() == State.CLOSED; }
    public synchronized boolean isOpen() { return state.get() == State.OPEN; }
    public synchronized boolean execute(Runnable action);
}
```

## Alternatives Considered

### 1. Simple Retry Count
**Rejected**: Doesn't prevent cascading failures, no fast fail.

### 2. External Circuit Breaker Library
**Rejected**: Better to implement lightweight version to control behavior precisely.

### 3. Timeout-Based Only
**Rejected**: Doesn't handle repeated failures, no recovery state.

## State Machine

```
CLOSED --[failure]--> OPEN --[recovery timeout]--> HALF_OPEN
  |                                      |
  | [success]                            | [failure]
  v                                      v
CLOSED <---[success]                  HALF_OPEN --[success]--> CLOSED
                                        |
                                        | [failure]
                                        v
                                      OPEN
```

## Monitoring

Track:
- `circuit_breaker_state {state: "closed", "open", "half_open"}`
- `circuit_breaker_failures_total`
- `circuit_breaker_open_duration_seconds`

Alert when:
- Circuit breaker is open for > 10 minutes
- Failure rate exceeds threshold
