# Batch execution and accounting

Implementation reviewed on 2026-10-31. The older beta URL and claimed llama-server
JSONL batch endpoint in this document were stale.

## Native asynchronous OpenRouter jobs

`OpenRouterBatchClient` submits to the selected connection's `/api/v1/batches`,
reads status/results, lists jobs, and supports cancellation and deletion. Request
serialization keeps endpoint/model before requests; IDs must be unique and bounded.
Routing/data-retention restrictions remain in `OpenRouterPolicy.batch` and cannot
be bypassed by per-item provider overrides. No remote-storage permission is inferred
from enabling the local work queue.

OpenRouter's [batch announcement](https://openrouter.ai/blog/announcements/batch-api/)
documents a 24-hour processing window, inline results, supported models and pricing
that varies by model. A local parallel queue uses ordinary completion requests and
does **not** qualify for a native batch discount. Actual provider usage/cost receipts
remain authoritative; the app does not apply a blanket 50% reduction.

## Immediate queued requests

`executeBoundedBatch` uses a fixed number of workers, preserves input order, retains
individual failures, and cancels all children with the parent. At most 1,000 items
are admitted per batch and at most 16 run concurrently. The previous semaphore
inside a sequential loop never created concurrent work; both OpenRouter call paths
now use the shared worker implementation.

`OpenRouterBatchWorker` already stores connection identity/revision and an explicit
model instead of API credentials in WorkManager input. It revalidates the connection,
uses a scoped cache identity and retains provider receipts. Native batch and immediate
queue operations use bounded response bodies and cancellation-aware sockets; uncertain
submissions are not blindly retried.

## Local llama-server

The [upstream server API](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
provides `/completion`. Local jobs now use that endpoint with at most four concurrent
requests and explicit generation limits. Saved JSONL jobs are adapted to those calls;
no `/v1/batch` endpoint is assumed. JSON serialization preserves control characters.

Results must contain decoded text. A model's token IDs are vocabulary indices, not
Unicode code points; token-only rows report a failure requiring decoded server output.
Partial rows retain their original indices, missing rows remain failures, and excess
rows cannot extend the accepted request set. Calls remain on the selected local
server and do not fail over to a cloud service implicitly.

## Validation

Behavioral tests cover actual overlap and bounded concurrency, stable ordering,
individual errors, parent cancellation, token-ID rejection, payload encoding, scoped
routing and existing native-batch serialization/receipt contracts. Provider execution,
end-to-end latency and billed savings require the user's configured endpoints and
representative work; passing unit tests does not establish a monetary saving.
