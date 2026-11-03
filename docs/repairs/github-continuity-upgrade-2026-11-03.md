# GitHub continuity upgrade — 2026-11-03

Work targets `feat/voice-github-status-redesign-2026-11-02`, starting at `4039327bd84de8b30be5622c5b25f4f5a74ab1d6`. The full request and remaining scope are tracked in [the task checkpoint](github-continuity-upgrade-2026-11-03-task.json). This is a partial implementation of the full expansion request, not a claim of complete integration acceptance.

## Delivered behavior

- Mutation fences and engineering memory use verified durable account/connection identity. Credential refresh is independent; current secrets are obtained per request. Known legacy token scopes remain authoritative. Archived write fences retain deduplication across restart.
- Integration testing continues through bounded tool batches within the total budget. Discovery and representative capabilities precede optional inventory exhaustion. Collections have independent completion. Equivalent requests and diagnostic questions retain the active task and its receipts.
- Answer verification, engineering acceptance and generation outcome are separate. Successful source answers, including yes/no questions, can complete. Implementations need exact-revision test evidence; missing earlier job pages, a stale head or an old rerun attempt cannot pass acceptance. Room persists partial, blocked and missing-evidence outcomes and exposes them to recovery; chat renders distinct labels. Late stale writes cannot replace a terminal answer.
- Native and gateway CI policy share fixtures. Required checks must succeed. Healthy watches schedule a subsequent observation without failure backoff. Optional PR following archives the old revision and invalidates prior health evidence.
- Status has active/current/history filters, continuation/counts, retained terminal jobs, attempt comparison, steps, failure search and logs/artifact links. Rerun/cancel recheck the exact head and attempt. Health, bounded activity and observed measurements are accessible alongside it.
- New GitHub prompts align at the top and reserve response space. Streaming does not pull the reader to the bottom. Upward motion beyond 64 dp at the prompt releases the anchor; downward motion beyond 32 dp restores it. Fast flicks express the same intent, while weak inertia stops at the prompt. Normal response scrolling remains available.
- HTTP inspection is byte-bounded before parsing. CI failure grouping cites log lines and matching changed files as hypotheses. APK provenance displays package, version, signing certificate, ABIs, SHA and attempt; unavailable attestations remain explicit.
- Export streams ZIP parts and diagnostic events, labels missing evidence, and separates interim/estimated/final token counts. Per-run replay serialization is bounded and cached.

## Gateway validation configuration

Publication now requires successful local validation bound to the exact base SHA and patch. Set `GATEWAY_ENGINEERING_VALIDATION_POLICY` to a host-controlled JSON file whose lowercase repository keys map to `mirror`, `image`, `commands`, and optional `timeout_seconds`.

`mirror` must be an already provisioned local bare Git mirror containing the pinned source SHA. `image` must identify an already installed Docker image by `@sha256:` digest. `commands` is a list of argv lists selected by the host operator, not model-generated shell text. Prepare compiler/dependency caches inside that image: execution has no network, no host credentials, a read-only container root and only the disposable checkout and `/tmp` writable. Missing policy is BLOCKED; it never silently falls back to publishing before tests. Container execution itself still requires live host qualification.

## Isolated acceptance

`scripts/github_live_acceptance.py` is opt-in. It accepts an exact private fixture repository, full source SHA and output path. Credentials come only from `GITHUB_ACCEPTANCE_TOKEN`, `GITHUB_ACCEPTANCE_ROTATED_TOKEN`, and optionally `GITHUB_ACCEPTANCE_REVOKED_TOKEN`. Fixture writes additionally require `--allow-fixture-writes`. A unique absent branch is created once, reconciled by reading with the rotated token after discarding the response, and deleted only if its head remains the owned fixture SHA.

APK installation requires `--apk`, `--emulator-serial`, and `--allow-apk-install`; the selected device must identify as an emulator. Optional `--instrumentation` records that runner's outcome only. Generic success does not establish sign-in, background recovery or reboot acceptance. Those cases remain BLOCKED without case-specific evidence. Missing inputs produce exit code 2 and a report of blockers. Host tests of this harness are mocked boundary tests and never count as live acceptance.

## Remaining scope

Compiler-resolved Kotlin/Java navigation, a dedicated conflict-resolution workspace and native GitHub Enterprise support are not implemented. Gateway-to-Android push delivery, complete activity/approval history, a unified model-readiness probe, broader shared contracts, a full operation-specific task planner, all-tool write fixtures and the remaining performance/accessibility work are partial. Native installation selection is not yet a separately persisted identity dimension; only known legacy credential scopes can migrate. Request scheduling is shared within an authorized account/connection, without pooling separate connections. APK attestation retrieval remains open. Device keyboard/runtime warnings require reproduction; no cause or device performance improvement is claimed. Live account/App-token, process death, reboot, APK installation and gesture/IME/rotation acceptance remain unexecuted.

## Validation

- Production and unit-test Android Kotlin/Java compilation passed with JDK 21, SDK 37 and Gradle 9.8.0.
- 865 selected Android/JVM and Robolectric tests passed across 140 suites, with no failures or skips. This includes Room outcome persistence, same-account credential rotation, revoked/configuration changes, prompt positioning during response growth, drag/flick anchoring, completed progress and compact watch rendering. Exact test filters are recorded in the checkpoint.
- 554 focused host regressions passed. These overlap the Android suite and use platform adapters; they are not additional live acceptance.
- The canonical fast preflight passed with 297 host tests, resource, architecture, dependency, regex, secret and tool-statistics checks.
- Gateway validation passed 335 Python and 7 browser-bridge JavaScript tests. One additional Python LAN test was skipped because this environment has no bindable LAN interface. All 15 authentication-broker/app tests passed with the pinned dependencies installed.
- All changed/new Kotlin files passed ktlint 1.3.1; whitespace checks passed.

The final Android run used fresh generated outputs outside the synchronized workspace, with build telemetry disabled and Byte Buddy preloaded for MockK. It verified runtime/compiled class equality before testing. Earlier attempts encountered stale generated artifacts and filesystem synchronization errors; those attempts are not the final validation evidence. Full CI, lint/APK packaging, configured Docker validation and live/device acceptance are not claimed by these results. Keep PR #788 draft pending those checks.
