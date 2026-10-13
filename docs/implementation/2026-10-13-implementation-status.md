# October 13 implementation and release record

Integration base: `379e014da8b42e6caeb7b4f415a84bcf149ad140` (PR #668).
Branch: `feat/unified-runtime-local-first-2026-10-13`.
Runtime dependencies remain pinned; normal application identity remains unchanged.

## Work packages

| Package | Requirements | Implementation | Validation |
|---|---|---|---|
| Profile picker | SP-01–SP-16, U01–U22, C01–C18 | In progress | Pending |
| Native usage and Assist lifecycle | GGUF F03–F10, T05–T20; UAR-02, 05, 08, 17 | Pending | Pending |
| Normal-app GGUF | GGUF F01–F02, T01–T04, T21 | Pending | Pending |
| Unified execution policy and budgets | UAR-01–UAR-18 | Pending | Pending |
| Research settings and statistics | RQ-01–RQ-04, NAV/UI/MET | Pending | Pending |
| Research archive and restore | RQ-05–RQ-07, RQ-13–RQ-14, ARC/FRESH/DATA | Pending | Pending |
| Citations, Examine and appraisal | RQ-08–RQ-12, CIT/EX/APP/AI/SEC | Pending | Pending |
| Draft PR, CI, merge and signed release | Exact-head validation and signed publication | Pending | Pending |

## Ownership map

ChatRepositoryImpl owns response entry, canonical run identity and final synthesis. Existing InvocationLedger owns physical model records. ResearchRunLedger owns durable research reservations. SharedToolCallBroker owns authorized read-only producers. ResearchSessionStore owns run corpus bodies/checkpoints. LocalRuntime/LocalEngineHolder own engine and conversation lifetimes; process runtime owns IPC. Settings repositories own policy persistence. New services adapt these owners rather than duplicating their dispatch or accounting.

## Qualification

Implementation, deterministic fixtures, Android emulation, physical-device qualification and live-provider measurements are recorded separately. No physical ROG Phone, live-model cost comparison or scientific expert adjudication is claimed from CI. Unknown backend, usage, publication and coverage states remain unknown.

## Picker caller inventory

Picker: HomeScreen → SelectPlatformDialog. Insights: NewChatInsightsViewModel and NewChatProfileInsights. Labels: ProfileLabelComponents editor, HomeScreen conversation labels/filter, PlatformCheckBoxItem, AddPlatformScreen, PlatformSettingScreen and AiPlatformsScreen. FadingAlertDialog wraps Material AlertDialog; the new lazy picker uses a bounded dialog surface.
