# Gateway client CI triage

## Intent and revision

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Parent pull request: `#2254`
- Base ref: `origin/review-pr-2254`
- Base SHA: `5e4ab1ebdcc8c27110a992e982697e4071c3e5ab`
- Working branch: `work/review-ci-gateway-2026-11-22`
- Intent: reproduce and correct the Gateway admission/dashboard failures reported by stale-head full-unit run `45052936588`, job `137305365276`, against current source. Preserve distinctions between HTTP admission errors, invalid payloads, transport failures, shared network cooldowns, cancellation and explicit recovery.

## Checkpoint

`AGENTS.md` and `.github/instructions/ai-engineering.md` are unchanged from the previously read integration base. The current architecture index identifies Gateway client and network subsystems. Root owns generated inventories, full Android CI and release work. The stale run's original assertions remain evidence to investigate, not permission to weaken the contracts.

## Reproduced cause and correction

The focused current-source harness reproduced exactly the stale CI run's 11 failures among 36 tests: nine `GatewayAdmissionClientTest` failures collapsed to `TRANSPORT_INTERRUPTED`, plus the dashboard retry-count and cancellation assertions.

`gatewayClientExchange` records real local transport failures in the shared `LocalServiceHealth` hold and checks that hold before the next first attempt. The bounded read owner alone may perform its second attempt. The admission test's deliberate exhausted EOF case left its loopback fixture in that shared hold, so later independent tests never reached their mock HTTP responses. Its setup previously cleared only capability evidence.

The dashboard test independently expected cache invalidation to trigger another network attempt during the same connection hold. `GatewayDashboardClient.refresh()` invalidates observation caches; explicit connection recovery uses `LocalServiceHealth.checkAgain`, as the existing force-network UI paths already do. Clearing a result cache must not bypass transport backoff.

The correction stays in the two test classes:

1. Each fixture clears its own connection cooldown before and after a test. The existing capability reset remains in place.
2. Dashboard tests now assert that cache refresh alone returns `LocalServiceUnavailableException` without another network attempt or stale-pool eviction. Explicitly rechecking the same connection then preserves the original two-attempt retry and eviction expectations.
3. A new admission regression verifies that a fresh client and different read surface remain held after an exhausted transport read, report no HTTP response and `NOT_DISPATCHED`, and recover with one successful read after explicit recheck.

Original assertions for HTTP codes, account/source identity, unsupported routes, malformed or oversized responses, secret-free diagnostics, cancellation and TLS handling remain. Production clients, parsers, deadline handling, retry policy and shared cooldown behavior are unchanged. A second worker independently reviewed the test diffs and found no blocking concern.

## Validation

- BEFORE: 36 focused JVM tests ran; exactly 11 failed with the original CI names and failure shapes.
- AFTER: 37 focused JVM tests passed, including the added cross-client/surface recovery regression.
- The harness uses actual Gateway clients, DTO serializers, bounded-body reader, parsers, exchange, retry policy, caches and local connection-health code with pinned Ktor 3.6.0, Kotlin/compiler serialization plugin 2.4.20 and kotlinx serialization/coroutines 1.11.0. Narrow unrelated Android DI, telemetry, owner-control and private-metadata-grant boundaries are supplied locally; the targeted HTTP fixture paths use the regular mock client.
- PASS: `bash scripts/validate-local.sh fast`, including 605 host tests, generated metadata checks, Android regex validation (1,885 literals; 84 dynamic patterns skipped; zero errors), resources/icons and native dependency provenance checks.
- PASS: ktlint 1.3.1 for both changed Kotlin files and `git diff --check`.
- NOT RUN LOCALLY: complete Android compilation, Hilt/Compose generation, full app unit suite or device tests. The parent worker owns required current-head CI.

Baseline and corrected logs, dependency coordinates and the focused host runner are retained at `/workspace/scratch/dfab796cea9c/gateway-host-tests`. `baseline-tests.log` preserves the before-fix failures; `tests.log` contains the successful corrected run.
