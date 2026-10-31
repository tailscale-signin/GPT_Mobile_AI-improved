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
