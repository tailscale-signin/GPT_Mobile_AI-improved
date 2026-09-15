# 001: Error Handling Architecture

**Status**: Accepted  
**Author**: tailscale-signin  
**Created**: 2026-09-15

## Context

Conversations in the GPT Mobile AI application can unexpectedly terminate without proper error notification, leaving users confused and unable to diagnose issues. Common failure modes include:

- Network timeouts during LLM requests
- Connection resets and DNS failures
- Resource exhaustion (memory, GPU, disk)
- Uncaught exceptions being swallowed
- Context length exceeded errors
- Rate limiting and quota exhaustion

## Decision

Implement a **layered error handling architecture** with the following principles:

1. **Isolation**: Each layer (presentation, application, service, infrastructure) handles its own errors independently
2. **Progressive Recovery**: Multiple fallback layers (cache → primary LLM → fallback LLM → error response)
3. **Graceful Degradation**: System continues functioning at reduced capacity rather than failing completely
4. **Actionable Errors**: Users receive clear, actionable error messages with suggestions
5. **Comprehensive Monitoring**: All errors are tracked with metrics, traces, and alerts

## Consequences

### Positive
- Users receive helpful error messages instead of silent failures
- System automatically recovers from transient errors
- Cascading failures are prevented via circuit breakers
- Error patterns are visible through metrics and logs
- Recovery success can be measured and optimized

### Negative
- Increased code complexity across multiple layers
- More files and dependencies to maintain
- Performance overhead from retries, circuit breakers, and metrics
- Requires careful testing of error paths

### Neutral
- May require periodic circuit breaker state resets
- Cache invalidation adds complexity

## Files

| File | Purpose |
|------|---------|
| `RobustConversationHandler.java` | Main entry point with validation, circuit breaker check, timeout wrapper |
| `ConversationEngine.java` | Multi-layer recovery orchestrator |
| `ErrorClassification.java` | Categorizes errors as retryable/non-retryable/critical |
| `CircuitBreaker.java` | Prevents cascading failures to LLM service |
| `RetryPolicy.java` | Exponential backoff with jitter |
| `ErrorTemplates.java` | User-friendly error messages with suggestions |
| `MetricsRegistry.java` | Collects error, latency, and recovery metrics |
| `TraceContext.java` | Distributed tracing for error correlation |

## Implementation Details

### Layered Architecture

```
Presentation Layer → Application Layer → Service Layer → Infrastructure Layer
```

Each layer:
1. Validates inputs
2. Handles its own errors
3. Delegates to next layer
4. Provides fallbacks

### Error Classification

| Type | Examples | Handling |
|------|----------|----------|
| RETRYABLE | TIMEOUT, CONNECTION_RESET, RATE_LIMITED | Retry with backoff |
| NON_RETRYABLE | CONTEXT_EXCEEDED, INVALID_INPUT, QUOTA_EXCEEDED | Inform user, suggest alternatives |
| CRITICAL | OOM_KILLER, CUDA_OUT_OF_MEMORY, DATABASE_CORRUPT | Alert, manual intervention needed |

### Recovery Flow

1. Try cache first (fastest)
2. Try primary LLM (best quality)
3. Try fallback LLM (smaller model, reduced context)
4. Try local fallback (simple model)
5. Return error response (last resort)

## Alternatives Considered

### 1. Global Exception Handler
**Rejected**: Too monolithic, hard to debug, doesn't provide per-request context.

### 2. Simple Try-Catch at Entry Point
**Rejected**: Only catches top-level errors, misses errors in nested calls, no retry logic.

### 3. Retry Everything
**Rejected**: Could cause cascading failures, wastes resources, no circuit breaker.

### 4. Single LLM with Fallback Only
**Rejected**: No cache layer means slower responses, no metrics for optimization.

## References

- [RESILIENT_CONVERSATIONS_LAYOUT.md](../RESILIENT_CONVERSATIONS_LAYOUT.md)
- [Circuit Breaker Pattern](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Graceful Degradation](https://en.wikipedia.org/wiki/Graceful_degradation)
