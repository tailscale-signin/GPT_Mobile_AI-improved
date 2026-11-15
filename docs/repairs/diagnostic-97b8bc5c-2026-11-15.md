# Diagnostic session 97b8bc5c: evidence and successor qualification

## Scope and status

This review independently verifies the retained diagnostic archive for capture `97b8bc5c` and joins issues **#1199** and **#1200** to their exact captured runs. The installed app is commit `2136e908c289ec13a1909fe5746e79b6ad94fe87`, version 0.9.66.7, build 141, debug. The captured repository observations concern main `5d4b4d81c121b36cf01b79fe8162fb78f793f203` and PR #1122 head `b553525da42a9f6d24c56f249355a7ef03bf3f13`.

The coordinator subsequently reported that #1122 was externally merged using the old b553 head at 01:36:50 UTC, producing main `485515d3d63010aceb217ac0988ba28fff7eabf4`. That later merge does not qualify the pending repairs or authorize their issue closures. The archive remains historical evidence for the installed build. Composition into one successor draft, review of its exact source, and final-head CI qualification remain pending.

No product tests, source changes, service calls, approval grants, or repository mutations were performed for this archive review.

## Integrity and record coverage

The ZIP SHA-256 is `0b4420326778d036d803acb9f3c64024e6e1d487c7e69933054c583660cba789`; the manifest SHA-256 is `4ac9fc3cc01ff05bc4c456e3dc9008c7e8049fe2d25b2dd9e6d4f432fd7bc4c8`. ZIP CRCs, all 20 stage byte lengths and hashes, all record chains, and all chunk reconstructions were independently verified. The capture contains 9,018 physical JSONL rows and 8,555 logical records after reconstructing 32 chunk groups. Counts of chunks and projections are not counts of independent executions.

`captureCompleted=true` means the requested staged capture completed. The manifest explicitly retains `capturedFully=false`: deleted, evicted, uncaptured records and absent provider wire payloads remain unavailable. Stage snapshots have independent frozen boundaries and are not globally atomic.

Seven unique runs are retained: four BLOCKED, two PARTIAL, and one RUNNING. The tool database contains 47 unique events: 46 COMPLETED and one RUNNING. The 46 completed evidence receipts contain 63 GitHub read requests and zero recorded GitHub mutation requests; those GitHub counters exclude graph transport. A completed tool wrapper or provider invocation does not establish completed user-task acceptance.

## Exact individual-report joins

| Issue | Approved payload SHA-256 | Captured run | Verified join |
| --- | --- | --- | --- |
| #1199 | `3b2f2abbde28355c75d2af278de81aaf23a072bec9a7e0080fa80b44506b6988` | Health assessment | Approved payload, complete context, occurrence time, installed commit, and domain-separated run hash match the retained report-history record. |
| #1200 | `c6116a8c62092bf2e9a255c4e8917ad6d90fd16698236fc4f48c8f5877c534d6` | CI diagnosis | The same joins match; the original error-code hash resolves exactly to `GITHUB_REVIEW_INVALID_VERDICT` under the installed producer's hash domain. |

The record-index receipt provides original stage, logical and physical row locations, and record hashes. Twenty-one full receipts are separately retained: all eight health-run receipts, all six diagnosis-run receipts, and seven run-history pages from the distinct ongoing integration run.

## Confirmed findings and dispositions

### #1199: the health request omitted a required tool

The health task explicitly requires recent failed workflow runs. The resolved tool catalog contains `github_ci__github`, but the actual admitted catalog in workspace row 629 contains only discovery, context, decision, and PR tools. All eight actual tool events complete without a CI call. The final verdict in row 640 verifies five of seven requirements; “Recent failures” has no receipt, while **24 of 32 calls remain**. Both unmet criterion-ID hashes join exactly to the public report.

The installed source supports this omission: `ToolCatalogAdmission.kt` pins only context/PR for scoped status, and `GitHubOperationTool.taskTools` makes the same selection. `GitHubStatusRequest.nextCall` has the failure-run recipe but returns no call when the required operation definition is absent. The correction must preserve authorized tool admission and make the required read reachable. A change to the recipe alone is insufficient.

The other unmet criterion is separate: the branch receipt reports `protected=true`, classic protection HTTP 404, applicable rules observed, and incomplete policy. The archive does not distinguish absent classic protection from inaccessible information or establish the selected app credential's privileges. Preserve the conservative policy qualification. **The earlier capsule-only “no new defect” disposition for #1199 must be revised before posting.**

