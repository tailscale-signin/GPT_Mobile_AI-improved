# Research Mode reliability — implementation specification
Date: 2026-10-25
Target: fix/combined-diagnostics-empty-writer-budget-jank-2026-10-25
Source: export_Research Chat_1792982531295.zip (app 0.9.60.0 build 128 commit d1c415e4)

## Scope and non-regression requirements
Implement fixes below without changing existing Combined Mode semantics. Reuse shared provider health/cooldown machinery where appropriate, but keep Research stage state independent. Do not treat this specification as proof the code is implemented.

## P0 — Provider execution and output handling
1. Introduce a typed provider outcome: SUCCESS_VISIBLE, REASONING_ONLY, INVALID_PAYLOAD, INVALID_REQUEST, UPSTREAM_FAILED, RATE_LIMITED, ALLOWANCE_EXHAUSTED, INTERRUPTED.
2. BridgeNode gpt-oss-20b: 512 tokens were consumed with zero visible output. Detect reasoning-only completions; negotiate supported output/reasoning budgets, then fall back to another compatible model/provider if still empty. Never retry an identical failed configuration indefinitely.
3. Vireonix: classify hosted generation errors; bounded retry only for transient failures; move work to a healthy provider.
4. OVHcloud: honor allowance exhaustion and retry-after; preserve queued stage state until eligible.
5. BlockRun: handle reasoning budget exhaustion and unavailability separately.
6. CehPoint: validate and normalize provider payloads; capture sanitized payload-shape diagnostics.
7. Pollinations: build provider-specific valid requests and test free-mode operation.
8. Use provider/model capability metadata for max context, visible output limits, reasoning control, request schema, cooldowns and free quotas. Never assume HTTP 200 means usable generation.

## P0 — Durable research stage orchestration
1. Persist immutable researchRunId, stageId, providerAttemptId, sourceId and generationId.
2. Stage state machine: PENDING, RUNNING, WAITING_PROVIDER_LIMIT, RETRYABLE_FAILED, TERMINAL_FAILED, COMPLETED, SKIPPED_WITH_REASON.
3. Checkpoint accepted search results, source retrieval, extraction, evidence, synthesis and verification before launching downstream stages.
4. Resume only incomplete stages after process death, provider failure, or cooldown; deduplicate retries and prevent concurrent stage completion races.
5. Provider failure must not cancel unrelated profiles; replace provider per stage where allowed.
6. Bound retries by error classification and capability changes; ensure cancellation propagates without deleting checkpoints.
7. Keep run completion distinct from final report delivery completion.

## P0 — Evidence and extraction
1. Maintain evidence ledger: discovered -> fetched -> readable -> extracted -> validated -> accepted; retain provenance, source URL, citation locator, extraction timestamp, content hash and reason for exclusion.
2. Never discard accepted evidence when an extraction or writer fails.
3. Structured extraction schema validation and one repair attempt for malformed output; otherwise fallback to another eligible provider.
4. FIND_GAPS must operate on accepted evidence and identify missing subtopics without duplicating previous search results.
5. Citation verification: every cited claim must resolve to accepted evidence; unsupported claims are flagged, not silently laundered into sourced assertions.

## P0 — Report delivery
1. Assemble report from all accepted evidence, not only the last successful provider response.
2. Validate minimum report usefulness, citations, completeness, and delivery payload before declaring success.
3. If some stages fail, produce an explicitly partial report with accepted citations when useful evidence exists. Do not claim 'no readable relevant source' solely because final synthesis failed.
4. Publish the final report atomically; recover from interruption without duplicate user-facing reports.
5. Record report generation status separately from UI publication/acknowledgement.

## P1 — UI and diagnostics
1. Show stage progress, accepted sources, active/limited providers, and meaningful retry states. Keep technical logs behind Debug mode.
2. Per-profile stats must distinguish logical tasks, physical requests, successful visible outputs, failed attempts, retries, rate limits and accepted contributions.
3. Avoid heavy journal parsing/rendering on main thread; throttle progress updates and render only changed rows.
4. No false 100% until verified report is published; avoid spinner stuck after failed/canceled runs.

## Required regression matrix
- HTTP 200 with reasoning tokens but empty visible answer
- reasoning budget exhaustion, with/without supported reasoning controls
- malformed provider payload, invalid request, upstream 5xx, 429 with Retry-After
- allowance exhaustion and cooldown across app restart
- partial source retrieval, extraction schema failure, repeated FIND_GAPS
- crash/restart after source acceptance and before final publication
- simultaneous profile failure and successful independent provider
- valid partial report with citations; zero evidence fallback
- exact-once publication, cancellation, no duplicate stages
- no regressions in Combined Mode, provider selection, or UI performance

## Acceptance gates
- Run Android compile, unit/integration tests, lint and applicable emulator CI.
- Test Research end-to-end with mocked failures and real provider smoke tests where credentials are available.
- Inspect final report for actual citations and non-empty evidence.
- Check CI and review changed files before merging.
