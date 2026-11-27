# Intelligence and GitHub execution redesign — diagnostic follow-up (2026-11-27)

## Scope and evidence

This follow-up is based on the retained diagnostics for app 0.9.66.19 (build 153, app commit `c942b1c8361e5f9a5dd1816ec44b58487f8cbfbc`). The archive is a bounded retained snapshot, not a complete provider wire capture. The observed failures include:

- `GITHUB_TRANSIENT_NETWORK`: three unanswered GitHub reads, DNS transport classification, 29,999 ms total wait, zero HTTP responses.
- `GITHUB_CIRCUIT_OPEN`: later reads were rejected while the circuit remained open; the observed retry-after was about 23.8 seconds.
- `GITHUB_RUN_INTERRUPTED`: the retained stack points to `SavedPageReading.validate` called from `AgentRunner.collectRound`.
- `TOOL_UNAVAILABLE`: web search readiness explicitly reported `PROFILE_TOOLS_DISABLED`; this is a profile capability/configuration decision, not evidence of a provider failure.
- `GITHUB_TASK_INCOMPLETE`: a 1,200-second wall-clock task window expired after only 3 of 64 calls, with 61 calls remaining, four unmet requirements, no extracted draft and zero verified claims.
- `VOICE_PLAYBACK_FAILED`: Android audio focus was denied after three attempts; caption alignment was `NO_TIMING`.

Do not interpret a retained terminal status of `COMPLETED` as proof that the accepted engineering task was completed. Completion, execution, remote acceptance and criterion verification are separate facts.

## Immediate implementation in this PR

The saved-page failure is consistent with unrelated tool results evicting delivery digests that are still referenced by saved private-reading notes. The runner used a single bounded digest map for both ordinary tool output and source receipts pinned by saved-page notes. Once unrelated results displaced a pinned digest, the next validation could reject a valid saved-page append.

The fix adds `SavedPageReading.retainDeliveryDigests` and uses it after each result batch. It retains every digest referenced by the current saved-page reading and uses the remaining bounded capacity for the newest unrelated delivery digests. A focused regression test proves that a pinned saved-page digest survives unrelated result eviction.

The source and regression test are committed to the existing draft PR branch. The exact-commit hosted checks are the qualification gate; no merge is authorized while they are pending or failing.

## Target architecture: evidence-first adaptive engineering agent

### 1. One durable task authority

Create one immutable task envelope at admission:

- selected account, GitHub installation/connection, owner/repository and accepted branch/ref;
- accepted base/head SHA and repository-map revision;
- selected model/profile and privacy route;
- user request, explicit constraints, approval policy and acceptance criteria;
- execution budget, checkpoint identity and ownership fence.

Every preparation, model call, tool call, continuation, verification and final answer must bind to this envelope. A branch advance creates a new observed revision and triggers reconciliation; it must not silently rewrite the accepted task identity.

### 2. Repository intelligence as a reusable graph

Build one canonical repository map per repository and source revision, not one fresh map per chat:

- modules, packages, build targets, public APIs and entry points;
- imports/call graph, ownership boundaries and dependency edges;
- tests mapped to production symbols and features;
- CI workflows, required checks, release packaging and deployment paths;
- generated sources, external services, feature flags and configuration;
- recent commits, active PRs, unresolved review threads and issue links.

Persist the map with content hashes and provenance. Reuse it across GitHub chats and profiles only when authorization permits. Refresh incrementally from changed paths and commit ancestry; invalidate affected graph nodes on source changes. Never count a map entry as a source read, a compiler result or a test receipt.

### 3. Adaptive task planning and model routing

Classify the request into lookup, explanation, incident diagnosis, repository audit, implementation, migration, test repair, review or release. Produce a dependency graph with:

- explicit user requirements and testable acceptance criteria;
- affected modules, callers, configuration and security boundaries;
- evidence gaps and high-risk assumptions;
- parallel-safe versus serialized work;
- required approvals and validation commands;
- a primary execution plan and a recovery plan.

Select tools and model capability from the task, not from a fixed prompt template. Use cheap/fast models for deterministic extraction, routing and summaries; reserve the strongest configured reasoning model for architecture, ambiguous root-cause analysis and final synthesis. Use an independent verifier for high-risk diffs and acceptance. Preserve the selected profile and privacy route; never silently fall back to another provider or a less-private route.

### 4. Capability discovery and readiness

Discover the authorized tool catalog once per session, cache schemas by provider/connection/permission revision, and refresh on explicit invalidation or authorization changes. Keep the complete inventory separate from the per-round advertised subset.

