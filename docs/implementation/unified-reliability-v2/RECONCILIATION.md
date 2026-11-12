# Unified Reliability v2 — acceptance reconciliation and execution

Updated 2026-11-12 on `fix/github-job-recovery-and-open-issues-2026-11-12`,
based on main `8877f0ef12ac46f87c9db6e5d79b1b661dc0f62f` with PR #834 integrated
at `39594bdb93ef9f304c6e7fa0321f946f8cdea881`. The read-only ledger inventory
used that exact integration revision. New working-tree repairs are component
changes awaiting their own exact-commit CI and acceptance qualification.

The previous review compared main `7db0c735f9e86eaece24513fbaf5c0a624f13822`
and PR #816 `5190aac3564319ca95a26c09552d06f9aa0622a2`. Those remain historical
snapshots; each CI evidence report must record the source revision it tested.

## Truthful ledger baseline

The versioned ledgers intentionally remain unchanged in this implementation:

- 48 tasks: 0 DONE, 2 IMPLEMENTED, 43 IN_PROGRESS, 3 NOT_STARTED (V46–V48).
- 110 cases: 11 PASS, 99 NOT_RUN (33 LA, 14 LC, 26 CM, 26 IX).
- Older PASS receipts are case-bound synthetic/JVM fixtures. The historical unit
  receipt acknowledges a separate failing suite test; it is not overall release qualification.
- The current non-delivery fix branch runs a **scope-only** ledger gate. A successful
  scope-only gate is not full Unified Reliability v2 acceptance.
- Signed release and named v2 delivery branches retain strict --require-done.
  The strict command MUST fail while the ledgers remain incomplete.

