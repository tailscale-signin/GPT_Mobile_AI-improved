# Gateway 16 and conversation recovery

Implementation on PR #816, extending `0116890e7e411c544babb4afd6c6d67ff35c3f1b` above `main` at `7db0c735f9e86eaece24513fbaf5c0a624f13822`. The diagnostic and support-bundle repairs in [the session remediation](gateway-session-2026-11-09.md) remain included.

| Request | Implemented behavior | Verification boundary |
| --- | --- | --- |
| Gateway 16 | Runtime, API metadata, package and Windows release names use 16.0.0. New v16 aliases retain v14/v15 compatibility. Stable update selection accepts 16, rejects future unsupported majors and downgrades, and retains trial/rollback state. Historical v15 fixtures stay immutable; v16 fixtures come from an actual local HTTP response. | Local gateway and updater regressions; a published Windows installer and deployment are separate. Existing installed v15 updaters may require manual installation of v16. |
| Smooth preview/summary follow | A single relative moving-target animation follows measured row growth over 1.5 seconds. Newly sent GitHub work follows the bottom. Upward user intent still detaches; downward return or Jump to latest reattaches. Reduced motion is honored. | Numeric motion/intent tests locally; Compose timing, interruption and layout tests in Android CI. |
| Inline interaction | Saved-patch review, tool permissions, personal-data consent, delegation choices, questions and multiple choice render after the conversation and queued prompts. Actions use the existing native approval/input callbacks. Tool approvals and MCP input retain the initiating run and are filtered to its conversation; workspace inputs remain separate. Long details scroll inside a bounded card; actions wrap. Exact GitHub approvals never expose remembered permission. | Native Compose action tests include exact approval and schema-validated multiple choice. No automatic permission grant. |
| Premature incomplete response | Previously, execution correction only applied before any new receipt existed. After real progress, a partial verification can now resume the first unfinished requirement using the selected primary profile, current tools, retained receipts and original limits. Up to three progress-triggered corrections are allowed per session, in addition to the existing initial correction. Repeated receipts/cache hits do not unlock another correction. | Behavioral session test continues after the first successful read and refuses another correction without progress. Budget, approval, authorization, unknown-outcome and verifier-failure gates remain intact. Completion still requires acceptance evidence. |
| Gateway conversation icon | Gateway conversations use the menu's cloud shape. Unread replies are filled and static; working rows use a filled pulse; read idle rows use the outline. Motion obeys lifecycle, animation and frame-pressure settings. Gateway unread state now comes from durable publication receipts. | Shared row implementation and native tests; physical-device performance remains unmeasured. |

The recurring generic “Partial response delivered” message is replaced by measured acceptance counts and the relevant recovery condition. The existing recovery action is labelled **Resume saved task**. A genuine unresolved requirement remains partial: this repair does not convert missing evidence or a blocked prerequisite into success.

## Actual job state, not retained identifiers

The gateway keeps a job ID as a recovery reference after completion. Version 16 adds `job_state`, read from the principal-scoped engine record in the same snapshot transaction. Android uses known running states for animation; a missing state on older gateways, cached inventory, a queued job or an approval pause does not establish generation. Local mirrors also use the matching server observation when the phone-side run has detached.

One lifecycle-owned browser observer joins the existing in-flight refresh, polls every 10 seconds during active work and every two minutes while idle, and backs off through 30/60/120 seconds after failures. Leaving the screen cancels observation, never the durable job. Tests cover terminal job IDs, principal isolation, decode compatibility, polling cadence and manual-refresh coalescing.

## Validation

Final host validation: 730 gateway Python cases (729 passed, one unavailable LAN-interface skip), seven JavaScript tests, 446 fast-gate tests, 200 recovery tests, 62 MCP tests, 51 gateway-client tests and 12 motion/job-state tests passed. Changed Kotlin files passed ktlint 1.3.1.

The source-bound receipt is [evidence/gateway16-chat-2026-11-09/validation.json](evidence/gateway16-chat-2026-11-09/validation.json). Counts overlap; they are not a whole-app coverage score.

The first gateway aggregate exposed one runtime test still comparing against the historical v15 compact fixture. It was corrected to a separate v16 fixture; the historical fixture remains unchanged. The affected 25 runtime tests passed afterward. The continuation host harness was updated to compile the current fixture/recovery dependencies before its 200 tests passed.

Full local Android validation cannot download Gradle 9.8.0 (`java.net.SocketException: Network is unreachable`). Android compilation, Room/Hilt wiring, Compose behavior/rendering, APK checks and emulator execution therefore remain assigned to exact-head CI. No live phone, deployed model, real GitHub mutation or published release is inferred from host fixtures.
