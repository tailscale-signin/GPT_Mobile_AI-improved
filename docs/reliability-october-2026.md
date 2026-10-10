# October 10 reliability repairs

Branch: `fix/long-delegation-combined-synthesis-2026-10-10`

This patch addresses the reported coordination failures in build 109. It is **not a verified release**: Android compilation, Kotlin tests, device benchmarks and live provider checks remain gated on CI/device access. The original diagnostic log is evidence of the reported failures, not a benchmark for this patch.

## Implemented behavior

| Area | Change |
| --- | --- |
| Long answers | The final writer owns the word goal. Delegates receive a bounded evidence task. Text-only continuation can run for several bounded requests, retains completed tool evidence, checks context capacity, strips literal overlap, stops on no progress, and preserves partial output. Per-request caps remain respected. Continued conversation answers account for prior response words. |
| Combined mode | The primary model synthesizes a structured envelope containing every contributor's response. Instructions require a shared outline, chronological ordering for history, grouped themes, semantic deduplication, and preservation of distinct supported contributions. Original contributions remain available. Failed or canceled synthesis is terminal until retried or inputs change. |
| Foreground service | Inject expensive dependencies lazily. Promote in `onCreate` before acquiring dependencies or observing runs. Guard idle shutdown with the latest start ID, recheck active runs, and record startup/promotion/stop timestamps. Completion notification work has a bound. |
| Recovery controls | Resolve exact control phrases to typed actions and reconstruct the objective from persisted user turns. Include prior completed and interrupted work from the recovery chain. Primary-only removes delegation and preparation/review requirements while retaining permitted primary tools. An orphan control produces a clear missing-task error without searching. |
| Generation outcomes | Explicit output-limit errors set output-cap/truncation flags even without usage counters. Reasoning-only, empty and partial content are distinguished. Typed failures carry observed/estimated usage into delegate waste accounting. Estimated counts do not establish a server cap violation. |
| Retry policy | Remove the mandatory five-retry floor. Output limits and authentication failures stop unchanged attempts; repeated failure fingerprints, empty results and timeouts stop early or use an eligible fallback. A successful request resets transient failure history. Cancellation propagates. |
| Reasoning configuration | Isolated llama.cpp requests put reasoning controls in the request body, in addition to gateway headers. Diagnostics identify the observed server and client-requested settings; server effectiveness remains explicitly unverified until tested. |
| Independent review | Distinguish passed, rejected, unavailable and timed-out review. Missing reviewers and transport/schema failures retain evidence as unverified; they do not create a rejection score or claim review passed. |
| Evidence | Standard research now checkpoints source passages independently of summaries. Recovery can read those passages after summary failure, deadline expiry or process restart. Temporary-conversation evidence stays in memory. Readable-page counters no longer imply accepted evidence. An empty relevance selection prevents crawling unrelated leads. |
| Budgets | Preserve a shared preparation deadline, reserve review time and stop collection before the remaining allowance is exhausted. Keep existing tool-call and byte limits. Correct elapsed/remaining deadline telemetry. Saved passages provide a bounded fallback. |
| Provider errors | Preserve bounded, sanitized provider error messages, codes, provider identity, generation IDs and nested upstream messages. Do not dump arbitrary request echoes or successful response bodies. Keep HTTP error and in-stream paths separate. Certificate/hostname failures are not retried as transient I/O. |
| MCP/authentication | Add explicit authentication-required health and suppress unchanged credential retries. Credential changes or explicit connection refresh allow a new attempt. Keep existing protocol negotiation, session reinitialization, POST support and bounded read reconnects. Do not replay side-effecting tool calls after an uncertain transport result. OAuth diagnostics record sanitized error codes; existing serialized refresh and reconnect behavior remain. |
| Page reading | Resolve relative redirects with an HTTP URL parser; retain destination/DNS safety checks. Record DNS separately, bound network/page time, close response channels, cache unavailable URLs, respect host rate-limit cooldowns, and mark truncated excerpts. Route supported bounded PDF/Office downloads to the existing document extractor off the main thread. |
| Memory readiness | Missing local models leave WorkManager work pending. Persist only stable source references/digests, resume waiting items when a local runtime loads, and retain source-change/deletion/privacy checks. No cloud fallback. Show queued, running, waiting and recent successful enrichment counts. Invalidate runtime exclusion caches when installation finishes. |
| Memory search | Try FTS5, then FTS4, then the portable LIKE fallback. Reuse restored indexes and identify the actual index engine. No Room schema-version change. |
| Diagnostics/performance | Add native heap telemetry separate from Java heap/PSS, distinguish embedding owner from actual engine identity, provide JSONL exports with correlation/settings/usage fields, and separate provider completions from run failures and interruptions. Expected model cancellation is informational. Add service-startup race and keyboard-during-streaming benchmark fixtures. |

