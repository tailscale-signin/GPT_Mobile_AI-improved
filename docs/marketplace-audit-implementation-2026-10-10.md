# Marketplace reliability and UI implementation

Based on `Marketplace_Local_AI_Plugins_Audit_2026-10-10.md`, compared against main commit `19f696a326fe91b39cd0fcbd19ba208c7a7688bc`.

## User-facing changes

- Marketplace search and filters now include Installed, Needs attention and Downloading. Cards distinguish in-app adapters from remote services and explain internet/key requirements.
- Native integration actions use “Add integration”; installed cards have a single enable control, with the same enablement path used by the installed-plugins settings panel. Setup, service gates, provider cooldown and daily allowance have distinct labels. Profile permissions remain independent.
- Settings show today's request usage and existing (?) help explaining installation, configuration and profile access. Registry corruption has a visible repair action; repair preserves the damaged file and rebuilds only disabled registrations from verified packages.
- Model downloads display Queued and Verifying independently from transfer progress. A persistent Wi-Fi-only preference applies to new/retried downloads through WorkManager constraints. Storage totals include actual partial, orphan and installed files in both supported roots.
- Model cards distinguish publisher checksum availability from device qualification, explain context versus output limits and label NPU packages as candidates. No fabricated performance or verification badge is shown.
- Hub search supports direct `owner/repository` lookup and reports bounded first-page coverage and partial failures. A failed repository no longer discards working results.

## Audit disposition

“Implemented” below describes source changes, not a claim of passing Android/device acceptance tests.

| Audit IDs | Disposition |
|---|---|
| B01, B02 | Implemented: password is transient in the export DTO, protection is omitted unless Settings is selected, and restoration preserves the destination password and ignores protection on partial restores. |
| LM01 | Strengthened existing main implementation: Hub LFS SHA-256 is carried into exact artifact/SoC metadata. Existing digest verification remains. Catalog entries without a trusted digest are explicitly identified; no hashes were invented. |
| LM02 | Implemented: positive catalog byte lengths are authoritative; response lengths cannot replace them. Streaming rejects excess bytes. A wrong catalog size now needs correction rather than silently redefining the artifact. |
| LM03 | Main already uses atomic replacement; retained. Multi-file version activation/rollback remains a separate follow-up. |
| LM04 | Implemented generation guard: persistent per-model work identity plus a shared lock covers writes, cancellation/deletion, database status and activation callbacks. Obsolete workers cannot mutate the replacement generation. Device race/fault-injection coverage remains required. |
| LM05 | Implemented for downloads and resolution: transfer pins internal/external root, resolution and metadata scan both supported roots. Existing internal downloads remain discoverable after external storage returns. |
| LM06 | Physical totals and checked deletion implemented. Separate reclaimable/cache buckets and user-confirmed orphan cleanup remain follow-ups. |
| LM07 | Implemented full case-sensitive repository/file digest IDs. Unambiguous saved provenance retains legacy installed IDs/profile references; ambiguous identities are not guessed. |
| LM08 | Implemented per-repository isolation with cancellation propagation, shared metadata concurrency limit and partial-results notice. |
| LM09 | Direct repository lookup and honest coverage implemented. Cursor-based Load more remains a follow-up. |
| LM10 | Current catalog metadata takes precedence over stored installation metadata. Separate installed/available version comparison UI remains a follow-up. |
| LM11, LM14 | Claims and RAM/context guidance clarified. Exact runtime/ABI capability qualification, measured memory budgets and device tests remain required. |
| LM12 | Foreground promotion failure stops the download and produces an actionable error. A user-initiated transfer-job backend is not introduced. |
| LM13 | Persistent network constraints, streaming maximum and ongoing storage floor implemented. Cross-download reservations/concurrency controls remain follow-ups. |
| LM15 | Progress uses the validated transfer total, clamps percentages and exposes verification separately. |
| LM16 | Atomic cache replacement, input bounds, duplicate-ID rejection and cancellation propagation implemented. Source/freshness metadata and catalog signing remain follow-ups. |
| P01 | Both UI entry points now share serialized enablement with required-settings validation, fail-disabled behavior and feature-gate rollback. Profile restrictions are preserved. |
| P02 | Removed alias-based connection deletion from package uninstall so native integration removal cannot delete an unrelated MCP connection. Persistent originPresetId/installationUid migration and renamed-connection management remain follow-ups. |
| P03 | Explicit disabled repair from verified packages with corrupt-file preservation implemented. Credentials must be re-entered after repair. |
| P04 | Durable uninstall marker, automatic finish-uninstall and recovery of verified but unregistered packages implemented. Full cross-store transactions still require device/process-death testing. |
| P05, P06 | Meaningful-error classification and bounded Retry-After seconds/date propagation implemented. No automatic tool replay was added. |
| P07 | Safe, distinct recovery messages for registry, package verification, storage, connectivity, setup, cooldown and daily allowance. Full diagnostic correlation IDs remain a follow-up. |
| P08, P09 | Deferred: managed OSM auth/custom-port configuration and historical trusted package-manifest migration need separate contract/upgrade fixtures. Current protections remain intact. |
| P10 | Corrected action and execution-location wording; in-app adapters remain compiled into the APK. |
| P11 | Bounded valid JSON retains prioritized identifiers/URLs and reports truncation/unknown fields. Successful oversized records no longer become wholesale tool failure. Full provider-specific normalization and continuation handles remain follow-ups. |
| P12, P13 | Deferred: provider-specific retention enforcement across chat/export and a coherent backup snapshot across every store. Do not consider these controls complete based on this change. |

Existing benchmark, Local Assist, runtime admission and debug-export implementations are retained. No runtime dependency upgrade, heavy automatic benchmark, remote processing fallback or invented model-performance ranking is included.

## Validation

- Focused JVM suite uses production identity, response, transfer guard and path-policy code, plus repository JUnit tests. Covers error:null/empty errors, Retry-After, bounded records/coordinate pairs, collision resistance, cancellation, legacy identity preservation, stale generations and authoritative size enforcement.
- Additional Android/Robolectric tests cover registry repair, startup removal recovery, enablement consistency/validation, backup password exclusion, partial-restore isolation and catalog precedence/cancellation.
- Changed Kotlin files are formatted and checked with repository-pinned ktlint 1.3.1.
- A full local Gradle build is blocked because the required Gradle 9.8.0 distribution cannot be downloaded in this environment. Full Android compilation, Robolectric suite, lint, APK packaging and phone validation must be assessed from CI/device results, not inferred from the focused JVM suite.

## Device review checklist

1. Test small/large font on narrow phones, light/dark/dynamic themes, rotation and TalkBack; all filters/actions must remain reachable.
2. Switch Wi-Fi to metered while a Wi-Fi-only job runs; retry, cancel and delete around EOF/verification; no old worker may publish Ready or recreate deleted artifacts.
3. Use an internally stored model, restore external storage and verify resolution without redownload. Check physical bytes with partials present and test deletion failure.
4. Exercise both plugin enable surfaces, missing/expired keys, cooldown, daily cap, corrupt registry and a process restart during uninstall. Profile restrictions must survive.
5. Export/decrypt plain and encrypted backups with a sentinel saved password; verify no serialized password and no protection change on conversation/tools-only restore.
6. Review the deferred audit items above before a broad marketplace release; this change is not a declaration that all 31 findings or all 15 proposed enhancements are closed.
