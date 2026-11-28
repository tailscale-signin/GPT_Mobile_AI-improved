# GitHub intelligence diagnostic implementation — 2026-11-27

## Revision and evidence

This work implements the remaining reproducible gaps in the supplied November 27
investigation on PR **#2651**, `feature/windows-native-benchmark-kit`. Intake found
that it was the only open PR and that its head had advanced from the supplied
`58e595e7ce44801ed677804e61850f5821bca745` to
`b54df163105a38570e7d42b06e9d7bdc7a6c2d15`. The accepted base was `main` at
`6435462ac6f306d63b720205a9f2aafc33de1e4d`.

Before publication, the branch advanced through `66eb4ce3` (unified intelligence,
durable choices, repository synchronization and profile benchmarking) to
`440561e3f71e54ce1058c9edddc0f9416fb7d84f`. This repair was reconciled onto
that head. Both sets of feature mappings, source changes, regressions and exact
release-scope entries were preserved. The combined payload receives its own
validation; the earlier local result is not treated as proof of the new tree.

The audit follow-up was subsequently reconciled onto diagnostic sweep
`22443d5c` and its manifest-provenance-only child `2dd0220e`. The incoming source,
context, verification, Gateway and voice repairs were preserved. Source delivery,
accepted authority and verification-budget overlaps were reviewed and recompiled;
their focused regressions pass on the combined runtime. The full host, Gateway
and report-service gates were rerun for the combined payload. The task record
distinguishes these results from earlier hosted builds and canceled unit runs.

The pasted diagnostic findings are historical observations from another app
revision. The original archive was not independently reopened in this pass.
Findings below come from current source inspection, live CI failure logs and
new executable regressions. Existing implementation is identified separately
from changes made here. Source presence alone does not establish device,
provider or comparative product qualification.

## Reproduced defects and changes

### Retained evidence can actually reach the model

`GitHubReplayRecovery.nextSavedRead` treated `retained_original_complete=true`
as a reason to suppress a local saved-view read, except for one source-page
format. CI logs use another fragment format. In addition,
`GitHubCiReadProgress.reused` replaced an already downloaded observation with a
small reuse marker that discarded its issued local recovery cursor.

The shared reuse projection now preserves the original source identity,
observation call and authorized cursor. Exact delivered-projection digests
distinguish a source page from a small reuse marker. An explicit request to
reopen a retained observation can consume unread local fragments, and the
runner prioritizes those fragments before another remote page. The original
owner, connection and immutable revision remain mandatory. Reopening retained
bytes does not repeat a network request or grant permission to replay a write.

Replay compaction reserves that exact unread-page cursor and its provenance
before fitting optional diagnostic summaries. A large summary can no longer
replace an otherwise fitting local recovery route with an omission notice.

`ToolExecutionBudget` also distinguishes a schema-valid cursor-only saved read
from new source acquisition. A local page may use the original remaining
verification allowance; it still consumes a durable call and must pass current
authorization. This does not increase the accepted ceiling.

### Small context windows retain useful exact source

Gateway `task_context.project_bundle` previously discarded the entire body of
an over-budget source range. It now admits the largest prefix of whole lines
that fits, recalculates the excerpt hash and end line, and records the next
unread line and retained end. Oversized individual lines and over-budget ranges
with malformed bounds or content hashes remain unread pointers. Only bytes
actually sent in a successful bound invocation can authorize an edit. The full
original stays retained.

The regression demonstrates that a matching function can remain editable after
compaction while an earlier, omitted source line remains ineligible. No live
token-saving percentage or model-quality gain is inferred from this fixture.

### Compatible configuration changes preserve recovery authority

The active dispatch guard already allowed a user to raise the GitHub intelligence
level while an accepted turn continued with its original effort. A serialized
provider-wait authority lacked that accepted value, so the same compatible
change failed after recovery with `PROVIDER_WAIT_PERMISSION_CHANGED`.

`CapturedProviderAuthority` now retains the accepted intelligence level and
applies the same narrow compatibility rule as live dispatch. It continues to
check parent authority, account/profile identity, model, route, privacy and
permission revisions. Lowering permissions, changing destination or changing
another incompatible setting still invalidates continuation. Old serialized
records remain readable and do not acquire invented authority.

