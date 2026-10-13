# Memory, diagnostics, and delegation repair implementation

Branch: `feat/memory-rebuild-diagnostics-2026-10-12`
Draft PR: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/668
Baseline: `1d4dcd8698356abddc5f1be946bb0811017b7d7a` (0.9.51.0).
Implementation date: October 13, 2026 UTC.

This file records implemented behavior and the available verification. The delegation design remains the acceptance specification. A unit-test mapping below does not claim that every device, provider, accessibility, or performance condition in a T-row has been qualified. No release version or application ID changes are included.

## Implementation

### Durable memory

`MemoryRepository` and `MemoryDao` use the existing Room database with an explicit 37-to-38 migration and committed schema 38. Canonical fact payloads are encrypted, revision checked, and committed before asynchronous indexing. Legacy facts and graph content migrate through a parity-checked cutover. Canonical deletion, suppression, and revision history survive portable encrypted backup/restore. A stale edit cannot overwrite a newer revision. Fact vectors are rebuildable in-memory derivatives; existing document-vector storage is unchanged.

`FactVaultRepository`, local graph tools, backup integration, and the memory screens use the canonical store. Facts retain type, validity, provenance, and local-only policy. Local-only memory in a response or revision prevents that history from being forwarded to an ineligible destination. Memory initialization failure does not disable ordinary chat; cancellation still propagates.

Tests: `MemoryRebuildPolicyTest`, `FactVaultRepositoryTest`, `ChatRepositoryImplTest`, `ChatDatabaseV2MigrationsTest`, and `MemoryDurabilityInstrumentedTest`. `LocalSemanticMemoryInstrumentedTest` exercises the packaged encoder and ObjectBox JNI, verifies document-vector reopening, checks that fact vectors are absent from disk and rebuilt from a canonical snapshot, and verifies scope changes and deletion.

### Diagnostic reliability

Deterministic native tensor setup failures are retained as readiness failures until configuration change or explicit recheck. Benchmarks skip dependent work after infrastructure failure. Provider retries use structured status and Retry-After information with shared cooldowns; authentication, unknown forbidden, explicit budget denial, and transient failure have different handling. Diagnostics redact sensitive material before truncation, distinguish cancellation from errors, and export explicit schema/provenance fields. Bounded document extraction streams to temporary storage and requires a complete, size-checked response.

Tests: `LocalReadinessRegistryTest`, `ProviderRetryPolicyTest`, `DiagnosticPrivacyTest`, `StructuredDiagnosticExportTest`, `ReadUrlToolTest`, `UnifiedMemoryToolTest`, and benchmark regressions. Native readiness caching is not proof of successful inference on the incident device/model combination.

### Delegation closure map

