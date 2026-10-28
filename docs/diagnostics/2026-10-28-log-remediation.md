# October 28 diagnostic remediation

Input: app-diagnostics-1457976057342031402.jsonl, schema 6, app 0.9.61.0 (129), commit 52edbffc6c58dab743193e57a27ad3f421f0bb17. The source log is not committed because it contains device and conversation diagnostics.

There are 5,013 exported rows and 399 events marked countAsIssue. These are observations, not 399 independent root causes; tool failure and completion records can describe the same failed request.

| Observation | Change or remaining action |
| --- | --- |
| StrictMode disk read (~155 ms), with GitHubWorkspaceDraftStore constructor in the stack | Defer noBackupFilesDir resolution until read/write executes on Dispatchers.IO. Regression tests cover construction, dispatcher, encrypted round-trip and connection isolation. |
| Source indexing can inherit a UI caller dispatcher | Move lexical indexing and content hashing to Dispatchers.Default. This is a preventive improvement; the export does not prove indexing caused the long stalls. |
| GITHUB_INVALID_ARGUMENT on search_repository | Validate query before dispatch, advertise length bounds, and replace the bare backend requirement with an actionable message. The export omits the failed call arguments, so its exact invalid field is unknown. |
| Repeated length-limited continuations, including reasoning-only output | Keep the existing two-round negligible-visible-progress guard active using the configured output cap when reported usage is absent. Preserve partial text and tool evidence; do not replay actions. Useful, nontrivial continuations are still allowed. |
| GITHUB_WRITE_BLOCKED, HTTP 403, Resource not accessible by personal access token | External credential change required in the Android app: grant the selected repository Contents: write, then verify app/token and organization authorization. can_push=true alone does not establish token endpoint permission. No credential permissions are changed by this patch. |
| github_read saved view unavailable/outside task | Preserve the task boundary. Re-read source using the current task's pinned commit/ranges; reconcile any earlier write from its receipt. Do not reuse a cursor from another task or repeat a mutation to recover output. |
| Private MCP address port 8101 unreachable | Restore the server/listener and device route, VPN or local-network access as applicable. Address is redacted; this environment cannot diagnose or change that server. |
| Remote brave-search MCP request timeout (5,000 ms) | Verify gateway availability and the device's DNS/TLS route. Existing session code already applies longer remote initialization deadlines; a different failed request phase must be identified before changing a global timeout. |
| Repeated main-thread stalls (up to 13,038 ms dispatch), skipped frames (up to 2,357), and historical process exit reason 6 | Unresolved performance incident. No usable application ANR frames are retained. Reproduce with Perfetto and collect the OS ANR trace, correlating main-thread work, GC, text layout, blur and active runs. Do not infer the culprit solely from delayed Room/Flow continuations. Verify the changes on the affected ASUS device before claiming this resolved. |
| ASUS asus_leak_detect_proj SecurityException from ActivityThread/BinderInternal finalizer | Stack belongs to the device framework. Check vendor firmware updates/report the framework defect. Do not request privileged permissions or intercept the global exception handler to hide it. |
| Cleared Reference/finalizer warning, Looper Drained, thread-suspension warnings | Correlate with the performance trace; these records do not independently prove an application leak. |
| FRAME_TIMING percentileOverflow=true | Treat the reported percentile bucket as overflowed; retain the measured maximum rather than claiming an accurate percentile. |

## Validation

Passed locally: git diff --check; Android regex preflight (949 literal patterns, zero errors); Android XML resource preflight (29 files).

Added regression tests for invalid search queries, absent-usage continuation stopping, and draft initialization/encrypted persistence. The targeted Gradle test invocation could not start: downloading gradle-9.8.0-bin.zip failed with Network is unreachable. JVM tests, Android lint/build, and device performance acceptance remain pending. No APK is produced or installed by this change.

## Second export: build 129 at dfa1740fd

