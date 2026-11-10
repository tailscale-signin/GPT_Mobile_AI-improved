# Gateway defaults, repository map and GitHub verification follow-up

This work extends PR #816 on `fix/audit-execution-recovery-2026-11-09`. Its parent is `e85dec10ec07b1a7dd58e4a4237a3c9e81572d6d`; the base remains `main` at `7db0c735f9e86eaece24513fbaf5c0a624f13822`. The earlier audit, durable recovery, detailed progress, OAuth, model qualification and device-evidence changes remain in scope.

## Requested behavior

The user requested Ultra Maximum defaults on the client and server, repair of `GATEWAY_ENGINEERING_GITHUB_REQUIRED`, 1.5-second GitHub preview and summary fades, 50% preview opacity, the main branch's theme highlight across the newest preview, all feasible fixes from the pasted GitHub verification report, and a redesigned repository map with working gateway recovery.

### Progress text and motion

Detailed summaries retain the earlier evidence-based narrative work: concrete findings, their effect and the next action, with concise previews and accessible expansion. Arrival and replacement fades now take 1,500 ms. Replaced text fades out while current text fades in; only current text is exposed to accessibility. Retained history does not replay its entrance.

Preview text and icons use a 0.5 base alpha. The active preview at the bottom pans the theme highlight across its full row with the existing 1,800 ms cycle. An older preview stops highlighting when later content arrives. Reduced-motion settings, lifecycle state and frame pressure control every effect.

### Ultra Maximum defaults and compatibility

New saved gateway engineering jobs request 24 hours, 512 model invocations and 2,097,152 aggregate model tokens. Native GitHub execution uses its existing bounded 512-tool-call Ultra Maximum policy. Model invocations and tool calls are different budgets.

New non-free profiles use the existing default-limit sentinel, allowing the GitHub mode to supply its bound. Saved profile limits, explicit smaller budgets, free-provider limits and task ceilings remain effective. An old saved value of 50 or 100 does not identify whether the user chose it, so it is preserved.

Older gateway capabilities negotiate an advertised duration and call limit, and the UI shows requested versus accepted allowances. Existing durable requests retain their accepted defaults during retry; an upgrade cannot turn an omitted legacy field into a different job or duplicate submission. Client decode compatibility and new-job default selection are tested separately.

### Host GitHub connection and setup recovery

The desktop launcher previously stripped the documented engineering GitHub selector and token from its child environment. It now preserves those two settings while retaining the other launcher restrictions. The backend respects an explicit host selection or dedicated host credential and can select a single eligible known GitHub connection when neither is supplied. Ambiguous, disabled, invalid or incompatible configurations remain explicit failures.

Engineering and repository graph access use the selected gateway's credentials. The phone's GitHub token is not forwarded. Local configuration readiness does not prove remote repository access.

The client has an explicit readiness check for the selected gateway. It refreshes that route's capability cache and performs no job submission. A known preflight failure is presented as setup work with a route to the saved gateway settings. Retrying after repair is offered only when structured evidence says no request was accepted; uncertain job outcomes retain their existing recovery path.

### Repository map

The map uses the explicitly selected engineering gateway while keeping the selected chat profile as its authorization context. A cloud inference profile can therefore use a separately selected private gateway for repository indexing. The map remains reachable when setup is incomplete and provides a settings action.

Empty, checking, indexing, paused, blocked and ready states have their own controls. A blocked empty map does not display a misleading build prompt or relationship legend. The selected repository and revision stay visible, with the full SHA available to copy. Real file and relationship observations drive the graph and its counts; unknown progress remains indeterminate. Selecting a different host or editing the current host clears the previous view and rejects late responses from the earlier selection.

Graph readiness is evaluated independently of engineering write prerequisites. Read-only GitHub access is sufficient for graph operations that only read repositories. Engineering writes retain their stricter policy. Graph recovery remains bound to the saved host, principal and repository revision; an unresolved index submission is checked before another batch is allowed.

## Verification report repairs

The pasted report is retained as [user-reported evidence](evidence/audit-execution-recovery-2026-11-09/github-verification-capture.json). Its original operation payloads and complete checkpoint were not attached. Historical acceptance remains 2/5; repository changes do not rewrite it.

| Observation or gap | Repository change and verification boundary |
| --- | --- |
| Three successful discovery receipts but zero verified pages or names | A producer-to-journal regression reproduced loss of catalog proof during generic large-result compaction. Catalog projection now retains exact bounded names, fingerprints, versions, page counts and continuation. Genuine schema changes and malformed pages remain rejected. Without the historical raw pages, this is a reproduced defect rather than an asserted cause of that particular capture. |
| `github_issue:comments`, issue 674, rejected with `DISPATCH_POLICY_CHANGED` | A saved known-unsent read can receive one new invocation after restoration under a fresh validated authorization revision. Unchanged rejections, revoked tools, incompatible schemas, writes and uncertain outcomes are excluded. Original receipts remain unchanged, and current dispatch authorization still runs. |
| Cleanup shown as complete while the fixture criterion remains unverified | Explicitly local staging and approval proposals with complete numeric zero-write counters no longer invalidate an independently verified cleanup lifecycle. A remote proposal with missing, malformed, positive or uncertain mutation counters cannot receive that exemption. Publication acceptance is still separate. The exact historical reason is unavailable without its full receipts. |
| Generic recovery advice for policy changes | Reports distinguish required revalidation from denied access, display only recognized changed-field names, and retain uncertainty-first recovery. Values, credentials and raw payloads are excluded. |
| Incomplete pagination and provisional all-tools denominator | Each exact continuation and catalog page still needs an observed result. Repeated pages do not enlarge the number of tools exercised. The compaction fix preserves the evidence needed to resume this work. |
| Missing issue, commit, workflow and decision coverage | A source fix or successful local proposal does not qualify an unexecuted API action. Owned fixtures, exact leases, approval, terminal verification and cleanup remain required. A test cannot invent a genuine user decision. |

All new zero-counter checks require JSON numbers. Strings such as `"0"` and `"000"` are malformed evidence and cannot establish non-dispatch or excuse fixture proof.

## Validation and remaining qualification

The repaired parent revision completed 8,704 Android unit cases: 8,699 passed, five skipped and none failed. Its three earlier UI regressions passed. API 34 and API 36 acceptance each completed 60 tests. Those results establish the parent baseline, not the later motion, map or gateway changes.

The follow-up retains exact source hashes, scoped host logs and independent review findings in its validation receipt. Full Android compilation, Compose rendering, instrumentation, APK packaging and the canonical `Required verification` aggregate must be evaluated on the newly published head and generated merge revision. Overlapping host suite counts are not a coverage percentage.

Actual host sign-in, deployment updates, the three privacy-aliased MCP repairs, selected-model generation, every named live tool fixture and physical-device performance still require observations from those environments. The original audit remains at 25/27 measured passes with 67 untested checks until a new qualified run supplies evidence. Disabled and uninstalled optional plugins remain explicit configuration choices.
