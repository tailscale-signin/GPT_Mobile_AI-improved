# Diagnostic reliability audit — October 11, 2026

Branch: `fix/diagnostic-audit-reliability-2026-10-11`

Base: `63bfbebde4c1ce1b74890a91c20517ae2dd4cc19`.

This branch addresses the diagnostic audit without merging to main, changing the release version, or publishing a release. No raw diagnostic export, health record, credential, private endpoint address, or user conversation is included. The audit identifies both defects and hypotheses; an observed warning is not automatically a confirmed application bug.

## Implementation map

| Audit | Response in this branch | Verification boundary |
|---|---|---|
| 1. UI stalls | Batch streamed text without rebuilding the complete response for every delta; adaptive presentation intervals; bounded delegate previews; asynchronous Markdown, transformed-text and memory-highlight preparation; weighted caches; independent syntax-highlighter builders. | Physical-device frame timing and text-layout cost still require profiling. No claim that every frame is under budget. |
| 2. Extremely large output | Cumulative/delta usage accounting is separated; configurable time, character and sustained-exact-repetition safeguards stop runaway generation while retaining partial output. | Reported provider usage is not assumed to equal visible text. |
| 3. Large conversation inputs | Add component-level context cost diagnostics and official provider catalog ceilings; retain existing history budgeting and evidence compaction. | Inspect real multi-profile histories for application-specific duplication. |
| 4. Intermittent health reads | Use one captured request time for the 30-day boundary; classify permissions, invalid queries, timeout, IPC, I/O and unavailable-service errors without recording raw health payloads. | OS permissions, service state and watch synchronization require device testing. |
| 5. Enrichment waits | Preserve the existing durable waiting queue and model-availability wake-up behavior; distinguish pending work from actual completed outcomes. | A successfully scheduled worker does not imply enriched facts. |
| 6. Missing local model | Preserve installed-file/registry preflight and explicit model-unavailable errors. | The branch cannot download an absent model or repair an external file on the user's phone remotely. |
| 7. Refused local endpoint | Classify refusal separately from timeout, DNS and authentication; apply endpoint-scoped cooldowns. | Starting or correcting a private gateway remains an operator action. No silent gateway bypass. |
| 8. Oversized methods | Extract agent result/failure processing and score aggregation/normalization from large execution methods. | The large chat orchestration method and ART instruction counts still need optimized-build profiling; compiler-limit elimination is not certified. |
| 9. Large Binder payload | Stop using the entire copied answer as the clipboard label; use a narrow FileProvider URI for very large text. Preserve the complete text rather than truncating it. | Other system parcels and saved-state paths need a device trace if warnings persist. |
| 10. Output-cap rejection | Combine explicit user limits, endpoint/model/routing-scoped learned limits and official default-route catalog hints. | Catalog metadata is a hint, never a claim of server-confirmed settings. |
| 11. Overlapping health reads | Add bounded run-local reuse of exact-query snapshots, including summary-to-category reuse; recheck foreground state and permissions on cache hits. Retain paginated reads and cumulative aggregation. | Reused data retains original freshness; the cache never pretends a new watch sync happened. |
| 12. Large tool catalogs | Advertise a task-ranked initial catalog with progressive discovery of other authorized tools. | Discovery never installs tools, grants permissions or resurrects blocked tools. Internal gateway-managed tools retain their own behavior. |
| 13. Health privacy boundaries | Keep AI sharing opt-in and add independent device-local consent for known external-memory write tools used by health-enabled profiles. Enforce at dispatch, not merely in UI. | This is a conservative known-tool-name gate, not a universal detector of every possible custom tool's side effects or provider retention. |
| 14. Replay evidence | Deduplication keys include tool identity, arguments, error state and payload; equal values from different tools remain distinct. Correct small-result budget accounting. | Original task/evidence preservation remains covered by existing recovery tests and targeted new tests. |
| 15. Repeated execution | Preserve explicit recovery actions, run/call correlation and read-only sharing boundaries; do not globally suppress legitimate user regeneration. | Exactly-once remote writes cannot be guaranteed for arbitrary MCP servers. Server-supported idempotency must be integration-specific. |
| 16. MCP connection failures | Keep per-endpoint network/retry protections and add actionable local-service error categories. | DNS, VPN and server availability are external conditions. |
| 17. Stream retry | Preserve the existing pre-payload retry boundary: a retry must not replay already exposed assistant/tool activity. | Recovery cannot guarantee server-side cancellation on every third-party provider. |
| 18. Telemetry | Preserve provider/generation IDs; distinguish visible/reasoning usage, finish reasons, estimate sources and unverified effective settings. | Segment completion is not a quality or whole-task success certificate. |
| 19. Enrichment deduplication | Use content/version-aware durable completion markers and shared observation; clear markers on conversation deletion. | Updated message content remains eligible for new enrichment. |
| 20. Embedding lifecycle | Record creation/release reasons, live-handle state and process-scoped native allocation labels. | Process native bytes are not misreported as engine-specific bytes; no unproven memory-leak claim. |
| 21. Score calculation | Compute cohort anchors once, avoid duplicate snapshot computation and keep calculations off the main dispatcher. | Validate with large benchmark histories and device traces. |
| 22. Empty community feed | Retain a valid last-known snapshot when a refresh unexpectedly returns empty and expose an empty-state reason. | An empty feed is not labeled an inaccessible repository. |
| 23. FTS compatibility | Retain working FTS4 fallback and classify index-ready recovery correctly. | Do not disable search because FTS5 is unavailable. |
| 24. MCP GET 405 | Retain valid POST transport recovery. | A rejected optional GET alone is not an endpoint failure. |
| 25. Input lifecycle | Retain stable text-field state and existing focus cleanup on disposal; reduce the rendering pressure that coincided with warnings. | Typing, keyboard dismissal and navigation during streaming require on-device acceptance; an OEM warning alone does not justify hidden-API workarounds. |
| 26. Optional embedding warnings | Separate optional feedback/JNI attachment messages from fatal generation failures. | Embedding CPU execution does not establish a GPU/NPU fallback for the chat model. |
| 27. ASUS finalizer | Classify the hidden-setting exception as platform-origin noise rather than a proven application crash. | Do not request privileged permissions or access hidden OEM settings. |
| 28. Diagnostic quality | Export schema 3 separates severity, category, actionability, recovery and duplicate eligibility; parse retry attempt/delay as distinct values. Keep evidence records without counting every INFO line as an issue. | Classification is not a complete causal incident graph; raw stack lines remain available for investigation. |
| 29. Validation | Add regression tests and branch-only resource, Kotlin style, unit-test, lint and debug-build checks. | Debug CI does not substitute for an optimized physical-device performance run. |