Reviewed all 5,398 rows in app-diagnostics-7604554342784736769.jsonl: 3,098 event rows and 2,300 continuation rows. Exactly 102 rows carry countAsIssue=true; these are not 102 independent failures. The build commit is the synthetic merge of PR #751 with main, so this follow-up continues that PR rather than duplicating it. The original export is not committed.

| Observation in second export | Disposition |
| --- | --- |
| Four StrictMode DiskReadViolation records, with ChatRepositoryImpl.deleteChatsV2 resolving context.filesDir at lines 5655/5656 | Resolve the directory and invoke checkpoint/crawler cleanup inside Dispatchers.IO. Apply the same repair to updateTemporary. A regression invokes both operations from the caller thread and asserts filesystem access occurs elsewhere. |
| Missing base in changed_since; malformed compact calls | Validate github_context against its advertised schema and require a non-blank string base_sha for action=changes before backend dispatch. Do not invent a comparison base. Tests retain valid explicit-ref translation and reject missing/blank/typed/legacy-action arguments. |
| Existing PR test expected two restored continuation dispatches but the earlier empty-output guard stops after one | Preserve the earlier stop and assert its actual no-progress reason; independently exercise two output-limited requests without usage and require the low-yield stop. No retries are added to satisfy the test. |
| Existing draft-store test formatting failed four ktlint checks | Format the vault overrides. |
| Three provider HTTP 403 responses for ~google/gemini-flash-latest | External provider access denial. Verify the configured route/credential/model authorization. The log does not disclose a specific upstream cause; do not fabricate one or bypass permissions. |
| Local provider CONNECT_TIMEOUT on port 8080; one unexpected-end-of-stream retry | Restore server/Tailscale reachability and verify Test connection on-device. The existing bounded retry is not proof of server recovery; do not change the preserved endpoint. |
| MCP private endpoint port 8101 connection failures | Restore the server/listener/device route. Existing failures/backoff must remain visible. |
| Brave MCP five-second request timeouts | Source identifies this as the bounded optional server/discover phase in ModernMcpTransport, distinct from the longer SDK handshake. Verify gateway responsiveness and protocol support before changing the discovery deadline; a timeout does not prove protocol incompatibility and must not trigger hidden second connections. |
| GitHub create_branch 403, resource not accessible by personal access token | Android token Contents: write and, for workflow changes, Workflows: write must be verified on the selected repository. This session's connector write permission does not modify the device token. |
| commit_files disagrees with selected workspace branch | Correctly rejected destination mismatch. Select and authorize the intended working branch; do not bypass workspace binding. |
| Repeated github_read rejected at per-response repeat limit | Correctly bounded duplicate reads. Use retained results or distinct required ranges within the accepted budget. Do not remove the guard. |
| Rendering warnings, maximum measured frame 748.059093 ms, skipped frames up to 98 | Remaining physical-device performance acceptance. The attributed IO fix does not prove all rendering stalls resolved. Capture a Perfetto/OS trace on the ASUS device, including GC, main thread and GLASS_BLUR activity. |
| ASUS hidden-settings SecurityException in ActivityThread/BinderInternal finalizer | Vendor framework defect; preserve evidence. Application code must not suppress global exceptions or request system-only settings access. |
| WorkManager onNetworkChanged warning, stale WorkSpec, IME callback, ART monitor/finalizer and native optional-feedback/property warnings | Retained platform/lifecycle observations, with no application crash or corrective callsite established by this export. The dependency is already WorkManager 2.12.0. Reproduce on target firmware before changing library scheduling or native runtime behavior. |
| Android historical exit reason 10 | Marked informational in the export, not evidence of a fresh application crash. |

The proposed 0.9.62.0 release remains scoped. CI and signed-artifact checks cannot close remote-service or physical-device acceptance. At preparation time local whitespace review passed; full Android execution runs in CI because this host cannot reach GitHub/Gradle downloads and has no Android SDK. Exact final CI results are recorded on PR #751 and the release workflow, not inferred here.
