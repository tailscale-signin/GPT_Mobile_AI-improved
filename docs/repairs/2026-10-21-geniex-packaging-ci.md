# GenieX minified packaging CI repair

The CI results for `3602cf9d4672d23875b0f58fa2f3f7bc400e3bb0` confirmed that the prior repairs passed PR Validation, diagnostic audit, Kotlin lint, gateway checks, native CPU smoke, minified native memory smoke, UI APK, Android acceptance, marketplace contracts, build downloads and CodeQL. GenieX preview validation was the sole failing current workflow; there were no review threads.

In [GenieX run 39704055995](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/39704055995), unit tests and the debug APK passed. The step named “Validate release shrinker” also completed R8 and stored its cache entry. It then advanced to `packageNativeSmoke`, where the combined invocation exhausted its 25-minute step allowance. The retained log does not establish an R8 error or a Java out-of-memory exception.

The standard native-memory-smoke workflow already separates shrinking from packaging because the in-process Kotlin/KSP/R8 analysis heap can remain live when ApkFlinger starts. GenieX now uses the same process boundary:

1. Run `:app:minifyNativeSmokeWithR8` with `--no-daemon` and let that Gradle process exit.
2. Run `:app:assembleNativeSmoke` in a fresh process, reusing completed shrinker outputs.
3. Run all existing native-stack, telemetry and Maven AAR audits before publishing artifacts.

Both stages retain the existing 4 GiB heap, one-worker setting, GenieX/native-smoke flags and bounded process isolation. A subsequent cold-run showed `:app:packageNativeSmoke` remaining active after AAPT2 completed until its 45-minute step deadline; the audited arm64 GenieX payload includes a roughly 88 MB `libQnnHtpPrepare.so`. The packaging allowance is therefore 90 minutes and the enclosing job is 210 minutes, still finite and independently auditable. Packaging receives its own `package.log`, included in the existing always-uploaded diagnostics directory. Application code, minification, resource shrinking, library selection and audit requirements are unchanged.

Run `41180689148` then reached the same point after every prerequisite and the
shrinker had passed: `packageNativeSmoke` started, AAPT2 shut down, and the step
produced no further output before its full 90-minute timeout. The process had
forced ApkFlinger's separate common pool to one worker. Packaging now retains
Gradle's one-worker and 4 GiB bounds while allowing two common-pool compression
workers. This is the smallest increase that avoids single-worker starvation
without restoring the unbounded parallel compression that previously exhausted
the heap. A one-shot watchdog records bounded process, heap and JVM thread
evidence after 15 minutes if a future packaging attempt remains active; it does
not extend the step timeout or bypass the native and artifact audits.

Local validation parsed the workflow YAML, syntax-checked every shell block, and checked stage ordering, matching Gradle flags, independent process invocations and timeout coverage. `git diff --check` passed. Runtime confirmation must come from the new GenieX workflow run; the previous green app and native checks are evidence for the unchanged source, not a substitute for validating this CI process change.

## 2026-11-01: measured signing deadlock

Repository `tailscale-signin/GPT_Mobile_AI-improved`, branch
`feat/github-operation-resilience-2026-10-31`, PR #769, head
`09fc11cbbfc7c7eca0989f214e9982b1c0c1cd2c`, base main
`be5d55930df8be6caad2380eea71b2f9a6bb0419`.

Run [41252265770](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41252265770)
failed at the 5160-second packaging deadline. Its diagnostic artifact
`13064719419` contains the watchdog captured at `2026-11-01T00:14:26Z`.
The signing thread held the `StableArchive` monitor while waiting for
`IncrementalPackagerBuilder.withSigning` common-pool tasks. Three common-pool
workers were blocked trying to add compressed files under that same monitor.
This evidence supersedes the earlier assumption that more packaging time or
two pool workers alone would resolve the stall. The exact AGP 9.4.1 source
also closes the archive in `finally` after joining compression tasks, so an
earlier packaging exception can be hidden by signing during cleanup. The
original exception was not observable; do not label it a confirmed OOM.

The preview workflow now opts into unsigned packaging **only** for the
GenieX `nativeSmoke` variant, then runs Android `apksigner` in a separate
process with the standard debug key. Both v2 and v3 signatures must verify
before each file is promoted and before the unchanged native, telemetry,
Maven and artifact-index audits. Other variants and normal native-smoke
builds retain their signing configuration. No release is published.

At capture, the packaging heap held 3,819,863 KiB out of 4,194,304 KiB,
with 5,378,188 KiB process RSS on the 16 GiB runner. The isolated packaging
process now has a bounded 6 GiB heap; compile, tests and shrinker keep their
existing bounds. Timeouts and the diagnostic watchdog remain enabled.

Local signing-helper tests cover every split, missing APKs, signature
verification failures and retaining the original file after a failed sign
or verification. Canonical host checks pass (282 tests). Actual Android
packaging and cryptographic verification must pass on the new exact head;
no physical-device qualification has been performed.
