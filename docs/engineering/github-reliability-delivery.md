# GitHub reliability and coding context

## Contract and recovery follow-up

Search accepts up to 100 context lines on each side and up to 100 new blobs per
request. The existing four-download concurrency limit, twelve-second page
deadline, and continuation remain in force. Excerpts share a 48 KiB page budget
and disclose truncated ranges; a match can be reopened at its pinned revision.
Fuzzy symbol confidence is the observed identifier similarity, not a semantic
probability. The default threshold remains 0.8; 0.7 can be requested explicitly.

Tool batches retain the first saved receipt, reject conflicting call identities,
and prevent a new batch or finalization from discarding queued calls. In-flight
writes still return an unknown outcome until reconciled; no retry is inferred.
The runner rejects a tool result associated with the wrong call ID and preserves
the uncertainty that the requested operation may have executed. Schema and GitHub action validation remain the input contracts. The shared local
invocation contract declares major/minor compatibility, validates replay identity
pairing, and normalizes direct, delegated and provider-owned receipts. A new major
requires an adapter before dispatch. Structured error categories preserve unknown
outcomes and never imply that a mutation is retryable.
Checkpoint schema version 1 reads older unversioned data and rejects unknown
versions. Recovery context reports counts and age only for its latest 32 retained
execution records. Context load duration is not an end-to-end recovery time;
an RTO has not been configured or established.

Batch restoration now takes one mutex-protected snapshot of pending calls and
dispatch accounting, so a concurrent checkpoint update cannot mix different
versions of the batch. Known pre-dispatch rejections do not use an executable
call slot; a claim without a saved receipt still counts as potentially sent.
The runner records the measured checkpoint snapshot time and can accept an
optional `CheckpointRestoreObjective` through `AgentRunLimits`. Its MET or
EXCEEDED status applies only to checkpoint loading, not to provider availability,
remote reconciliation or completion of the user's task. No default objective
or device-level recovery guarantee is asserted.

Write-schema feedback identifies missing file paths before showing the full
schema and instructs the model to retrieve paths and SHAs rather than invent
them. Mismatched receipts and interrupted writes remain unknown outcomes, and
the same write is blocked until reconciliation. The diagnostics log view groups
records off the main thread and composes a bounded visible list; device frame
measurements are still needed to establish the effect on the observed stalls.

Tool discovery serializes concurrent cold acquisitions and caches successful
queries for the run. Inventory continuations carry a version derived from the
authorized names and schemas; a changed inventory must be restarted. The schema
fingerprint records change, not compatibility or a publisher version. Inventory
can display the last dispatched outcome observed during this run; that observation
is not an active health probe and does not assert present availability. Inventory
can expose declared dependency edges and identify names outside the authorized
catalog; an empty declaration is reported as unknown. GitHub tools also expose
action-specific, optional evidence links for reading source, staging and
committing, PR review and merge checks, CI job logs, and check watches. These
links identify whether the referenced tool is authorized and never grant access
or assert that another operation is mandatory. Inventory versions change when
these relationships change.

GenieX CI verifies its candidate index against actual paths, sizes, SHA-256,
runtime, version and source revision and requires debug and minified APKs. This
does not replace the existing APK/native audits, prove a signature, or establish
physical-device behavior. Rollback remains a separately reviewed forward change.

Implementation scope: PR #765, based on
`020b351bc3b98bd30cbdbb3443a332e8f5bd88f9`. The recommendations mix existing
capabilities, missing behavior, sample API calls, and aspirational measurements.
This matrix records their concrete implementation and limits. Tool inventory is
not an end-to-end operational test, and an API list count is not a count of
workflow definitions in source.

API 36 acceptance on `6d185b26` exposed a problem in launcher isolation:
disabling the only real home left Settings.FallbackHome repeatedly waiting for
a replacement. The captured System UI ANR obstructed visual fixtures, and four
early instrumentation processes failed startup amid high emulator CPU pressure.
The setup now selects and starts a minimal Android-framework-only HOME activity
from the test APK before display changes, then stops the observed stock launcher
without disabling it. The activity and manifest are confined to `androidTest`;
the shipped application contains neither. Missing fixtures, selection/launch
failures and uncertain setup commands still fail closed, with app ANR reporting
and visual foreground assertions intact. Host behavior tests cover this contract;
the updated API 34 and API 36 runs are needed for device evidence.

