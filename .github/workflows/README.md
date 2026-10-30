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
| `pr-validation.yml` | PR Validation | pull_request, push, merge_group, workflow_dispatch | Unified Reliability v2 completion gate, Unit tests and Android lint, Repository and gateway contracts, Changed Kotlin formatting, Required verification |
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
