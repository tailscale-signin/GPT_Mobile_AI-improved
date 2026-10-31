# GitHub reliability and coding context

Implementation scope: PR #765, based on
`020b351bc3b98bd30cbdbb3443a332e8f5bd88f9`. The recommendations mix existing
capabilities, missing behavior, sample API calls, and aspirational measurements.
This matrix records their concrete implementation and limits. Tool inventory is
not an end-to-end operational test, and an API list count is not a count of
workflow definitions in source.

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
| Semantic, pattern and reference discovery | Existing bounded fuzzy identifier search, definitions/references/import indexes and related source/test hints are retained. New `patterns` uses ordered literal segments separated by `*`, bounded to one line, without arbitrary regex evaluation. Search context supports up to 20 nearby lines within the result byte budget. These are explainable lexical/fuzzy tools, not embedding-based semantic or compiler analysis. |
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