| Requested capability | Implementation and evidence boundary |
|---|---|
| Deterministic invocation and replay | `GitHubExecutionJournal` atomically claims canonical, account/connection/chat/task-scoped identities. Lifecycle records retain execution/trace IDs, checkpoint sequence, output hash, and bounded receipts. Concurrent calls join the saved outcome without publishing twice. A lost response or cancellation retains reconciliation state. |
| Persistent state and transactions | The existing encrypted `GitHubRepositoryStateStore`, Room engineering ledger, Git Data saga, and recovery worker preserve checkpoints and reconcile remote outcomes. New HTTP identities include the reviewed invocation preconditions. CI writes include observed run attempt; separately requested dispatches can use a new `execution_id` after reconciliation. Retention is bounded; the journal is not an unlimited audit archive or a multi-process lock service. |
| Versioned stages and rollback | Identical stages preserve their original receipt. New content produces immutable version IDs and parent links. `restore_stage_id` selects an earlier local version only at the same repository, branch, and base SHA. Remote publication still needs the exact reviewed head and patch approval. Uncertain claims are not released by restart. Remote rollback uses a new reviewed forward change, never a force reset. |
| Retry, circuit admission, and diagnostics | Existing HTTP read retries and `gradle_retry.py` retry only classified transient failures. The credential-local request circuit bounds transient transport cascades and admits one recovery probe after cooldown. TLS/auth/validation/test failures are not blindly retried. Local structured logs omit source arguments and credentials; remote failure diagnostics retain their existing bounded expansion handles. |
| CodeQL build stability | Direct verified ObjectBox implementation resolution, transient dependency retry, dependency caching, bounded analysis resources, and real command/profile metrics. Clean instrumented compilation remains uncached and forced so tracing is complete. The current repository has one CodeQL language; no artificial language matrix is added. A 40% speed gain has not been established. |
| Workflow consolidation and sequencing | `apply-ui-pipeline.yml` supplies configurable requested/final/production qualification and legacy/GenieX/hybrid coverage after host preflight. `android_pipeline.py` is shared by debug and unsigned-release wrappers. Existing workflow IDs and debug failure reporting remain compatible. Signed publication keeps its privileged identity boundary. The named three old UI files were already absent. |
| PR/background state | Existing WorkManager `github_watch_checks` watches persist exact repository, credential, PR/run, and head associations. No PR number or historical run ID is hardcoded as application configuration. Watch registration is a user operation; listing the tool does not prove a background delivery occurred. |
| GenieX packaging and artifacts | Cold compilation, tests, shrinker, and fresh-process native packaging retain their memory, timeout, native, provenance, and telemetry checks. Every stage now writes correlated measurements. Indexed artifacts carry actual SHA-256, runtime, version, source SHA, run and attempt; diagnostics retain 30 days. Runtime selection supports legacy, GenieX, or both in the UI pipeline. |
| UI regression and rollout | Actual native-rendered PNGs are compared to reviewed manifests by digest, dimensions, changed pixels, and channel deltas. Requested candidates can be collected before baseline review; final/production qualification fails closed on an absent baseline. No device rollout or production publication is inferred from an unsigned candidate. See `docs/ui-baselines/README.md`. |
| Testing and benchmarks | API 34 phone and API 36 tablet emulator coverage complements the existing font/theme/native Compose fixtures. Host smoke tests validate workflow policy, command admission, checkpoint interruption, PNG parsing/comparison, forward versioning, artifact digests, and SLO statistics. Real `build_metrics.py` records wall time/cache/RSS/I/O; percentage comparisons require the same environment/command identity. Physical device validation remains hardware-dependent. |
| Release automation | `prepare-release.yml` and `prepare_release.py` generate a forward versionCode/name patch, commit-derived changelog/notes, and optional forward-revert proposal at an exact head. `--apply` modifies local review files only. Existing signed release generation already creates immutable tags/releases and uses notes or GitHub-generated notes after its gates. Preparation never publishes or rewrites a tag. Release proposal artifacts retain 90 days. |
| Observability and alerts | Versioned `.github/ci-objectives.json` defines availability and latency objectives. Collectors emit service-level dashboards, p95 successful wall duration, retry signals, recent/older-half trends, and sampled queued/running capacity. Small samples are explicit. Existing signed opt-in failure notifications remain owner-configured; no webhook or recipient is activated by this PR. |
| Multi-file dependency context | Ranges are validated at one immutable tree and distinct blobs load with concurrency two. Original input order and shared excerpt budgets remain enforced. A bounded import graph, ambiguity/coverage markers, related tests, repository guidance, and follow-up ranges accompany the bundle. This is lexical resolution, not a compiler dependency graph. |
| Commit and branch awareness | Pinned commit pagination exposes parent edges, unloaded parents, and explicitly heuristic message intent hints. Existing branch context, dependencies, compare and PR health expose multiple heads, conflicts and unknown policy. A page is not described as the entire commit graph. |
| Output cache and cross-session guidance | Existing immutable blob, index, evidence, and search-page caches are keyed by credential/repository/revision and exact query options. Canonical output hashes support replay inspection. Fuzzy results do not substitute for exact source evidence. Persisted code/reference/import indexes supply examples across sessions; they do not train or fine-tune a model. |
| Pre-commit feedback and generated tests | Native and engineering stages report added-text hygiene, credentials and merge conflicts with file/line codes. Related test/symbol context and behavior/boundary/recovery guidance support AI-generated tests. Compiler/lint/unit/CodeQL explicitly remain `NOT_RUN` until executed; no generated test is considered passing by construction. |
| Semantic, pattern and reference discovery | Existing bounded fuzzy identifier search, definitions/references/import indexes and related source/test hints are retained. New `patterns` uses ordered literal segments separated by `*`, bounded to one line, without arbitrary regex evaluation. Search context supports up to 100 nearby lines within the result byte budget. These are explainable lexical/fuzzy tools, not embedding-based semantic or compiler analysis. |
| Inline editing and code review | Existing inline exact-snippet editing restages a new immutable patch and requires reapproval. A shared selectable diff now highlights headers, hunks, added and removed lines, retaining text prefixes for accessibility. Frozen approval snapshots remain immutable. Contextual help and repository test guidance accompany staging/planning. |
| AI suggestions and refactoring | The existing model receives pinned multi-file, history, index, related-test and local validation evidence through the operation planner. Suggestions and refactoring recommendations remain model output that must be staged and tested. A separate learned pattern engine, automatic AST refactorer, or measured accuracy increase is not claimed. |

