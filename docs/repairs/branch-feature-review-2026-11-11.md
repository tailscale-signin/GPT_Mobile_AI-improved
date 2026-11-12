# Branch feature review — November 11, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`
Branch: `fix/voice-chat-crash-and-diagnostics-2026-11-11` · PR #819
Reviewed starting head: `160e0e8d222eb48e01a83454d7c070908f15dd14`
Base: `main` at `e62bded83b3861816c2c6e97116b30c9eb0920f1`

## Reproduced defects and repairs

| Finding | Resulting behavior |
| --- | --- |
| Full Batch approval changes the durable job to MERGE but leaves the stage graph at MERGE_READY. Later verification cannot advance that graph. | Approval advances the projection in the same journal transaction. An approved job reaches VERIFY and a completed graph without repeating a merge. |
| Cancellation, superseding a paused job, and changed merge readiness do not update the graph. | Controls update the projection with the job. Pending cancellation and confirmed cancellation remain distinct; late worker output cannot fabricate completed stages. Invalidated readiness returns the graph to CI or needs attention. |
| A claimed stage remains queued, and selected-model loading is labelled as a rate-limit wait. | Worker claims publish running state. Model preparation uses waiting_model; provider cooldowns retain waiting_rate_limit. Neither wait increments completed stages. |
| A GitHub API request can wait behind another job until its accepted deadline expires, then dispatch. | Both identity checks and API admission recheck cancellation and the accepted deadline while queued. Existing MERGE/VERIFY completion exceptions remain unchanged. |

Five added behavioral tests exercise these paths through the real store or account governor. The approval, cancellation, readiness, and queued-deadline tests failed against the starting implementation before their fixes.

## Feature assessment

| Area | Reviewed evidence and limits |
| --- | --- |
| Voice startup and native lifecycle | Inspected deferred Kokoro construction, Moonshine callback fencing, native initialization serialization, Android TTS initialization/retirement, and Voice Chat lifecycle effects. Starting-head CI compiles the app and passes voice/runtime API instrumentation. Physical microphone recognition, Kokoro sound quality and time to first audio remain unmeasured in this review. |
| Provider and GitHub recovery | Inspected durable provider waits, preserved batch siblings, mutation receipts, execution ownership, evidence publication fences, and MCP configuration quarantine. Gateway behavior tests cover selected-model readiness, rate limits, read recovery, and uncertain writes. |
| Full Integration mode | Core host opt-in, Android contracts, saved stage tracking and conservative GitHub API admission exist. This review repairs tracking/control integration. Independent test sharding, mixed-workload fairness and physical device performance remain open in the feature's qualification checklist. Native provider batching remains intentionally unsupported. |
| Diagnostics and issue reporting | Inspected bounded report payloads, crash filtering, WorkManager persistence, and broker write reconciliation. Authentication/reporting tests pass. Direct issue submission requires a deployed reporting service and an ISSUE_REPORT_ORIGIN build setting; the repository default is empty. No live issue was submitted during this review. |
| Packaging and generated metadata | Regenerated the Gateway package hashes and app source inventory after repairs. These source hashes establish package consistency, not executed-line coverage. |

## Starting-head CI evidence

The following observations apply to the starting head and its PR merge checkout, not automatically to later commits:

- [Android validation job](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43091401349/job/130769372756): successful build/test commands; **8,965 passed, 5 skipped, 0 failed**. The job failed when artifact creation returned invalid HTML/upstream responses and a log-upload step timed out.
- [Android 16 instrumentation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43091401229/job/130769249608): instrumented assertions including the local voice runtime/API, model-install action, and download/removal controls passed. Artifact creation subsequently failed.
- [Minified JNI validation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43091401272/job/130769245055): native memory and CPU GGUF checks passed; incompatible GPU tests were capability-skipped. Artifact creation subsequently failed.
- The draft APK and GenieX preview jobs also built successfully before artifact-upload failures. Android lint, CodeQL, formatting, Windows installer, and all four Windows/Linux Gateway matrix jobs passed.

Required verification was not green at review time. Missing artifacts and unfinished checks are not waived by passing underlying tests. New-head CI and physical Android 16 voice testing remain necessary before declaring the branch fully qualified.

Local validation totals and the publication checkpoint are recorded in `branch-feature-review-2026-11-11-task.json`.