### #1200: repeated discovery displaced the requested diagnosis

All six CI receipts are workflow-definition observations: **two live pages, two cached replays, and two suppressed repeats**. There are no runs, jobs, or log operations. Earlier continuation requests expose seven tools, including CI actions. Workspace row 713 then records `allowTools=false`, an empty tool list, and an explicit instruction to continue without further tool calls. The final ledger retains **28 of 32 calls**, with no diagnosis criterion verified.

`AgentRunner.kt` removes all exposed and executable tools when `GitHubInvestigationProgress` reports a stall. Four repeated successful workflow keys satisfy the installed progress rule. The retained request directly proves global tool removal; its internal stall notice is nonpersistent, so this review does not present that notice as a retained causal flag. The correction must preserve unrelated authorized work, exact-operation duplicate suppression, hard budgets, and bounded recovery.

The inventory also regresses. Page 2, row 693, establishes **57/57 complete** workflow definitions. Later page-1 repeats carry **30/57 incomplete**, and the final receipt-only answer in row 735 adopts that lesser observation. `GitHubPartialReport.render` selects the last workflow summary. A replay must preserve stronger complete coverage within the same qualified query and revision; it must not manufacture execution or acceptance evidence.

### First and final review failures are different evidence claims

The repair request in row 732 retains the **first** validator error: “Review must acknowledge every supplied evidence ID exactly once.” Its required IDs are `0:0`, `0:1`, `1:0`, and `2:0`. This proves an exact-coverage mismatch. Without the raw invalid verdict, missing versus duplicate versus extra IDs cannot be independently distinguished.

Both final review invocations completed with `stop`, using 479 and 757 output tokens under the 1,536 cap. The second validation failure is recorded only as the generic invalid-verdict result after one format repair. Installed `GitHubReviewedSession.validatedPass` discards that second detailed exception. **Do not claim the second failure had the same specific cause as the first.** The draft and six evidence records are retained; the candidate review was withheld and an explicit bounded review retry is available without repeating completed GitHub operations.

An earlier synthetic compatibility request exhausted its 2,048 output allowance, then its increased-budget retry completed. That recovered preflight event is not the terminal review failure. Likewise, a COMPLETED output checkpoint means draft generation completed, not that the GitHub task passed acceptance.

### Separate ongoing run: traversal identity splits equivalent run pages

A distinct integration run was still RUNNING during capture. Its seven retained run-history pages contain **206 distinct IDs**. Page 1 has coverage `[1]`; pages 2–7 have coverage `[2,3,4,5,6,7]`, totaling 176 in the second group. Page 1 omits `ref`; the continuations add `ref=main` while preserving the same commit and actual filter fields.

`GitHubPaginationBoundary.key` includes `ref` for workflow-run traversal identity. `GitHubTool.handleListWorkflowRuns` uses branch/status/actor/event/created/head filters and translates `commit_sha` to `head_sha`; it does not transmit `ref` as a filter. The source therefore supports this split for equivalent HTTP query filtering. Any canonicalization must preserve real filters, repository, epoch, revision, and changing-inventory checks.

The 206-ID union is an observation of retained records. It does not prove atomic remote inventory, model consumption, terminal task completion, or a cause of the distinct #1199/#1200 runs.

### Historical PR-health reduction requires attempt identity

The health receipt `ev_47b61a4489c2b3a31e42adbd`, workspace row 661, reports overall FAILED for PR head b553 while its check page also contains newer in-progress checks and historical successful/failed/cancelled observations. The raw GraphQL health nodes are not retained, so their complete provenance cannot be reconstructed. The assigned correction must distinguish applicable latest attempts by workflow/app/check identity, preserve history and policy uncertainty, and invalidate stale head/base observations. This receipt is not a current merge-readiness result.

## Remaining archive dispositions

