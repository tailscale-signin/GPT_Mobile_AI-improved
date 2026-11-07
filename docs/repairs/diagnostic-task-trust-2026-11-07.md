# Diagnostic task trust and recovery — November 7, 2026

## Accepted scope and source identity

This change implements the supplied F01–F23 diagnostic review on
`fix/diagnostic-remediation-8597579-2026-11-07` in
`tailscale-signin/GPT_Mobile_AI-improved`, preserving the existing PR 813 work.
The inspected base is `main` at `3ac89ceafd9083f67da30e92f9f93aae27be4600`.
Work began at `156ca471af822bc6a0619fb7395ab869d151a535`; the concurrent
`423b917196419605a6c789810d3d469c55d09ce8` SQL-fixture typing repair was inspected
and incorporated before publication. Its fixture correction matched the local
correction exactly. The published commit and current CI checks are authoritative
in [PR 813](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/813).

The companion task checkpoint records the accepted scope, validation boundaries,
and remaining external verification. This document describes source behavior;
it does not substitute host tests for Android CI or physical-device measurements.

## Completion and recovery model

Standard chat and dedicated GitHub chat establish the same native repository
acceptance boundary. An operational GitHub request requires one authorized
repository/connection; a missing or ambiguous target fails before repository
dispatch. A task contract retains the original user-message identity, repository
and ref, requested actions, acceptance criteria, required tools, original call
limit, explicit deadline, receipt-store identity, and cleanup policy.

Short follow-ups resolve to that accepted task. A bare integration retest inherits
the accepted restrictions. Expanded requests with changed scope, budgets, or
selectors establish a new authoritative task; they cannot be replaced by an
earlier write-capable request. Full-request comparison preserves case-sensitive
branch and path identities. A session regression confirms that a new read-only
request cannot schedule the earlier task's fixture branch. Follow-ups retain the spent call budget
and deadline, including retry aliases and primary-only controls. Temporary chats
still enforce the current run's accepted contract without creating persistent
workspace records. Historical time windows in a prompt are not interpreted as
execution deadlines.

Invocation completion, readable answer delivery, and verified task completion
remain independent. A plan without operations cannot complete an operational
request. It receives at most one tool-enabled corrective attempt; text alone from
that attempt cannot bypass the selected review pipeline or become evidence.
Unfinished work produces a receipt-derived partial or blocked report.

Execution recovery keeps tools and checks uncertain mutations before replay.
Failure-explanation requests use historical diagnostics. Requests to retry writing
or show the report deliver a binding-, receipt-set-, and content-hash-checked saved
answer without rerunning the task. The existing structured continuation and
paragraph-boundary behavior remains in place.

Pure repository continuations replace accumulated plans with the complete accepted
task, newest instruction, and a bounded receipt projection with exact observed
selectors and counts. Full receipts remain in the protected store. Research and
Combined flows retain their own delivery protocols. Corrective requests use the
same bounded projection. Omitted older evidence is explicitly identified; its
absence is not permission to invent selectors or repeat writes.

## Finding disposition