A green generic PR, Gateway, Android emulator, or CodeQL workflow is never
automatically evidence that one of the 99 outstanding formal cases passed.
[Issue #674](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/674)
was read back on 2026-11-12 as **open**, with `state_reason=reopened` and last
update `2026-11-11T02:57:36Z`. Its acceptance closure conditions remain unfinished.
The earlier statement that the issue was closed is superseded by this observation.

## Main branch enforcement verified

Readback on 2026-11-12 of
[ruleset 27005122](https://api.github.com/repos/tailscale-signin/GPT_Mobile_AI-improved/rulesets/27005122)
confirmed active enforcement for `refs/heads/main`, required pull requests,
deletion and non-fast-forward protection, strict required status checks, no bypass
actors and `current_user_can_bypass=never`. The required checks are
`Required verification` and `Analyze (java-kotlin)`, bound to the GitHub Actions
integration (`15368`). This satisfies the issue's outstanding server-side
main-protection requirement at the observed configuration.

`Required verification` depends on the reliability scope/completion gate,
unit tests/heap checks/review APK, Android lint, repository/gateway contracts
and changed-Kotlin formatting. Each must succeed; the aggregate does not turn
the scope-only mode of an incremental repair branch into full acceptance.
The signed-release `--require-done` gate remains in place.

## Current host verification and limits

The read-only reconciler reports the unchanged 48-task / 110-case inventory.
No current JUnit artifact was supplied to this local inventory, so
`junit_evidence_scope=NONE`, named candidates remain unobserved and formal
promotions are zero. This does not infer that the candidate tests failed.

The 26 Python evidence-ledger, PR-gate and reconciliation regressions passed
locally after adding rejection of Git's all-zero object placeholder for PASS
case commits and VERIFIED/DONE implementation commits. The two new regressions
failed before that fix. Scope validation and this repair branch's scope-only
gate passed. `check_unified_reliability_v2.py --require-done` correctly exited
nonzero with all 48 tasks and 99 cases outstanding. These host checks validate
the evidence gate, not the Android, hardware, live-model or formal case outcomes.

The next host/CI evidence to collect is the existing exact named JUnit mapping
below, followed by the new recovery/export/reporting regressions at the final
repair commit. Preserve raw XML, its qualification receipt, GitHub artifact
digest and exact run-attempt URL. A component PASS cannot promote a formal case.

### Related repair components; no task or case promotion

| Repair component | Related tasks/cases | Evidence still needed |
| --- | --- | --- |
| Saved gateway job/tool-result recovery and active-conversation admission | V13; LC-T13, LC-T16 | Same-job resumption with exact retained results, process interruption and uncertain-write reconciliation across the actual serving route. |
| Bounded same-run failure summaries, snapshot matching and accepted-policy references | V40, V42; IX12, IX19, IX20 | Full active-capture consistency, independently verified replay boundaries, diagnostic-off/temporary-session integration and response-UI qualification. |
| Explicit critical-source availability and correct disabled-recorder recovery | V39, V40; CM-T23, CM-T25, IX15, IX19 | Disk/queue pressure, missing sequence disclosure, operational versus diagnostic storage failures and no false complete/resumable claim. |
| Typed public-report failures without private job identifiers | V39; CM-T22, IX20 | Remaining legacy logging/exports, privacy transitions and end-to-end incident retention; public code allowlists alone do not qualify every content path. |
| Reject null Git revisions in acceptance evidence | V48; IX27 | Reviewed case/task receipts, exact-commit release CI, production JNI/ABI, signature, artifact and upgrade qualification. |

## Automated read-only evidence inventory

The mapping in component-fixtures.json names existing JVM regression methods that
exercise **parts** of 17 outstanding acceptance cases. It explicitly describes
the remaining requirements for each case and cannot change ledger status.
These are candidate probes, not new passing case receipts.

Run from the repository root with Python 3, then execute JUnit tests on JDK 21
with the repository's Android toolchain:

    python3 -m unittest \
      scripts.tests.test_unified_reliability_v2_evidence \
      scripts.tests.test_unified_reliability_pr_gate \
      scripts.tests.test_unified_reliability_v2_reconcile -v
    python3 scripts/check_unified_reliability_v2.py
    python3 scripts/check_unified_reliability_pr.py \
      --head-ref fix/audit-execution-recovery-2026-11-09
    python3 scripts/check_unified_reliability_v2.py --require-done
    # The final command must exit nonzero while acceptance is unfinished.

    python3 scripts/gradle_retry.py -- :app:testDebugUnitTest --no-daemon \
      --tests 'dev.chungjungsoo.gptmobile.data.assist.AssistReliabilityTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.context.RequestAdmissionTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuardTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCacheTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.ProgressWatchdogSessionTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.context.CombinedCorpusMultipassTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.CombinedClaimInventoryTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.CombinedWriterRequestPlanTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.CombinedWordingLineageTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.CombinedClaimMatchingTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.CombinedProvenanceValidatorTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.CombinedSynthesisPlannerTest'

    python3 scripts/unified_reliability_v2_reconcile.py \
      --source-sha "$(git rev-parse HEAD)" \
      --output build/reports/unified-reliability-v2/reconciliation.json

The existing PR Validation job runs this reconciliation after the JVM test
step and uploads the JSON alongside raw JUnit XML. A failed/missing candidate
probe is a regression signal, not permission to edit the ledgers. If no JUnit
results are present, the report uses NOT_OBSERVED instead of inventing PASS.
The local command produces the scope inventory. To reconcile a CI artifact,
pass the downloaded ZIP as `--junit-dir`, its GitHub artifact digest as
`--artifact-sha256`, the companion qualification JSON as `--qualification`,
the tested `--source-sha` and the exact `/actions/runs/<run>/attempts/<attempt>`
`--run-url`. The collector binds the execution window and canonical XML hashes
to that attempt. The tested SHA is the checkout commit. For PRs the collector
also verifies both ordered merge parents against the event payload.

## Evidence needed for a formal PASS

Review all clauses of the exact assertion in acceptance-tests.json. A
qualifying receipt must bind the case ID, actual 40-character tested commit,
reproducible fixture and command, observed environment/backend, named assertions,
workflow/run/attempt or qualified physical session, raw result and artifact
hashes, and reviewed limitations. The existing checker supports the original
case-specific machine fixture receipt shape under evidence/. It now rejects
passing-case references to arbitrary source files or unscoped documentation.
If a test is simulated, the receipt must not imply a real live model or device.

Task DONE additionally requires all dependencies DONE, all linked cases PASS,
no remaining work, and a manually reviewed evidence/task receipt of schema
unified-reliability-v2-task-verification.v1. Such receipts must state task_id,
implementation_commits, acceptance_ids, and proof fields reviewed, reviewer,
command, artifact_sha256. These are structural checks, not external attestation
verification. Independent artifact/run readback and reviewer approval are still
required before a receipt or ledger status is published.

## Next executable work (do not bulk-promote)

1. **P0 CI fixtures:** finish the remaining assertions for IX05/06, LC-T02/03/04/14,
   CM-T04/05/09/12–16/26 and IX10/14. Link raw named JUnit XML and exact
   platform/tool results. Add synthetic search A/B, truncated and auth/error
   incident replays for LA-QA25–29 with source-preserving transport assertions.
2. **P0 implementation:** complete the actual Combined and continuation
   requirements in V30–V34 (complete source coverage, durable multipass/restart,
   semantic verifier with abstention, non-lossy response, contributor attribution);
   finish diagnostic export consistency and real permission/cancellation fences.
3. **P0 emulator integration:** use .github/workflows/release-0950-acceptance.yml
   and bash scripts/run_release0950_acceptance.sh on prepared API 34 and 36
   emulators. Archive named instrumentation result packets, UI captures, source
   revision and metrics. These cannot replace phone or live-server acceptance.
4. **P0 physical/server:** V47, IX21–26, LC-T17, LA-QA04–06/16/18,
   IX17/29 require the actual serving route, ASUS SM8750 CPU/GPU/NPU/GGUF,
   verified model artifact and backend, real physical dispatch, cold/warm
   measurements, cancellation, thermals, latency and long-context/output
   behavior. Use the dedicated self-hosted .github/workflows/device-validation.yml
   with explicit backend and QNN inputs. No backends requested means NOT_QUALIFIED.
5. **P0 final:** V46/V48 cross-mode and production release qualification, exact
   commit and signature/ABI/signed binary evidence and migrations. Main branch
   protection is now verified above; recheck the rule and required-check identities
   at merge/release time. Do not release with false green checks.

Issue #674 still requires reviewed completion of the original 48 tasks and
99 outstanding formal cases, including V46/V47/V48. This report does not change
remote issue state or rules. This branch's changes do not claim that any of the
99 cases have newly passed.