## Existing protections retained

Perplexity credential quarantine, serialized OAuth refresh, MCP negotiation fallback, stale-session/read recovery, bounded source memory, source URL deduplication, research workspace history, adaptive research modes, tool-history replay compaction, retail product routing, long-chat/streaming benchmarks, and baseline-profile generation already existed. They are preserved rather than replaced wholesale. Existing Amazon fixtures still assert product-tool routing.

## Validation in this workspace

- Passed: 51 repository Python tests.
- Passed: Android resource preflight, exported Room schema checks, regex preflight, and `git diff --check`.
- Passed in host SQLite: FTS4/FTS5 table creation, engine-specific prefix matching, scope isolation and deletion. This exposed and corrected an FTS4 quoting mismatch; Android vendor builds still require device verification.
- Added/updated Kotlin cases: long-response continuation, Combined contribution retention, recovery chains, reviewer availability, typed output-limit accounting, retry cutoff/reset, saved source passages, deadline headroom, relevance selection, request-body reasoning controls, sanitized provider errors, MCP credential recovery, redirects/DNS/rate limits/truncation, FTS4 and restored-index handling, TLS trust failures, and JSONL records.
- **Not run:** Kotlin unit tests, ktlint, APK build, instrumentation and Macrobenchmark. `./gradlew :app:testDebugUnitTest --no-daemon` cannot download Gradle 9.8.0 because network access to its distribution is unavailable. The workspace also has Java 17 rather than the required Java 21.
- **Not verified:** a live 3,000-word French-history generation or primary synthesis against the user's configured models. Prompt contracts and simulated continuation tests cannot prove semantic deduplication or historical accuracy.

## Required CI and device acceptance

1. Run ktlint and the full Kotlin unit suite with Java 21/Android 37, then build debug and release variants. Run existing native-memory/JNI/resource gates.
2. Request approximately 3,000 words of French history with 512-, 1,024- and 2,048-token request caps. Check total length, no empty reasoning-only success, stable outline, no repeated paragraphs, and no repeated completed tool actions. Include a small-context model: it must preserve partial output and explain a context limit.
3. Supply Combined contributors with overlapping eras, disjoint facts and conflicting claims. Check chronological order, thematic grouping, preservation of unique relevant contributions, explicit uncertainty and retained original tabs. Repeat after canceling synthesis.
4. Exercise Continue, Retry full and Proceed primary-only after output limits, failed review, process restart and completed partial answers. Verify queries contain the original objective; primary-only must not choose delegates/reviewers. Verify unknown outcomes before retrying external actions.
5. Run `foregroundServiceStartupRace` and real generation start/cancel/restart/background/reopen loops. Inspect elapsed timestamps and Android crash logs. The fixture covers service startup races; a real active model run is still required.
6. Inject HTTP 400/401/403/429, reasoning-only output, reviewer absence/timeout, expired MCP sessions, EOF, malformed redirects and summary failures. Check bounded requests, credential-specific recovery, cancellation, preserved source passages, independent review status and measured/estimated usage.
7. Queue enrichment without a local model, restart the app, load a model and check eventual completion. Delete/change a source while queued and confirm it is not enriched. Restore/rebuild memory on devices with and without FTS5.
8. Run release Macrobenchmarks for startup, long-chat scrolling, streaming and keyboard transitions. Profile map lifecycle and product images on real devices/accounts. Collect native allocations and Java/PSS separately before claiming a memory or jank improvement.

## Limits and follow-up work

- Invalid external credentials and OAuth grants still require reconnection. HTTP 403 restrictions, DNS/server outages and certificate failures cannot be repaired by changing app retries.
- OEM finalizer errors, Scudo messages, inference-feedback warnings and SDK-internal map cancellations are not relabeled as proven app crashes or leaks. Native/OEM diagnosis and image/map correctness require device traces.
- A dedicated model-compatibility inspector with an executable probe, a complete evidence-quality dashboard and fully automated map/product-card performance fixtures are **not complete in this patch**. Requested/observed configuration, review/content states, source history and backlog data are exposed through existing diagnostics and the added JSONL export. A server accepting a request does not prove its reasoning policy took effect.
- Primary synthesis is model-generated: the prompt enforces an editorial contract, but unique contributions and chronology still require the live acceptance cases above. Context, total-run tokens and the continuation ceiling can legitimately stop an exceptionally large response.
- This work is proposed for review; it is not a release. CI results and remaining validation are tracked in the pull request.