| Finding | Implemented or preserved behavior | Regression evidence |
| --- | --- | --- |
| F01 | Native task acceptance, target setup blockers, durable contract, and Standard/dedicated budget parity | `GitHubTaskAcceptanceScopeTest`, `GitHubTaskTrustTest`, task recovery tests |
| F02 | App-owned receipt verification separates invocation, answer, and task outcomes; proposed work cannot satisfy acceptance | Plan-without-calls and corrective-prose regressions; existing acceptance and execution-ledger tests |
| F03 | Execution resumes with authorized tools; writing and diagnostics are separate; uncertain effects retain reconciliation fences | Tool-preserving correction, writing-only saved-answer tests, existing output-continuation and mutation-recovery tests |
| F04 | Initial discovery omits invented versions; subsequent pages use exact cursors and opaque versions; one explicit restart after stale pagination | Catalog readiness and authorized-catalog regression cases; authority invalidation tests |
| F05 | Discovery totals, successful calls, page coverage, GitHub denominator, and invocation/verification coverage come from eligible receipts | Fabricated `87/87` and nine-page report regression; integration acceptance tests |
| F06 | Newest instruction is retained; a stalled plan receives one corrective execution attempt, then an actual incomplete report | `GitHubTaskTrustTest` corrective-context, no-progress, diagnostics, and saved-report cases |
| F07 | Explicit qualified aliases establish required readiness; pending, timeout, acquisition failure, absence, ambiguity, and authorization are typed separately | Delayed catalog, qualified alias, disabled/missing/ambiguous tool regressions |
| F08 | Persisted root request drives readiness and tool resolution across short controls and process-restored history | `TaskRecoveryActionTest`, task-continuity and alias regressions |
| F09 | Terminal recovery checks nested saved-job identity, preserves partial text and original failures, and provides a readable result without fabricating a job ID | Gateway result-recovery tests; transport integration cases |
| F10 | Existing fixture ownership, explicit branch binding, expected heads, verification, cleanup, and uncertain-write fences remain enforced | `GitHubOwnedFixturesTest`, `GitHubContentFixtureTest`, existing branch and mutation acceptance tests |
| F11 | Existing action-specific schema alternatives require observed issue identifiers and freshness guards; one corrected proposal has a new call ID | Actual operation-facade missing-issue/repeated-invalid/corrected-new-call regression |
| F12 | GraphQL application errors remain failures despite HTTP 200; safe type/path/resource/operation evidence is retained, with explicit truncation | `GitHubHeaderQueryTest` |
| F13 | Real policy changes retain field-level reasons and completed/uncertain receipts; local pagination staleness does not become authority loss | Policy-invalidation, failure-metadata, and durable failure-classification tests |
| F14 | Definition-page timestamp maxima are parsed and computed; page coverage is explicit; definitions cannot prove branch head or build status | `GitHubWorkflowEvidenceTest` |
| F15 | Typed readiness/failure stages survive serialization; original and resolved arguments are sanitized, bounded, and separately recorded | `GitHubArgumentProvenanceTest`, `ToolFailureMetadataTest`, `ProfileFailureRecordTest` |
| F16 | Definite unsupported `/props` and `/health` results remain cached for the relevant route/credential/network/instance/revision identity | 24-hour negative-cache, revision-change, and explicit-invalidation tests |
| F17 | Upstream events, actual ASGI handoff/send completion, Android parsing, and run-scoped Compose draw are separately observed | Delayed event/backpressure tests, malformed-optional-telemetry tests, draw-lineage tests |
| F18 | Measured frame-phase breakdown, refresh-rate/build identity, and balanced export traces support focused device investigation | Frame phase accounting and balanced-stage trace tests; device comparison remains unmeasured |
| F19 | Timeline reuses the captured journal; source/chunk hashes and counts are verified; serialization and hashing are bounded; resume refreshes partial derived stages | Frozen-source adapter regression, corrupt-source rollback, cancellation/resume, coverage, and 64 MiB heap gate |
| F20 | Immutable profile revision, configured/requested/serialized reasoning, backend-reported model, and endpoint digest remain distinct from unmeasured runtime facts | Reasoning receipt, provenance DTO, parser, transport, and invocation-ledger tests |
| F21 | Resumed and corrective repository prompts use the accepted task and bounded execution ledger instead of accumulated plans | Exact selector, constraint, latest-intent, bounded-count, and private-body exclusion tests |
| F22 | Composer input ownership is released before send, at externally started runs, and on disposal without clearing the draft or stealing another editor's focus | Three added Compose lifecycle regressions; Android CI required |
| F23 | Routine omissions become compact changes; bounded inventory snapshots are exported; actual authority failures retain detailed warnings | Snapshot/diff/clear/privacy tests and staged-export adapter tests |

## CI integration follow-up

The concurrent `b34c7dc01368584a480a20a83d15f0f573a11f15` update is preserved,
including persisted check-watch identity, selected-revision staging, late tool
admission, and the one-time output allowance for a newly admitted planner. Its
test repairs use the same full verification snapshot contract as production;
compact audit events are not substituted for saved verdicts. The subsequent
`87709e6f5ff91befb4ede0f30fcbd1d48999afe7` update is also preserved: owned
content fixtures may start from a selected feature branch, while exact source
leases, same-task fixture ownership and guarded cleanup remain mandatory. The
legacy fallback stays restricted to the default branch.

The follow-up retains the existing schema and prompt-token budgets while restoring
explicit integer bounds. Source ranges reject unknown selectors, wrong JSON types,
nulls, and oversized offsets before backend dispatch. Path-only requests retain
their existing bounded default read. This prevents an unrepresentable offset from
silently becoming zero.

Branch regressions inspect the actual operation envelope, distinguish selected
reading context from an explicit review target, require a complete proposal and
exact lease, and prove that rejected requests cause no mutations. Missing observed
SHAs remain absent. A default-branch check may read repository metadata; the write
still fails before mutation. User-message fixtures explicitly supply the required
nullable `platformType`, matching the real Room entity constructor.

