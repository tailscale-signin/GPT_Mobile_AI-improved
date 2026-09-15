# Resilient Conversations - Implementation Guide

This guide provides step-by-step implementation instructions for building a robust error handling system that prevents conversations from exiting unexpectedly without proper error notification.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Phase 1: Base Structure](#phase-1-base-structure)
3. [Phase 2: Core Conversation Handling](#phase-2-core-conversation-handling)
4. [Phase 3: Error Handling Layer](#phase-3-error-handling-layer)
5. [Phase 4: LLM Service with Fallbacks](#phase-4-llm-service-with-fallbacks)
6. [Phase 5: Caching and Metrics](#phase-5-caching-and-metrics)
7. [Phase 6: Testing](#phase-6-testing)
8. [Phase 7: CI/CD Setup](#phase-7-cicd-setup)
9. [Phase 8: Deployment and Monitoring](#phase-8-deployment-and-monitoring)
10. [Troubleshooting](#troubleshooting)

---

## Prerequisites

- Java 17+ (or Kotlin 1.9+)
- Gradle 8.x
- Git
- GitHub account with access to the repository
- LLM API credentials (OpenAI, Anthropic, etc.)

---

## Phase 1: Base Structure

### 1.1 Create Directory Structure

```bash
mkdir -p app/src/main/java/com/gptmobileai/{conversation,error,llm,cache,metrics,network,tracing}
mkdir -p app/src/test/java/com/gptmobileai/{conversation,error,llm,cache,integration}
mkdir -p docs/{error-handling,resilience,testing}
mkdir -p docs/adr
mkdir -p scripts/{error-simulation,testing,monitoring}
mkdir -p .github/workflows
mkdir -p .github/ISSUE_TEMPLATE
```

### 1.2 Add Gradle Module

Add to `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GPT_Mobile_AI"
include(":app")
```

### 1.3 Add Dependencies to `app/build.gradle.kts`

```kotlin
dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // LLM Clients
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    // Circuit Breaker
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.2.0")
    
    // Cache
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    
    // Metrics
    implementation("io.micrometer:micrometer-core:1.12.0")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.0")
    
    // Tracing
    implementation("io.opentelemetry:opentelemetry-api:1.33.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.33.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("io.mockk:mockk:1.13.8")
}
```

### 1.4 Add ProGuard Rules

Add to `app/proguard-rules.pro`:

```proguard
# Keep error classes
-keep class com.gptmobileai.error.** { *; }

# Keep conversation classes
-keep class com.gptmobileai.conversation.** { *; }

# Keep LLM client classes
-keep class com.gptmobileai.llm.** { *; }

# Keep metrics classes
-keep class com.gptmobileai.metrics.** { *; }

# Keep tracing classes
-keep class com.gptmobileai.tracing.** { *; }
```

---

## Phase 2: Core Conversation Handling

### 2.1 Create Data Models

**ConversationRequest.java**

```java
package com.gptmobileai.conversation;

import java.util.List;
import java.util.Map;

public class ConversationRequest {
    private final String prompt;
    private final List<Message> history;
    private final String userId;
    private final String sessionId;
    private final int maxTokens;
    private final float temperature;

    public ConversationRequest(String prompt, List<Message> history, String userId, 
                              String sessionId, int maxTokens, float temperature) {
        this.prompt = prompt;
        this.history = history;
        this.userId = userId;
        this.sessionId = sessionId;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    // Getters
    public String getPrompt() { return prompt; }
    public List<Message> getHistory() { return history; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public int getMaxTokens() { return maxTokens; }
    public float getTemperature() { return temperature; }
}
```

**ConversationResponse.java**

```java
package com.gptmobileai.conversation;

import java.util.List;
import java.util.Map;

public class ConversationResponse {
    private final String content;
    private final Status status;
    private final String source;
    private final String error;
    private final List<String> suggestions;
    private final Map<String, Object> metadata;

    public ConversationResponse(String content, Status status, String source,
                               String error, List<String> suggestions, Map<String, Object> metadata) {
        this.content = content;
        this.status = status;
        this.source = source;
        this.error = error;
        this.suggestions = suggestions;
        this.metadata = metadata;
    }

    // Getters
    public String getContent() { return content; }
    public Status getStatus() { return status; }
    public String getSource() { return source; }
    public String getError() { return error; }
    public List<String> getSuggestions() { return suggestions; }
    public Map<String, Object> getMetadata() { return metadata; }

    public static ConversationResponse error(String errorType, String message) {
        return new ConversationResponse(
            message,
            Status.FAILED,
            "error",
            errorType,
            List.of(),
            Map.of()
        );
    }

    public static ConversationResponse degraded(String message) {
        return new ConversationResponse(
            message,
            Status.DEGRADED,
            "degraded",
            null,
            List.of(),
            Map.of()
        );
    }
}
```

**Status.java**

```java
package com.gptmobileai.conversation;

public enum Status {
    STARTED,
    GENERATING,
    COMPLETED,
    FAILED,
    ABORTED,
    DEGRADED
}
```

### 2.2 Implement ConversationEngine

**ConversationEngine.java**

```java
package com.gptmobileai.conversation;

import com.gptmobileai.llm.LLMService;
import com.gptmobileai.cache.LocalCache;
import com.gptmobileai.metrics.MetricsRegistry;
import com.gptmobileai.error.ErrorTracker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ConversationEngine {
    private final LLMService primaryLLM;
    private final LLMService fallbackLLM;
    private final LocalCache cache;
    private final MetricsRegistry metrics;
    private final ErrorTracker errorTracker;
    private final int maxContextLength;

    public ConversationEngine(LLMService primaryLLM, LLMService fallbackLLM,
                             int maxContextLength) {
        this.primaryLLM = primaryLLM;
        this.fallbackLLM = fallbackLLM;
        this.maxContextLength = maxContextLength;
        this.cache = new LocalCache(10000, 300, TimeUnit.SECONDS);
        this.metrics = MetricsRegistry.getInstance();
        this.errorTracker = ErrorTracker.getInstance();
    }

    public ConversationResponse generate(ConversationRequest request) {
        // Layer 1: Validate context length
        if (exceedsContextLength(request)) {
            metrics.recordError("CONTEXT_EXCEEDED");
            return ConversationResponse.error(
                "CONTEXT_EXCEEDED",
                "Your conversation history is too long. Some details may be lost."
            );
        }

        // Layer 2: Try cache
        ConversationResponse cached = tryCache(request);
        if (cached != null) {
            metrics.recordCacheHit();
            return cached;
        }

        // Layer 3: Try primary LLM
        try {
            ConversationResponse primary = primaryLLM.generate(request);
            cacheResponse(request, primary);
            metrics.recordSource("primary");
            return primary;
        } catch (Exception e) {
            metrics.recordError("PRIMARY_FAILURE");
            errorTracker.recordError("PRIMARY_FAILURE");
        }

        // Layer 4: Try fallback LLM
        try {
            ConversationResponse fallback = fallbackLLM.generate(request);
            metrics.recordSource("fallback");
            return fallback;
        } catch (Exception e) {
            metrics.recordError("FALLBACK_FAILURE");
            errorTracker.recordError("FALLBACK_FAILURE");
        }

        // Layer 5: Return error response
        return ConversationResponse.error("ALL_LAYERS_FAILED", 
            "All services are unavailable. Please try again shortly.");
    }

    private boolean exceedsContextLength(ConversationRequest request) {
        int estimatedTokens = estimateTokens(request);
        return estimatedTokens > maxContextLength;
    }

    private int estimateTokens(ConversationRequest request) {
        int promptTokens = estimateTokens(request.getPrompt());
        int historyTokens = request.getHistory().stream()
            .mapToInt(this::estimateTokenCount)
            .sum();
        return promptTokens + historyTokens;
    }

    private int estimateTokenCount(String text) {
        return text.length() / 4; // Rough approximation
    }

    private ConversationResponse tryCache(ConversationRequest request) {
        String key = cacheKey(request);
        return cache.get(key);
    }

    private void cacheResponse(ConversationRequest request, ConversationResponse response) {
        String key = cacheKey(request);
        cache.set(key, response);
    }

    private String cacheKey(ConversationRequest request) {
        return request.getSessionId() + ":" + request.getPrompt();
    }
}
```

---

## Phase 3: Error Handling Layer

### 3.1 Implement Error Classification

**ErrorClassification.java**

```java
package com.gptmobileai.error;

import java.util.Set;
import java.util.HashSet;

public class ErrorClassification {

    public static final Set<String> RETRYABLE_ERRORS = new HashSet<>(Set.of(
        "TIMEOUT",
        "CONNECTION_RESET",
        "CONNECTION_REFUSED",
        "DNS_FAILURE",
        "RATE_LIMITED",
        "SERVICE_UNAVAILABLE",
        "GATEWAY_TIMEOUT"
    ));

    public static final Set<String> NON_RETRYABLE_ERRORS = new HashSet<>(Set.of(
        "CONTEXT_EXCEEDED",
        "INVALID_INPUT",
        "QUOTA_EXCEEDED",
        "API_KEY_INVALID",
        "MODEL_UNAVAILABLE"
    ));

    public static final Set<String> CRITICAL_ERRORS = new HashSet<>(Set.of(
        "OOM_KILLER",
        "CUDA_OUT_OF_MEMORY",
        "DATABASE_CORRUPT",
        "FILE_SYSTEM_FULL"
    ));

    public static boolean isRetryable(String errorType) {
        return RETRYABLE_ERRORS.contains(errorType);
    }

    public static boolean isNonRetryable(String errorType) {
        return NON_RETRYABLE_ERRORS.contains(errorType);
    }

    public static boolean isCritical(String errorType) {
        return CRITICAL_ERRORS.contains(errorType);
    }
}
```

### 3.2 Implement Error Tracker

**ErrorTracker.java**

```java
package com.gptmobileai.error;

import com.gptmobileai.metrics.MetricsRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ErrorTracker {
    private static final ErrorTracker INSTANCE = new ErrorTracker();
    private final Map<String, Integer> errorCounts;
    private final Map<String, Integer> errorByUser;
    private final Map<String, Integer> errorBySession;

    private ErrorTracker() {
        this.errorCounts = new ConcurrentHashMap<>();
        this.errorByUser = new ConcurrentHashMap<>();
        this.errorBySession = new ConcurrentHashMap<>();
    }

    public static ErrorTracker getInstance() {
        return INSTANCE;
    }

    public void recordError(String errorType) {
        errorCounts.merge(errorType, 1, Integer::sum);
        MetricsRegistry.getInstance().recordError(errorType);
    }

    public void recordError(String errorType, Exception e) {
        recordError(errorType);
        // Log detailed error with stack trace
        System.err.println("Error [" + errorType + "]: " + e.getMessage());
        e.printStackTrace();
    }

    public int getErrorCount(String errorType) {
        return errorCounts.getOrDefault(errorType, 0);
    }

    public int getTotalErrors() {
        return errorCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public void clearUserErrors(String userId) {
        errorByUser.entrySet().removeIf(e -> e.getValue().equals(userId));
    }

    public void clearSessionErrors(String sessionId) {
        errorBySession.entrySet().removeIf(e -> e.getValue().equals(sessionId));
    }
}
```

### 3.3 Implement Circuit Breaker

**CircuitBreaker.java**

```java
package com.gptmobileai.error;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

public class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long recoveryTimeoutSeconds;
    private final int halfOpenRequests;
    private final AtomicInteger failureCount;
    private final AtomicReference<State> state;
    private long lastFailureTime;

    public CircuitBreaker(int failureThreshold, long recoveryTimeoutSeconds, int halfOpenRequests) {
        this.failureThreshold = failureThreshold;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
        this.halfOpenRequests = halfOpenRequests;
        this.failureCount = new AtomicInteger(0);
        this.state = new AtomicReference<>(State.CLOSED);
    }

    public synchronized boolean isClosed() {
        return state.get() == State.CLOSED;
    }

    public synchronized boolean isOpen() {
        return state.get() == State.OPEN;
    }

    public synchronized boolean execute(Runnable action) {
        if (state.get() == State.OPEN) {
            return false;
        }

        try {
            action.run();
            success();
            return true;
        } catch (Exception e) {
            failure();
            return false;
        }
    }

    private void success() {
        if (state.get() == State.HALF_OPEN) {
            state.set(State.CLOSED);
            failureCount.set(0);
        }
    }

    private void failure() {
        failureCount.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();

        if (failureCount.get() >= failureThreshold) {
            state.set(State.OPEN);
        }
    }

    private void checkRecovery() {
        if (state.get() == State.OPEN &&
            System.currentTimeMillis() - lastFailureTime > recoveryTimeoutSeconds * 1000) {
            state.set(State.HALF_OPEN);
            failureCount.set(0);
        }
    }

    public void runRecoveryCheck() {
        checkRecovery();
    }
}
```

### 3.4 Implement Retry Policy

**RetryPolicy.java**

```java
package com.gptmobileai.error;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryPolicy {
    private final int maxAttempts;
    private final long initialDelayMs;
    private final double backoffMultiplier;
    private final long maxDelayMs;
    private final boolean useJitter;

    public RetryPolicy(int maxAttempts, long initialDelayMs, double backoffMultiplier,
                      long maxDelayMs, boolean useJitter) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelayMs = maxDelayMs;
        this.useJitter = useJitter;
    }

    public <T> T execute(Callable<T> action) throws Exception {
        AtomicInteger attempt = new AtomicInteger(0);

        while (attempt.get() < maxAttempts) {
            try {
                return action.call();
            } catch (Exception e) {
                attempt.incrementAndGet();

                if (attempt.get() >= maxAttempts) {
                    throw new RuntimeException("Max retries exceeded", e);
                }

                long delay = calculateDelay(attempt.get());
                System.err.println("Retry attempt " + attempt.get() + " in " + delay + "ms");
                Thread.sleep(delay);
            }
        }

        throw new RuntimeException("Max retries exceeded");
    }

    private long calculateDelay(int attempt) {
        long delay = (long) (initialDelayMs * Math.pow(backoffMultiplier, attempt - 1));
        if (useJitter) {
            delay = delay / 2 + new java.util.Random().nextLong() % (delay / 2);
        }
        return Math.min(delay, maxDelayMs);
    }
}
```

### 3.5 Implement Error Templates

**ErrorTemplates.java**

```java
package com.gptmobileai.error;

import java.util.List;
import java.util.Map;

public class ErrorTemplates {

    public static final Map<String, ErrorTemplate> TEMPLATES = Map.of(
        "TIMEOUT", new ErrorTemplate(
            "⏱️ The response took too long. This might be due to high traffic. Please try again.",
            List.of(
                "Try rephrasing your question",
                "Break your question into smaller parts",
                "Check your internet connection"
            ),
            30L
        ),
        "CONTEXT_EXCEEDED", new ErrorTemplate(
            "📝 Your conversation history is too long. Some details may be lost.",
            List.of(
                "Summarize key points",
                "Start a new conversation thread",
                "Refer to earlier messages directly"
            ),
            null
        ),
        "RATE_LIMITED", new ErrorTemplate(
            "🚫 Too many requests. Please wait before trying again.",
            List.of(),
            60L
        ),
        "MODEL_UNAVAILABLE", new ErrorTemplate(
            "🔧 The model is temporarily unavailable. Please try again shortly.",
            List.of(),
            60L
        ),
        "ALL_LAYERS_FAILED", new ErrorTemplate(
            "⚠️ All services are currently unavailable. Please try again in a moment.",
            List.of("Check back in 2-3 minutes"),
            120L
        )
    );

    public static String getMessage(String errorType) {
        ErrorTemplate template = TEMPLATES.get(errorType);
        return template != null ? template.getMessage() : "An error occurred. Please try again.";
    }

    public static List<String> getSuggestions(String errorType) {
        ErrorTemplate template = TEMPLATES.get(errorType);
        return template != null ? template.getSuggestions() : List.of();
    }

    public static long getRetryAfter(String errorType) {
        ErrorTemplate template = TEMPLATES.get(errorType);
        return template != null ? template.getRetryAfter() : 0L;
    }

    public static int getSeverity(String errorType) {
        if (ErrorClassification.isCritical(errorType)) return 3;
        if (ErrorClassification.isRetryable(errorType)) return 2;
        return 1;
    }

    public record ErrorTemplate(String message, List<String> suggestions, Long retryAfter) {}
}
```

---

## Phase 4: LLM Service with Fallbacks

### 4.1 Implement Primary LLM Client

**PrimaryLLM.java**

```java
package com.gptmobileai.llm;

import com.gptmobileai.conversation.ConversationRequest;
import com.gptmobileai.conversation.ConversationResponse;
import com.gptmobileai.metrics.MetricsRegistry;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class PrimaryLLM implements LLMService {
    private static final String API_URL = "https://api.openai.com/v1";
    private static final int REQUEST_TIMEOUT = 30000;

    private final String apiKey;
    private final OkHttpClient client;
    private final LLMClient llmClient;

    public PrimaryLLM(String apiKey) {
        this.apiKey = apiKey;
        this.client = new OkHttpClient.Builder()
            .connectTimeout(REQUEST_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(REQUEST_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(REQUEST_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build();

        Retrofit retrofit = new Retrofit.Builder()
            .baseUrl(API_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build();

        this.llmClient = retrofit.create(LLMClient.class);
    }

    @Override
    public ConversationResponse generate(ConversationRequest request) {
        try {
            long startTime = System.currentTimeMillis();

            // Build messages array
            java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
            messages.add(Map.of("role", "user", "content", request.getPrompt()));

            // Make API call
            ChatResponse response = llmClient.chatCompletion(
                "gpt-4o-mini",
                messages,
                request.getMaxTokens(),
                request.getTemperature()
            );

            long duration = System.currentTimeMillis() - startTime;
            MetricsRegistry.getInstance().recordLatency(duration);

            return new ConversationResponse(
                response.choices().get(0).message().content(),
                com.gptmobileai.conversation.Status.COMPLETED,
                "primary",
                null,
                null,
                Map.of("duration_ms", duration, "tokens", response.usage().totalTokens())
            );

        } catch (Exception e) {
            MetricsRegistry.getInstance().recordError("PRIMARY_LLM_ERROR");
            throw e;
        }
    }

    interface LLMClient {
        ChatResponse chatCompletion(String model, List<Map<String, String>> messages,
                                   int maxTokens, float temperature);
    }
}
```

### 4.2 Implement Fallback LLM Client

**FallbackLLM.java**

```java
package com.gptmobileai.llm;

import com.gptmobileai.conversation.ConversationRequest;
import com.gptmobileai.conversation.ConversationResponse;

public class FallbackLLM implements LLMService {
    private final LLMService localFallback;

    public FallbackLLM(LLMService localFallback) {
        this.localFallback = localFallback;
    }

    @Override
    public ConversationResponse generate(ConversationRequest request) {
        // Try local fallback first
        try {
            return localFallback.generate(request);
        } catch (Exception e) {
            // Fall back to smaller LLM
            return trySmallerModel(request);
        }
    }

    private ConversationResponse trySmallerModel(ConversationRequest request) {
        // Use a smaller, faster model
        // Implementation depends on available models
        return new ConversationResponse(
            "I'm running out of options. Please try again later.",
            com.gptmobileai.conversation.Status.DEGRADED,
            "fallback",
            null,
            List.of("Try a simpler question"),
            Map.of()
        );
    }
}
```

### 4.3 Implement LLM Service Interface

**LLMService.java**

```java
package com.gptmobileai.llm;

import com.gptmobileai.conversation.ConversationRequest;
import com.gptmobileai.conversation.ConversationResponse;

public interface LLMService {
    ConversationResponse generate(ConversationRequest request) throws Exception;
}
```

---

## Phase 5: Caching and Metrics

### 5.1 Implement Local Cache

**LocalCache.java**

```java
package com.gptmobileai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.gptmobileai.conversation.ConversationResponse;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

public class LocalCache {
    private final Cache<String, ConversationResponse> cache;
    private final ConcurrentHashMap<String, ConversationResponse> directMap;

    public LocalCache(int maxSize, long ttlSeconds, TimeUnit timeUnit) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttlSeconds, timeUnit)
            .build();

        this.directMap = new ConcurrentHashMap<>();
    }

    public ConversationResponse get(String key) {
        ConversationResponse response = cache.getIfPresent(key);
        if (response != null) {
            directMap.put(key, response);
        }
        return response;
    }

    public void set(String key, ConversationResponse response) {
        cache.put(key, response);
        directMap.put(key, response);
    }

    public void invalidate(String key) {
        cache.invalidate(key);
        directMap.remove(key);
    }

    public void clear() {
        cache.invalidateAll();
        directMap.clear();
    }

    public int size() {
        return directMap.size();
    }
}
```

### 5.2 Implement Metrics Registry

**MetricsRegistry.java**

```java
package com.gptmobileai.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public class MetricsRegistry {
    private static final MetricsRegistry INSTANCE = new MetricsRegistry();
    private final PrometheusMeterRegistry registry;
    private final Timer latencyTimer;
    private final io.micrometer.core.instrument.Counter errorsCounter;

    private MetricsRegistry() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.latencyTimer = registry.timer("conversation.latency");
        this.errorsCounter = registry.counter("conversation.errors.total");
    }

    public static MetricsRegistry getInstance() {
        return INSTANCE;
    }

    public void recordLatency(long durationMs) {
        latencyTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordError(String errorType) {
        errorsCounter.increment();
        registry.counter("conversation.errors.by_type." + errorType).increment();
    }

    public void recordSource(String source) {
        registry.counter("conversation.source_count." + source).increment();
    }

    public void recordCacheHit() {
        registry.counter("conversation.cache_hits").increment();
    }

    public void recordCacheMiss() {
        registry.counter("conversation.cache_misses").increment();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
```

---

## Phase 6: Testing

### 6.1 Create Unit Tests

**ConversationEngineTest.java**

```java
package com.gptmobileai.conversation;

import com.gptmobileai.llm.LLMService;
import com.gptmobileai.cache.LocalCache;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ConversationEngineTest {

    @Test
    public void testPrimarySuccess() {
        LLMService primary = mock(LLMService.class);
        LLMService fallback = mock(LLMService.class);

        when(primary.generate(any())).thenAnswer(invocation -> {
            ConversationRequest request = invocation.getArgument(0);
            return new ConversationResponse(
                "Success response",
                Status.COMPLETED,
                "primary",
                null,
                null,
                null
            );
        });

        ConversationEngine engine = new ConversationEngine(primary, fallback, 10000);
        ConversationRequest request = new ConversationRequest("test", null, "user1", "session1", 100, 0.7f);

        ConversationResponse response = engine.generate(request);

        assertEquals("Success response", response.getContent());
        assertEquals(Status.COMPLETED, response.getStatus());
        assertEquals("primary", response.getSource());
        verify(primary).generate(request);
        verify(fallback, never()).generate(any());
    }

    @Test
    public void testFallbackActivation() {
        LLMService primary = mock(LLMService.class);
        LLMService fallback = mock(LLMService.class);

        when(primary.generate(any())).thenThrow(new RuntimeException("Primary failed"));
        when(fallback.generate(any())).thenAnswer(invocation -> {
            return new ConversationResponse(
                "Fallback response",
                Status.DEGRADED,
                "fallback",
                null,
                null,
                null
            );
        });

        ConversationEngine engine = new ConversationEngine(primary, fallback, 10000);
        ConversationRequest request = new ConversationRequest("test", null, "user1", "session1", 100, 0.7f);

        ConversationResponse response = engine.generate(request);

        assertEquals("Fallback response", response.getContent());
        assertEquals("fallback", response.getSource());
        verify(primary).generate(request);
        verify(fallback).generate(request);
    }

    @Test
    public void testAllLayersFailure() {
        LLMService primary = mock(LLMService.class);
        LLMService fallback = mock(LLMService.class);

        when(primary.generate(any())).thenThrow(new RuntimeException("Primary failed"));
        when(fallback.generate(any())).thenThrow(new RuntimeException("Fallback failed"));

        ConversationEngine engine = new ConversationEngine(primary, fallback, 10000);
        ConversationRequest request = new ConversationRequest("test", null, "user1", "session1", 100, 0.7f);

        ConversationResponse response = engine.generate(request);

        assertEquals("ALL_LAYERS_FAILED", response.getError());
        verify(primary).generate(request);
        verify(fallback).generate(request);
    }
}
```

### 6.2 Create Integration Test

**FullConversationFlowTest.java**

```java
package com.gptmobileai.integration;

import com.gptmobileai.conversation.*;
import com.gptmobileai.llm.LLMService;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FullConversationFlowTest {

    @Test
    public void testFullFlowWithSimulatedFailures() {
        // Simulate: success -> timeout -> success -> success
        LLMService primary = mock(LLMService.class);
        LLMService fallback = mock(LLMService.class);

        java.util.List<Throwable> exceptions = new java.util.ArrayList<>();
        exceptions.add(new RuntimeException("First success"));
        exceptions.add(new java.util.concurrent.TimeoutException("Timeout"));
        exceptions.add(new RuntimeException("Second success"));
        exceptions.add(new RuntimeException("Third success"));

        java.util.List<ConversationResponse> responses = new java.util.ArrayList<>();
        responses.add(new ConversationResponse("Response 1", Status.COMPLETED, "primary", null, null, null));
        responses.add(new ConversationResponse("Response 2", Status.DEGRADED, "fallback", null, null, null));
        responses.add(new ConversationResponse("Response 3", Status.COMPLETED, "primary", null, null, null));
        responses.add(new ConversationResponse("Response 4", Status.COMPLETED, "primary", null, null, null));

        int callCount = 0;
        when(primary.generate(any())).thenAnswer(invocation -> {
            if (callCount < exceptions.size()) {
                callCount++;
                Throwable t = exceptions.get(callCount - 1);
                if (t != null) throw t;
            }
            return responses.get(callCount);
        });

        ConversationEngine engine = new ConversationEngine(primary, fallback, 10000);
        ConversationRequest request = new ConversationRequest("test", null, "user1", "session1", 100, 0.7f);

        for (int i = 0; i < 4; i++) {
            ConversationResponse response = engine.generate(request);
            assertNotNull(response);
        }

        verify(primary, atLeast(3)).generate(any());
        verify(fallback, atLeast(1)).generate(any());
    }
}
```

---

## Phase 7: CI/CD Setup

### 7.1 Create CI Workflow

**.github/workflows/error-handling-ci.yml**

```yaml
name: Error Handling CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
      - name: Build with Gradle
        run: ./gradlew build
      - name: Run Tests
        run: ./gradlew test
      - name: Run Error Simulation Tests
        run: ./gradlew test --tests "*Error*"
  chaos:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run Chaos Engineering Tests
        run: ./scripts/testing/run-chaos-tests.sh
```

---

## Phase 8: Deployment and Monitoring

### 8.1 Configure Alerts

**Alerts Configuration**

```yaml
alerts:
  - name: "High Error Rate"
    condition: "error_rate > 5% for 5 minutes"
    severity: "critical"
    message: "Conversation error rate exceeded threshold"
    
  - name: "Circuit Breaker Open"
    condition: "circuit_breaker_open > 0 for 10 minutes"
    severity: "warning"
    message: "LLM service circuit breaker is open"
    
  - name: "Unusual Abortion Rate"
    condition: "abort_rate > 3% for 15 minutes"
    severity: "warning"
    message: "Higher than normal conversation abort rate"

  - name: "P99 Latency Spike"
    condition: "latency_p99 > 5s for 5 minutes"
    severity: "warning"
    message: "Response latency is too high"
```

### 8.2 Monitoring Dashboard

Key metrics to track:
- **Conversations**: total, successful, failed, aborted
- **Errors**: by type, by recovery method, by user segment
- **Performance**: P50/P95/P99 latency, time to first token
- **Recovery**: retries, fallbacks, cache hits
- **Circuit Breaker**: state (closed/open/half-open)

---

## Troubleshooting

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| All retries fail | Circuit breaker open | Wait for recovery timeout or manually reset |
| High error rate | LLM API issues | Check API status, use fallback immediately |
| Cache not working | TTL too short | Increase cache TTL or check cache key format |
| Memory leak | Unbounded cache | Add size limits and eviction policy |
| Slow responses | No timeouts configured | Add request timeouts to HTTP client |

### Debug Checklist

- [ ] Check error logs for stack traces
- [ ] Verify circuit breaker state
- [ ] Check cache hit/miss ratio
- [ ] Review metrics for latency spikes
- [ ] Test with simulated failures
- [ ] Verify fallback activation
- [ ] Check API rate limits
- [ ] Review user reports

---

## References

- [RESILIENT_CONVERSATIONS_LAYOUT.md](./RESILIENT_CONVERSATIONS_LAYOUT.md) - Repository layout plan
- [Error Classification](./error-handling/error-classification.md) - Error type definitions
- [Retry Strategies](./error-handling/retry-strategies.md) - Retry policies
- [Circuit Breaker](./error-handling/circuit-breaker.md) - Implementation guide
- [Fallback Mechanisms](./error-handling/fallback-mechanisms.md) - Fallback options
