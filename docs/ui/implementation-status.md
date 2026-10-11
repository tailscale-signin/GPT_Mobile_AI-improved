# UI renovation implementation status

Specification: `GPT_Mobile_AI_Complete_UI_Redesign_2026-10-11.docx`, transcribed in `redesign-specification.md`.
Baseline: `33002673dd5c55f252ee69717af4b124bebe9785`.
Branch: `feat/complete-ui-renovation-2026-10-11`; draft PR #653.

This is a working implementation record. A source change, a compiled test, and a successful device exercise are different kinds of evidence. No unexecuted device, accessibility, performance, backup or visual scenario is marked complete.

## Delivered in the branch

- Four root destinations with compact bottom navigation and a rail at 600 dp; focused Activity, delegation, conversation preferences, privacy/data and feature category routes. Existing workspace and deep-link owners remain available.
- Five paired colour families, independent accent roles, shared shapes/type/spacing, atomic appearance drafts, saved classic themes and legacy ARGB preservation. Reduced motion covers route transitions, shared sheets, disclosure effects and decorative chat motion, and respects Android's animation preference.
- Conversation search and hierarchy, full-window multi-profile selection, neutral assistant reading surfaces, explicit export scope, independently preserved Stop/Send controls and the existing streaming/entry-position controllers.
- AI profile/connection organization, Behaviour/Tools/Advanced sections, focused local Installed/Discover/Runtime views, delegation sections, memory hierarchy, tool hub/marketplace organization and adaptive GitHub file/editor panes.
- Shared bounded choice sheets and draft editor shells. Provider reset actions remain explicit. Structured input, permission decisions, source inspection, recovery and tool configuration use their appropriate shared shell. Broad grants retain the actual backend scope.
- Stable settings keys and defaults, canonical feature routes, preserved provider option JSON fields, draft restoration for non-secret fields and retained secret-storage ownership.

## Evidence and failures found

| Evidence | Result |
| --- | --- |
| Baseline source/manifest scan | `ui_surface_inventory.json`: source inventory includes named surfaces, controller callbacks, inline overlays and Activities; each has an explicit current status. |
| Feature/default mapping | `setting_parity.csv`: all 29 AppFeature and 11 DebugMetric keys. Broader dynamic/editor parity is still tracked by surface review. |
| CI on `1d2d2aa` | Kotlin lint passed; app compiled; 2,298 unit tests executed, 2,297 passed. One delegation test still targeted the removed layout. Device compilation found an incorrect test import. Both are corrected in the following checkpoint. |
| Local compiler | Successful non-incremental application compile. An incremental duplicate-symbol result did not reproduce in the clean CI build. |
| Local full unit suite | Not accepted as a green result: this execution environment could not initialize ByteBuddy's runtime attachment for many MockK tests. The CI suite is the authoritative full-suite run. No test was disabled to hide this environment failure. |
| Emulator workflow | Added focused real Compose tests for selection IDs/mode, disabled profiles, grant scope, editor discard/save, large text/RTL, and existing composer regressions; execution and screenshots must be checked at the final head. |

## Remaining release evidence

The PR stays draft while coverage and final-head verification are incomplete. Remaining work includes individual auxiliary/inline surface review, broader process-recreation and upgrade/backup evidence, visual inspection of actual device screenshots, full accessibility interaction review and measured release-like performance on the specified physical devices. The source ledger explicitly retains pending classifications rather than treating shared theming as proof of completion.

A release is eligible only after the document's acceptance gates and the repository's required checks are satisfied. Merge/version/release automation must operate on the verified commit and retain signature/runtime/privacy checks.
