# Qualcomm LiteRT dispatch build provenance

The checked-in `libLiteRtDispatch_Qualcomm.so` is built from the LiteRT revision used by the app's LiteRT-LM dependency, against the headers for the packaged QAIRT runtime.

| Input | Value |
| --- | --- |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |
| LiteRT revision | `26895c9fbcc25c43faa8c1a98cd1fd28951602c3` |
| QAIRT SDK | `2.52.0.261104` |
| Android NDK | `29.0.14206865` (r29) |
| Bazel target | `@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so` |
| Output SHA-256 | `8a8b31aa09b5f0998955014ee4c8e2811f993461cfc797d95b30e3999b417e06` |
| Output size | `642088` bytes |

Build with `LITERT_QAIRT_SDK` pointing to the QAIRT SDK root containing `include/QNN`, using the Android ARM64 LiteRT Bazel configuration. Copy the resulting `libLiteRtDispatch_Qualcomm.so` to `app/src/main/jniLibs/arm64-v8a/`, then regenerate `local-runtime-native-libraries.json` from the packaged APK and run `scripts/check_local_runtime_apk.py`.

All ELF `LOAD` segments in this output are aligned to `0x4000` (16 KiB). This establishes source/runtime ABI provenance and APK structural compatibility; a matching AOT model still requires execution on its target Snapdragon SoC and firmware.

## Reproducible release inputs

`qualcomm-dispatch-inputs.json` pins the LiteRT-LM release commit, its exact
`WORKSPACE` LiteRT revision/archive hash, QAIRT headers digest, NDK and Bazel.
The release source is LiteRT-LM commit `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27`.
The dispatch was rebuilt against QAIRT `2.52.0.261104`, matching the packaged
`com.qualcomm.qti:qnn-runtime:2.52.0` Android runtime (native build
`2.52.0.261104115025`) and `com.qualcomm.qti:onnxruntime-android-qnn:2.8.0`.
The complete QNN headers SHA-256 is
`ba0553991309e51262cc9398442ad406331ed7f2708dc78d11c55c1a843491c5`
(QNN API 2.41.0, System API 1.16.0). The SDK came from Qualcomm’s official
[2.52.0.261104 download](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.52.0.261104/v2.52.0.261104.zip).
LiteRT Core is owned by LiteRT-LM's source pin; adding a separate Core 2.3.0
Android dependency would not rebuild this vendor dispatch or replace the Core
inside `liblitertlm_jni.so`, and could introduce incompatible native interfaces.

LiteRT-LM 0.18.0 and the app's ONNX Runtime 1.28.2 CPU baseline remain pinned.
Both Qualcomm AARs and POMs were downloaded from Maven Central, matched against
their publisher SHA-1 sidecars, and pinned with computed SHA-256 in Gradle
verification metadata. The exact publisher URLs, sizes and both digests are in
`qnn-runtime-candidate.json`; its original bytes remain unchanged so the build
receipt's input hash remains verifiable. `qnn-runtime-promotion-2.52.json`
records the subsequent promotion separately.

The actual source build passed in [hosted run 45309295693](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/45309295693)
at repository commit `b50c29febaeb54808a4561955220f5b33bd09253`.
Artifact `14882976564` was downloaded through the authenticated GitHub API and
matched its API SHA-256 `2b7f3c08ae50ced2a39742a53208dfa21ce852fa83978fb0dfd5da1eb891fad7`.
`qnn-dispatch-build-2.52.json` preserves the original build receipt. The binary
depends only on `libc.so`, `libdl.so`, `liblog.so` and `libm.so`. The verified
QAIRT inventory includes the new V85 stub/skeleton as well as the existing V79
files; the installed APK audit must match every packaged QNN payload.

With a clean LiteRT-LM checkout at that commit and a versioned SDK root:

```bash
python3 scripts/rebuild_qualcomm_dispatch.py \
  --workspace /path/to/LiteRT-LM \
  --ndk /path/to/android-ndk-r29 \
  --qairt-sdk /path/to/qairt/2.52.0.261104
python3 scripts/check_qnn_provenance.py
```

Install Bazel 7.6.1 and host Clang first. The verified build here used
`--host-cc gcc --host-cxx g++` for host-only FlatBuffers tooling; Android code
was compiled with NDK r29 Clang. The script validates the clean release
checkout and upstream source pin, the complete QNN header digest, ARM64 ELF,
16 KiB LOAD alignment, exported `LiteRtDispatchGetApi`, and absence of a
separate `libLiteRt.so` / `libc++_shared.so` dependency before replacing the
binary and updating the native manifest. Update this Markdown hash/size
alongside generated metadata, then build the APK and run
`scripts/check_local_runtime_apk.py` on it. Strict provenance is enforced by
source validation and debug/release build workflows.

## Speech plugin and embedded-context compatibility

The tiny hybrid ONNX fixture in
`local-kokoro-runtime/src/testHost/kokoro_epcontext_compatibility_probe.py`
passed with the publisher's QNN EP 2.8 / QAIRT 2.52 **host emulator** and ORT
1.28.0. It exports an embedded EPContext, destroys the original session, reloads
the saved model, and observes both QNN and CPU kernel events. Original metadata,
input/output names and order, a CPU `NonZero` node and its initializer survive.
Prepared and reloaded QNN outputs match bitwise; the maximum floating difference
from CPU is `4.76837158203125e-7` under explicit absolute/relative `1e-6`
tolerances. Integer outputs match exactly. The receipt is
`qnn-host-context-2.8-ort-1.28.json`, with publisher identities in
`qnn-host-probe-publisher-2.8.json`.

This test verifies a small graph's plugin/context compatibility, not Android
loading, the full Kokoro graph, GPU numerical behavior, speech quality or physical
HTP performance. The speech workflow repeats the fixture using the exact pinned
host artifacts; release APK and compatible-device checks remain independent.

## SM8750 / HTP V79 qualification

Package presence is a prerequisite, not an inference result. The opt-in
`QualcommNpuInstrumentedTest` requires an app-readable, matching SM8750 AOT
`.litertlm` model, prepares the app's QNN environment, selects `Backend.NPU`
directly, initializes the native engine and requires a nonempty generated
response. There is no router or CPU fallback in this test.

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.localruntime.QualcommNpuInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.qnnModelPath=/app/readable/model.litertlm \
  -Pandroid.testInstrumentationRunnerArguments.qnnModelSha256="$QNN_MODEL_SHA256"
```

The Android device validation workflow accepts the same model path as
`qnn_model_path`, requires `qnn_model_sha256`, and retains test reports, QNN logcat, firmware/SoC, thermal
and memory evidence. Qualification requires two cold starts and two conversations per start through
the production guarded NPU runtime. Missing inputs, skipped tests and missing
success markers report `NOT_QUALIFIED`. This rebuild has **not** been executed
on a physical SM8750 device. No QNN performance or device success claim is
made until that test passes on the target phone/firmware.