| Design items / acceptance targets | Implemented production behavior | Regression evidence |
| --- | --- | --- |
| D01, D10 / T01–T03, T25 | Persist known writer denials by configuration identity. Continue is blocked before tool preparation; explicit retry rechecks the writer while retaining saved evidence. Unknown 403 stays unknown. | `ChatRepositoryImplTest.knownForbiddenWriterBlocksContinueBeforeToolPreparationAndExplicitRetryRechecksOnlyWriter`; `DelegationRepairContractsTest`; `ProviderRetryPolicyTest` |
| D02 / T04–T06 | Continue and the Retry button resolve the original source message. A serialized `TaskResumeDescriptor` carries root and generation identity separately from the task. Original research state and budgets remain under their authority scope. | `TaskRecoveryActionTest`; `ResearchSessionStoreTest` restart, authority, and repeated-button-retry cases |
| D03 / T07–T10 | Preserve cancellation provenance, unknown origins, stop-and-summarize behavior, persistent generation fencing, and terminal compare-and-set ownership. | `DelegationRepairContractsTest.cancellationCauseIsFirstWriterWinsAndUnknownIsNotUserStop`; `AndroidResearchWorkflowTest` cancellation cases; `ResearchSessionStoreTest` fencing/deletion cases; existing coordinator/persistence tests |
| D04 / T11–T13, T42 | Admit required independent review before acquisition. Unknown canonical independence cannot pass. Preview uses the same reviewer reservation and helper selection, with exclusion reasons. Off, Best effort, and Required remain explicit. | `ReviewerPolicyTest`; `DelegationRepairContractsTest`; existing scoped policy tests |
| D05 / T14–T16 | Versioned planning accepts explicit legacy adapters without losing evidence requirements. Invalid types, references, contradictory empty decisions, and unknown versions fail validation. One bounded repair precedes marked deterministic fallback. | `ResearchRepairContractsTest`; `AndroidResearchWorkflowTest.invalidPlanDoesNotClaimResearchWasUnnecessary` |
| D06–D07, D13 / T17–T20, T34 | Collection counts, question coverage, exact quote verification, passage verdict, relevance, publication suitability, and independent review are distinct. Readable quota alone produces partial coverage. Applicable contrary/uncertain evidence is retained. | `ResearchRepairContractsTest`; `AndroidResearchWorkflowTest`; `DelegationActivityTest` |
| D08 / T21 | Per-source-version extraction checkpoints and per-claim verification state let an unavailable academic verifier resume without planning, extraction, reads, or duplicate claims. | `AndroidResearchWorkflowTest.restoredAcademicVerifierResumesOnlyVerificationAfterCollectionGoal` |
| D09 / T22–T24 | Manifest and findings precede the long source listing. Handoffs preserve complete claim/quote blocks, included source references, review metadata, and omission counts. A saved review report and final-writer handoff bind to an exact corpus digest; changed evidence cannot inherit an old pass. Saved context is paged with Unicode-safe offsets. | `DelegationEvidenceTest`; `ResponseRecoveryContextTest` |
| D11 / T26–T29 | Child capabilities intersect parent and helper connection/tool identities. Ambiguous aliases fail closed; helper connection authorization is rechecked. Gateway-local execution cannot escape an explicit inherited catalog. Saved-evidence reads recheck conversation/profile/connection authority; sensitive health results are not replayed. | `ChatRepositoryImplTest.helperCapabilitiesIntersectOwnersAndRejectAmbiguousAliases`; `ResponseRecoveryContextTest`; `ResearchSessionStoreTest.privateAuthorityAndSharingOptOutIsolateCorpora`; existing tool resolver tests |
| D12 / T30–T33 | Existing research ledger gains durable idempotent reservations for logical research operations, worker/model calls, and physical requests. Resume cannot reset the original limits. Review allowance is preserved. Primary output budgeting remains independent of helper handoff size. | `ResearchRunLedgerTest`; `LocalDelegationCoordinatorTest`; existing budget and primary response tests |
| D14 / T35 | Delegation events join physical invocation, root, step, attempt, and role identifiers through the existing activity store and invocation ledger. Delivery is an independent stage outcome. | `DelegationActivityTest`; ledger/coordinator tests |
| D15 / T36 | Claims persist in hashed corpus chunks with a versioned index. The old 30-claim persistence tail loss is removed. Review checkpoints source versions and completed verdicts across batches. | `ResearchSessionStoreTest.pagedClaimsSurviveManifestLimitRestartAndDeletion`; `ResearchRepairContractsTest.moreThanThirtyClaimsAndVerificationUpdatesSurviveStorageMerge` |
| D16 / T37–T40 | Preserve existing usable partial child output, tool-free reviewer requests, bounded malformed-output repair, native admission, lifecycle cancellation, and exactly-once terminal ownership. | `LocalDelegationCoordinatorTest`; `ResearchSessionStoreTest`; existing native/coordinator/persistence regressions |
| D17 / T41–T43 | Saved-evidence card shows writer failure separately, exposes evidence and focused retry actions, and wraps controls on narrow layouts. Preview labels model-call versus logical-operation limits. Activity distinguishes output/review/delivery. | Source integration plus existing compact/large-font/RTL emulator acceptance; comprehensive new-screen accessibility qualification remains open |
| D18 / T29, T44 | Synthetic public fixtures, scoped recovery, live authorization checks, deletion fences, temporary-chat boundaries, and redacted diagnostic metadata. | `DiagnosticPrivacyTest`; `ResponseRecoveryContextTest`; `ResearchSessionStoreTest` |