Known-profile OpenAI Responses, Anthropic, Gemini, and OpenAI-compatible requests
carry immutable profile provenance into the shared serialization boundary. The
invocation ledger records bounded per-attempt settings from the final admitted
payload, including a transport failure that never emits a usage event. Retries
preserve earlier settings, and overflow has an explicit omission count. Only
allowlisted reasoning controls are retained; prompt bodies, credentials, and
unacknowledged effective settings are excluded.

## Measurement boundaries

The gateway reports `completion_buffered` because generated content still passes
complete-result processing and tool validation before delivery. Progress events
and actual ASGI forwarding are independently observable. A completed awaited ASGI
send is not proof of a physical socket or reverse-proxy flush; that fact stays
unmeasured.

Compose draw completion is measured for the rendered run stream with run,
ownership-fence, part, process, and monotonic-time identity. It is not a physical
display-presentation receipt. The renderer cannot currently bind every verified,
combined, or saved answer to one provider invocation. Consequently per-HTTP
`first_ui_paint_ms` remains null unless that lineage is explicitly available;
the diagnostics UI no longer pairs unrelated first-provider and first-draw events.

Backend-reported model names and a route digest do not establish loaded weight
identity, runtime implementation, physical upstream machine, accelerator use, or
effective reasoning controls. Those remain `NOT_MEASURED` without authoritative
acknowledgments. No fallback model or relaxed authority policy is introduced.

Frame and export instrumentation does not establish a device speedup. Matched
debug/release traces at the same refresh rate, a running APK with authorized live
GitHub fixtures, and compatible-hardware qualification remain separate acceptance
work. No OOM, ANR, memory leak, or graphics-driver cause is inferred from the supplied
archive.

## Validation record

Focused JVM harnesses compile current production implementations with explicit
Android/service boundaries. They do not establish a full Android build. The
companion checkpoint records final run counts and canonical validation results.

- Task/catalog/evidence/session suite: 249 passing tests, compiling 90 current
  production files and 29 repository test/helper files, with 27 explicit host
  boundary files (including two extracted session checkpoint methods) and no
  source drift. Regressions include actual `GitHubVerifiedSession`,
  `GitHubEvidenceBook`, task continuity, budget parsing, readiness, operation facade,
  provenance, and acceptance code.
- Focused schema/admission suite: 225 passing tests (overlapping the task/evidence
  suite). Ordinary schemas measure 2,990 UTF-8 bytes / 997 estimated tokens under
  the unchanged 1,000-token limit; the admitted prompt is 1,436 tokens under 1,500.
- Actual HTTP/workspace branch suite: 23 passing tests, including exact leases,
  explicit review targets, missing observed SHAs, and default-branch rejection.
- Complete gateway gate: 49 Python suites, 560 passing cases and one declared
  network skip; all seven browser bridge tests pass. The 50,000-event / 782
  FULL-sync-commit durable stress test passes with unchanged watchdogs.
- Gateway/provider focused Kotlin suite: 30 passing tests; admitted-request
  provenance and retry retention: nine passing tests. Five receipt cases overlap
  between those two batches. The full ledger and provider-adapter integration
  regressions remain part of Android CI.
- Gateway focused Python suites: 73 passing tests, comprising 16 message-contract,
  25 completion-recovery, 29 actual gateway-route, and 3 ASGI-observation cases.
- Export/timeline core: 54 passing cases and one assumption-skipped heap case in
  the ordinary run. The separate real 64 MiB heap gate passes capture, resume, and
  hashes for 20,000 records / 163,840,000 source bytes.
- Journal/coverage/draw: 28 passing cases; actual staged-export adapter: 17 passing
  cases; bounded omission/frame helpers: 12 passing cases. These suites overlap
  and must not be summed into a unique-test count.
- Canonical `bash scripts/validate-local.sh fast`: 398 script tests and every
  fast gate pass, including 1,274 literal Android regex checks and 30 XML files.
- Full Android compilation, Room/Compose/provider integration, DEX-size checks,
  and aggregate required verification belong to CI on the published commit.

Generated architecture and app-audit source inventories, the complete gateway
package manifest, and the exact allowed release paths are refreshed. Dependency
checksums, configured build-memory limits, release identity, and the warning boundary of 16,383
DEX code units remain unchanged.
