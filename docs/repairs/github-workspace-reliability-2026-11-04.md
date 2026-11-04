# GitHub workspace reliability repair — November 4, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`
Branch: `fix/github-workspace-reliability-2026-11-04`
Pull request: #800
Starting revision: `2585da69c5f07ee7be24e4085f8658764614c08f`
Observed base revision: `d97a1e54b9357d592bc9a77363c31b0f860a943f`

## Purpose

Complete the implementation work behind the November 4 diagnostic review without changing the user's authorization, increasing accepted tool budgets, losing evidence, repeating an uncertain mutation, or treating successful API calls as completed acceptance criteria.

The starting revision's CI ran 6,237 tests: both new pagination regressions failed, with four other tests skipped. Its changed-source formatting check also failed ([original run](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41836988322)). The original branch had pagination and integer-boundary safeguards plus their tests. Its pagination change contained literal `\n` characters in a comment, leaving the intended cap commented out; this repair makes the guard executable and covers bounded and exhaustive cases.

## Implementation coverage

| # | Diagnostic area | Implemented behavior and principal regression coverage |
|---|---|---|
| 1 | Streamed tool-batch overflow | Supported provider requests disable parallel tool generation; instructions bound batches to eight. Chat Completions, Responses and Anthropic assemblers reject a ninth distinct tool slot before exposing any partial batch. `ProviderAssemblyBoundsTest`, `ChatStreamIncidentTest`, `ProviderToolRequestSerializationTest`. |
| 2 | Complete accepted tool allowance | One policy resolves user, conversation, profile and task caps before tool binding and inference. A protected atomic counter reserves each admitted call before dispatch, including nested acquisition and synthesized discovery catalogues. Automatic recovery, ordinary Resume/Retry and text continuation retain spent calls under the original task identity; an additional allowance requires a separate explicit grant. `ToolCallBudgetCheckpointTest`, `ToolBudgetScopeTest`, `GitHubExecutionLimitsTest`, `TaskRecoveryActionTest`. |
| 3 | Nonempty terminal response | Deterministic delivery boundaries handle preparation and workflow failures without another model call. They preserve the failure, emit a truthful nonempty incomplete response when necessary, and suppress duplicate terminal delivery. Cancellation remains cancellation. `GitHubTerminalDeliveryTest`, `GitHubApiDeliveryTest`. |
| 4 | Final-review capacity | Review measures the full serialized envelope, retains all receipt fragments, splits long candidates, and resumes saved verdicts. A saved capacity failure can resume after a real capacity increase. `GitHubReviewedSessionTest`. |
| 5 | Durable interruption recovery | Task evidence and an incomplete acceptance ledger are written before generation, after observed tool results and on failure. Tool allowances, review verdicts and corrected sections survive recreation. Existing operation journals remain the authority for mutation reconciliation. `GitHubAcceptanceLedgerTest`, `GitHubVerifiedSessionTest`, `ToolCallBudgetCheckpointTest`, `GitHubReviewedSessionTest`. |
| 6 | Acceptance controls completion | Engineering verification requires the implemented SHA, latest observed workflow attempt, complete job pages and passing job outcomes. API observations alone cannot satisfy product requirements. `GitHubRequestCompletionTest`, `GitHubAcceptanceLedgerTest`. |
| 7 | Local service/TCP recovery | Bounded read-only connection preflight performs at most three checks and bounded cooldown waiting, respects cancellation/deadlines, and never substitutes an endpoint. Potentially dispatched gateway-tool requests are not automatically replayed. `LocalConnectionPreflightTest`, `ProviderDispatchRetryGuardTest`, `OpenAIRecoveryTransportTest`. |
| 8 | Evicted saved evidence | Exact issued cursors restore captured response fragments from an encrypted independent store; restoration never invokes the original operation. `GitHubSavedViewRecoveryTest` recreates the file-backed store and cache for each recovered page and asserts exactly one original mutation. |
| 9 | Duplicate failures and delivery | Terminal guards deliver failure once; progress receipt identities suppress repeated job/sequence and terminal call events. Existing continuation checkpoints retain already delivered prefixes. `GitHubTerminalDeliveryTest`, `GitHubApiDeliveryTest`, `GitHubChatProgressTest`. |
| 10 | Context/output/tool-result limits | Capacity reconciliation keeps discovered limits and their identity while applying smaller profile, catalog and route ceilings. Review input and reserved output are admitted together. `CapacityReconciliationTest`, `GitHubReviewedSessionTest`. |
| 11 | Bounded final review output | Reviewer instructions bound issue/claim counts and lengths; each pass has a bounded output allowance. Oversized correction inputs are repaired by sections, cached and fully re-reviewed. The pass limit remains finite and recoverable. `GitHubReviewedSessionTest`. |
| 12 | Requested/effective reasoning | AUTO remains the saved requested mode while helper/maximize policy records its effective ON/OFF choice. `ReasoningRequestPolicyTest`. |
| 13 | Acceptance ledger | Encrypted checkpoints contain receipt-linked PASS, FAIL, BLOCKED and NOT_TESTED rows. Optional and out-of-scope rows remain visible but neither satisfy nor block required acceptance. At least one required criterion is needed for completion. `GitHubAcceptanceLedgerTest`. |
| 14 | Tool readiness distinctions | Capability rows separately report discovered, authorized, available, dispatched, tested and successfully tested observations. Historical evidence must match the current tool and schema. `GitHubAcceptanceLedgerTest`, `GitHubChecklistPaginationTest`. |
| 15 | Token usage provenance | Aggregates distinguish measured/provider observations from estimates and unknown counters. Negative values remain unmeasured; additions saturate instead of overflowing. `RunTokenUsageTest`. |
| 16 | Efficient evidence collection | Nonexhaustive remote inventory and missing dependency traversal are bounded while local fragments and explicit exhaustive requests remain available. Receipt attempt indexes avoid repeated full-history scans. `GitHubChecklistPaginationTest`, `GitHubEvidencePolicyTest`. |
| 17 | Llama health diagnostics | Runtime capacity-discovery failures record same-route authenticated health/model observations alongside connection diagnostics. TCP, HTTP health, model listing and actual inference are distinct; inference is never claimed tested by these probes. `LlamaHealthDiagnosticsTest`, `LocalServiceHealthTest`. |
| 18 | Persistent evidence outside page cache | Immutable captured payloads and granted cursor inventories use the bounded encrypted repository state store. Executed receipts are persisted before bounded review projection. Secondary page-storage failure retains the accepted operation and records incomplete secondary evidence. `GitHubSavedViewRecoveryTest`, `GitHubAcceptanceLedgerTest`. |
| 19 | Workflow progress and recovery UI | Status shows an execution dashboard, criterion states, grouped operations, evidence/readiness details, and run-targeted recovery controls. Deterministic failures, cooldowns and unknown effects constrain recovery. Incident exports disclose failure categories and evidence bounds. `GitHubWorkflowPresentationTest`, `GitHubIncidentExportTest`, dashboard and private-filesystem instrumentation. |
| 20 | Regression and recovery scenarios | Behavioral coverage includes concurrent budget admission, process recreation, interrupted correction, lost gateway responses, streamed slot overflow, optional criteria, complete CI job inventories and actual dashboard interactions. The release acceptance script explicitly selects six dashboard interactions and an Android filesystem fence test. Full device/private-service qualification remains separate. |

