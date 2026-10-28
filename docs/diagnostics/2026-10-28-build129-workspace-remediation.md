# October 28 build 129 log review and workspace repair

Source: app-diagnostics-4614619606409478729.jsonl, 5,336 valid JSON records, schema 6. Export identifies 0.9.61.0/build 129, app commit dfa1740fdf890f6124160c58a886996a7e51e825. This is older than current main and the latest repair branch. The private source export is not committed.

99 records carry countAsIssue: 50 performance, 23 warning, 20 error and 6 main-thread disk IO. These are observations; a failed request, segment and completion can repeat the same cause.

| Family | Disposition |
| --- | --- |
| Six StrictMode disk reads in deleteChatsV2, including checkpoint/crawl directory cleanup | Move the complete deletion operation to Dispatchers.IO, covering filesystem property resolution and cleanup as well as suspended database work. |
| 50 performance observations, including skipped frames and FRAME_TIMING stalls up to 748 ms | Remove the workspace background blur and collapse operation history by default. Earlier branch defers draft IO and moves indexing to Default. Physical ASUS frame-time/Perfetto verification remains required; this change does not prove all jank fixed. |
| GitHub create_branch HTTP 403 / Resource not accessible by personal access token | The device credential needs repository Contents: write and any required organization/SSO authorization. can_push is repository membership, not token endpoint authority. Code cannot grant that credential permission. |
| commit_files branch differs from selected workspace | Keep the destination guard; automatically authorize tool invocations only for the selected connection/repository in explicitly opted-in workspace conversations. The commit popup displays the actual proposed branch and validates the frozen patch/head. |
| changed_since without base | Reject changes requests locally with an actionable base_sha instruction, before backend dispatch. Do not invent a comparison base. |
| github_read repeat allowance reached twice | Preserve the bounded per-response limit; use already retained evidence or a new pinned range. This is a budget/control result, not a server crash. |
| Three provider 403s and associated failed model segments | Upstream model/account access failure. Existing credential/model circuit breaking remains; verify provider key, entitlement and endpoint on device. No fabricated provider-specific cause or silent fallback. |
| Local provider TCP connect timeout at private port 8080 and unexpected end of stream | Restore local server/Tailscale reachability and test the configured endpoint. Existing bounded stream retry/cooldown remains. Endpoint is preserved. |
| MCP private port 8101 unreachable; remote search gateway timeout at 5 seconds | Restore the server/listener/device route or remote gateway; inspect which timeout phase failed. No global timeout inflation or removal of health backoff. |
| MCP initialization coroutine cancelled | Cancellation during lifecycle teardown is retained; do not retry cancelled initialization as a new user request. |
| ASUS framework finalizer SecurityException / asus_leak_detect_proj | Framework ActivityThread/BinderInternal stack; firmware/vendor fix required. No app stack or privileged-setting workaround is justified. |
| WorkManager onNetworkChanged / missing WorkSpec; ignored JobInfo flag | Platform/library scheduling observations; no proof of a failed app job. The ignored flag already has informational classification. Verify actual scheduling on the affected OS before replacing WorkManager services. |
| READ_DEVICE_CONFIG jank monitor initialization, IME unregister, ART Monitor::Inflate/finalizer warning, hardware property denial | Platform/runtime observations retained for correlation. They are not evidence that an app should request privileged permissions or globally intercept exceptions. |
| Native feedback tensor/single-signature, JNI thread attachment, compatibility/connectivity progress | Existing optional-feature/informational dispositions are retained. |
| Latest repair branch CI fails formatting and missing-usage continuation test | Format draft-store test overrides and let output-limited empty continuations reach the two-round visible-progress guard. No test expectation is weakened. |

Workspace changes: solid surface, outlined buttons, icon shortcuts, collapsed operation details, explicit per-workspace automatic tool authorization and a single scrollable branch/change-summary popup for each submitted commit. The user approves the immutable patch once; the controller preserves exact head/blob checks, account binding, duplicate-write rejection and verified commit receipts. Ordinary chats retain their current policies. Administrative credential changes remain outside the exposed repository tool set. Successful popup approval queues a continuation using the current workspace profile; the committed action is not replayed.

Release qualification: scoped application repairs only. No claim of physical-device performance, local-server availability, provider entitlement or firmware repair. Full Unified Reliability v2 acceptance remains incomplete.
