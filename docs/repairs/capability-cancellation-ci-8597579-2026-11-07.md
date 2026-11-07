# Capability cancellation regression under coroutine stack-trace recovery

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Parent PR: #811. Base: `b1647150e449a15b50f21c5bcebc6f7ef35949b7`. Work branch: `work/capability-cancellation-ci-8597579`.

## Confirmed failure

PR Validation run `42376267952`, job `128414459199`, completed 7,309 Android unit testcases: 7,303 passed, one failed, five dedicated heap-gate cases were skipped, and zero errors. Artifact `13576541839` has SHA-256 `b845c461fac8c930ad2ed7df50bea7e14844291fca7e2efc0a0cc52c03925801` and metadata naming the exact base head above.

The sole failure was `GatewayCapabilityDiscoveryTest.capabilityCancellationPropagatesWithoutDiscoveryFailureOrRetry`. Its outer-exception `assertSame` failed even though the propagated exception remained a `CancellationException` with the caller's message. The other 30 capability/negotiation cases passed, as did the repaired reduced-motion case. The unit failure correctly prevented the dedicated heap gates and PR review APK step, and canonical Required verification failed. Android lint and the other three PR prerequisites passed.

The Gradle Test Executor command includes JVM `-ea`. The unchanged production host jar reproduced the same assertion failure with `-ea`, passed all seven discovery cases without `-ea`, and passed all seven with `-ea` when coroutine stack-trace recovery was explicitly disabled. This isolates the unsupported exception-object identity assumption: recovery can copy an exception while retaining the original as a cause.

## Change and retained behavior

Only the cancellation regression in `GatewayCapabilityDiscoveryTest.kt` changes. It now requires the propagated failure to be a `CancellationException`, retain the caller's message, and retain the original caller cancellation directly or in a cause chain bounded to eight entries. The bound also prevents a cyclic cause chain from hanging the assertion.

The test explicitly verifies the single request is `GET /v1/gateway/capabilities`, requires exactly one HTTP call, and requires zero connection evictions. A discovery-error wrapper, changed caller cancellation, retry, inference request, or eviction still fails the test. Production discovery, transport, exception handling, and build settings are unchanged.

## Validation

- All 31 capability/API/negotiation tests pass with `-ea -Dkotlinx.coroutines.debug=on -Dkotlinx.coroutines.stacktrace.recovery=true` (2.484 seconds).
- The same 31 tests pass with recovery set to `false` and assertions still enabled (3.188 seconds).
- `bash scripts/validate-local.sh fast` passes 378 tests and its repository, dependency, regex, resource, and whitespace checks.
- ktlint 1.3.1 passes on the changed Kotlin file; independent review found no issue.

The focused harness freshly compiles the real capability API, transport, DTOs, and negotiation sources with the repository's pinned Kotlin and dependency artifacts. It records source and fixture hashes and checks they remain unchanged during validation. Android/platform and logging boundaries are adapted for the Java 17 host. Evidence is retained under `/workspace/scratch/22f000a845c8/gateway-cancellation-ci-host/`, including `source-manifest.json`, `test-modes.json`, and both mode logs. Original-failure controls are retained under `/workspace/scratch/22f000a845c8/gateway-capability-host/cancellation-*-baseline.log`.

There is no local Android SDK, so a fresh full Android run at the integrated follow-up head remains required. No remote job was rerun, no gate or checksum was changed, and no private diagnostic payload was committed.