### Verification calls are used for missing proof

The ordinary session previously stopped as soon as the two-call verification
reserve was reached. Its publication verifier evaluates retained receipts; it
does not acquire missing CI evidence. A committed change could therefore stop
without using the reserved calls for its test results.

`GitHubVerificationReserve` selects only missing CI observations for an already
published, trusted implementation at the accepted repository/connection and
current observed head. Depending on the evidence already available, it reads
the pinned runs inventory and/or the exact run-attempt job inventory. It keeps
fixture-cleanup capacity, never expands the total allowance, avoids repeating
the same selector, and does not initiate implementation or mutation. A
receipt-derived verification draft enters the existing acceptance evaluator;
the draft is not evidence that tests or criteria passed. Stale heads, newer
failed attempts, incomplete inventories and unknown operations remain blockers.

### Two live CI failures are repaired at their source

Three Android jobs at intake failed the same regression after compiling:
`GitHubAcceptanceRecoveryEvidenceTest.retainedDeadlineReportsRelativeTimeOnlyWhenObserved`.
The retained projection dropped `task_deadline_source`. It now preserves the
closed provenance values through recovery, Android diagnostics and the server
receiver; unknown or private strings remain excluded.

The concurrent upgrade independently included the Kotlin provenance correction.
Reconciliation retained one implementation and combined its regressions. The
report service now also accepts exactly the new `ACTIVE_EXCLUDING_USER_CHOICES`
clock alongside `WALL_CLOCK`, matching Android's contract. Relative allowances
can exclude persisted user-choice waits; absolute deadlines keep wall-clock
semantics. Unknown clock strings and non-string values remain rejected.

The incoming pending-choice tool result also required an explicit `isError`
argument. It now identifies the non-dispatched `GITHUB_CHOICE_PENDING` dependency
boundary consistently, so the runner parks the original invocation rather than
claiming a successful tool result.

The cutoff classifier now also recognizes tested delivery clauses with a direct
object, such as “Finish the change within 10 minutes.” Its bounded phrase cannot
cross sentence punctuation into a separate active-work allowance. The original
deadline/by-time checks and wait-interval arithmetic are preserved.

The Windows/Python 3.12 Gateway job timed out waiting for an already received
answer. `gateway_v13_transport.observed` synchronously persisted diagnostic
progress before offering validated text to the stream. The order is now durable
checkpoint, tool-contract validation, visible output, then diagnostic progress.
A thread-latched regression reproduces slow journal persistence without raising
the existing three-second visibility bound. Failed checkpointing and an
unauthorized tool response still prevent publication.

### Voice verification requires observed rendering

`VoiceSpeechPlaybackResult.captionManifestValid` used `renderedManifestWords != 0`,
which also accepted a missing nullable measurement. A current-protocol restored
report could consequently pass caption or sustained-playback qualification
without renderer evidence. A positive observed count is now required. The
playback-clock accounting still handles brief words crossed between rendered
frames; it does not require every word to appear in a separate UI frame.

### Legacy choice waits stay neutral in the session audit

Concurrent commit `79981ac5` extends waiting-state presentation and exact-receipt
completion of the original saved invocation. Integration review found that its
session-audit aggregate still counted a bound legacy choice stored as `FAILED`.
The chat, activity and usage views already recognized that same receipt as a wait.

The audit now inspects only native legacy choice candidates from the selected
runs, in pages of 32 identities, and uses the existing archive-aware per-event
read and exact waiting predicate. A transaction keeps counting and classification
on the same snapshot. It subtracts only proven non-dispatched waits; foreign
identities, malformed receipts, dispatched calls and real failures stay counted.
An extra aggregate group preserves all 32 visible real groups when a wait group
is removed. Plain and archived receipt coverage plus a three-page regression
both fail against the original query and pass against the correction. The local
SQLite/JVM harness does not substitute for hosted Room/KSP validation.

## Subsequent integration and Windows text decoding

