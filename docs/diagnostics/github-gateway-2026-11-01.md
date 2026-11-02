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

The native control centre now has Overview, General, Advanced and Add-ons, with the app's default dark teal colours and scrolling pages. The installer launches the dashboard and adds a Start menu shortcut.

Overview contains persistent total generated tokens, today's output, seven-day token history, measured generation speed, current backend/journal health and recent jobs. It is a personal installation view, not a requests-per-second dashboard. Backend output may include reasoning; missing usage is never estimated or silently reported as zero. Counters begin with this update, persist in a separate local SQLite file and exclude cached delivery duplicates. Lost telemetry storage never fails generation. Cached data is visibly marked stale.

General exposes existing connection, startup and update settings. Advanced exposes worker/chat limits and shutdown grace. Add-ons exposes GitHub engineering scope and MCP service enablement, preserving all other configuration fields and refusing stale saves. The existing MCP allowlist remains authoritative.

## Validation

- Gateway regression suite and browser bridge tests: passed locally.
- Canonical `bash scripts/validate-local.sh fast`: passed locally.
- Python source compilation, manifest integrity and whitespace checks: passed locally.
- Added behavior regressions for streaming/non-streaming two-round handoffs, durable restart, missing receipts, terminal immutability, bounded required-tool repair, provider retry policy, reasoning helpers, consent dispatch, persistent measured statistics and add-on file edits.
- Targeted Android unit tests could not start: Gradle distribution download is unavailable in this environment (only JDK 17 is installed; the repository requires JDK 21/Android SDK 37).
- Windows installer/UI execution is not claimed locally. The existing Windows CI now runs the added desktop tests and a frozen-app UI smoke covering all four sections and minimum window size.
- Real phone-to-PC/network and model-backend interoperability still requires device testing after both gateway and Android updates.
