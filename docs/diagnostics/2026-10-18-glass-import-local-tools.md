# October 18 diagnostics repair

Source: app-diagnostics-4792478374200410058.jsonl, app commit 6e2bf27a.
Branch: fix/glass-import-local-tools-2026-10-18.

## Changes

- The model import action opens a single-document picker. `.litertlm`, `.gguf`, `.localmodel` and manifest ZIP packages use the existing transactional copy/install pipeline rather than a seekable source reference. ZIP review no longer requires external extraction or a folder. Existing folder jobs remain readable for compatibility.
- Import installation retains cancellation, staging cleanup, content identity, hash checking, and worker progress. Persisted URI grants permit worker restart and are released after a copied installation. URI-grant acquisition and enqueue preference writes run on IO. Worker failures now record a diagnostic reason instead of only WorkManager FAILURE.
- Shared glass opacity is 0.70. Haze owns the tint once; a second background pass previously compounded opacity. Settings destinations now share a full-screen glass owner over recorded home drawing commands. Scaffolds and top bars inherit transparent background/surface colors within this owner. The retained home backdrop has no interactive/accessibility controls and is cleared when the activity stops. Existing Solid and Blur Off choices remain respected.
- Research sheets and the benchmark result dialog now use the shared popup renderer. New-chat glass uses the shared opacity too.
- Native tool decoding requires explicit runtime capability `true`. `false` and unknown capability use the authorized prompt-tool bridge, without native descriptors. Calls still use the existing execution budget, schema/permission validation, revocation checks, tool-result events, and result continuation. One rejected prompt call can receive a corrective instruction; rejected calls are never dispatched.
- Calculator supports zero-argument `random()` in [0,1), fixing `floor(random()*200000)+1`. This is ordinary, non-cryptographic randomness.

## Diagnostic disposition

| Finding | Disposition |
| --- | --- |
| Four failed model import workers | Replace reference linking with stream installation; add failure diagnostics. Device retest required. |
| Local readiness rejected before native dispatch | Keep rejection; a model that did not install cannot be loaded. Re-import using the repaired flow, then Verify load. No hardware compatibility checks were bypassed. |
| Unknown native tool support | Use app-managed bridge rather than assuming a native function decoder. Add adapter integration regression for unknown support and repair. |
| Calculator unknown random function | Implemented and tested. |
| MCP ports 8101/8102 unreachable | External endpoint/network condition. Restore gateway reachability; no client patch can start the remote servers. |
| Perplexity HTTP 401 | Correct the search connection credential. AI profile credentials are separate. |
| Brave/Jina permission denied/revoked | Review the corresponding grants. Keep enforcement; do not auto-authorize denied tools. |
| DuckDuckGo HTTP 202 challenge/cooldown and parse/transport failures | External search availability; existing alternate endpoints, cooldown and honest failure results retained. Redacted provider payloads prevent a complete root-cause reconstruction. |
| Oversized nine-call stream batch | Existing admission guard rejects the complete round before dispatch and attempts tool-free finalization. Do not dispatch a partial batch or raise the boundary blindly. Provider behavior still needs a fresh run. |
| Frame jank and oversized coroutine methods | Unresolved performance work. The new settings backdrop avoids recomposing a second HomeScreen, but this is not evidence that chat/research frame timing or large methods are fixed. Profile on-device. |
| StrictMode disk reads | Import grant/enqueue persistence moved to IO. Remaining recorded read stacks are needed to attribute other disk reads; not claimed resolved globally. |
| ART monitor/finalizer, JobInfo flag and ImeBackDispatcher messages | Platform messages in this export; no established independent app defect. Preserve the evidence and retest instead of suppressing it. |

## Validation

- 23 focused Kotlin tests: prompt-tool parsing/capture, schema references, document ranges.
- 21 focused Kotlin tests: calculator, file/bundle preview, locator, transactional installation/cancellation.
- The focused harness compiles production logic with boundary substitutes for Android/platform services; it does not execute the adapter integration test or Compose UI.
- 138 Python repository-script unit tests; Android resource and regex preflights; Unified Reliability v2 scope check; changed-file ktlint and git diff whitespace checks.
- Full Gradle compile attempted with JDK 21: blocked before source compilation by unavailable `com.android.legacy-kapt:9.4.1` dependency in the restricted environment. No APK or device-pass claim.

## Device acceptance

1. Open each Settings destination from home, then its nested dialog/menu. Verify 70% tinted backdrop with sharp text, scrolling, back navigation, Solid mode and Blur Off. Background/resume the activity and rotate it.
2. Import one on-device `.litertlm` file and a supported single-file bundle. Verify progress, profile creation and load; remove/move the original and verify that the copied installation still loads. Cancel an import and retry. Restart during an import and verify recovery/grant cleanup.
3. On a tools-disabled/unknown-native-capability model, authorize calculator and one reachable search/read tool. Check actual tool call/result events and an evidence-based final answer. Test malformed arguments, an unknown name and revoked permission. No rejected action may execute.
4. Repeat combined/research workload and inspect frame timing, oversized batches, provider errors and new import diagnostics. Actual vision reading still requires a vision-capable model and its required assets; app-managed tool calling does not grant vision capability to text-only models.
