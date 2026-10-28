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
