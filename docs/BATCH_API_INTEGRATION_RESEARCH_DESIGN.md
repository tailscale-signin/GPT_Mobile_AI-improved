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
| **Native Batch API** | ✅ Yes - `/api/beta/batches` endpoint |
| **Alternative Approach** | ✅ Parallel requests via `/v1/chat/completions` (fallback) |
| **Endpoint** | `POST https://openrouter.ai/api/beta/batches` |
| **HTTP Methods** | `POST` (submit), `GET /{id}` (status/results), `GET` (list), `POST /{id}/cancel` |
| **Payload Schema** | Stream-parsed JSON: `endpoint` → `model` → `requests` (strict order required!) |
| **Completion Window**| `24h` only |
| **Rate & Data Limits** | Text only (images/audio/video rejected). Results inlined upon completion. 30-day retention |
| **Cost Model** | Discounted batch pricing for supported models (e.g. `:batch` variant) |

**Key Findings:**
- OpenRouter provides a dedicated asynchronous **Native Batch API** under `/api/beta/batches` (previously mistyped as `/api/v1/batch` causing 404s).
- **Strict field order requirement:** The OpenRouter server stream-parses batch submissions. Top-level JSON properties **must** be ordered:
  1. `endpoint` (e.g., `"/v1/chat/completions"`, `"/v1/responses"`, `"/v1/messages"`, `"/v1/embeddings"`)
  2. `model` (e.g., `"openai/gpt-4o"`, applied to all batch requests)
  3. `requests` (array of `{ "custom_id": "...", "body": { ... } }`)
  Placing `requests` before `endpoint` or `model` results in a `400 Bad Request`.
- **Inlined Results:** Completed batches return results inlined in the `GET /api/beta/batches/:id` response under the `results` array—there is no separate file download endpoint.
- For interactive or immediate execution where the 24h window is not desired, the client-side semaphore parallelization fallback is retained.

**Native Batch Request Format:**
```json
{
  "endpoint": "/v1/chat/completions",
  "model": "openai/gpt-4o",
  "requests": [
    {
      "custom_id": "req-0001",
      "body": {
        "messages": [{ "role": "user", "content": "Summarize OpenRouter" }]
      }
    }
  ]
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

---

## 3. Production Implementation Details

### OpenRouter Native Batch Operations (`OpenRouterBatchClient.kt`)

```kotlin
// Endpoint derivation
fun batchesUrl(): String = "https://openrouter.ai/api/beta/batches"

// Submit batch (POST /api/beta/batches) with strict field ordering
suspend fun submit(endpoint: String, model: String, requests: List<NativeBatchRequest>): BatchMeta

// Check status / retrieve inlined results (GET /api/beta/batches/:id)
suspend fun status(batchId: String): BatchStatusResponse

// List workspace batches (GET /api/beta/batches?limit=100)
suspend fun list(limit: Int = 100): List<BatchMeta>

// Cancel batch (POST /api/beta/batches/:id/cancel)
suspend fun cancel(batchId: String)
```
