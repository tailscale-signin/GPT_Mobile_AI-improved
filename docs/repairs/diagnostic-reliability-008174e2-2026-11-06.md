# Diagnostic reliability and GitHub workspace repair

## Scope and source

This branch addresses the twelve requested reliability areas and the additional
GitHub workspace requests: a visual repository map, detailed selected-profile
progress, stable animated counters, a centered loading icon, and correct UI/job
state transitions.

The starting revision is `223b7c9f1dbf1ecaa77b65936ca112817cfb6a9b`.
The reviewed verification-recovery work from PR #807 at
`68f31592e395858f2c03c68ff485fe29b8ac626a` is included without changing its branch.

The previously inaccessible diagnostic ZIP was recovered and inspected. Its
SHA-256 is `10b5e1faf604790f35320ef7a236e826eb463c2a6ef85eae175204370fb3e600`;
all 18 stage hashes verified. It records app 0.9.66.6, build 140. The app's recorded
commit and the starting main revision contain the same source files. Raw logs,
conversation text, credentials and private endpoint identities are excluded from
this repository report.

## Findings established by the archive

| Observation | Evidence and interpretation | Disposition |
| --- | --- | --- |
| Diagnostic values omitted | Capture reported 88%, with 155 node-limit omissions: one timeline and 154 workspace values. Rows were emitted, but those values were missing. | Count JSON values correctly, permit bounded larger structured records, and preserve explicit omissions beyond the retained limits. |
| Diagnostic spool contention | 50 monitor-contention warnings, 103–284 ms, in status/batch/import/acknowledgement paths. | Publish immutable committed snapshots; readers use cached metadata and parse only selected rows without acquiring the writer's persistence lock. |
| Invalid frame deadlines | Two windows accepted near-`Long.MAX_VALUE` deadlines while recording zero fallback deadlines. | Reject invalid duration/sentinel values and use a sanitized display-period fallback. |
| Integration stopped partial | Four selected runs: three conversational completions and one partial integration. The latter had 74 calls: 73 successes and one graph failure, with 16 unread continuations and five required acceptance criteria, none verified. | Include PR #807 continuation, workflow selection and acceptance repairs. API success remains distinct from verified task completion. |
| Graph unavailable | Engineering handshake succeeded, then the graph request failed. Native reads correctly remained marked as incomplete graph coverage. | Preserve typed graph admission/readiness failures and explicit native-read limitations; improve graph presentation and refresh recovery. |
| Local grant called authentication | A local rejected/revoked grant was labeled `AUTH_REQUIRED` with `dispatched=false`. | Distinguish `PERMISSION_DENIED` and `PERMISSION_REVOKED`; do not tell users that an unattempted remote authentication failed. |
| Gateway unavailable or denied | Fifteen readiness HTTP 503s, three admitted-surface HTTP 403s, and two engineering-capability transport failures. | Preserve readiness versus transport versus authorization distinctions. Admission restrictions remain enforced. |
| MCP stale session | Discovery reported `Not connected` after cancellation, classified as unknown. | Reproduce with the actual SDK, discard the detached session and retry a safe read once. Never replay `tools/call`. |
| Frame stalls | 5,828 sampled frames, 198 jank frames and seven stalls above 100 ms; maximum 202.73 ms. | Repair concrete spool/allocation and deadline issues; device frame measurements remain necessary to quantify improvement. |
| Speech-service bind failure | One correctly classified service-start failure with bounded cooldown. | Existing service guidance and no-silent-online-fallback behavior remain appropriate; no additional app defect was established. |

All 568 retained journal events had continuous sequences and batches. The archive
contains zero model invocations, consistent with the deterministic acknowledgements
and integration planner taking paths that did not dispatch a model. It does not
establish a fallback-model incident or a context-overflow incident. Missing payloads
cannot be reconstructed from their omission markers.

## Implementation and acceptance mapping

