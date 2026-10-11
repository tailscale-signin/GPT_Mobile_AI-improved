# Qualcomm LiteRT dispatch build provenance

The checked-in `libLiteRtDispatch_Qualcomm.so` is built from the LiteRT revision used by the app's LiteRT-LM dependency, against the headers for the packaged QAIRT runtime.

| Input | Value |
| --- | --- |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |
| LiteRT revision | `26895c9fbcc25c43faa8c1a98cd1fd28951602c3` |
| QAIRT SDK | `2.51.0.260929` |
| Android NDK | `r28b` |
| Bazel target | `@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so` |
| Output SHA-256 | `af706abd358f35932d8610b26f9ee4ea335e5005913ba67824f700ae3ddef6af` |
| Output size | `674656` bytes |

Build with `LITERT_QAIRT_SDK` pointing to the QAIRT SDK root containing `include/QNN`, using the Android ARM64 LiteRT Bazel configuration. Copy the resulting `libLiteRtDispatch_Qualcomm.so` to `app/src/main/jniLibs/arm64-v8a/`, then regenerate `local-runtime-native-libraries.json` from the packaged APK and run `scripts/check_local_runtime_apk.py`.

All ELF `LOAD` segments in this output are aligned to `0x4000` (16 KiB). This establishes source/runtime ABI provenance and APK structural compatibility; a matching AOT model still requires execution on its target Snapdragon SoC and firmware.

## Reproducible release inputs

`qualcomm-dispatch-inputs.json` pins the LiteRT-LM release commit, its exact
`WORKSPACE` LiteRT revision/archive hash, QAIRT headers digest, NDK and Bazel.
The release source is LiteRT-LM commit `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27`.
The dispatch was rebuilt against QAIRT `2.51.0.260929`, matching the packaged
`com.qualcomm.qti:qnn-runtime:2.51.0` Android runtime (native build
`2.51.0.260929181845`). The complete QNN headers SHA-256 is
`7a768f4969411153847a6bb18ca30628b2b161db6926a325cc10101197162633`
(QNN API 2.40, System API 1.15). The SDK came from Qualcomm’s official
[2.51.0.260929 download](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.51.0.260929/v2.51.0.260929.zip).
LiteRT Core is owned by LiteRT-LM's source pin; adding a separate Core 2.3.0
Android dependency would not rebuild this vendor dispatch or replace the Core
inside `liblitertlm_jni.so`, and could introduce incompatible native interfaces.

Checked on 2026-10-11 against the official
[LiteRT-LM metadata](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml)
and [QNN metadata](https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/maven-metadata.xml):
0.18.0 and 2.51.0 respectively are the latest published Android releases.
[Standalone LiteRT Core](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/maven-metadata.xml)
is 2.3.0; this app uses the supported LiteRT-LM bundle with its upstream Core
revision, rather than asserting that it embeds the separate 2.3.0 AAR.
The QNN AAR and POM were downloaded from Maven Central, checked against the
published checksums, and pinned with SHA-256 in Gradle verification metadata.

With a clean LiteRT-LM checkout at that commit and a versioned SDK root:

```bash
python3 scripts/rebuild_qualcomm_dispatch.py \
  --workspace /path/to/LiteRT-LM \
  --ndk /path/to/android-ndk-r28b \
  --qairt-sdk /path/to/qairt/2.51.0.260929
python3 scripts/check_qnn_provenance.py
```

Install Bazel 7.6.1 and host Clang first. The verified build here used
`--host-cc gcc --host-cxx g++` for host-only FlatBuffers tooling; Android code
was compiled with NDK r28b Clang. The script validates the clean release
checkout and upstream source pin, the complete QNN header digest, ARM64 ELF,
16 KiB LOAD alignment, exported `LiteRtDispatchGetApi`, and absence of a
separate `libLiteRt.so` / `libc++_shared.so` dependency before replacing the
binary and updating the native manifest. Update this Markdown hash/size
alongside generated metadata, then build the APK and run
`scripts/check_local_runtime_apk.py` on it. Strict provenance is enforced by
source validation and debug/release build workflows.

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
