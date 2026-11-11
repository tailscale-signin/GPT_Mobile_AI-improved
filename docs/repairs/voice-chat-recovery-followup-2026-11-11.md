# Voice, GitHub chat and recovery follow-up

Repository `tailscale-signin/GPT_Mobile_AI-improved`, branch `fix/audit-execution-recovery-2026-11-09`, PR #817. The base is `3a1f44bb3dff7dbe10a839f1cad7b408b34aadeb`; the follow-up starts from `2a75e9e0985c57d197190b0f6bb16e4141e97acf`. Earlier audit captures and their outcomes remain historical evidence.

## Implemented behavior

| Area | Behavior |
| --- | --- |
| GitHub conversation | Original prompts appear in the conversation. App-generated workspace headers and trailing preset markers are hidden in the displayed and copied prompt. Stored routing metadata remains available to the task. |
| Header | GitHub chat starts with collapsed controls. Back navigation and a themed expand control remain visible. Expanding and collapsing animate the title, tabs and status controls. |
| Activity | The active preview text has full opacity. Historical previews retain their existing styling. Actual operation, checklist and recognized Gateway counters update a stable preview row without adding a separate history entry for every count. |
| Summaries | The initial summary remains immediate. Later public summaries wait at least 45 seconds, coalesce repeated observations and describe changed evidence. GitHub no longer requests a summary every ten tool calls. |
| Decisions | Bound native approval, Gateway merge, tool consent, MCP input and user-choice states expose compact action cards. Notification deep links return to the affected chat for review or response. Exact patch and write approval checks still run. |
| Voice settings | Recognition engine, playback engine, voice, rate, pitch where supported, captions, review, listening behavior and Kokoro thread count are configurable from Voice. |
| Kokoro | A voice session retains one model, prewarms it on its worker, starts from a short stable clause and feeds a bounded PCM queue into one AudioTrack per reply. Interruption clears queued audio. Memory pressure, model changes and session exit release retained resources. Playback IDs correlate model, PCM, played-frame, underrun and terminal events. |
| Voice initialization | Shared model directories and validation resolve on IO. The route waits for its saved room and selected profile before capture; unresolved privacy keeps online speech disabled. Invalid preference fields recover independently. A missing room offers a return to chats. |
| Native startup | Opening Voice starts recognition first. Kokoro warmup waits for an accepted reply to generate, and a worker gate prevents initialization while Moonshine owns its decoder. Engine IDs record startup, readiness, failure and decoder release. Native process exits still require the failing APK trace. |
| First use and microphone recovery | First use requires Enable & start. Enabled sessions open directly. Paused/failed sessions have a one-tap Start/Retry path; denied permission exposes Open app permissions. |
| Voice turn admission | Checking, queued, accepted and failed states replace interpretation of generic notice text. Drafts remain through admission. Saved request IDs resolve a queued turn, and accepted turn/run IDs determine which reply supplies captions. |
| Captions and settings | Playback failure stops audio independently while captions continue through model completion. Audio and Settings share one spoken-replies value. An application-owned serialized writer saves preferences after screen exit and reports failed writes. |
| Claim extraction | Small structured batches target specific requirement IDs. Each validated batch and its private draft are saved in the encrypted task checkpoint. A failed batch reports its precise error, and Continue can reuse the draft and completed batches under the same evidence and authority. Combined claims retain correct deduplicated indexes. |
| Selected profile | Extraction retains the selected profile. Lower reasoning is used only for the default OpenRouter route when its catalog advertises control and no explicit reasoning override forbids it. |
| Current and retained Gateways | New-job readiness and retained job bindings each get their own row and export identity. An existing job on Gateway A cannot hide current Gateway B. Settings and bounded readiness actions resolve the original target and refuse changed configuration. Direct checks leave earlier audit results unchanged. |
| MCP request correlation | The audit row and qualification worklist identify response-ID mismatch separately, with bridge/proxy guidance to echo the request ID value and JSON type on success and error paths. |
| Continuous Kokoro speech | After the initial short clause, complete clauses or at most twelve complete words continue into synthesis without waiting for sentence completion. Stable chunks are bounded to 240 characters where a complete word boundary exists, and repeated snapshots do not repeat audio. Final text and the existing total reply limit remain preserved. |
| MCP audit admission | The frozen target generation and fresh local credential precheck are revalidated after the durable dispatch marker. An intervening configuration or credential change prevents network dispatch. |

These complement the branch's earlier pinned repair commands, durable approval continuation, separate inference and engineering Gateway audit targets, strict engineering readiness, route-specific capability caching, JSON-RPC validation, fresh/shared/cached discovery receipts, live GitHub verification, CI evidence provenance and diagnostic export corrections.

## Validation and remaining qualification

The task record `audit-execution-recovery-2026-11-09-task.json` records the final local test outcome. Local JVM execution uses the publisher-verified Android/Robolectric dependencies and an explicit Byte Buddy test agent. It establishes application and Compose behavior in the host test environment. It does not attest an installed APK or execute the native model on a physical phone.

Required release evidence remains separate:

- Run the new APK on the ASUS device and match its binary to the exact build artifact. Open the actual Voice route with both speech packs installed and Kokoro selected; test permission granted and denied, one complete spoken turn, interruption, app closure/reopening, and a settings change immediately before leaving. Capture engine IDs, logcat and the OS exit reason if the process exits. Measure warm and cold speech latency, native heap, thermals, frame timing, cancellation and underruns. The proposed warm p95 target remains unmeasured.
- Reauthorize the exact saved OAuth connection, repair the actual bridge endpoints and protocol metadata, then record fresh discovery plus a named tool fixture per connection.
- Qualify the deployed Windows Gateway with the saved model binding through app closure, reconnection and host restart. Repository tests cannot load or validate that host's selected model.
- Exercise the remaining authorized issue, PR, release, workflow and interaction fixtures. Historical fixture branches require exact ownership and head receipts before deletion.
- Install and enable only integrations in the chosen release scope. Keep intentional exclusions explicit.
- Complete the outstanding Unified Reliability v2 acceptance receipts. The ledger remains 11 passing cases out of 110, with 0 of 48 tasks done. Issue #674 is open for the remaining qualification. No case was promoted from partial harness coverage.

Fresh CI is required on the published follow-up commit. Earlier green checks, cancelled runs and partial local suites do not satisfy the required aggregate for a different revision.
