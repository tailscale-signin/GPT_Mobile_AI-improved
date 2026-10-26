# Chat export recovery fixes — October 26, 2026

## Evidence and scope

Reviewed all five files in `export_New Chat_1793032951407.zip`: ten messages, six selected-chat runs, twenty model invocations, sixteen tool events, 517 journal events and 500 recent app-wide log entries. Export build: 0.9.61.0 (129), commit ca9b20d9. The export predates this branch's repairs and is a partial capture, not a complete provider wire trace.

## Findings and repairs

| Finding | Evidence | Repair or disposition |
| --- | --- | --- |
| Recovery changes reasoning policy and is rejected | App-wide turn 5:19 stalls for 180,188 ms with zero output; its `stream_interrupted_continuation` fails after 103 ms with `REASONING_OFF_UNSUPPORTED` | Text-only recovery now disables tools while preserving the original reasoning allowance and all other request constraints. Explicit user OFF and hard stage restrictions remain enforced. |
| Empty watchdog expiry triggers another unproductive attempt | The same turn attempts continuation despite having no saved answer | Do not automatically continue a watchdog expiry without visible saved output or completed evidence. Keep the structured deadline and terminal failure. A stall after useful saved output can still resume, using its structured deadline rather than relying only on message wording. Ordinary bounded overload/socket recovery remains available. |
| GitHub changes fails opaquely | `github_context(action=changes)` omits `base_sha` and returns `Failed requirement.` | Validate the required base before repository resolution or network work; return `CHANGES_BASE_REQUIRED` with the comparison field and PR-context/status alternatives. Document the conditional requirement in the exposed schema and tool description. Never guess a comparison base. |
| Renderer trace receipts are dropped | Export reports 58 process-session validation rejections; current renderer emits `10:pending` and longer source/block identities while the journal accepts only numeric IDs | Hash composite part identities before persistence and accept only numeric legacy IDs or fixed-format SHA-256 IDs. Corresponding input/transform events retain correlation without raw source IDs. This mismatch is confirmed in code; the aggregate counter cannot prove that all 58 rejections have this cause. |
| Branch CI formatting failure | Current branch's McpCatalogueDeadlineTest has three statement-wrapping violations | Apply ktlint formatting. |
| Empty local multi-chat replies | Selected-chat local request is cancelled after about 101 seconds; another local request and remote request are cancelled after about four seconds | Preserve cancellation semantics. The capture does not prove a successful response or identify the initiating cancellation action. Do not convert cancellation into automatic retry or success. |
| Local server accepts but emits no useful output | llama.cpp returns HTTP 200/SSE, while retained local invocation has no visible/reasoning output before cancellation | Acceptance is not completion. Recovery changes above prevent the confirmed secondary failure; server-side prefill/queue performance remains unverified without server telemetry. |
| GitHub review misses source problems | Remote answer reports checks still running and summarizes PR areas; it does not inspect enough source to establish that no defects remain | Not evidence of a clean source tree. This repair audits source and fixes the reproducible issues; repository issue count is not a quality gate. |
| UI stall | One retained frame sample has max 107.55 ms and one stall over 100 ms | Observation only: the capture does not identify the blocking operation or establish blur/driver causality. No speculative rendering change. |

No local-runtime incidents or new on-device GGUF GPU failure is retained in this export. HTTP llama.cpp traffic is a server route, not proof of phone GPU execution. Existing branch GPU fixes still need physical-device verification.

## Verification

- Full branch changed-source ktlint 1.3.1, `git diff --check`, and resource preflight pass.
- Ten focused assertions pass against production reasoning/constraint and renderer-ID helpers, using small infrastructure stubs.
- Production recovery-session smoke scenarios cover empty-watchdog rejection, saved-output continuation and bounded ordinary overload retries, using synchronous flow infrastructure stubs. These do not establish coroutine timing or Android integration behavior.
- Added real regression tests in OutputLimitRecoverySessionTest, LlamaReasoningAdmissionTest, RunDiagnosticJournalTest and GitHubPersistentEvidenceTest.
- Android unit-test execution is blocked locally: Gradle 9.8.0 distribution download fails with `Network is unreachable`. GitHub CI must compile and run the Android tests. No physical-device performance or GPU success is claimed.

## CI follow-up

The first full Android run compiled successfully and executed 5,032 tests, with 11 failures and four skipped. The failures were concentrated in three suites:

- Five AndroidResearchWorkflowTest cases asserted the previous 256-token extraction prompt literally. Update the shared fixture to require the compact one-claim instruction, 80-character claim bound, 64-character exact-quote bound and complete JSON within 256 tokens. Existing packet completion, malformed row, overflow, cursor, acquisition and budget assertions remain in force.
- ExaminationRetrievalLifecycleTest's interrupted-model case captured an incompletely proven source but assumed the reader would never run. Give this transport-isolation fixture explicit full-text capture proof and assert that coverage before model execution. Incomplete-source recovery remains enabled in production; partial model output must still stay unaccepted and retain its transport-failure checkpoint.
- Five ResearchArticleViewModelTest failures exposed real background-dispatch scheduling in tests and obsolete expectations that no settings mutation occurs on consent dismissal. Inject a qualified IO dispatcher while retaining the production IO default; assemble details in IO and publish only on the main view-model coroutine. Tests share a controlled scheduler. Consent tests now assert saved OFFERED/DISMISSED receipts and verify that decline or stale acceptance grants no academic permission and starts no job.

All 11 reported failures are addressed by these changes; their runtime verification requires the next Android CI run. Local Gradle remains blocked by the unavailable distribution download. Formatting and Android regex checks pass locally.