The follow-up preserves the concurrent voice/translation work through `f1003415`.
Hosted compiler failures identified a missing `captureProviderInvocation` import
and a missing `flow` import in a new repository test. The corresponding concurrent
fixes were adopted after source comparison. The new multilingual CPU backend was
also added to the report receiver's closed enum; the full 249-case authentication
suite passed after that correction.

At `f100341554a30bd0510f3d9c8d158341c833b2e5`, both Windows Python 3.12 and
3.13 jobs completed all 2,324 canonical Gateway cases (49 platform skips), all
seven browser checks and the 50,000-event stress case. Their following
authentication runs each failed one of 249 cases: the deadline provenance test
read UTF-8 Kotlin source using the Windows CP1252 default. Explicit UTF-8 decoding
corrects the test without changing the runtime contract or skipping its checks.
The original failure was reproduced locally; all 249 authentication cases then
passed with normal decoding and with CP1252 supplied for otherwise unspecified
`Path.read_text` encodings. The latter is a local compatibility check, not a
substitute for the next hosted Windows run.

The same baseline has a completed Windows installer receipt (run `46085949177`,
artifact `15238374980`), passing Linux Gateway/authentication jobs, and full Android
production and unit-source compilation. Its unfiltered standard unit run completed
15,457 cases in 2,016 suites, with 24 failures and six skips. The downloaded result
artifact `15239504319` matched its published SHA-256
`a3ebc80ed8157bceaa1621c36ce9d8763fc8de929d8b1ed99c2da8feb285bf4f`.
The GenieX unfiltered suite independently reproduced the same 24 failing cases
with identical totals; its downloaded XML artifact `15240752861` also matched its
published digest. The combined correction passes the canonical host gate (850
cases, one skip) and formatting for all 23 changed Kotlin files.
The PR test merge checkout
`f162c9428d6f61f1ed21b76f64f886b8a3700294` has exactly the same source tree,
`1a334330c75a33650bad7618ce550f696ebe2850`, as the baseline branch head. The task
record keeps these revision identities and distinguishes baseline evidence from
the containing correction's required hosted checks.

### Corrections exposed by the full unit run

The full runner tests exposed integration paths beyond the earlier focused
harnesses. Repository status and explicit short source reads now retain their
schemas. A continuation may restore accepted intent only from the same profile
and conversation; greetings, temporary chats and unrelated external-file work
remain isolated. Discovery queries are ranked within the authorized catalog.
Explicit GitHub work within a mixed external-service request remains eligible.
Shorter repository-target prose restores the existing 2,000-token compact schema
limit without changing the schemas or their execution permissions.

Cursor-only saved-page reads now use their immutable source/offset identity in
the progress watchdog. Distinct retained pages no longer become repeated mutable
status observations. Mandatory saved reading notes also receive the same
pre-dispatch capacity check in both continuation-planner modes.

The remaining corrections align fixtures with current contracts: authenticated
Gateway metadata, unchanged page bodies with separate runner instructions,
bounded multi-window extraction, one coroutine test clock, explicit recovery
provenance, observed-only voice evidence, actual Room updates and current tool
revocations. Existing negative evidence, privacy, replay and authorization checks
are preserved; no timeout, context limit or verification ceiling was raised.

Focused validation passes for routing/history (6 cases), saved-source progress
and continuation planning (8), extraction/window/identity (8), reserve (11), and
voice diagnostic projection (6). Baseline copies reproduce the relevant routing,
progress, capacity and virtual-clock failures. These actual-source harnesses use
declared application boundaries; the revised full runner, provider, Room and
Robolectric tests still require the containing commit's hosted run.

### Remaining full-suite failures at `f04372e4`

Both unfiltered suites subsequently completed at
`f04372e40a04cbb3d332463ef8c9ad0327c63945`: 15,461 tests in 2,016 suites,
with two failures, no errors and six skips in each configuration. Standard
artifact `15242886039` and GenieX artifact `15243060362` were downloaded and
their SHA-256 digests verified. Both identify the same two failing assertions.
The actual PR checkout, `bf04d526a94d2a13d62e0c427f318cbb1c26c5c6`, has
the same tree `2a53084f505fb1a4cbe311f54168ba6c699ebd10` as that head.

