# Intelligent hybrid batch execution

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Working branch: `fix/github-stress-reliability-2026-10-31`.
Working baseline: `597dff5a4725f050150fcd649bb2961cb8928f6e`.
Base branch: `main`, observed at `9781b63c72bf3a2a4786ede07f0d9e094cd07db2`.

Intent: add adaptive, dependency-aware tool batching to the existing Android
agent and gateway without changing execution ownership or mutation approval.
The current gateway package layout remains compatible; these are additive
Gateway 15 workflow capabilities, not a claim of a complete 15.0 release.

The branch advanced during implementation. Integrated working base:
`99d6046f78573b49493e51e37c867a6a75529041`; main remained at the SHA above.
The existing review, portable-backup and per-profile GitHub settings changes in
draft PR #766 are preserved.

Implemented a gateway-owned durable engineering state machine, principal-scoped
HTTP controls and SQLite receipts/events. Android persists a create key before
dispatch, reconnects to the same job, pages missed updates and shows unread
events in GitHub Chat. Gateway execution does not depend on an open app. The
existing loading header shows milestones and elapsed time; background sync uses
WorkManager and existing notifications.

Independent GitHub reads now use adaptive dependency-aware scheduling in the
Android agent and gateway MCP read bundles. Interrupted confirmed reads replay
their receipts. Writes and approval/context operations remain serial; unknown
write outcomes require reconciliation. The gateway coding backend performs
pinned reads, bounded anchored edits, independent review, atomic tree commits,
draft PR publication, exact-head CI inspection and bounded repair loops.

Merge approval is a separate authenticated UI action bound to the PR, head,
base and principal, expires after 30 minutes, and is freshly checked before
dispatch. Required reviews, unknown policy, missing/skipped required checks,
drafts, conflicts and changed identities block the merge. The supervisor cannot
grant approval. Backup restoration remaps only the local chat association and
preserves remote idempotency and account binding.

Configuration and operational bounds are documented in
[`gateway/INTELLIGENT_ENGINEERING.md`](../../gateway/INTELLIGENT_ENGINEERING.md).
Host credentials, explicit engineering scope and the updated package must be
configured before use. This source change does not deploy a server, start a real
coding job, register a webhook or merge a PR. CI is the build executor; no local
shell sandbox was added. Notifications are OS-scheduled background sync, not
instant FCM push. No 3–12-hour production/hardware benchmark is claimed.

Validation:

- `python3 gateway/check_gateway.py`: the complete Python and browser boundary
  suites pass, including batch scheduling, durable worker recovery without a
  client, transient interruption, signed webhook reconciliation, ownership,
  exact-commit approval, policy pagination, full PR evidence and mutation races.
- `bash scripts/validate-local.sh fast`: 276 host tests and the repository policy,
  dependency catalog, resource, icon, regex and provenance checks pass.
- Kotlin 2.4.20 host compilation with platform fixtures: 13 focused JUnit tests
  pass for batching, interrupted read/write recovery, portable job identity,
  HTTP reconnect cursors, create idempotency and explicit merge requests. The
  production engineering repository and ViewModel also compile against inert
  platform/DI fixtures. These checks do not replace an Android build.
- ktlint 1.3.1 passes on changed Kotlin files; `git diff --check` passes.
- The full Android Gradle compile/unit test attempt is blocked before execution
  by `java.net.SocketException: Network is unreachable` downloading the pinned
  Gradle 9.8.0 distribution. This host also has JDK 17 and no Android SDK 37.
  Exact-head Android compile, unit, lint, APK and device checks remain required
  in CI. No physical-device or multi-hour production execution is claimed.
