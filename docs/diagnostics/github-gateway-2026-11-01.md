# GitHub gateway diagnostics and desktop dashboard

Repository: `tailscale-signin/GPT_Mobile_AI-improved`
Base: `main`, `2ae52c78ddae2a5470d8a0ee7dc93b6a9101f65a`
Branch: `fix/github-gateway-contract-recovery-2026-11-01`

## Evidence and repairs

The attached diagnostic export contains 6,379 JSONL records: 3,355 events and 3,024 continuation records. It reports app version 0.9.65.0/build 133 at `74a2f906874430b542cecc11d40d7449ebaf9f19`. No raw log, prompts or personal data are committed.

| Finding | Repair / disposition |
| --- | --- |
| 18 provider HTTP 400 `invalid_v14_request` events; GitHub tool reads succeed before the continuation fails | Client-owned delegate rounds returned tool calls without emitting a handoff. Completion incorrectly became terminal and rejected Android's next request with the same gateway job ID. Emit handoff progress and derive waiting state from the validated result even if progress is missing. |
| Client handoff could be lost on gateway restart | Persist pending call IDs, restore waiting state without executing anything, and require the app's tool results before explicit continuation. A truly terminal result remains immutable and returns a specific 409 error. Legacy jobs without a durable handoff receipt remain conservatively interrupted. |
| Generic validation error hides the actionable cause | Preserve contract error code/status and explicit non-retryability. Do not misclassify model contract errors as generic backend connection failures. |
| Backend omitted a required tool call; transport retry restarts generation | Allow one repair within the original deadline and remaining measured output allowance. Retain the required/forced tool selection, reject a different tool, and stop on missing usage or exhausted budget. Android honors explicit non-retryable errors, including legacy gateway wrappers. |
| `REASONING_OFF_UNSUPPORTED` blocks GitHub claim extraction | Match the review-helper policy: prefer OFF with a verified template control; otherwise use Auto for internal extraction. Explicit ordinary user OFF requests retain strict admission. |
| Health Connect consent refusal recorded as dispatched | Record whether the health reader actually began. Plugin/consent/query rejection is undispatched; revocation after a read remains dispatched. |
| TCP refusal, backend/stream timeouts, MCP bridge resets and unavailable ports | These establish service/network unavailability, not a proven application listener cause. Preserve configured endpoints and existing bounded health recovery. The dashboard reports actual model/journal health; a live PC/phone check is still required. |
| Saved recovery content grows across repeated failed continuations | The handoff fix removes the demonstrated trigger. Saved evidence is preserved; this log alone does not prove that its growth is duplicate data. |
| Android keyboard/platform messages and frame stalls | Observed but not independently reproduced as gateway defects. No speculative framework or rendering change is claimed. |

## Desktop changes

The native control centre retains the tray/taskbar dashboard from parent PR #780, with token-focused Overview, Devices, Recent activity, Search jobs and GitHub jobs. General, Advanced and Add-ons open directly from the sidebar as scrolling settings tabs in the same theme. The installer launches the dashboard and adds a Start menu shortcut.

Overview contains persistent total generated tokens, today's output, seven-day token history, measured generation speed, current backend health and recent jobs. It is a personal installation view, not a requests-per-second dashboard. Backend output may include reasoning; missing usage is never estimated or silently reported as zero. Counters begin with this update, persist in a separate local SQLite file and exclude cached delivery duplicates. Lost telemetry storage never fails generation. Cached data is visibly marked stale.

General exposes existing connection, startup and update settings. Advanced exposes worker/chat limits and shutdown grace. Add-ons exposes GitHub engineering scope and MCP service enablement, preserving all other configuration fields and refusing stale saves. The existing MCP allowlist remains authoritative.

## Validation

- Gateway regression suite and browser bridge tests: passed locally.
- Canonical `bash scripts/validate-local.sh fast`: passed locally.
- Python source compilation, manifest integrity and whitespace checks: passed locally.
- Added behavior regressions for streaming/non-streaming two-round handoffs, durable restart, missing receipts, terminal immutability, bounded required-tool repair, provider retry policy, reasoning helpers, consent dispatch, persistent measured statistics and add-on file edits.
- Android validation runs in CI: this environment has JDK 17 and no Android SDK; the repository requires JDK 21/Android SDK 37.
- Windows installer/UI execution is not claimed locally. The existing Windows CI now runs the added desktop tests and frozen-app UI smoke checks covering the dashboard and all three settings sections at minimum window size.
- Real phone-to-PC/network and model-backend interoperability still requires device testing after both gateway and Android updates.

## Integration with PR #780

PR #781 integrates PR #780 at `58684998abb4190159c67cd016e239dd2a527587`, merged into main as `e0a3fdca3e90d18f3b9b31e35f054a8f4fbf7037` after all applicable checks passed. The reconciliation preserves key-free listener configuration, explicit firewall actions, observed devices, search/GitHub history, tray lifetime and the opt-in Android voice plugin. One observer writes a combined usage/activity snapshot; competing snapshot writers were removed. Desktop control results return through a queue to the Tk thread. Native Windows smoke tests exercise both the dashboard and the General/Advanced/Add-ons window.

## Additional diagnostic checklist

The supplied five-export examination is a separate evidence set from the attached 6,379-record export above. Its counts are not combined with these records.

- Every publication now derives completion from the report's task outcome and completion verification. A delivered partial answer produces a partial outcome, retains the original task and interruption cause, and leaves a resumable interrupted run. Claim verification, response delivery and task completion are separate report fields.
- HTTP and SSE failure parsing retains the gateway's original code plus allowlisted stage, request/job identifiers, dispatch state and retryability. Claim-extraction errors remain separate from the initiating failure. Deterministic contract codes cannot trigger unchanged model-round retries.
- Saved-page repair replaces arguments with the exact cursor-only correction once and revalidates authorization scope. The rejected call remains in the execution receipt; unchanged invalid calls reuse failure history without dispatch. Evidence uses corrected arguments, preventing a rejected commit field from creating false immutable attribution.
- `status`, `status of repo` and `github status` share four bounded reads: current ref, SHA-bound checks/statuses, open PRs and pinned recent commits. Missing observations are explicit; workflow text is conditional and never establishes branch health. Only fresh trusted receipts can support current status.
- Plain greetings bypass generation, review and extraction. Review/extraction use their task-specific instructions and evidence instead of repeating the entire engineering prompt. Ordinary engineering tasks retain review and verification.
- Run totals aggregate distinct measured invocations across primary, review, extraction and recovery. Estimated/reserved values remain separate. Gateway progress measures private backend first output separately from publication, without exposing reasoning text or inventing a visible first-token measurement.
- Context admission requires an observed runtime allocation from backend `/props`; model training context size is not accepted as allocation evidence. An unavailable allocation produces an actionable rejection before inference.

Behavior regressions cover partial outcome propagation, preserved errors, greeting bypass, equivalent status planning, complete versus incomplete snapshots, cursor correction and failure-history reuse, private-pass accounting, backend timing, and active context admission. Current revision CI must pass before merge; no phone, ASUS firmware or physical backend result is inferred from unit tests.