The account-level request “List my GitHub repositories” incorrectly inherited
the saved workspace's source revision through the repository-intent matcher.
A whole-request account-inventory guard now keeps that discovery unbound to a
selected source. Compound requests that also ask for repository work retain
their acceptance and exact-head binding. The original failing assertion is
preserved alongside account-phrase variants and mixed-request regressions.

The catalog regression still assumed the ordinary eight-tool window after an
explicit repository task selected the existing sixteen-tool advanced window.
The existing maximum is now named `MAX_TOOLS`; its value and admission behavior
are unchanged. The test checks both window sizes, deliberately rotates enough
distinct schemas to evict the earlier tool, then verifies complete authorized
inventory, rediscovery, revocation and a tool-free verification pass.

Fresh focused JVM runs reproduce the original scope and catalog assertions
against `f04372e4`. The repaired scope/intent/history harness passes 15 cases.
The actual catalog and native schemas pass the complete amended regression,
including both initial and continuation scenarios and all later authority checks.
Their declared storage/provider boundaries remain separate from a full Android
run; the new containing revision still requires hosted verification.

That same `f04372e4` revision passed all four hosted Gateway combinations
(Linux/Windows, Python 3.12/3.13), including all 249 authentication tests on
both Windows versions. The installer run `46091132983` passed its 16 packaged
smoke scenarios and retained artifact `15242195085`. API 36 acceptance passed
131 instrumentation executions; API 34 passed 130 with one existing assumption
skip because its emulator had no English TTS voice. These are exact historical
receipts for `f04372e4`. The containing account-scope/catalog correction requires
its own hosted checks and does not convert either failed unit suite into a pass.

## Coverage of the requested correction areas

“Existing” below means a current runtime path and named behavioral tests were
reviewed. The final validation receipts specify which suites were actually run.

| ID | Implementation and verification boundary |
| --- | --- |
| P0-01 | Existing durable task envelope, `GitHubExecutionJournal`, `ToolBatchCheckpoint` and `GitHubVerifiedSession` continuation; restored compatible authority repaired here. Accepted deadlines remain explicit. |
| P0-02 | Existing `GitHubAcceptanceContract`, `GitHubAcceptanceLedger`, `GitHubEvidenceBook` and `GitHubExecutionLedger`; source delivery and missing final CI acquisition repaired here. |
| P0-03 | Existing residual prompt budgeting, repository-map reuse and incremental receipts; local retained-log recovery and Gateway whole-line projection now avoid specific repeated or empty context paths. |
| P0-04 | Live/restored compatibility rules aligned without changing the accepted model, effort or authority. |
| P0-05 | Evidence-dependent use of the remaining verification calls, with cleanup and final-response reservations preserved. |
| P0-06 | Existing `GitHubCiInvestigationRecovery` and Gateway inspection/edit/check workflow; current Android and Gateway failure causes reproduced and repaired. |
| P0-07 | Existing `ToolArgumentValidator`, `GitHubOperationArguments`, `ToolArgumentRepairBudget` and typed transport failures; exact issued-cursor and immutable-source correction remain bounded. |
| P0-08 | Existing pinned saved-page delivery-digest retention preserved; saved-view recovery and capacity-aware source projection strengthened. |
| P0-09 | Existing `GitHubRequestCompletion` requires implementation plus complete test inventory at the exact SHA/latest attempt; reserve reads use that same identity. |
| P0-10 | Existing `ContextBudgetService`, route-capability limits, schema staging and replay reserves; Gateway projection now retains a smaller exact source window. |
| P0-11 | Existing journals retain pending/unknown operations and original task counters across interruption; source snapshots and compatible provider-wait recovery are regression-tested. |
| P0-12 | Retention, projection delivery, local reopening and source-edit authority are now explicitly distinct along the repaired log path. |

