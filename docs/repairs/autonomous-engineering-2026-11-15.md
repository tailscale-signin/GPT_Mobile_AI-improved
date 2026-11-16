# Autonomous engineering implementation

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base branch: main; observed base: e9610259b7238043de7aaad937a865c65976ff5b
Working branch: fix/post-1203-diagnostics-2026-11-15 (PR #1299)
Starting head: 56d7c91c27a12dcab107df09ed9eff178becebbc

Intent: integrate SHA-pinned repository exploration, adaptive isolated validation,
shared execution-budget receipts, structured actions, specialist reviews, patch
iteration, explicit existing-PR adoption, generated instruction metadata,
checkpoint inspection and production-task qualification into the existing runner.
Retain a single write authority and exact-source validation/merge gates.

## Implemented connections

| Area | Implementation |
| --- | --- |
| Exploration | `WorkspaceSearchService`: literal/POSIX regex search over the pinned Git mirror, file globs, stable query-bound cursors, exact-symbol-first graph retrieval, bounded ranked pages. Binary files are excluded. Search never grants source-edit authority. |
| Adaptive validation | `discover_tests` parses tests without importing repository code. `run_test`, `compile_module`, `lint_changed_files`, `check_patch` select matching commands from the unchanged admitted host policy. New unapproved tests are reported as `HOST_POLICY_REQUIRED`. |
| Context | Versioned `gateway.execution-budget.v1` receipts bind measured/estimated input, output, stage reserves, schemas, route binding and capability revision. Physical dispatch produces a child allocation after transport normalization. Android displays this authority rather than deriving another Gateway allocation. |
| Verification | Requirement verdicts are separately persisted in the append-only ledger. Model views page acceptance requirements four at a time; deterministic completion still evaluates every accepted requirement. |
| Structured actions | Protocol v1 binds complete actions to the source SHA. Native JSON schema is selected only when provider metadata advertises support. Existing SSE transport checkpoints bounded partial JSON privately; partial patches never execute. Complete-response/legacy JSON fallback remains available. |
| Specialists | Six configurable investigation roles cover source, tests, CI, security, regressions and patches. Full-mode planning, repair and review invoke them. References include source/blob identity. Local bindings serialize model work; one supervisor owns writes. |
| Patch iteration | Exact line hunks address repeated anchors; stale anchors/hunks return diagnostic feedback to the same bounded coding loop. Candidate comparison, patch previews and retained test logs support selecting an earlier tested candidate. |
| Existing PR | Explicit `adopt_pr` includes number, head and base SHA. Admission and PLAN verify the actual same-repository PR; durable continuation retains owner/actor identity and rechecks current head. Desktop task composition exposes the selection. |
| Instructions | `sync_agent_metadata.py` generates SDK/schema facts from Gradle and ChatDatabaseV2, checks the current schema export, and runs in PR policy validation and the fast host command. |
| Checkpoints | Android and Windows show candidate history, patches and test output. Explicit restoration is revision-fenced, permits only paused unpublished local work, and re-enters REVIEW with prior approval/validation cleared. Any recorded remote mutation blocks restoration. |
| Production qualification | Ten-scenario manifest runner submits fixed authorized jobs and reads scenario-specific durable evidence. The desktop Benchmarks panel charts production results separately from fixture scores and exports the report. |

## Workspace actions

Every workspace action supplies the exact current `head_sha`. `repo_search`
accepts `query`, optional `mode` (`literal`/`regex`), `glob`, and the returned
`next_cursor` as `cursor`. A page scans at most 64 regular paths and returns up to
40 matches. Follow empty pages with a continuation cursor. Git runs with a fixed
argument vector, disabled text conversion/external grep, a 20-second deadline
and a 4 MiB output cap; broad queries can require narrowing. `read` remains the
only path for exposing source ranges for edits.

`symbols`/`relationships` retrieve the existing revision-bound graph; `explore`
uses graph context when present and otherwise searches text. Graph results are
source-level evidence, not runtime proof. `discover_tests` accepts a path query
and offset. A discovered case is runnable only through a listed approved index.
Exploratory execution never replaces complete final validation.

## Production qualification usage

Use an installed Gateway, a selected model with an admitted execution binding,
and a designated test repository. Create a JSON manifest with:

- `schema`: `gateway.production-qualification.v1`
- `test_repository`: the explicitly selected fixture repository
- `cases`: exactly one row for each ID in `SCENARIOS` in
  `gateway/benchmarks/engineering_qualification.py`
- Each row has `scenario` and either a retained `job_id` or a complete accepted
  engineering `request` with a stable `request_id` and `command_id`.

Run `python gateway/qualify_engineering.py --root <installed-config> --manifest
<manifest.json> --output <results.json> --execute` to submit the listed tasks.
Re-run without `--execute` to inspect saved results. A lost submission response
retains the same request ID; it never authorizes a different write. The desktop
**Production tasks…** button performs read-only assessment of this manifest and
exports `<manifest>.results.json`.

Acceptance for the unfamiliar-bug, CI-repair, lost-mutation, disconnect and
terminal-blocker scenarios must include the matching scenario ID and independent
typed behavior/device evidence. Restart qualification requires multiple observed
Gateway boot identities. Context qualification requires actual compaction. Merge
qualification retains exact approval and merge receipts. Arrange restart,
disconnect, failure injection and exact-merge actions in the test environment;
the runner does not silently authorize them. Duplicate-write metrics count
repeated identical dispatch intents and explicitly state that measurement scope.
Unknown tokens, absent device evidence and missing runtime observations remain
unmeasured. A controlled unit test is never promoted into a production PASS.

## Verification scope

Local behavior checks exercise real temporary Git revisions, pagination, regex
and path boundaries, graph revision rejection, approved-test discovery, hunk
conflicts, protocol validation, receipt accounting, explicit PR identity,
checkpoint restoration and refusal after uncertain mutations. JVM tests cover
cross-surface allocation identity and context-overflow rejection.

The Android Gradle wrapper could not download Gradle 9.8.0 in this execution
environment (`Network is unreachable`). Full Android compilation, native display
interaction, installed Windows/phone execution and the ten live production
scenarios require CI or the configured runtime. No parity or live completion
score is inferred from source changes and controlled tests.

## Review follow-up: read-only graph fallback and specialist checkpoint safety

- The `explore` workspace action now degrades from a failed graph transport or observed graph HTTP 5xx into exact-head, read-only text search over the pinned Git mirror. It retains `graph_status=UNAVAILABLE` and a typed `graph_reason`; it does **not** downgrade permission/cancellation failures or rewrite remote state. The `symbols` and `relationships` graph-only actions continue to surface their original failure.
- Investigator checkpoints are now bound to model, selected execution route, principal/actor, permission revision, source SHA and task identity. Only complete findings can be reused. Malformed model output remains a typed, bounded `FORMAT_REJECTED` result, and a later authorized attempt can retry without treating that failed checkpoint as successful work.
- Regression tests cover graph connection failure, HTTP 503, HTTP 403, cancellation, transient invalid structured output, and checkpoint rebinding. These are host-level source validations, not proof of live device or deployed Gateway behavior. Exact-head CI and real engineering qualification remain required before merge.