## User-facing controls and limits

Generation safeguards are in the existing token/context settings panel. Defaults are a 30-minute additional time ceiling, 1,048,576 generated characters, sustained-exact-repetition protection, and adaptive tool discovery. Time and character safeguards can be disabled independently; disabling them does not disable the provider's or runner's existing limits. Full responses and tool evidence are retained; the 64 KiB limit applies only to the live delegate preview, not the handoff.

Adaptive discovery starts with 24 task-ranked schemas plus its discovery tool and adds up to 12 authorized definitions per discovery call. It can be switched off. Context planning remains conservative about the full authorized catalog rather than underestimating its maximum cost.

Health read reuse lasts at most 10 seconds, uses exact range/timezone/source/limit keys, and is bounded to eight snapshots and approximately 512 KiB. It is memory-only and not shared through the global read-only broker. Permission changes, background restrictions and read failures invalidate reuse.

Smartwatch Health > Access exposes external-memory consent separately from AI access. Both choices live in no-backup storage. Revoking AI access revokes external-memory consent; re-enabling AI access does not automatically restore the latter. Because reliable content-level sensitivity inference is impossible for arbitrary tools, known external-memory writes are conservatively gated for the entire health-enabled profile, including non-health messages. Previously transmitted data is not remotely deleted by revocation.

Large clipboard copies use a text-file URI above 32,768 characters, with a short label and the sensitive-clipboard flag. Recipient apps must support pasted text files. The toast explains this limitation and directs users to Export when necessary. Clipboard files are cache-only, bounded and expired; no new backup inclusion is introduced. Code blocks above 128 KiB remain complete but use plain rendering instead of expensive syntax tokenization.

## Required device acceptance

Run an optimized build on the target phone and record frame percentiles/worst frame, memory, time to first token and cancellation latency. Exercise simultaneous profiles, a large saved conversation, streaming code/math, theme changes, large Copy/Export, typing and navigation under load, and background/resume. Do not certify a fixed frame-time target from a debug CI build.

For Health Connect, test fresh and historical ranges across a daylight-saving transition, all requested metrics, empty and paginated results, foreground/background transitions, permission revocation between reads, AI-consent revocation, external-storage rejection/acceptance and consent persistence across restart. Check full-data backup remains encrypted and a restore does not restore health-sharing consent.

For local execution, test an absent model, stale registry entry, actual readable model, stopped gateway, restored gateway, DNS/VPN change and a stream disconnected before and after visible output. Confirm tools with side effects are not automatically replayed. These checks require the actual phone, installed models and private services.

## Automated validation

The branch workflow runs `git diff --check`, resource validation, ktlint 1.3.1 on changed Kotlin files, `:app:testDebugUnitTest`, `:app:assembleDebug` and `:app:lintDebug`. Inspect the workflow artifacts for the exact tested commit, test XML, lint report and debug APK. Intermediate red runs are preserved as evidence rather than represented as successful checks.

The initial suite exposed a stale test that selected sensitive backup sections but expected an unencrypted export. The test now uses conversations-only for the unencrypted path and separately asserts sensitive exports never fall back to unencrypted storage. Production encryption policy is unchanged.
