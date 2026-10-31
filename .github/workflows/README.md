# Workflow inventory and required checks

Workflow entry points remain directly in this directory. Shared setup lives in
`.github/actions/setup-android/action.yml`; putting entry points in subdirectories would
break discovery. This inventory reflects the modernization branch, not the proposed plan.

`PR Validation` runs for every pull request (including stacked feature PRs), main push,
merge group and manual dispatch. Its `Required verification` job always runs and requires
all five producer jobs to succeed: reliability completion, Android tests/heap/APK,
Android lint, repository/gateway contracts and changed Kotlin formatting. No path-filtered workflow is
used as that aggregate gate. Parallelism is between independent jobs; Gradle worker limits
remain bounded. Permissions default to contents:read; publishing jobs declare scoped writes.

Configure the `Required verification` check from the GitHub Actions app in main's ruleset
only after it has run successfully. This source change alone cannot enforce repository
settings. Keep security and device/release workflows in the review/release policy; the
aggregate check is not a claim that physical-device qualification or CodeQL has completed.
See `docs/ci-cd/modernization.md` for remaining settings and evidence gates.

| File | Purpose / workflow name | Triggers | Jobs |
|---|---|---|---|
| `artifact-retention.yml` | Artifact retention | workflow_dispatch | Read-only preview or explicit cleanup |
| `ci-observability.yml` | CI observability | workflow_dispatch, schedule | Opt-in status sampler |
| `ci-status-notification.yml` | CI status notification | workflow_run | Opt-in signed completion notification |
| `audit-workspace.yml` | Diagnostic audit validation | push, pull_request | preflight, android |
| `build-apk.yml` | Build Android APK | push, workflow_dispatch, pull_request | Build APK |
| `codeql.yml` | CodeQL Advanced | push, pull_request, schedule | Analyze (${{ matrix.language }}) |
| `crawler-contracts.yml` | Crawler contracts | pull_request, workflow_dispatch | contracts |
| `debug-build.yml` | Generate Debug Version | workflow_dispatch, push | Build debug APK |
| `device-validation.yml` | Android device validation | workflow_dispatch | device |
| `gateway-stability.yml` | Gateway stability | pull_request, push, workflow_dispatch | gateway |
| `geniex-preview.yml` | GenieX preview validation | workflow_dispatch, pull_request | preview |
| `gguf-native-smoke.yml` | GGUF CPU native smoke | pull_request, workflow_dispatch | native |
| `github-build-downloads.yml` | GitHub build downloads | pull_request, workflow_dispatch | APK catalog and download contracts |
| `ktlint.yml` | Kotlin Lint Check | pull_request | Check Kotlin Code Format |
| `marketplace-contracts.yml` | Marketplace contracts | push, pull_request | contracts |
| `native-memory-smoke.yml` | Minified native memory smoke | pull_request, workflow_dispatch | R8 and real JNI on Android 16 |
| `post-merge-validation.yml` | Post-merge correctness | push, workflow_dispatch | Post-merge contracts |
| `pr-validation.yml` | PR Validation | pull_request, push, merge_group, workflow_dispatch | Unified Reliability v2 completion gate, Unit tests/heap/APK, Android lint, Repository and gateway contracts, Changed Kotlin formatting, Required verification |
| `release-0950-acceptance.yml` | v0.9.50 Android acceptance | pull_request, push, workflow_dispatch | emulator |
| `release-build.yml` | Publish Signed Release | workflow_dispatch, push | Check whether the version changed, Validate exact release commit, Build, verify and publish |
| `remote-diagnostics.yml` | Remote diagnostics | workflow_dispatch | Resource and XML preflight, ${{ matrix.label }}, PR diagnostic report |
| `scoreboard.yml` | Community Scoreboard | issues, push, workflow_dispatch | validate-and-publish |
| `ui-review-apk.yml` | UI Review APK | pull_request, workflow_dispatch | Build draft review APK |
| `unsigned-release-build.yml` | Build Unsigned Release | workflow_dispatch, pull_request | Build Release APKs and AAB (unsigned) |
| `workflow-health.yml` | Workflow health | workflow_dispatch, schedule | report |

Inputs are declared in each workflow's `workflow_dispatch` block. In particular,
`device-validation.yml` requires a trusted android-device runner and explicit model/backend
inputs; it must never run arbitrary fork code on that runner. Release workflows additionally
require the configured signing and release credentials and exact-commit validation.

