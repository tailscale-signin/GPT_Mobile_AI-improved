# Research & Design Document: AI Platform Batch API Integration
## OpenRouter & Llama.cpp Focus

---

## 1. Executive Summary

This document provides comprehensive research on batch API capabilities for **OpenRouter** and **Llama.cpp (llama-server)**, with design specifications for integrating an "API Batch Key" configuration option under AI Platform Settings in the GPT Mobile AI repository.

---

## 2. Research Findings

### 2.1 OpenRouter

| Feature | Details |
|---------|---------|
| **Native Batch API** | ❌ No dedicated batch endpoint |
| **Alternative Approach** | ✅ Parallel requests via standard endpoint |
| **Endpoint** | `POST https://openrouter.ai/api/v1/chat/completions` |
| **Batching Method** | Client-side parallelization with rate limiting |
| **Rate Limits** | Varies by model; typically 50-100 RPM per key |
| **Cost Model** | Pay-per-token; no batch discount |

**Key Findings:**
- OpenRouter does **not** provide a native batch API endpoint
- However, they support **parallel request processing** which can be leveraged for batching
- The standard chat completions endpoint accepts multiple concurrent requests
- Best practice: Use parallel requests with exponential backoff for large volumes

**Recommended Approach:**
```typescript
// Client-side batching strategy
async function batchProcessOpenRouter(
  requests: Array<ChatCompletionRequest>,
  apiKey: string,
  maxConcurrent: number = 10
): Promise<Array<ChatCompletionResponse>> {
  const results = [];
  const semaphore = new Semaphore(maxConcurrent);
  
  for (const request of requests) {
    await semaphore.acquire();
    try {
      const response = await openRouter.chat.completions.create({
        model: request.model,
        messages: request.messages,
        api_key: apiKey
      });
      results.push(response);
    } finally {
      semaphore.release();
    }
  }
  
  return results;
}
```

---

### 2.2 Llama.cpp (llama-server)

| Feature | Details |
|---------|---------|
| **Native Batch API** | ✅ Yes - `/v1/batch` endpoint |
| **Endpoint** | `POST http://localhost:8080/v1/batch` |
| **Input Format** | JSONL with prompt + context + n_tokens_to_predict |
| **Output Format** | JSONL with token IDs and logprobs |
| **Processing** | Synchronous, returns immediately |
| **Rate Limits** | None (local server) |

**Batch Endpoint Specification:**

**Request Body (JSONL):**
```jsonl
{"prompt": "Hello", "context": "", "n_tokens_to_predict": 10}
{"prompt": "World", "context": "", "n_tokens_to_predict": 5}
{"prompt": "Test", "context": "", "n_tokens_to_predict": 8}
```

**Response Body (JSONL):**
```jsonl
{"token_ids": [7167, 290], "logprobs": null}
{"token_ids": [314, 50256], "logprobs": null}
{"token_ids": [13, 290, 7167], "logprobs": null}
```

**Alternative: Chat Completions Endpoint**
```jsonl
{
  "messages": [
    {"role": "user", "content": "Hello"}
  ],
  "n_predict": 10
}
```

**Key Findings:**
- llama-server provides a **true batch endpoint** at `/v1/batch`
- Input must be JSONL format (one request per line)
- Returns results in the same order as input
- Best for local inference or self-hosted models
- Supports multiple models simultaneously

---

## 3. Design Architecture

### 3.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    GPT Mobile AI App                         │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────┐   │
│  │              AI Platform Settings Screen              │   │
│  │  ┌────────────────────────────────────────────────┐  │   │
│  │  │  Platform: [OpenRouter / Llama.cpp / Others]   │  │   │
│  │  │  API Key (existing)                            │  │   │
│  │  │  API Batch Key (NEW - for batch operations)    │  │   │
│  │  │  Batch Mode: [Radio: Standard / Batch]        │  │   │
│  │  └────────────────────────────────────────────────┘  │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                    Backend Service Layer                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────┐ │
│  │  Platform       │  │  Batch Manager  │  │  File       │ │
│  │  Adapter        │──▶│  Orchestrator   │──▶│  Handler   │ │
│  │                 │  │                 │  │             │ │
│  │ • OpenRouter    │  │ • Job creation  │  │ • Upload    │ │
│  │ • Llama.cpp     │  │ • Status polling│  │ • Download  │ │
│  │ • Others*       │  │ • Result merge  │  │             │ │
│  └─────────────────┘  └─────────────────┘  └─────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                    Platform API Clients                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ OpenRouter│  │ Llama.cpp│  │ Anthropic│  │ Others   │   │
│  │ Client   │  │ Client   │  │ Client   │  │ Clients  │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Data Model

#### Batch Configuration Schema
```typescript
interface BatchConfig {
  platform: 'openrouter' | 'llama' | 'anthropic' | 'google' | 'aws';
  apiKey: string;
  batchKey?: string; // NEW field for batch operations
  batchMode: boolean;
  
  // Platform-specific settings
  openRouter: {
    maxConcurrentRequests: number;
    retryDelayMs: number;
    timeoutMs: number;
  };
  
  llama: {
    serverUrl: string;
    batchSize: number;
    jsonlPath?: string;
  };
}
```

