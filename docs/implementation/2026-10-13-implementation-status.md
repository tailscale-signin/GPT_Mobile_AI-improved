# October 13 implementation and release record

Integration base: `379e014da8b42e6caeb7b4f415a84bcf149ad140` (PR #668).
Branch: `feat/unified-runtime-local-first-2026-10-13`.
Release candidate: `0.9.52.0` (`121`); published state is determined by the signed-release workflow, not this document.
Runtime dependencies remain pinned; normal application identity remains unchanged.

## Work packages

| Package | Requirements | Implementation | Validation |
|---|---|---|---|
| Profile picker | SP-01–SP-16, U01–U22, C01–C18 | Compact UID-keyed picker, details, labels and refresh lifecycle implemented | Unit/Compose CI pending; first compile import corrected |
| Native usage and Assist lifecycle | GGUF F03–F10, T05–T20; UAR-02, 05, 08, 17 | Native segment accounting, explicit provenance, helper ownership cleanup and readiness implemented; exact span handoffs and producer/consumer joins implemented | Regression fixtures added; Android CI pending |
| Normal-app GGUF | GGUF F01–F02, T01–T04, T21 | Pinned CPU JNI module, v3 neutral imports, compatibility routing and inventory implemented | Host JNI real generation and release-minified Android 16 x86-64 JNI acceptance passed |
| Unified execution policy and budgets | UAR-01–UAR-18 | Opt-in policy, existing-ledger transactional model reservations, retained root limits, synthesis reserve, subscriber-owned shared reads and exact span handoffs implemented | Regression tests added; Android CI and broader qualification pending |
| Research settings and statistics | RQ-01–RQ-04, NAV/UI/MET | Shared three-tab feature editor, scoped policy, method-safe metrics, durable receipt storage, percentiles, epoch clear and receipt drilldown implemented | Archive/appraisal contract tests pass; durable statistics regression and schema export pending CI |
| Research archive and restore | RQ-05–RQ-07, RQ-13–RQ-14, ARC/FRESH/DATA | Transactional FIFO 1,000-work membership, immutable versions, separate retention roots, metadata preflight, scoped legacy migration, encrypted portable sections and idempotent restore implemented | FIFO, authority, clear-epoch and Unicode fixtures pass; portable backup and statistics tests pending CI |
| Citations, Examine and appraisal | RQ-08–RQ-12, CIT/EX/APP/AI/SEC | Strong-ID citation bindings, article cards/details, application-owned examination jobs, exact-quote batch validation, independent review/resume and five bounded evidence tools implemented | 2,664/2,666 unit tests passed on commit 64d28c4; two opt-in permission fixture setup failures corrected. Broader production-path and device qualification remain in progress |
| Draft PR, CI, merge and signed release | Exact-head validation and signed publication | Draft PR #670 open; merge, version bump and signed release pending | Exact-head release gates remain required |

## Ownership map

ChatRepositoryImpl owns response entry, canonical run identity and final synthesis. Existing InvocationLedger owns physical model records. ResearchRunLedger owns durable research reservations. SharedToolCallBroker owns authorized read-only producers. ResearchSessionStore owns run corpus bodies/checkpoints. LocalRuntime/LocalEngineHolder own engine and conversation lifetimes; process runtime owns IPC. Settings repositories own policy persistence. New services adapt these owners rather than duplicating their dispatch or accounting.

## Qualification

Implementation, deterministic fixtures, Android emulation, physical-device qualification and live-provider measurements are recorded separately. No physical ROG Phone, live-model cost comparison or scientific expert adjudication is claimed from CI. Unknown backend, usage, publication and coverage states remain unknown.

## Picker caller inventory

Picker: HomeScreen → SelectPlatformDialog. Insights: NewChatInsightsViewModel and NewChatProfileInsights. Labels: ProfileLabelComponents editor, HomeScreen conversation labels/filter, PlatformCheckBoxItem, AddPlatformScreen, PlatformSettingScreen and AiPlatformsScreen. FadingAlertDialog wraps Material AlertDialog; the new lazy picker uses a bounded dialog surface.

## GGUF host evidence

Production JNI built against immutable llama.cpp `4413a192410fad1f054e9216114087a3e01527a0` and passed three warm generations, partial cancellation counters, context-bound rejection, stale handle rejection and reopening on Linux x86-64. `scripts/prepare_gguf_smoke.py` verifies the upstream tiny fixture and derived metadata-only ChatML fixture hashes. This establishes host ABI/protocol behavior, not Android device speed, scientific quality, GPU/NPU support or phone qualification. The fixture is not shipped in the APK.

## Native Android acceptance

GitHub Actions run `38371508401`, job `115209365360`, passed the actual minified Android 16 x86-64 application/test boundary. The instrumentation protocol independently required the memory JNI test and `GgufNativeInstrumentedTest.packagedCpuRuntimePreservesRepeatedSessionsAndPartialUsage` to start, pass and finish. This covers repeated CPU inference, partial cancellation counts, context overflow, stale handles and reopening. It does not qualify physical ROG hardware, GGUF GPU/NPU execution or live model answer quality.

## Research migration and recovery limits

Historical source manifests without provable public authority migrate into their original chat scope; migration does not promote old Supported claims into independent appraisal. Source text, appraisals, independent review and figure/supplement coverage remain separate. Native examination explicitly reports unsupported PDF/gateway routes and incomplete figure/supplement coverage. Required review pauses before reading if canonical independence or permission is unresolved. A completed rejected review is retained, not retried for a better verdict.

Gateway examination now reuses the approved academic facade, with retained root budget and source validation receipts. Native targeted discovery follows at most two explicit same-origin full-text HTML links and stops on refusal or identity mismatch. Temporary/deleted chats are excluded from persistent article bindings and examination; no temporary body or identifying archive record is exported. Review reports retain artifact hashes, model identity, reasons and physical invocation IDs.

Archive category/topic creation, rename, move and merge, historical study comparison, paragraph-adjacent citations and method/warning settings are implemented. Android acceptance adds light and dark/RTL picker selection and accessibility actions at 320 dp and 200% text size, with screenshot artifacts. The coordinator recovery regression uses real Room/body storage and scripted provider boundaries. A 102-task, two-repeat, randomized paired wrapper fixture writes every sample to `app/build/reports/unified-runtime-paired.json`; it reports actual test timing and request counts, with live-model tokens, quality and costs explicitly unavailable.

Exact-head CI remains the merge gate. Physical ROG qualification, billable live-provider comparison, and expert scientific adjudication cannot be inferred from emulation or scripted fixtures; optimization stays off by default and unsupported routes remain blocked.