| Observation | Disposition and limit |
| --- | --- |
| Three implementation attempts return `ACCEPTANCE_CONTRACT_REQUIRED` | Retained admission receipts show HTTP 412, `dispatched=false`, `remoteAcceptance=NO`, and no associated model invocations. Preserve this validation gate. Actual host policy and gateway binary revision are absent, so missing, invalid, ambiguous, or unmatched acceptance mapping cannot be selected as the precise cause. Configure approved mappings or supply a concrete contract; do not infer model incapacity. |
| Branch review on main returns `REVIEW_INPUT_REQUIRED` | A baseline was not established. Do not invent a prior commit or silently choose another PR. |
| Ongoing integration checklist has 124 catalog entries, 70 required | The required snapshot is 22 passed, 22 blocked, 19 untested, and seven conditional, with 226/256 calls remaining at that snapshot. Pending discovery, continuations, and ASK fixture approval are not failed mutations, exhausted budget, or authorization to perform writes. |
| Graph status retains index uncertainty and a recovery cursor | This is a qualified recovery state inside a still-running integration run. It does not demonstrate a new terminal failure, an observed replay loop, or an absent recovery path. Existing graph repair components require their own source/CI qualification. |
| Debug frame timing records slow frames | Independently counted: 41 reports, 4,614 observed frames, 300 jank frames, nine over 100 ms, none over 500 ms, zero dropped samples; maximum 262.435434 ms. These are sampled debug-build observations. Operation context is windowed, explicitly noncausal, and lacks individual absolute span bounds. It cannot identify the exact critical-path cause or prove a recomposition/exporter defect. The assigned narrow span-bound correction improves attribution evidence; it is not a measured performance cure. |
| `run_attempt` shares the job-ID schema description | Confirmed source wording error, assigned to the investigation component. No retained evidence ties the typo to the historical loop. |
| Final validator detail is unavailable | Historical evidence gap confirmed above. The coordinator records the separate 10ed diagnostic component as accepted; its independent source/report binding remains separate from this archive review. This does not reconstruct the missing historical verdict. |

Privacy-based external-tool exclusions, disabled Local Assist, unrequested catalog entries, and a still-running operation are not additional proven defects. No speculative optimization or additional unowned implementation blocker is established by this bounded review.

## Voice report coverage in this attachment

A separate bounded review compared eighteen approved voice reports: #1171–#1184,
#1187, and #1189–#1191. Four exact report-history projections are retained for
#1187 and #1189–#1191. Their shared fields and complete contexts match the approved
reports; the additional values describe retention and projection. The other
fourteen have no exact retained report/session/run match in this capture.

None of the eighteen has an independently joined run, voice session or operation
receipt establishing a new cause. Existing diagnostic and playback repair
qualifications therefore remain unchanged. In particular, #1187's LOCAL/THINKING
state does not distinguish synchronous submission refusal from a later owned
asynchronous admission failure. That branch remains unknown. Missing retained
records do not prove that historical execution did not occur.

The frozen coverage receipt is bound by SHA-256
`85f638b4f179c089cb8f4486be911b0e18c7160f09d1542863fb7364f00341cc`,
and its independent source/record binding by
`8d5e748a353b98c3cef8e3994f22a195818eb33492e6758be19f2bd1ad4a2e82`.
No additional source change or replay was inferred from repeated report context.

## Ownership and remaining qualification

Separate reviewed components cover investigation progression, status admission, replay/inventory projection, PR-health latest-attempt identity, the run-attempt schema description, frame-span evidence, and authentication CI module selection. The latter is a CI producer correction, not an inferred cause of this device capture. No competing implementation was created by this audit.

Seventeen source files are bound in the main receipt and six additional files in the disposition receipt. Fifteen of the main set match installed 2136 byte-for-byte. The current `ToolCatalogAdmission.kt` differs in its separately composed budget-message helper while retaining the status-selection gap; `IssueReportIncidents.kt` also has separately composed diagnostic changes. These distinctions prevent attributing all current worktree bytes to the installed application.

This document qualifies observations and repair scope. It does not declare the successor fixed, deployed, CI-green, vulnerability-free, or ready for issue closure. Qualification must bind the composed final source and actual final-head execution receipts.

## Retained review packet

The coordinator retains the complete machine-readable evidence under `ci-evidence/new-archive-97b8bc5c/`:

| File | SHA-256 |
| --- | --- |
| `integrity-receipt.json` | `2bed3ce3ea0eebb01723f0dd91dcd5de5b0a4c135da622731d49bad10f363ed9` |
| `source-join-receipt.json` | `115987878b61d362c8b61a1f6a27d0895e316981600fc71d2a40df4a5ab95b73` |
| `remaining-dispositions-receipt.json` | `e00737124f26406a4c6919cb53480a7b518ec2d29b427b72b51af83cf4aec3c0` |
| `full-retained-receipts.json` | `6e1dd61d44e7087fee37da5a13e85f846a329439761436a6ffbc2ce20a05c693` |

`record-index.json`, the normalized originals, and the complete selected workspace records preserve the exact row/hash joins. The raw record packet remains separate from repository documentation. Only bounded findings and integrity/source bindings are published here; raw private tool arguments, prompts and original run identifiers are not copied into this document.
