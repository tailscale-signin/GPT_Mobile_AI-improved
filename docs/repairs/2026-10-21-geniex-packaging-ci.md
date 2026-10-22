# GenieX minified packaging CI repair

The CI results for `3602cf9d4672d23875b0f58fa2f3f7bc400e3bb0` confirmed that the prior repairs passed PR Validation, diagnostic audit, Kotlin lint, gateway checks, native CPU smoke, minified native memory smoke, UI APK, Android acceptance, marketplace contracts, build downloads and CodeQL. GenieX preview validation was the sole failing current workflow; there were no review threads.

In [GenieX run 39704055995](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/39704055995), unit tests and the debug APK passed. The step named “Validate release shrinker” also completed R8 and stored its cache entry. It then advanced to `packageNativeSmoke`, where the combined invocation exhausted its 25-minute step allowance. The retained log does not establish an R8 error or a Java out-of-memory exception.

The standard native-memory-smoke workflow already separates shrinking from packaging because the in-process Kotlin/KSP/R8 analysis heap can remain live when ApkFlinger starts. GenieX now uses the same process boundary:

1. Run `:app:minifyNativeSmokeWithR8` with `--no-daemon` and let that Gradle process exit.
2. Run `:app:assembleNativeSmoke` in a fresh process, reusing completed shrinker outputs.
3. Run all existing native-stack, telemetry and Maven AAR audits before publishing artifacts.

Both stages retain the existing 4 GiB heap, one-worker setting, GenieX/native-smoke flags and individual 25-minute timeout. The overall job allows 120 minutes for four bounded build stages plus setup and audits. Packaging receives its own `package.log`, included in the existing always-uploaded diagnostics directory. Application code, minification, resource shrinking, library selection and audit requirements are unchanged.

Local validation parsed the workflow YAML, syntax-checked every shell block, and checked stage ordering, matching Gradle flags, independent process invocations and timeout coverage. `git diff --check` passed. Runtime confirmation must come from the new GenieX workflow run; the previous green app and native checks are evidence for the unchanged source, not a substitute for validating this CI process change.