| Requested area | Implemented behavior and relevant code |
| --- | --- |
| Selected AI profile | Progress narration calls `openPrimarySession` with the selected profile, model and endpoint checks, dispatch authorization, no tools and no reasoning. `GitHubProgressNarrator` caps frequency, attempts, time and token use. Every summary inference is accounted for, even with optional diagnostics disabled. |
| Verified GitHub completion | PR #807's scoped continuation and failed-workflow selection complete eligible evidence reads within budgets. `GitHubVerificationRecovery` and `GitHubFailureSummary` retain incomplete criteria and actionable failures. Unknown write outcomes outrank later authorization failures. |
| Gateway connectivity | Existing bounded preflight, connection recovery and saved-result reconciliation remain enforced. The MCP repair adds the missing exact SDK detached-session case without broadening authentication, cancellation or mutation retries. |
| Background persistence | Existing saved jobs and mutation fences remain in place. Gateway `BatchExecutor` now drains already admitted reads and checkpoints completed siblings on cancellation; interrupted reads stay unfinished for explicit resume. |
| Context budget | Gateway evidence compaction stops traversing exhausted collections, bounds the omission ledger, reduces both text and node budgets, and replaces omitted top-level fields instead of accidentally restoring their full original contents. Objective and final-response reserves remain intact. |
| MCP integration | Actual SDK tests cover disconnected catalog recovery alongside malformed schemas, authentication, cancellation and mutation non-replay. Discovery can refresh once; tool execution retains its no-replay boundary. |
| GitHub errors | Typed grant, authentication, SSO, branch rule, validation, rate-limit and server failures retain distinct guidance. Generic UI retry hints cannot authorize repeating a GitHub write. Explicit terminal provider codes and uncertain outcomes retain precedence over incidental text. |
| CI reliability | Retain existing exact-revision workflow verification and required release gates. Include observed latest-attempt selection from PR #807 and the regressions for successful-run versus failed-run log selection. This branch does not disable or reduce checks. |
| Diagnostics | Committed snapshots remove reader dependence on spool fsync. Bounded projection and subtree reuse reduce repeated allocations. Correlation IDs, sequences, durable acknowledgements, redaction, integrity hashes and explicit omissions are preserved. |
| Android performance | Avoid repeated full spool JSON parsing and unnecessary projection copies. Sanitize frame deadlines, reduce refresh-rate lookup frequency, and keep the response icon's rotation centered. Physical-device FPS and allocation gains are not claimed from host tests. |
| Gateway batching | Cancellation and deadlines reach engineering reads and retry backoff. Completed concurrent results are journaled before cancellation exits. Existing admission, concurrency, dependency and retry bounds remain enforced. |
| Actionable failure UI | Failure templates distinguish correction, credentials, grants, cooldowns, unavailable services and reconciliation. A terminal task no longer displays a stale loading animation solely because the active-run list has not refreshed. |

### GitHub workspace presentation

The Status tab puts a real repository network ahead of the status tiles. The
presentation accepts the architecture endpoint's actual file dependencies and
symbol-query nodes/edges, draws only recorded relationships, and provides selectable
bubbles, search, paging, source details and source links pinned to the selected
commit. Repository changes reset selection state. Failed refreshes retain the last
valid map, accompanied by the failure; old repository results must not appear under
a new revision label.

Indexing consumes issued cursors with a finite page/time allowance, detects missing
or repeated cursors, and can continue retained progress. The client cursor belongs
to the ViewModel lifetime; persisted indexing on the gateway is the durable source
after process loss. No device process-death acceptance is inferred from that design.

Progress counters use stable keys per category, so `Check 2/8` updates to `Check 3/8`
in the same row. File/page/job counters remain independent and visible when compact
history trims older entries. Full summaries preserve the distinct details of recent
work. Optional AI prose comes exclusively from the selected profile; local factual
acknowledgements and observed counters remain explicitly non-model-authored.

Narration runs between work rounds, at most once per new evidence revision and
starts attempts no more frequently than 15 seconds. Completed prose is published
immediately, before the next tool event can supersede its pending state. It receives bounded public observations rather
than raw tool payloads or private reasoning. A slow/unavailable selected model does
not trigger another model and cannot wait indefinitely. Progress prose cannot
establish passing tests, a published change or overall acceptance.

## Validation record

The canonical `bash scripts/validate-local.sh fast` passed, including 329 script
tests, dependency/CI/secret checks, Android regex validation and resource checks.

The regenerated gateway manifest passed the complete gateway checker: 496 Python
cases, including one environmental LAN-address skip, plus seven browser tests.
The 50,000-event durable journal stress test completed 782 commits and verified
restart/digest behavior. All executed cases passed.

Focused Kotlin host harnesses compile the changed production implementations with
cached Kotlin 2.4.20 dependencies. Narrow Android/database/provider DTO boundaries
are supplied by the harnesses; these runs do not substitute for the Android build:

- 51 diagnostic tests, including spool concurrency/restart, constrained 64 MiB
  heaps, large plain/compressed values, redaction and protected receipt recovery.
- 50 MCP tests using the real SDK transport, including a before/after reproduction
  of the disconnected cached-session failure.
- 89 inherited verification, graph admission, workflow selection, pagination and
  permission tests against the current branch.
- 22 graph presentation/ViewModel, activity and terminal-loading tests. Full
  Compose rendering and icon geometry tests require the Android CI runtime.
- 69 progress, narration, frame deadline and error classification/compatibility tests,
  including selected-session generation, budget admission, timeout, immediate
  publication, exact source-path preservation and terminal-code precedence.

The first combined candidate, `965f81066179b921c757e17430a4140a11abbcf8`,
passed both full Android unit suites, JaCoCo coverage, Android lint, review APK and
benchmark compilation, API 34/API 36 emulator acceptance, CodeQL, and the complete
Linux/Windows gateway and installer checks. The final follow-up preserves exact
source paths and publishes completed selected-profile prose immediately; it also
passes the 69-test focused suite and the canonical fast checks.

The current head's check results on [PR #809](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/809/checks)
are authoritative for final Android and release-gate qualification. A prior head's
green result does not replace them. No merge or release is performed by this task.

## Remaining operational acceptance

Actual private gateway availability, account grants, provider capacity, affected
phone rendering, process death/reboot, Windows deployment and post-install smoke
tests need the corresponding live environment. The branch retains finite budgets,
durable recovery and explicit blocked/partial outcomes; permanent outages or missing
permissions cannot be made into successful jobs by UI changes or blind retries.
