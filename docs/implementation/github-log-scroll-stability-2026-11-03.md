# GitHub retry and scrolling repair

Source: all five files in `export_Status_1793716534359.zip`, app 0.9.66.3 / build 137 / commit `9118a4ea9750f20de002a427423a3e997103ca36`. The capture has five runs (one completed, two partial, two failed), 60 tool events (53 successful, seven errors), 628 journal events, 295 conversation diagnostic events and 500 recent app-log records. The last two collections overlap; they are not independent issue counts. Capture completeness is explicitly unknown, with no recorded journal gaps.

Branch: `fix/github-log-scroll-stability-2026-11-03`, based on PR #791 at `6ef54ecf39403da2892be78b5dba6c866f4b01e2`. This deliberately includes its graph features and Status audit fixes. It does not repeat their implementation or change release configuration.

## Changes in this branch

- Sending a new GitHub prompt animates its lazy-list position over 1,500 ms. Distance is corrected as rows are measured. Touch scrolling can cancel the motion; incoming tokens do not restart it.
- The latest turn reserves the usable viewport below the toolbar and above the composer. The activity summary and previews are inside that turn, immediately after the prompt, rather than after a screen of reserved whitespace.
- Initial activity always renders a white summary before previews, including retained timelines that contain preparation before their first summary. Later summaries remain chronological. Compact activity keeps the summary that introduces its visible previews.
- Web-search fallback previews use that same ordered list, so summary-only or empty activity cannot render a search preview above its introduction. The fallback participates in compact selection and latest-preview motion without duplicating an existing preview.
- History release requires both 64 dp of movement beyond the prompt boundary and at least one second of sustained upward scrolling. Pauses longer than 250 ms, reverse direction and separate gestures reset intent. Fast upward flings and inertial movement cannot bypass the duration requirement.
- A downward drag of 32 dp, or an intentional downward fling, reattaches the prompt. The animation waits until the user scroll releases the list and rechecks anchor intent, so it cannot be cancelled immediately by the same gesture or override a later history/bottom decision. Reaching the true bottom re-enables bottom following. The bottom button also restores bottom following. New prompts reset following to the prompt position.
- Streaming growth is conflated while each smooth bottom movement completes, avoiding repeated cancellation of an animation on every incoming chunk. User scrolling still cancels following immediately.
- A retry accepts a changed branch only if a retained successful branch-creation or explicit context-selection receipt proves that destination in the same repository. Objective/repository changes, unknown outcomes, unverified receipts and unrelated branches remain rejected. Existing completed mutations are never dispatched again.
- Verified branch selection also aliases the active task pointer for the new branch, preserving cumulative evidence when the next user message is “continue”. The alias retains the original task binding and identifies its transition receipt; account, connection, profile, repository and live permission boundaries remain enforced.

## Log issue coverage

| Recorded issue | Resolution and scope |
| --- | --- |
| Two retries fail with saved objective/destination mismatch after branch creation | New receipt-bound branch transition and persisted continuation alias; restart regression checks receipt reuse and rejects unrelated destinations. |
| “Continue” starts again and loses previous write observations | Branch alias above plus PR #791's cumulative receipt recovery across catalog changes. |
| Full tool test remains partial and repeatedly discovers nonexistent `github__github_api` | PR #791's deterministic capability runner, exact known catalog selection, negative discovery cache, no-progress detection and explicit blocked capabilities. Missing authorization/fixtures remain blocked, never marked successful. |
| Invalid `github_commit action=status` | Deterministic planner uses the context tool for status; action-specific validation rejects invalid calls before dispatch. |
| Attempt to commit a test README edit to main | Default-branch guard remains enforced. The deterministic test runner uses an owned, unchanged disposable branch and cleans it up after dependency checks. Broad testing does not authorize arbitrary README commits. |
| Reusing a main-bound patch on `test-branch`; guessed edit anchor; mixed patch forms | Deterministic self-test no longer invents README edits. Branch-bound staging, exact anchor validation and mutually exclusive patch forms remain enforced. Real edits must be restaged from the selected branch's observed content. |
| CI logs requested while the job is still running, producing repeated 404 | PR #791 returns pending-log availability and selects completed jobs for log retrieval; genuine permission/not-found errors remain distinguishable. |
| Capability counts omit previous observations; all rows mislabeled with `test-branch`; PR state labeled as check state | Inherited cumulative ledger and typed workspace/resource identity and field labels. |
| Incomplete local/remote pagination and long final report | Inherited dependency-aware continuation, explicit incomplete/blocked terminal states, deterministic report and bounded table rendering. |
| Missing final usage observations, with large prompt/output reservations | Inherited immutable estimate/interim/final usage lanes and unknown-cost reporting. Reserved budget is not measured consumption. |
| Repeated tool-catalog diagnostics | Inherited unchanged-selection suppression and bounded export window. |
| MCP ports 8101/8102: unexpected stream end, then connection timeout; mcp.jina.ai: DNS failure | Existing shared connection/discovery ownership, one read-only reconnect, bounded backoff, DNS probe coordination and preserved cached schemas cover recovery. These are endpoint/network availability failures; source changes cannot establish that the desktop listeners or DNS are reachable. Device/network verification remains required. |
| Blank current body after failed retry | Fix the pre-stream checkpoint rejection; retained revisions and terminal error remain authoritative. Historical text is not relabeled as a successful current answer. |

## Validation

- Canonical host validation: 299 script tests plus policy, dependency, secret, regex, resource and provenance checks passed after the final implementation changes.
- Portable production-logic regression harness: checkpoint, branch transition, gesture timing and activity ordering; 27 tests passed. Android AtomicFile and timeline DTO are host adapters, not device validation.
- Android unit regressions are committed. A Compose clock test checks intermediate motion and arrival at 1.5 seconds; existing bottom-growth instrumentation covers continuing follow. Both suites are explicitly executed by the API 34/36 acceptance runner with animations enabled and named success packets required.
- Full Android build cannot run in this local host: only JRE 17 is present, SDK 37 is absent, and the Gradle distribution download fails with network unreachable. GitHub CI must compile and run the Android checks. Physical-device scrolling, microphone and MCP reachability are unmeasured here.
