# Shared-input multi-chat and diagnostic recovery

Input: app-diagnostics-3611955424703685780.jsonl, 7,740 exported records, app 0.9.53.0 / build 122 / e1dec3cd1107.

## Implemented changes

- New multi-chat creates one STANDARD room containing every selected profile. Existing generation orchestration persists one question and dispatches separate responses to active, unpaused profiles. Combined synthesis remains a distinct mode.
- Legacy split workspaces upgrade the root profile registry and display the shared ChatScreen. Other rooms and their histories remain intact and accessible using previous-chat buttons. They are not silently merged into another model's context.
- A themed composer panel identifies broadcast versus explicit one-message targeting, displays profile readiness/paused/generating status, and opens profile management. Per-response profile selectors continue comparing model responses.
- Keep input focus after sending to reduce unnecessary IME disconnect/reconnect cycles. Navigation still releases focus.
- Replace hardcoded gray pending-profile backgrounds, foregrounds and borders with theme roles. Neutral Material backgrounds, surfaces and outlines receive the active accent; chromatic custom surfaces retain their colors.
- DNS failures are deferred discoveries, retried on a later research invocation after recovery. They no longer add a five-minute negative reader cache entry. Shared network-outage detection stops page acquisition and reports Waiting for network rather than claiming evidence is exhausted. HTTP access denials remain terminal for the affected page.
- Invalid tool arguments return the required schema and correction instructions before any action is dispatched. No missing question is invented, and invalid calls do not consume an execution slot.
- MCP retry intervals progress from 5 seconds through 5 minutes for persistent failure. Existing authentication/configuration gates, manual refresh and health-reset behavior remain in place.
- Diagnostic persistence drains batches continuously instead of sleeping 250 ms between batches. UI snapshots are separately coalesced, and the queue remains bounded. Historical dropped records cannot be recovered.

## External conditions and verification limits

- The configured private MCP endpoint on port 8101 is unreachable. The server, address, local-network permission and network route must work before tools can connect. Backoff does not repair server availability.
- Publisher HTTP 403 and device DNS failures cannot be guaranteed away by an application patch. Research retains explicit incomplete/no-readable-source outcomes; snippets are not upgraded to verified evidence.
- The ASUS `asus_leak_detect_proj` SecurityException originates in ActivityThread/BinderInternal finalization, with no application frame. The app cannot make a restricted ASUS settings key readable. This export does not demonstrate an application-native crash or ANR.
- MediaPipe's feedback-tensor warning reports unsupported optional feedback for a multi-signature model; it is not evidence of failed inference.
- OS process-exit reason 10 is not treated as proof of an app crash.

Validation completed locally: 121 Python contract tests; Android resource preflight (28 XML files); Android regex checks; git diff whitespace check. Kotlin regressions cover one-room multi-chat creation, DNS recovery and reader-health behavior, and theme surface contrast. Android build/Kotlin test execution is blocked by Gradle distribution download network restrictions. No device verification or CI success is claimed. Publishing was blocked by automatic approval review and requires explicit user approval.