#### Supported Platforms Matrix
| Platform | Batch API | Async Support | Cost Savings | Setup Complexity |
|----------|-----------|---------------|--------------|------------------|
| OpenRouter | ⚠️ Parallel | ✅ Yes | N/A | Low |
| Llama.cpp | ✅ Native | ❌ Sync | N/A | Medium |
| Anthropic | ❌ None | ❌ No | N/A | N/A |
| Google | ⚠️ Limited | ✅ Yes | Variable | High |
| AWS | ⚠️ Limited | ✅ Yes | Variable | High |

---

## 4. Implementation Plan

### Phase 1: Core Infrastructure (Week 1-2)
- [ ] Add "API Batch Key" field to AI Platform Settings UI
- [ ] Create `BatchConfig` data model with platform-specific fields
- [ ] Implement platform detection logic
- [ ] Add batch mode toggle in settings

### Phase 2: OpenRouter Integration (Week 3-4)
- [ ] Implement OpenRouter parallel request handler
- [ ] Semaphore-based concurrency control
- [ ] Exponential backoff retry logic
- [ ] Progress tracking for large batches
- [ ] Error handling and partial failure recovery

### Phase 3: Llama.cpp Integration (Week 5-6)
- [ ] Implement Llama.cpp batch endpoint client
- [ ] JSONL file generation utility
- [ ] Direct HTTP POST to `/v1/batch` endpoint
- [ ] Result parsing and ordering preservation
- [ ] Fallback to chat completions if batch fails

### Phase 4: Extended Platforms (Week 7-8)
- [ ] Google Vertex AI adapter (GCS integration)
- [ ] AWS Bedrock adapter (S3 integration)
- [ ] Hugging Face sync batching fallback

### Phase 5: Testing & Polish (Week 9-10)
- [ ] Unit tests for all batch operations
- [ ] Integration tests with mock APIs
- [ ] Error handling and retry strategies
- [ ] Documentation updates

---

## 5. Security Considerations

1. **API Key Storage**: Batch keys stored in secure keychain/encrypted storage
2. **File Uploads**: Temporary files deleted after processing (especially for Llama.cpp JSONL)
3. **Rate Limit Headers**: Respect platform rate limits even in batch mode
4. **Error Isolation**: Failed requests in batch don't cancel entire job

---

## 6. Known Limitations & Risks

| Risk | Mitigation |
|------|------------|
| OpenRouter no native batch | Use parallel requests with concurrency control |
| Llama.cpp requires local server | Provide clear setup instructions for llama-server |
| Large file uploads | Implement chunked upload for >10MB files |
| Long processing times | Add progress notifications to users |
| Result ordering | Maintain input order mapping in results |

---

## 7. Platform-Specific Implementation Details

### OpenRouter Parallel Batching
```typescript
interface OpenRouterBatchRequest {
  model: string;
  messages: Array<{role: string, content: string}>;
  temperature?: number;
  max_tokens?: number;
}

async function processOpenRouterBatch(
  requests: OpenRouterBatchRequest[],
  apiKey: string,
  options: BatchOptions
): Promise<OpenRouterBatchResponse[]> {
  const results = [];
  
  // Process in parallel with concurrency limit
  const promises = requests.map((request, index) => 
    processSingleRequest(request, apiKey, index)
  );
  
  return await Promise.allSettled(promises).then(results => 
    results.map(r => r.status === 'fulfilled' ? r.value : null)
  );
}
```

### Llama.cpp Batch Processing
```typescript
interface LlamaBatchRequest {
  prompt: string;
  context?: string;
  n_tokens_to_predict: number;
}

async function processLlamaBatch(
  requests: LlamaBatchRequest[],
  serverUrl: string,
  apiKey?: string // Optional for local server
): Promise<LlamaBatchResponse[]> {
  const jsonlContent = requests.map(r => 
    JSON.stringify({
      prompt: r.prompt,
      context: r.context || '',
      n_tokens_to_predict: r.n_tokens_to_predict
    })
  ).join('\n');
  
  const response = await fetch(`${serverUrl}/v1/batch`, {
    method: 'POST',
    headers: apiKey ? { 'Authorization': `Bearer ${apiKey}` } : {},
    body: jsonlContent
  });
  
  const resultsText = await response.text();
  return resultsText.split('\n').filter(Boolean).map(line => 
    JSON.parse(line)
  );
}
```

---

## 8. Recommendations

1. **Prioritize Llama.cpp first**: It has a true native batch API with `/v1/batch` endpoint, making it the most straightforward implementation.
2. **OpenRouter parallelization**: Since OpenRouter lacks a native batch endpoint, implement client-side parallel processing with configurable concurrency limits.
3. **Add fallback mechanisms**: If batch mode fails, automatically fall back to standard API calls.
4. **User education**: Add tooltips explaining when to use batch vs. standard mode:
   - **Batch mode**: Large volumes (>100 requests), non-urgent processing, cost optimization (Llama.cpp)
   - **Standard mode**: Real-time responses, small batches, urgent processing

---

## 9. Next Steps

To proceed with implementation:
1. ✅ Confirm OpenRouter and Llama.cpp as priority platforms
2. Review the proposed data models and API interfaces
3. Decide on storage strategy for temporary batch files (especially for Llama.cpp JSONL)
4. Approve the phased rollout plan
5. Begin Phase 1: Core Infrastructure implementation

**Note:** For OpenRouter, since there's no native batch API, the "API Batch Key" field can be used to store additional configuration parameters (like concurrency limits, retry policies) rather than a separate authentication key.
