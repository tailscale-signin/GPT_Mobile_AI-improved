# GitHub answer ownership and progress repair

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base: main at fe0ed5607897ad211d61b02b2fd4e318348659c5
Working branch: fix/github-answer-ownership-progress-2026-11-02
Intent: repair the attached build 133 interaction failures and reduce repetitive progress.

## Evidence and priorities

The bundle retains 653 events, eight runs, 51 tool events and 500 recent app log entries. No journal events were reported dropped, but provider wire capture is incomplete and recent app logs cover only a window. One run completed; seven were interrupted. These are observations, not a complete device benchmark.

- P0: Three runs ended with ANSWER_REVISION_OWNER_CHANGED. Answer validation attempted database draft replacement while the outer GitHub verifier was buffering that draft privately. Prior tool-round prose was also retained in the validator's published-draft digest. Keep GitHub validation private and give each buffered round its own publication state; preserve fenced replacement and cumulative visible-draft identity for ordinary chats.
- P1: Continue produced a report with no trusted receipts after an earlier partial report. Restore encrypted evidence for the original task identity, repository, branch, profile and authorization revision. Historical observations keep their original timestamps and epoch; they are never fresh CI results.
- P1: Placeholder all-zero SHAs reached GitHub and failed with HTTP 422. Reject invalid immutable identifiers before dispatch, with branch-head discovery guidance. Legitimate tools stay available.
- P1: Missing issue/PR numbers, invalid line ranges and invented paths failed. Keep validation, improve discovery guidance, and test valid requests alongside invalid ones. Deliberate negative test requests in the export are expected rejections, not evidence that tools are broken.
- P1: The stress prompt with "massive full" modifiers missed the bounded diagnostic matcher. Recognize modified stress/benchmark requests while preserving explicit full-history audits.
- P2: Heartbeats append duplicate summaries/previews and echo the question. Summaries should explain the purpose of observed activity and distinguish waiting from completed work. Deduplicate rendered previews across intervening summaries, without discarding fragmented streaming prose or receipts.
- P2: Android reports inactive input connections and ignored job flags. These framework warnings alone do not establish a reproducible UI or scheduling defect; physical-device reproduction remains necessary.

## Validation

Targeted regressions, host validation and the Android build attempt are recorded below. No device benchmark, stress throughput or all-green CI claim is implied.

## Full requested issue ledger

The separate 1,996-record diagnostic review was supplied as text; its original JSONL is not attached here. Items below distinguish code fixes from existing behavior and device verification.

