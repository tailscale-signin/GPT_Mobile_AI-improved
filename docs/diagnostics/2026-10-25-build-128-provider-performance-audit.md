# Diagnostics audit — 2026-10-25 export (build 128)

## Scope and evidence

Audited `app-diagnostics-8644855436823461182.jsonl` (4,551 lines; one malformed/truncated JSONL record; 4,550 parseable records). The export identifies app version 0.9.60.0, build 128, commit `16889faaf5ace43793ddfc7706cc8ce300d822fe`.

The log is dominated by repeated informational telemetry (4,303 rows). The actionable set is 240 records: 119 frame-performance warnings, 76 general warnings, 32 errors, 10 main-thread disk-I/O warnings, one reported synthesis-quality failure, one input-lifecycle warning, and one compiler-method-size warning. These are event counts, not necessarily unique root causes.

## Highest-priority findings

### 1. Combined synthesis can return an empty final answer
A synthesis writer (Kilo) repeatedly ended with `finishReason=length`, roughly 722 reported reasoning tokens, zero visible bytes, and an unverified/empty writer digest. A later `SYNTHESIS_QUALITY` record reported `words=0`, `displayedCharacters=0`, and the empty SHA-256 digest. The app must not treat reasoning-only output as a deliverable or silently finalize an empty answer.

**Required behavior**
- Detect empty visible content before final publication, even when the provider reports a nominally completed HTTP stream.
- Retry once with a provider whose reasoning controls are verified, or with an explicit bounded reasoning/output configuration; do not repeatedly retry the same unsupported configuration.
- Preserve all collected contributor responses and attribution when changing writer.
- Keep the response in a recoverable failed/partial state if no valid writer is available; never report synthesis success with zero visible words.
- Record attempt lineage and distinguish transport completion from user-visible completion.

### 2. Rate-limit and transient-provider failure cascade
The export includes repeated `PROVIDER_RATE_LIMITED` events for BridgeNode, Cehpoint and LLM7, `HOSTED_GENERATION_FAILED` from Vireonix/Hugging Face, and HTTP 502 errors. Several errors report retry deadlines, but repeated attempts continue across the session.

**Required behavior**
- Treat provider cooldown as shared per provider/model/account scope, not only per individual run.
- Honor Retry-After and explicit provider deadlines; add bounded exponential backoff with jitter only when the server gives no deadline.
- Do not automatically re-dispatch a request that was already sent unless the request is idempotent or the provider explicitly confirms it was not accepted.
- During cooldown, skip unavailable providers and continue with other eligible providers; avoid synchronized retry storms.
- Expose the cooldown reason and next-eligible time in the UI, and allow a user-triggered retry.
- Ensure a transient 502/429 does not erase a previously collected response or overwrite a successful contributor slot.

### 3. Provider request preparation failures
Repeated `REQUEST_INVALID` failures from Kilo and Cehpoint occur at preparation, where the log says the request was not sent. These should be separated from remote failures and should not enter a network retry loop.

**Required behavior**
- Validate provider-specific request constraints before dispatch.
- On a deterministic invalid-request response, repair only the incompatible field(s) once or mark that provider ineligible for this request.
- Log a redacted reason and the normalized constraint decision; never log credentials or full prompts.
- Preserve the original prompt and settings for a safe retry with a different provider.

### 4. Main-thread disk I/O
Ten StrictMode events report main-thread disk reads/writes (typically 1–6 ms). Individual events are small, but they can compound with streaming and animation work.

**Required behavior**
- Move diagnostic persistence, chat/history reads and other nonessential disk operations off the main thread.
- Batch or debounce diagnostic writes and use bounded queues with explicit overflow handling.
- Add a regression test that runs the relevant chat actions with StrictMode disk detection enabled.

### 5. Frame-time instability during chat/streaming
119 frame timing windows include jank and occasional long frames: examples include a 220.7 ms maximum and 3 stalls over 100 ms; other windows show p95 around 20–30 ms and bursts of 50 janky frames. Glass blur is active in many affected windows, while the main thread also reports disk I/O. This does not prove blur alone is the cause.

**Required behavior**
- Profile chat rendering with streaming and multiple concurrent runs before changing visual effects globally.
- Coalesce streaming UI updates to display cadence; avoid rebuilding the whole conversation for every token.
- Keep expensive blur/effect work off hot recomposition paths and reduce or suspend blur while scrolling or under sustained jank.
- Measure frame-time p95/p99, stalls over 100 ms, and dropped samples before/after; don't rely on average frame time alone.

### 6. Input lifecycle and compiler method-size warnings
One input-lifecycle warning and one compiler-method-size warning appear in the actionable set. The export excerpt does not establish their exact source paths or user impact; identify their owning methods before making behavioral changes.

## Regression acceptance criteria

1. Empty/reasoning-only writer output never becomes the final answer; a bounded fallback writer can succeed without losing contributors or attribution.
2. A 429 with Retry-After causes no request before the deadline, and multiple runs share the same cooldown.
3. A 502 follows bounded retry rules; a sent non-idempotent request is not blindly duplicated.
4. A deterministic preflight/request-invalid failure does not enter a repeated network retry loop.
5. Combined output remains recoverable when all writers fail, with truthful failed/partial status and no empty success.
6. Chat rendering remains responsive under simultaneous streams; capture comparable p95/p99 and >100 ms stall metrics.
7. StrictMode regression tests detect no disk access on the main thread in the targeted paths.
8. Re-run the supplied diagnostics scenario on-device and export a new JSONL to verify issue counts decrease.

## Limitations

The export is telemetry, not a source-level stack trace. It supports the symptoms and priorities above but does not independently prove every code-level root cause. Verify each proposed fix against the relevant Kotlin implementation and automated tests before merging.
