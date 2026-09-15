# 003: Graceful Degradation Strategy

**Status**: Accepted  
**Author**: tailscale-signin  
**Created**: 2026-09-15

## Context

When the primary LLM service fails, the system should continue functioning at reduced capacity rather than completely failing. Users should experience degraded service, not no service.

## Decision

Implement a **multi-layer fallback strategy**:

### Layer 1: Cache
- Serve cached responses for identical prompts
- TTL: 300 seconds
- Size limit: 10,000 entries

### Layer 2: Primary LLM
- Best quality model (gpt-4o-mini)
- Full context support
- Standard response time

### Layer 3: Fallback LLM
- Smaller, faster model
- Reduced context window
- Lower response quality

### Layer 4: Local Fallback
- Simple keyword matching
- Pre-defined responses
- Minimal context support

### Layer 5: Error Response
- Clear error message
- Actionable suggestions
- Retry after delay

## Consequences

### Positive
- System always responds (no dead ends)
- Users get partial answers instead of nothing
- Performance degrades gracefully
- Multiple recovery paths available

### Negative
- Cache may serve stale responses
- Fallback responses are lower quality
- Local fallback is very limited
- More code complexity

## Response Quality Scoring

| Layer | Confidence | Notes |
|-------|------------|-------|
| Primary | 1.0 | Best quality |
| Fallback LLM | 0.8 | Good, but not optimal |
| Cache | 0.7 | May be slightly stale |
| Local | 0.4 | Basic functionality only |
| Error | 0.0 | Service unavailable |

## Implementation

```java
public ConversationResponse generate(ConversationRequest request) {
    // Layer 1: Cache
    ConversationResponse cached = tryCache(request);
    if (cached != null) return cached;
    
    // Layer 2: Primary
    try { return primaryLLM.generate(request); }
    catch (Exception e) { /* continue */ }
    
    // Layer 3: Fallback
    try { return fallbackLLM.generate(request); }
    catch (Exception e) { /* continue */ }
    
    // Layer 4: Local
    try { return localFallback.generate(request); }
    catch (Exception e) { /* continue */ }
    
    // Layer 5: Error
    return ConversationResponse.error("ALL_LAYERS_FAILED", ...);
}
```

## Alternatives Considered

### 1. Fail Fast
**Rejected**: Poor user experience, no recovery.

### 2. Single Fallback Only
**Rejected**: Not enough layers, single point of failure.

### 3. Queue Requests
**Rejected**: Adds latency, no immediate response.

### 4. Return Empty Response
**Rejected**: Confusing for users, no error notification.

## Metrics

Track:
- `conversation.source_count {source: "primary", "fallback", "cache", "local", "error"}`
- `conversation.fallbacks_used_total`
- `conversation.cache_hits_total`
- `conversation.degradation_level`
