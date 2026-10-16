# October 16 local inference, GenieX and Combined Mode repair

Base: `main` at `f6c353b2`. Working branch: `fix/diagnostics-crash-runtime-coverage-2026-10-16`.
This branch integrates the pending Combined Mode and Qualcomm import changes plus the fixes below.

## Evidence and root causes

- October 16 debug export 0.9.52.0/build 121 records 7,140 JSONL lines. A physical ART abort occurred after four LiteRT initialization failures that reported `Input tensor not found`.
- Android ART explicitly recorded `Method exceeds compiler instruction limit: 123014` for `ChatRepositoryImpl$preparedCompleteChat$1$2.invokeSuspend`. The subsequent fatal `throw_dex_pc=188134` is larger than that code's instruction length. The oversized coroutine is a specific and reproducible build-structure risk.
- Combined Mode three source inventories remained incomplete after repair: 36/40 to 26/40, 33/40 to 9/40, and 17/18 to 16/18 missing. The earlier source-preserving and text-lineage repair is integrated but must be retested.
- The external gateway on TCP 8101 repeatedly refused connections. This cannot be fixed solely in the Android client; verify supergateway listener, port, authentication, and transport on the PC.
- The selected `entity_profile` call had neither required `type` nor `value`; keep typed validation and report the error, never invent IDs or user data.
- A separate GenieX run reported `DISPATCH_POLICY_CHANGED`, then prolonged PREFILL without a token. A saved profile name does not establish the SDK. Standard APK `dev.melo.gptmobile.improved` does not package GenieX. The preview package is distinct and is built with `-PgeniexRuntime=true`.

## Implementation

1. Split the oversized `preparedCompleteChat` coroutine to reduce the affected Android ART method size while retaining its admission, authorization, cancellation and final cleanup. Reproduce the crash on the source phone; successful compilation alone is insufficient proof of repair.
2. Install adaptive first-native-token deadlines: 75 seconds for short prompts, up to five minutes for long ones. Once a first text/thinking token arrives, this specific guard switches off and normal per-turn watchdogs remain responsible for productive generations. On timeout, send cancellation; if the worker does not confirm stop within 12 seconds, quarantine/terminate its isolated process. **Never automatically replay native work.**
3. Record actual native worker backend and accelerator, model basename, load time, worker PSS, first-token elapsed time, and timeout reason, without logging full model prompts or private paths.
4. Permit at most three policy snapshot rebuilds while the physical-dispatch fence is still clean. Once any provider/tool/native action begins, policy changes continue to fail closed.
5. The standard APK refuses GenieX manifests. It does not relabel a generic llama.cpp CPU session as a GenieX SDK session. The dedicated preview artifact remains an explicit package/SDK dependency.
6. Include regression tests for prefill windows, safe policy retries, native artifact gating, source coverage, provenance colouring and Qualcomm imports.

## Validation gates

Run the PR's Android build, unit tests, ktlint, release preflight and device emulator jobs, and review all failures. For hardware acceptance, additionally:

- Build GenieX using `./gradlew -PgeniexRuntime=true :app:assembleDebug` (check the actual task from `gradlew tasks`), install the preview APK package `dev.melo.gptmobile.improved.geniex`, and verify it independently from the standard APK.
- Import a qualified GenieX `manifest.json` that matches the selected runtime. Check GGUF text generation on CPU first. GPU/NPU inference requires an export and backend actually qualified on the target phone; a Q4_0 filename is not proof of DSP compatibility.
- Prompt `hi`, confirm `ENGINE_READY` displays the expected GENIEX runtime and `LOCAL_FIRST_TOKEN` follows, then run a longer-context test. If PREFILL exceeds its limit, confirm a terminal timeout, cancellation and no duplicate native generation.
- Test unsupported/corrupt LiteRT exports: display `Input tensor not found` as a compatibility failure without triggering an ART abort or repeated native load.
- Change a setting during *preparation* and verify re-capture, then change it after dispatch and verify no automatic second call.
- Validate all Combined Mode contributor topics remain represented, source attribution is faithful, and incomplete synthesis is clearly labelled.
- Probe TCP 8101 from the device/PC separately; an offline gateway remains degraded, not a successful MCP session.

Do not merge or release on an incomplete check suite or without the device-specific native and crash reproduction tests.