## Recovery invariants

1. A tool call is reserved before dispatch. Storage failure blocks dispatch; an unreadable existing allowance cannot reset itself to zero.
2. An interrupted invocation retains its reservation. A new worker or provider round cannot create a fresh allowance implicitly.
3. An exact saved-view cursor grants access only to captured data in its authorized scope. An absent or invalid capture is never a reason to repeat a write.
4. A request that might have executed gateway server tools cannot be automatically resent. Missing reconciliation identity blocks ordinary Resume/Retry.
5. Review never publishes a partial verdict. Every candidate section and retained evidence fragment is covered before publication; a failed review produces an explicit incomplete response.
6. Execution completion and acceptance completion are distinct. A generated answer or HTTP success does not turn untested, blocked or failed requirements into PASS.
7. Unknown token counts, service health and evidence completeness remain unknown. Tests of an observation layer do not establish live service availability.

## Validation record

Validation is in progress. The task record beside this document tracks executed revisions and results. Before merge, reconcile these checkpoints with the final revision's required checks.

- Canonical host validation passed during implementation: 301 Python tests plus dependency/CI policy, secrets, Android regex, resources, icon and provenance checks.
- The durable recovery subgroup's focused JVM harness passed 33 tests using changed production sources and a file-backed state store. Android and contract-boundary stubs were used; this does not replace the Android Gradle suite.
- At the first published implementation, `2ad5a49c77498e3d5b9470d8706f835020eb75bd`, production and unit-test Kotlin/Java compilation passed locally and in CI. Android lint, CodeQL, Kotlin formatting and repository/gateway contracts passed in CI.
- The [API 34/36 acceptance run](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41848723652) passed 54 distinct methods on each emulator, including all six new dashboard cases and the private-filesystem budget fence case. The [review APK pipeline](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41848723653) also passed app, device-test and benchmark compilation plus packaged runtime/privacy verification.
- The first full unit run found actual failures: [PR validation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41848806800) ran 6,340 tests with 26 failures and four skipped; [diagnostic validation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41848723656) ran the same count with 25 failures and four skipped. The additional PR failure was a second instance of the same health-test scheduler race. Heap and coverage qualification were not established by those failing runs.
- The follow-up corrects omitted active-tool batch instructions, progress-checkpoint failure handling, continuity of confirmed delivered checklist digests, and implicit allowance renewal across recovery controls. Content-free task identity survives private-history projection; temporary counters remain in memory and missing receipts block replay. Test corrections supply the history now required by the gateway guard, preserve stable error-code expectations, account for authorization-denied calls without spending budget, use a deterministic health-test dispatcher, and verify safe nonempty failure delivery. New repository coverage checks durable unknown outcomes even when the visible message window omits the error.
- Focused tests and exact-revision CI must pass for the corrected source; the successful APK and emulator results above identify the earlier implementation revision, not an untested follow-up.
- Physical-device process death, frame pacing and the user's private Llama/server connectivity are NOT_TESTED in this environment.

## Remaining qualification limits

Temporary conversations keep their counters only in memory. An unavailable allowance or missing operation receipts blocks continuation; changing storage mode does not renew a task budget. Durable workflow recovery requires a conversation that permits persistent evidence.

Evidence stores and review passes remain bounded. Quota exhaustion or a permanently unavailable service can still block completion; the repair must retain a truthful status and safe recovery behavior in that case. A client can stop accepting a misbehaving streamed batch, but cannot limit an external provider's internal generation after dispatch.

Preview pacing and activity-summary intervals have source and regression coverage. Real frame performance, touch/IME/rotation behavior and device restart recovery require device receipts. Live credentials, private repository permissions and private server routing must be tested in the user's deployed environment. No source or host test result in this repair is presented as that live qualification.