Readiness failures must be typed and actionable. In particular, `PROFILE_TOOLS_DISABLED` must say which capability is disabled and where the user can enable it. It must not be misreported as a network outage, and the agent must not override explicit user tool exclusions.

### 5. Resilient transport and circuit control

Use endpoint-scoped transport state and typed failure classification:

- DNS resolution failures, transient connection resets and timeouts are retryable reads with bounded exponential backoff and jitter.
- Authentication, permission, invalid endpoint, TLS trust and schema errors are not transient retries.
- A circuit breaker opens only after independent failures of the same endpoint/operation class; it must not count local policy rejections, cached backoff waits, cancellation or a shared DNS-recovery lease as new failures.
- Half-open recovery permits one probe; concurrent callers share its result.
- A read whose response was not observed remains an uncertain read and can be retried only when its operation is idempotent.
- A write with an unknown remote outcome is reconciled by the original operation identity before any retry. Never replay a potentially accepted write with a new ID.

Expose retry deadline, failure class, request count, response count and reconciliation requirement in receipts without leaking credentials or private arguments.

### 6. Durable long-running execution

Replace one monolithic wall-clock timeout with a durable task supervisor:

- persist checkpoints after every completed operation and every verified criterion;
- separate active execution time from provider cooldown, queue wait, approval wait and user-offline time;
- support explicit long-running budgets (including 3–12 hour engineering tasks) without silently extending an accepted deadline;
- pause on rate limits and resume from the exact saved task, profile, repository and model identity;
- allow process/app restarts and device reconnects without losing ownership or replaying uncertain writes;
- preserve a bounded foreground slice for immediate progress while background work continues;
- reserve capacity for final verification and final answer synthesis.

A deadline reached with unused call budget should report the exact blocking clock and wait accounting, checkpoint status, remaining criteria and a direct continuation action. It must not label a partial response as completed.

### 7. Acceptance ledger and publication gate

Extract requirements before execution and persist stable criterion IDs. Each criterion progresses through planned, in-progress, blocked, verified or not-applicable states. A verified state requires a source-owned receipt tied to the exact task and source revision. Examples:

- code change: changed-file diff + compile/test receipt + review evidence;
- issue remediation: linked change or explicit action + observed resulting issue state;
- CI: exact commit SHA + required workflow/check conclusions;
- release: exact artifact digest + release publication receipt.

The final answer is generated from the ledger, not from model confidence or prose. Missing, stale, unbound or ambiguous evidence remains unknown. A completed tool call never proves the whole task.

### 8. Context and synthesis

Maintain separate bounded stores for:
- immutable accepted request and requirements;
- repository map and source excerpts;
- current-turn tool receipts;
- compact cross-turn engineering memory;
- private saved-page notes and their pinned source digests;
- final acceptance ledger.

Allocate context using the actual residual provider budget after system instructions, accepted request, output reserve and replay reserve. Summaries retain source IDs, revisions, line ranges, confidence and omissions. Do not let optional memory or stale summaries displace the current task contract.

### 9. Voice interaction

Keep voice playback lifecycle independent from text generation and GitHub task lifecycle. On audio-focus denial, distinguish transient focus contention from permanent route failure, avoid rapid repeated focus requests, and retain generated speech chunks for safe retry. Caption alignment must use actual synthesis/PCM word timing; never fabricate timestamps. Surface text immediately while playback recovers.

### 10. Observability and quality gates

For every run, capture a causally linked timeline of task, model invocation, tool call, transport attempt, receipt, checkpoint, acceptance criterion and publication. Preserve raw failure class and recovery observations; distinguish unavailable evidence from negative evidence.

Required regression gates include:
- pinned saved-page receipts across replay compaction;
- repeated DNS failures, half-open probe and recovery without circuit amplification;
- uncertain read versus uncertain write behavior;
- disabled-profile readiness messaging without permission bypass;
- task deadline with unused call budget and paused provider waits;
- process restart, branch advance and exact checkpoint ownership;
- no false completion with unmet criteria or absent draft;
- exact-SHA CI and artifact publication checks;
- audio-focus denial and caption timing fallback.

## Non-goals and safety boundaries

- Do not auto-enable tools disabled by the user.
- Do not bypass GitHub installation, repository, branch, approval or privacy restrictions.
- Do not resubmit uncertain writes.
- Do not claim production model quality, live Copilot success, physical-device performance or release publication without corresponding receipts.
- Do not make paid model requests as part of synthetic validation.