| ID | Current capability |
| --- | --- |
| P1-01 | `ProgressiveToolCatalog`, reviewed task routes and Gateway tool profiles advertise relevant authorized schemas. |
| P1-02 | Schema and typed semantic argument validation precede dispatch; deterministic normalization applies only to supported, safe corrections. |
| P1-03 | `RepositoryMapRepository`, phone graph shards and Gateway source/graph snapshots reuse immutable blobs across chats and revisions under current authorization. |
| P1-04 | `GitHubCiInvestigationRecovery`, CI observations and Gateway failure inspection preserve the run, job, attempt, source revision and relevant output. |
| P1-05 | `IntelligentToolBatch` and Gateway specialist/worker waves execute independent reads while serializing dependent effects. |
| P1-06 | `GitHubNextActionPolicy`, `GitHubInvestigationProgress` and progress watchdogs detect repeated/no-progress work; unread local evidence no longer counts as redundant network work. |
| P1-07 | Task-complexity reasoning, deterministic extraction and configured approved helpers/reviewers provide adaptive execution. Automatic helpers prefer approved local/private routes; a task does not silently change its primary provider or privacy destination. |
| P1-08 | Revision-bound source ranges, failure excerpts and whole-line projection prioritize useful evidence inside actual remaining capacity. |
| P1-09 | `AgentRunner.recoverFailedRound` permits bounded malformed-frame repair and receipt-only finalization without replaying completed writes. |
| P1-10 | `GitHubEngineeringMemory`, durable task context and Gateway working summaries retain verified findings with provenance and bounded admission. |
| P1-11 | Readiness and capability discovery distinguish disabled, unavailable and unauthorized tools before engineering dispatch. Explicit off choices remain off. |
| P1-12 | Staged patch, commit, mutation journal and merge paths validate source/head/owner identity; remote head is reconciled before this implementation is pushed. |
| P1-13 | Typed corrected requests can proceed; unchanged failures, duplicated invocation IDs and unknown mutation outcomes remain guarded. |
| P1-14 | Acceptance dependencies, `DelegationTaskPlan`, intelligent batch dependencies and Gateway task plans retain prerequisite order. |
| P1-15 | Configured independent patch review and the final source-owned acceptance evaluator remain separate from implementation proposals. Missing independence/evidence is not a pass. |
| P1-16 | Context/output planners preserve requested output limits and separate completion/recovery capacity; this pass does not hide over-budget work by changing models. |

## Gateway qualification

| Area | Code/test coverage and remaining qualification |
| --- | --- |
| Queue capacity | Existing transactional replacement, terminal-open counts and checkpoint-capacity recovery; synthetic journal/queue regressions. The user's live queue was not accessed. |
| Cancellation | Existing original-ID reconciliation and retained-job cancellation, including restarts/old identities; unknown remote outcomes remain explicit. |
| Long tasks | Durable accepted jobs, provider waits and checkpoints persist beyond individual model calls. An expired accepted task window is not silently extended. |
| Networking | Existing typed failures, bounded read backoff and circuit classes; no uncertain write replay. Live Windows-to-phone network qualification remains separate. |
| Engineering defaults | Built-in Gateway engineering and verified-owner/readiness/revocation checks remain intact. |
| Copilot | Existing default integration and receipt-bound progress retained. No live Copilot task or entitlement qualification is claimed. |
| Context contract | Exact source/projection identity and whole-line Gateway admission tested; local saved-page reads retain their connection and source identity. |
| Installer | The hosted `f04372e4` review build passed fresh install, repair, restart, rollback, scoped firewall and uninstall checks: run `46091132983`, artifact `15242195085`. Any later source commit requires its own check receipt. |
| Concurrent workers | Existing worktree/lease/head fencing retained; this task records its base and reconciles remote advances before publication. |
| Benchmarking | Native campaign/import/grading contracts exercised with synthetic fixtures. Comparative ChatGPT/Codex/Claude/GPT Mobile scores require actual matched product captures. |

## Voice qualification

| ID | Current implementation and boundary |
| --- | --- |
| V-01 | QNN environment/library readiness remains explicitly unqualified. Actual native execution receipts and backend qualification own acceleration claims. |
| V-02 | Selected backend, effective provider, accelerator kernels, CPU kernels and fallback are separate observations. Valid PCM and CPU-reference checks precede accelerated playback admission. |
| V-03 | Bounded focus retries, delayed gain, pause/resume, duck, timeout and replay guidance already exist. Text remains available independently of playback. |
| V-04 | First PCM, queued PCM, track write/play, first presented audio, sustained audio, refill and underruns are distinct measurements. |
| V-05 | Benchmarks can export GPU/NPU validity and native execution evidence, but physical hardware measurements were not produced in this environment. |
| V-06 | Captions use playback timing and manifest coverage. Missing renderer evidence now prevents positive qualification, including after export/restore. |

