# Debug APK packaging heap repair

## Pinned context

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`; PR #819.
- Base: `main` at `e62bded83b3861816c2c6e97116b30c9eb0920f1`.
- Working branch: `fix/voice-chat-crash-and-diagnostics-2026-11-11`.
- Observed head: `57fd7dc3d63960c4226e07ee6ddabc31c7256ddc`.
- Intent: repair the measured diagnostic APK compression heap failure and cover the same uncached build path in PR Validation while preserving bounded execution and verification gates.

## Current failure and comparison

The [diagnostic audit Android job](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43165575025/job/131006607174) completed production compilation and unit tests, then failed its Debug APK build at 06:38:35 UTC on 2026-11-12. The step lasted 8m42s; Gradle reported `BUILD FAILED in 8m 41s`, 79 actionable tasks (25 executed, 54 up-to-date), and exit code 1.

The completed step log, read through GitHub's live job page while lint continued, explicitly reports `java.lang.OutOfMemoryError: Java heap space`. The cause is in `NoCopyByteArrayOutputStream`, `Compressor.deflate`, `BytesSource.build`, and `Sources.from`, reached through `ApkFlinger.writeFile`; the error propagates through `ApkFlinger.close`, `IncrementalPackager.close`, and `PackageAndroidArtifact.IncrementalSplitterRunnable`. This is direct evidence of compression heap exhaustion, distinct from the historical packaging stalls documented in earlier repairs. Neither the 1,140-second process deadline nor the 20-minute step limit was reached.

The [UI Review APK job](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43165574984/job/131006646100) built the same source tree, `591f554e824554152f399cdd24d2a1aa3df7d452`, at synthetic merge commit `cb6c933b5cb5b65ed63b11375ca83373008797dd`. Its `assembleDebug` invocation used a 4 GiB heap and 768 MiB metaspace, and finished successfully in 15m9s at 06:20:42 UTC. All three APK variants subsequently passed the packaged-runtime, 16 KB native alignment, telemetry-absence, and research-dispatch checks. That workflow uses the default common-pool parallelism, so it is evidence for a successful bounded memory configuration, not an otherwise identical experiment.

The [PR Validation job](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43165575032/job/131006758317) also passed its APK step, at 06:53:41 UTC, using the original 3 GiB heap, 1 GiB metaspace and common-pool parallelism of two. Gradle completed in 10m1s, followed by all runtime, privacy, APK-size and research-dispatch checks. It restored `generateDebugGlobalSynthetics`, `desugarDebugFileDependencies`, `mergeExtDexDebug`, and `mergeLibDexDebug` from cache; the failed audit executed all four. Both builds executed `dexBuilderDebug` and `mergeProjectDexDebug`.

Different retained DEX memory before packaging is a plausible explanation for the differing outcomes, but no heap profile establishes that causal link. The audit failed before its 15-minute watchdog. No input, variant or packaging JVM-limit difference was identified between those two invocations. Future PR jobs can miss the same cache entries, so the repair covers both packaging invocations; it does not classify the successful PR run as a failure.

## Repair scope

The diagnostic and PR Validation APK invocations now use the same 4 GiB heap and 768 MiB metaspace ceiling. Both retain one Gradle worker, common-pool parallelism of two, `--no-daemon`, the 1,140-second process deadline, and the 20-minute step limit. The diagnostic watchdog, build metrics, artifact validators and log handling remain intact. Global Gradle defaults and production/test/lint invocations are unchanged.

## Validation

- Both workflow YAML documents parsed successfully; `bash -n` passed for all 38 run blocks (ten diagnostic, 28 PR Validation). A structural comparison confirmed that the only executable changes are the two heap/metaspace replacements; all guards, deadlines and artifact validators are preserved.
- `bash scripts/validate-local.sh fast` passed after both changes: 462 host tests and all canonical policy, schema, dependency, secret, regex, resource and provenance checks. Android regex validation checked 1,551 literal patterns with 76 dynamic patterns explicitly skipped; resource preflight checked 34 XML files.
- Full historical-base-to-working-candidate release-scope validation passed for 1,932 paths. Only the exact evidence-document and task-checkpoint paths were added to the allowlist; every other manifest field is preserved. Checkpoint JSON and `git diff --check` also passed. The full committed-candidate release-scope gate remains required before publication.

Fresh successful diagnostic and PR Validation APK builds on the repaired revision remain required; the successful prior builds do not replace those checks. The machine-readable checkpoint is [audit-apk-heap-2026-11-12-task.json](audit-apk-heap-2026-11-12-task.json).