The synthetic incident-shape persistence fixture retains 207 source records, 12 readable records, the 5/6/3 claim verdict distribution, unavailable-review/writer-403 notes, and 24 spent operations through restart and Continue. `ChatRepositoryImplTest.incidentReplayRetainsEvidenceAcrossTwentyFourHelperRequestsWriter403AndContinue` additionally scripts 24 physical helper requests through the production repository/coordinator, followed by writer 403, Continue, and explicit finalization retry. It starts from completed acquisition, asserts that no completed reads repeat, and contains no private export content.

## Migration and rollback

- Room schema 38 adds canonical memory records. Existing messages, tool receipts, and public IDs remain intact.
- Research snapshots use schema 4, the cumulative ledger reads schema 3 and writes schema 4, and claim corpus chunks are versioned independently. Missing/corrupt chunks and incompatible fingerprints fail closed rather than resetting allowance.
- Retry metadata uses the existing serialized timeline column; it does not require another Room migration.
- Activity/debug exports carry new versions with backward-compatible defaults. Historical `Supported` claims never become independently reviewed automatically; unknown cancellation remains unknown.
- `orchestrationVersion` / `orchestrationEnabled` provide a disable path. Dispatch stops while checkpoint readers remain available. Primary-only recovery is explicit and does not authorize a new cloud destination.

## Recorded validation

CI and artifacts are attached to PR #668. Earlier failed runs are retained as regression evidence; they must not be presented as current passing qualification.

At commit `f7dcb7a3d5a52e52472ea4325b2d7831a2a3d3e3`:

- Kotlin lint and CodeQL passed.
- Focused Marketplace contracts passed (156 tests).
- Android acceptance and review APK build passed: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/38347604784
- Both real Android/Room memory tests passed: encrypted cutover/stale writes and portable restore/deletion non-resurrection.
- The existing compact-width/large-font/RTL editor acceptance passed.
- Performance recording reported gaps; performance remains unqualified.
- The full JVM suite ran 2,611 tests and exposed nine failures. Subsequent commits correct the empty-DAO fixture path, benchmark relevance fixtures, and synthetic claim construction, and add further authorization/resume regressions. Use the latest PR check results for the final suite result.

Locally, schema history, Android regex compatibility, changed-file ktlint, and whitespace checks passed. Focused source-level JVM harnesses passed memory/policy (49 tests), research contracts (20 tests), and durable budgets (12 tests). These harnesses stub Android storage boundaries where necessary and are not substitutes for Android CI. The local full Gradle build could not resolve the Android plugin in this environment; full builds run in repository CI.

At commit `9826dbf4b97c4c16c547a3a9b3de042d9403dd79`, the expanded JVM suite passed 2,625 of 2,626 tests. The new incident replay had omitted the primary profile's delegation opt-in; the following commit corrects that fixture and adds review-artifact retention coverage.

At commit `8e854b86f80c2e614695cc6f21efed1167eac789`, all **2,628 JVM tests passed, with zero failures, errors, or skips**, including the production-path incident replay: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/38353935487. Kotlin lint, Marketplace contracts, and CodeQL passed. The native smoke test exposed an obsolete assertion that fact vectors survive independently on disk; the following change tests canonical rehydration and retained document-vector durability instead, and fixes a fact-cache scope-change defect. Use the latest PR checks for that native regression result and the final Android build/acceptance status.

## Qualification still required before release

The draft is not a release-qualification assertion for all T01–T44 combinations. Outstanding device/provider work includes the incident phone and exact local model, private-server and authorized cloud end-to-end runs, process death at every stage, airplane/reconnect and storage/memory pressure, complete new-screen accessibility/theme/scroll qualification, and paired quality/latency/cost benchmarks. Live billable tests have not been run. Review every mapped risk against the design before marking its D-item closed.

The scripted incident replay is deterministic CI coverage; it does not qualify live provider behavior or reconstruct missing wire events. Automatic repair of credentials/endpoints, permission expansion, and guessed historical authority are not supported recovery mechanisms.
