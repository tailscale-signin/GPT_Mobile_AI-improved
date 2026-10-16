# Feature audit remediation — October 15, 2026

Base: `6b19a9338c426733030458c29d0322670472d882`.
Branch: `fix/feature-audit-completion-2026-10-15`.

This is an incremental implementation, not Reliability v2 acceptance or release approval. No acceptance case has been changed to PASS without execution. Main, server protection, version and release status are unchanged by this local branch.

## Changes prepared

| Area | Change | Regression coverage added; execution pending |
| --- | --- | --- |
| Memory recovery | `PreparationReplayFence` recognizes the typed `MEMORY_STALE` preparation failure. Rebuild is allowed once, only before any physical inference or tool attempt. Consent/content are read again through normal preparation. No post-dispatch replay is introduced. | `PreparationReplayFenceTest` |
| Combined inspection restart | Completed per-pass reports are checkpointed and reused after restarting with identical frozen input, system, limits, pinned profile/configuration and dispatch policy. Source/report digests reject stale records. Current permissions are checked before checkpoint reads, saves and final writing. Failed, empty and length-stopped passes are never saved. | `CombinedCorpusMultipassSessionTest` restart and revocation cases |
| Checkpoint persistence | Inspection text is stored in encrypted SecretVault chunks, within the vault's size limit. Room retains only references/digest. New metadata is published after all chunks exist; replaced chunks are removed. Temporary/missing conversations do not retain or restore inspection evidence. Existing conversation deletion and memory-backup reference ownership apply. | `ProtectedSynthesisCheckpointTest` |
| Research numerical grounding | A cited value must match its explicit unit in captured passages. Ordered `A versus B` comparisons cannot be reversed. Bibliographic years and unrelated quantities cannot establish these matches. These are conservative checks, not semantic entailment certification. | `ResearchNumericalGroundingTest` |
| Research permission recovery | A permission/configuration denial during report repair terminates delivery instead of exposing the cached evidence fallback. Cancellation and thrown authorization failures propagate. | `ResearchDeliveryTest.revokedRepairNeverReleasesCachedEvidenceFallback` |
| Permission recovery UI | Blocked current responses offer source-grant review, retry after granting access, and a fresh conversation without saved evidence. The fresh path copies only the original question and selected enabled profile/model. Previous answers, attachments, tool receipts and recovery identities are not copied. Existing profile permissions still apply. No permission is automatically broadened. | UI/device qualification pending |
| Adaptive navigation | Restores the prior draft's four roots: Chats, AI, Tools and Settings. Bottom navigation becomes a rail at 600 dp. Conversation/editor/setup routes retain their focused layouts. Root navigation preserves saved state and avoids duplicate roots. | `AppNavigationShellTest`; device/back-stack qualification pending |
| Activity | Settings now links to an Activity hub for Runs, Usage, Benchmarks and Diagnostics, reusing existing operational screens. Opening this hub starts no inference or diagnostic collection. | Android/UI qualification pending |
| Theme renovation | Five paired light/dark palette families are registered in the existing theme editor with stable IDs. They use the existing preview/apply/discard, contrast and backup mechanisms. | Existing theme contracts; visual qualification pending |

## Correction to the supplied audit

Amazon external price history is already implemented on the baseline. `AmazonPublicHistoryProvider` fetches bounded public Keepa/camelcamelcamel chart images, `AmazonLocalTool.HISTORY` exposes `amazon_get_price_history__free_native`, and the product UI displays public history. A chart is not a numerical history API; neither historical prices nor historical-low claims may be fabricated from it. PR #618 was closed without merging its historical branch, but its functionality was subsequently reimplemented. It should not be resurrected merely to match old filenames.

The Google Scholar registry accurately exposes browser-assisted discovery and citation import. Automated discovery needs an explicit provider integration and access contract. This branch does not relabel web search or another academic engine as native Scholar discovery.

## Work still open

| Requirement | Current limit |
| --- | --- |
| All 48 Reliability v2 tasks | Remain at their existing implementation/qualification statuses. This branch does not close Issue #674. |
| Combined Mode | Attachment/multimodal multipass, complete semantic inventories and targeted repair, bounded final assembly for very large inspection reports, and long-output/process-death qualification remain. Checkpoint reuse requires the same plan; a changed source/policy causes fresh inspection. |
| Scientific semantics | Claim-to-source entailment, outcome/population/intervention identity, negation and full numerical interpretation need a qualified verifier. Unit/order checks do not establish these. `semanticCoverageVerified=false` remains explicit. |
| Contribution attribution | Production semantic sidecar generation/persistence, qualified exclusivity and comprehensive Markdown/Unicode/renderer acceptance remain. |
| Diagnostics | Complete lifecycle projection, cross-store snapshot consistency and consented content replay remain. |
| Scholar automation | Native automated discovery remains unimplemented; browser/citation import is retained. |
| Navigation/recovery/palettes | Compilation, Compose/device checks, accessibility, back-stack behavior and complete renovation screen parity remain. |
| Local Assist / readiness / performance | ASUS CPU/GPU/NPU execution, native recovery, long context, latency, IME/frame/thermal/memory-pressure measurements require actual hardware/services. |
| Release / branch protection | Server protection requires an authorized administrative API; production signing and release qualification remain gated. No gate was relaxed. |

## Validation actually executed

- `python3 -m unittest discover -s scripts/tests`: 116 PASS. This is the repository's Python script suite; its simulated instrumentation output is not physical-device evidence.
- `python3 scripts/check_android_regex.py`: 536 literal patterns checked, 36 dynamic patterns skipped, 0 errors.
- `python3 scripts/check_android_resources.py`: 28 XML files passed.
- `python3 scripts/check_room_schemas.py`: committed schema/migration history consistent; no migration added.
- `python3 scripts/check_unified_reliability_v2.py`: scope inventory remains 48 tasks / 110 acceptance cases.
- `git diff --check`: passed.

Android/Kotlin compilation, Kotlin test execution, lint and device tests were **not run successfully**. The local Gradle bootstrap cannot download `gradle-9.8.0-bin.zip` (`Network is unreachable`); the environment has Java 17 and no configured Android SDK. CI must compile and run the new Kotlin/Robolectric suites before any merge.

The initial push was rejected pending explicit authorization. The user subsequently instructed “Push”. The branch was published through the connected GitHub app after shell Git reported missing credentials; draft PR #690 is open. Follow-up build-121 fixes and validation are tracked in [Diagnostic_Root_Cause_Remediation_2026-10-15.md](Diagnostic_Root_Cause_Remediation_2026-10-15.md). No green-CI or complete acceptance claim is made.
