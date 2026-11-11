# Unified Reliability v2 — acceptance reconciliation and execution

Reviewed 2026-11-10 against main at 7db0c735f9e86eaece24513fbaf5c0a624f13822
and PR #816 at 5190aac3564319ca95a26c09552d06f9aa0622a2.
These are historical snapshots; each CI report records the source revision it tested.

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
Issue #674 is closed despite its unfinished closure conditions; reopening and
editing that issue requires separate user approval.

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
      --tests 'dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCacheTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.ProgressWatchdogSessionTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.context.CombinedCorpusMultipassTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.CombinedClaimInventoryTest' \
      --tests 'dev.chungjungsoo.gptmobile.data.agent.CombinedSynthesisPlannerTest'

    python3 scripts/unified_reliability_v2_reconcile.py \
      --junit-dir app/build/test-results/testDebugUnitTest \
      --source-sha "$(git rev-parse HEAD)" \
      --output build/reports/unified-reliability-v2/reconciliation.json

The existing PR Validation job runs this reconciliation after the JVM test
step and uploads the JSON alongside raw JUnit XML. A failed/missing candidate
probe is a regression signal, not permission to edit the ledgers. If no JUnit
results are present, the report uses NOT_OBSERVED instead of inventing PASS.
Artifact paths and XML SHA-256 digests permit independent reviewer readback.
A current run's checked-out SHA may be a PR merge revision, not its head; the
report explicitly records the actual checkout.

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
   commit and signature/ABI/signed binary evidence, migrations, and a verified
   server-side main branch protection rule. The branch API showed protected=false
   with no active rulesets at this review. Do not release with false green checks.

Do not modify issue #674 or main branch protection as part of this acceptance
report without separate approval. This branch's changes do not claim that any
of the 99 cases have newly passed.