| Item | Handling in this branch / existing evidence | Remaining verification |
| --- | --- | --- |
| 1 placeholder SHAs | Reject zero/malformed identifiers before dispatch; retain branch-head discovery guidance. | Negative and valid-read regressions. |
| 2 missing PR number | Existing action-specific preflight retained; schema now explicitly requires a discovered number. | Integration negative test. |
| 3 missing issue number | Explicit discovery guidance and positive ID schema. | Argument regression. |
| 4 opaque issue headers | Structured lookup cause, resource, failed step, next action and snapshot status; GraphQL query requests are now accounted as dispatched reads. | Header normalization regression. |
| 5 invented paths | Existing pinned-tree validation rejects without network dispatch and returns candidates; intentional negative test remains an expected rejection. | Existing path regressions retained. |
| 6 line ranges | Existing bounded validation retained; correction now provides consecutive 2,000-line chunk examples. Do not silently expand a request beyond its authorized result allowance. | Valid chunk and oversized-range tests. |
| 7 unchanged rejected retries | Restore settled rejection receipts instead of executing them; recovery also recognizes persisted INVALID_ARGUMENTS preflight failures. Corrected calls need new IDs. | Checkpoint regressions. |
| 8 completed pending batches | Recovery snapshot separates settled exchanges from unresolved calls. Replayed receipts remain sharedResult=true, preserving original dispatch evidence. | Checkpoint regressions. |
| 9 discovery reconciliation | Local catalogue discovery bypasses mutation reconciliation; unknown-outcome guards apply to non-replayable operations. | Recovery path inspection and CI. |
| 10 zero-tool next round | Discovery is no longer mistaken for an uncertain mutation. Existing runner budget/finalization notices state why tools are removed; it still cannot exceed a hard budget. | Runner regressions and device interaction. |
| 11 missing error codes | Preserve normalized GitHub validation/authentication/resource failure codes through AgentToolResult and dispatch accounting. | Tool trace integration in CI. |
| 12 MCP stream closure | Existing session invalidation, handshake and bounded safe-read retry retained; reconnect retries now have bounded exponential delay with jitter. Mutating tool calls are not replayed. | Live endpoints not available here. |
| 13 endpoint timeouts | Existing per-connection health, passive catalogue backoff, cached authorized metadata and explicit refresh retained; health logging now includes failureClass. | Ports 8101/8102 need the user's running services. |
| 14 DNS | Existing DNS classification, host-scoped circuit, network-epoch invalidation and configured search fallbacks retained. | mcp.jina.ai connectivity cannot be established from this capture. |
| 15 UI stalls | Reduce redundant progress state growth; remember Markdown typography; existing streaming publication batching and idle-only blur retained. | Physical-device frame measurements required; no invented speedup. |
| 16 text measurement | Stable typography identity avoids invalidating unchanged Markdown styles. Existing parser/highlight caches, per-content rendering identity and Compose text layout caching remain in use. Do not cache Placeables across measure passes: that violates Compose ownership. | Measure content/width/style changes on device. |
| 17 keyboard | Existing DisposableEffect clears focus on navigation; coroutine-scoped composer work cancels on disposal; no app-owned IME unregister callback exists to remove. | Android lifecycle warnings need device reproduction. |
| 18 usage | Existing invocation ledger includes private/rewrite passes; complete total is now unknown when any invocation is unmeasured, while measured subtotal is separately logged. | Provider final receipts remain authoritative. |
| 19 unverified settings | Existing requested/declared/observed distinctions and settingsConfirmed=false retained. No route is promoted to confirmed from configuration alone. | Actual provider confirmation may remain unavailable. |
| 20 duplicates | One event-ID set now covers provider history, runtime history and app log files; IDs with punctuation retain full identity. Duplicate records retain captureSource but do not inflate issue totals. | Structured export regression and Android export. |
| 21 truncated/missing evidence | Full error bodies remain in ToolEvent/retainedContent; shortened recorder previews stay marked truncatedAtSource, linked by causal incident/call ID. Vendor log unavailable and historical exits empty remain explicit, never proof of no crash. | Capture sources depend on device/platform. |
| Foreground hint | No app source calls setImportantWhileForeground; WorkManager manages scheduling. Existing classification labels the platform hint informational. | Verify supported worker lifecycle on device. |
| Feedback tensors | Existing optional_model_feature classification retained; inference is not failed by the optional signature warning. | Feature support depends on model. |
| Hardware property | No app property probe added; existing platform_optional_property classification tolerates denial. | Unknown chip names remain allowed. |
| Finalizer/runtime warnings | Retain the original warning and unverified causal attribution. No speculative native synchronization changes. | Recurrent incidents require device/native evidence. |
| Empty research workers | Examined: outbox emptiness alone is insufficient to skip enqueue because the worker also recovers pending discovery and waiting examination jobs. Existing unique KEEP work prevents concurrent duplicate execution; no unsafe empty-outbox shortcut added. | Measure startup/foreground scheduling before changing the recovery contract. |
| Rewrite overhead | GitHub rewrites now remain private and round-local; one rewrite occurs only after observable answer-contract violations. Existing structured evidence/projections bound replay. | No guaranteed latency reduction without device measurements. |

## Results

- Host validation: 287 tests passed; Android resources, literal regexes, icons, dependency/CI policy and QNN provenance checks passed.
- Isolated Kotlin: 31 regressions passed using the production progress, header, argument, evidence and answer-validation sources with lightweight platform interfaces. This does not compile the Android app.
- Android unit test attempt: blocked before compilation because the Gradle distribution download cannot reach services.gradle.org from this environment.
- Physical-device UI/MCP measurements: unavailable here. No benchmark or effective-provider-setting claim is made.

## Publication checkpoint

Automatic approval review rejected pushing this patch to the public repository, including after verifying repository identity and push permissions. Its stated reason is that public disclosure of source changes and log-derived documentation requires explicit user approval. The remote branch and draft PR have not been created, and PR CI has not run. The prepared patch and this ledger are reviewable before that approval.