## Operation examples

Use admitted schema fields, not example run IDs as workflow IDs, opaque cursors as
commit SHAs, or internal tools as GitHub Actions `uses` entries:

```json
{"action":"ranges","commit_sha":"<observed-full-SHA>","ranges":[{"path":"<observed-path>","start_line":1,"end_line":200},{"path":"<related-test>","start_line":1,"end_line":160}]}
{"mode":"patterns","query":"fun *(value:","context_lines":10,"file_extension":"kt"}
{"restore_stage_id":"<saved-stage>","expected_head_sha":"<original-base-SHA>"}
```

CLI preparation and inspection:

```bash
python3 scripts/android_pipeline.py --runtime geniex --variant nativeSmoke --phase package --plan
python3 scripts/artifact_manifest.py --output path/to/artifact-index.json --query geniex
python3 scripts/prepare_release.py --version 0.9.65.0 --expected-head <observed-full-SHA>
```

No 25–40% coding-accuracy gain, <5% flakiness, or release/device success is inferred
from these features. Such claims require controlled measurements on qualified
revisions and hardware.

Release publication follows the same execution contract as CI dispatch: reuse
`execution_id` for recovery; use a new ID only for a separately requested operation
after reconciliation. A replay preserves the original dispatch time, correlation,
and exact run receipt. Read-only release verification can recover a retained
credential/connection/conversation-scoped association in a later turn; it still
checks the requested repository, tag, workflow, event and source SHA. A matching
branch or recently observed run never substitutes for a missing dispatch receipt.

Publication fences are stored independently of evictable receipts. Cache pressure,
receipt cleanup or process restart cannot turn an earlier execution into a new
write: a missing receipt with a retained fence requires remote reconciliation.
The device retains at most 16,384 hashed fence identities; reaching that capacity
stops admission of new write claims while existing receipts and source reads
remain available. Fences contain no source or credential payload and are removed
only by explicit app-data erasure, rather than ordinary cache pruning. This is
single-process serialized state, not a distributed transaction coordinator.

The completion review adds mutex-consistent recovery disposition counts and
AtomicFile backup restoration, as well as version-bound health counters and
stale-observation markers. Rollback proposals now validate ancestry and require
an explicit mainline for merge commits; qualification remains a new forward
release. Neither a configured checkpoint objective nor an observed health counter
establishes an end-to-end recovery guarantee.
