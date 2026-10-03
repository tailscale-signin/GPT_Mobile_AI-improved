# Validation and dependency maintenance

## Reproducible builds

Use JDK 21 and the repository Gradle wrapper. Install `platforms;android-37.0` and `build-tools;37.0.0`. The `:app` project contains production/debug/benchmark variants; `:macrobenchmark` is a self-instrumenting device test application.

`gradle/verification-metadata.xml` pins SHA-256 checksums for build plugins, metadata, Android/JVM artifacts, lint and test dependencies. Default verification is strict. Do not add broad trusted-artifact exclusions or turn off verification to make a build pass. The initial checksum set is bootstrapped from configured repositories, not publisher-signature attestation.

For an intentional dependency update, review the official release, artifact origin, compatibility and native inventory. Regenerate metadata with the affected tasks in a disposable checkout, review version and checksum changes, then run the tasks again **without** `--write-verification-metadata`:

```bash
./gradlew --write-verification-metadata sha256 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :macrobenchmark:assembleBenchmark :app:assembleBenchmark
./gradlew --dependency-verification strict :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :macrobenchmark:assembleBenchmark :app:assembleBenchmark
```

Also validate with a fresh Gradle dependency cache before publishing verification changes. A warm cache can hide missing parent POM or Gradle module metadata checksums that a clean CI runner requests. Use a disposable checkout and keep strict verification enabled:

```bash
verification_cache=$(mktemp -d)
GRADLE_USER_HOME="$verification_cache" ./gradlew --no-daemon --dependency-verification strict :app:testDebugUnitTest :app:jacocoTestReport
GRADLE_USER_HOME="$verification_cache" ./gradlew --no-daemon --dependency-verification strict :app:lintDebug :app:assembleDebug
```

If verification reports a missing checksum, retrieve that exact coordinate from its configured official repository and review the digest before adding it. Investigate checksum mismatches separately; do not replace existing hashes or disable metadata verification to resolve them. Failed PR/CodeQL jobs retain the dependency-verification report as an artifact.

The repository defaults to one worker, sequential projects and in-process Kotlin/KSP compilation. On a memory-constrained builder, run each variant in a separate invocation with `--no-daemon` so compiler/lint state is released between variants. Do not run separate Gradle invocations concurrently.

Gateway dependencies are specified in `gateway/requirements.in`; `requirements.txt` includes all transitive versions and allowed PyPI artifact SHA-256 hashes. Resolve updates in a clean Python 3.12+ environment, review hashes against the official release, install with hash verification, and run both startup/authentication and contract tests:

```bash
python3 -m venv /tmp/gateway-validation
/tmp/gateway-validation/bin/python -m pip install -r gateway/requirements.txt
/tmp/gateway-validation/bin/python -m pip check
/tmp/gateway-validation/bin/python -m unittest discover -s gateway/tests
```

## Required repository gates

- Kotlin: ktlint 1.3.1 with the repository `.editorconfig`.
- Android resources/regex preflight: `bash scripts/validate.sh resources` and `python3 scripts/check_android_regex.py`.
- JVM tests, lint, debug APK and instrumented-test APK compilation.
- Room schema export must match versioned history (`scripts/check_room_schemas.py`). Migration tests exercise persisted evidence, backup restoration and scope/draft/private-session records; older schemas have dedicated upgrade fixtures.
- Packaged native/runtime inventory: `scripts/check_local_runtime_apk.py` validates runtime, ABI and model packaging. It cannot establish actual NPU execution or thermal performance.
- PR Validation runs the principal test/lint/APK pipeline once per PR event. Additional diagnostics and physical-device jobs are explicitly dispatched.

## Device validation

The manual `Android device validation` workflow targets a trusted self-hosted runner labelled `android-device`. Do not execute untrusted fork code on a device runner. Keep JDK/SDK versions aligned with the build configuration. Run separately on a compatible Qualcomm phone and a non-Qualcomm reference phone, recording device/build, app commit, model revision, accelerator, threads, context/output caps, thermal state and battery conditions.

The suite includes real native-memory/Keystore/database tests, chat autoscroll behavior, cold startup, 1,000-item synthetic chat scrolling, streaming frames and Baseline Profile generation. Benchmarks use synthetic data; they do not load a user's account or call paid services. Also manually exercise settings, unread navigation, share cancellation, locking, fold/rotation with drafts, and sustained native generation/cancellation.

Generated profile files come from `BaselineProfileGenerator`; inspect them and copy the production-relevant rules into `app/src/main/baseline-prof.txt`. Exclude benchmark-only activity rules. Build again and compare `CompilationMode.None` against `CompilationMode.Partial(BaselineProfileMode.Require)` on the same physical device. No profile is checked in until it has been generated and its effect measured.

Compare AndroidX JSON outputs using:

```bash
python3 scripts/check_performance_results.py baseline.json candidate.json --tolerance 0.15
```

The script compares matching startup/frame latency metrics, including sampled frame percentiles, and rejects absent/mismatched model/build identity. Confirm physical device, workload and settings also match. The initial 15% tolerance is configurable; refine it from repeated baseline variance. A pass is limited to the measured suite. No physical device was attached to the implementation environment; measured device results must be attached before release approval.

## MCP compatibility

| Peer | Transport | Checked behavior |
| --- | --- | --- |
| SDK-compatible legacy server | Kotlin MCP SDK initialization/session transport | Legacy catalog/call fixtures; ambiguous tool calls are not automatically retried |
| MCP 2026-07-28 server | Explicit per-request metadata + Streamable HTTP JSON/SSE | Version discovery, ID matching, bounded responses/pagination, header mirroring and form input exchanges |
| Modern tasks extension | Negotiated `io.modelcontextprotocol/tasks` draft | Durable handle storage, status/result polling, cooperative cancellation, form input and original-connection checks |
| Unsupported capability | No implicit fallback for writes | Actionable failure; original action is not replayed |

Legacy fallback is limited to the read-only protocol discovery probe. Task input keys are saved before submission; if delivery is ambiguous, the client does not silently submit the same input again. Endpoint/credential changes require the original connection to recover a task. No notification subscription, URL elicitation or sampling is advertised by the modern transport.

## Memory evaluation

The in-app memory corpus is versioned, synthetic, local and independent of user memory. Its 4,096/16,384-fact scenarios currently measure deterministic lexical recall and exact-code precision, extraction/scope guards, corpus construction, p95 time and PSS. These figures are not semantic paraphrase recall or multilingual model quality scores. The instrumented local-semantic tests exercise the actual packaged encoder and index. Compare encoder changes on the same devices and corpus before changing defaults.