Artifacts use explicit retention (normally 14 days, diagnostic failures 7 days, workflow
health 30 days). Check cancellation, timeout, first failure and current SHA before retrying.
Main/release publishing workflows serialize and avoid cancellation; pull-request verification
cancels superseded runs. Legacy targeted workflows remain to preserve hardware and release
coverage until replacements have equivalent measured evidence.

## GitHub reliability additions

`pr-validation.yml` runs Android lint independently from unit tests, heap recovery
and APK verification; both remain required by the aggregate gate. Existing cache
isolation and bounded worker memory remain in force. Release workflows retain
local Gradle `--profile` and shrinker reports for 14 days; they do not upload build
scans. Compare repeated equivalent builds before claiming improvements.

`ci-status-notification.yml` is an optional completion webhook, disabled unless
repository variable `CI_STATUS_WEBHOOK_ENABLED=true`. Configure repository secrets
`CI_STATUS_WEBHOOK_URL` (HTTPS) and `CI_STATUS_WEBHOOK_SECRET` to enable it. The
receiver verifies HMAC-SHA256 over the exact request bytes with
`X-Hub-Signature-256`, and deduplicates `X-CI-Delivery` (repo/run/attempt). The
trusted default-branch script sends only status, SHA and the GitHub run URL;
never triggering-branch code, source, logs or tokens. Redirects and automatic
retries are disabled. Nothing is sent until the owner configures a destination.

`ci-observability.yml` adds an optional 15-minute status sampler. Set
`CI_OBSERVABILITY_ENABLED=true` to retain read-only run/job/PR snapshots. It caches
completed run-attempt diagnostics for two hours (64 entries / 1 MiB), always reads
live status, and reports incomplete pagination. The cache never skips a build or
authorizes a merge. Notification mode also needs the webhook settings above.
`CI_STATUS_CHANNELS` can map event kinds (`summary`, `warning`, `error`,
`pr_merged`, `pr_stalled`, `long_running`) to receiver-side channel names. This is a
signed generic JSON receiver contract; a Slack adapter must translate the payload.
Summary/warning/error/merged/stalled/long-running delivery buckets are respectively
24h/1h/15m/1h/4h/30m. A stalled PR means no update for four hours, not proof that CI
is stuck. Notifications are bounded to 20 attempts / 120 seconds per invocation;
deferred counts are reported. GitHub schedule delays and delivery errors remain
possible. Nothing is sent before configuration, and no webhook is configured here.

`artifact-retention.yml` previews allowlisted artifacts against a 5 GB quota.
Explicit manual `apply=true` enables cleanup after a complete inventory and a fresh
identity check for every deletion. The newest non-expired artifact per name/branch
is protected; unknown artifact names are preserved. Quota cleanup never removes
artifacts younger than seven days. A `pending_delete` receipt after failure means
inspect that artifact's current existence before retrying; no blind retry is made.

`scripts/build_metrics.py` wraps the real unit/APK/release commands and preserves
their exit status. Reports include elapsed time, task cache hits, child RSS and I/O,
APK composition and available JaCoCo counters. The optional `--baseline` comparison
requires the same runner/image/Java path/task identity. It does not establish a
speed or size improvement until comparable reports exist. The command is public
workflow configuration and must never contain inline secrets. Reports remain in
the existing performance/validation artifacts.

`apply-ui-pipeline.yml` is the configurable requested/final/production qualification
entry point, with legacy/GenieX/both runtime selection and independent runtime jobs.
Debug and unsigned release wrappers share `scripts/android_pipeline.py`; signed
publication retains its existing identity and provenance gates. Final/production
visual qualification requires a reviewed manifest under `docs/ui-baselines`.
Requested candidates without one remain explicitly `NOT_QUALIFIED`. No reviewed
baseline was supplied by this change. Candidate/visual/profile artifacts retain
30 days; native stack and telemetry audits still run before qualification.

`prepare-release.yml` creates a 90-day proposal artifact containing a forward
version patch, commit-derived notes/changelog and optional forward-revert plan.
It neither publishes a release nor rewrites tags. `artifact_manifest.py` supplies
actual content digests and runtime/version/source tags for candidate search.

Workflow health and opt-in observability reports evaluate versioned objectives
from `.github/ci-objectives.json`. Availability excludes cancelled/skipped/neutral
and pending runs; p95 uses successful wall durations. Low samples are not declared
healthy, retry counts are not proof of test flakiness, and sampled queue capacity
is not the account runner quota. These dashboards do not authorize merges.