## Progress and recovery presentation

| ID | Current implementation |
| --- | --- |
| P2-01 | Typed failure/readiness presentation explains the blocked capability and supported recovery. |
| P2-02 | `GitHubPublicProgress`, progress tracking and next-action selection show evidence-based stages; Gateway output is no longer delayed behind diagnostic persistence. |
| P2-03 | Tool progress identifies the operation and its purpose through the existing preview system. |
| P2-04 | Copilot previews require source-owned execution proof; enablement is not completion. |
| P2-05 | Acceptance status/coverage and outstanding criteria derive from the same retained ledger used for final verification. |
| P2-06 | Recovery controls use typed failure/capability/ownership state; retained evidence can now supply the previously stranded local read. |
| P2-07 | Partial, interrupted, blocked, failed and completed work retain distinct outcomes; an absent renderer or unknown source no longer promotes verification. |
| P2-08 | Revision-bound CI observations retain job/run identities and links in the existing CI summary and workspace views. |
| P2-09 | Existing compact rich summaries and progress deduplication retained; optional summary text cannot replace the task contract or evidence identity. |
| P2-10 | Task/source/checkpoint recovery preserves the original conversation, account, repository, profile and pending operation identities. |

## Validation and remaining measurements

The companion task JSON records executed suites, exact source revisions and
hosted-check outcomes. Local Kotlin results are focused production-source JVM
compilations with declared Android/storage boundary doubles; they are not a full
Android build or a physical-device run. The canonical host and Gateway gates,
changed-file formatting and exact-head hosted Android/Windows checks are separate
receipts.

| Executed validation | Result |
| --- | --- |
| Canonical Gateway gate | 2,324 Python tests across 166 isolated suites on the combined diagnostic/audit payload, with 40 environment skips; seven browser-bridge tests passed; the 50,000-event durability stress passed. |
| Canonical host gate at `f04372e4` | 850 tests with one environment skip; policy, generated metadata, package hashes, secret scan, Android regex and resource checks passed. |
| Authentication/report service | All 249 tests passed on the combined diagnostic/audit payload, including the incoming typed dispatch and voice evidence contracts. |
| Focused source recovery JVM | All five tests passed. |
| Focused restored authority JVM | All seven tests passed. |
| Focused reserve/admission/acceptance JVM | All 11 tests passed after the later diagnostic reconciliation, including dynamic proof allocation and cleanup protection; complete session integration is separately added to the hosted Android suite. |
| Focused deadline projection JVM | All 13 tests passed after reconciliation, including the reproduced current-CI regression and active-time clock projection. |
| Focused decision/clock JVM | All eight tests passed; original incoming missing-argument compilation failure and delivery-cutoff regression reproduced before correction. |
| Portable voice JVM | All 106 tests passed; physical playback and acceleration remain unmeasured. |
| Legacy-choice audit JVM/SQLite | Both regressions passed using the actual SQL, DAO method body, bound-receipt predicate and archive codec. Both fail against the original query; full Room/Android execution remains a hosted gate. |
| Native benchmark contracts | 89 passed and eight PowerShell-dependent cases skipped; synthetic fixtures, no provider requests. |

The host's initial Gateway run used a dependency directory that an intentionally
isolated child did not forward. A standard virtual environment with the same
repository-locked dependencies resolved that environment failure, and the full
gate passed without changing its tests or timeout limits. Hosted check receipts
must identify the final containing commit; an earlier green revision is not
substituted for that evidence.

This work does not establish a comparative intelligence score, a measured
reduction from the historical 1,343,206 tokens, a successful live Copilot task,
physical GPU/NPU speech quality, or live installation/network/queue behavior.
Those require their actual target environments and matched run evidence. No
paid model/product trial was initiated during synthetic verification.
