# Qualcomm LiteRT dispatch build provenance

The checked-in `libLiteRtDispatch_Qualcomm.so` is built from the LiteRT revision used by the app's LiteRT-LM dependency, against the headers for the packaged QAIRT runtime.

| Input | Value |
| --- | --- |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |
| LiteRT revision | `26895c9fbcc25c43faa8c1a98cd1fd28951602c3` |
| QAIRT SDK | `2.50.0.260828` |
| Android NDK | `r28b` |
| Bazel target | `@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so` |
| Output SHA-256 | `3a28b06f6089f017bb047b61c3c566e504a2b13dd8c4d18c062c0c8946a6f769` |
| Output size | `674656` bytes |

Build with `LITERT_QAIRT_SDK` pointing to the QAIRT SDK root containing `include/QNN`, using the Android ARM64 LiteRT Bazel configuration. Copy the resulting `libLiteRtDispatch_Qualcomm.so` to `app/src/main/jniLibs/arm64-v8a/`, then regenerate `local-runtime-native-libraries.json` from the packaged APK and run `scripts/check_local_runtime_apk.py`.

All ELF `LOAD` segments in this output are aligned to `0x4000` (16 KiB). This establishes source/runtime ABI provenance and APK structural compatibility; a matching AOT model still requires execution on its target Snapdragon SoC and firmware.

## Reproducible release inputs

`qualcomm-dispatch-inputs.json` pins the LiteRT-LM release commit, its exact
`WORKSPACE` LiteRT revision/archive hash, QAIRT headers digest, NDK and Bazel.
The release source is LiteRT-LM commit `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27`.
Its QAIRT build pin remains `2.50.0.260828`; the newer SDK catalog build
`2.50.40.260831` is not a Maven version to substitute for `qnn-runtime:2.50.0`.
LiteRT Core is owned by LiteRT-LM's source pin; adding a separate Core 2.3.0
Android dependency would not rebuild this vendor dispatch.

With a clean LiteRT-LM checkout at that commit and a versioned SDK root:

```bash
python3 scripts/rebuild_qualcomm_dispatch.py \
  --workspace /path/to/LiteRT-LM \
  --ndk /path/to/android-ndk-r28b \
  --qairt-sdk /path/to/qairt/2.50.0.260828
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
  -Pandroid.testInstrumentationRunnerArguments.qnnModelPath=/app/readable/model.litertlm
```

The Android device validation workflow accepts the same model path as
`qnn_model_path` and retains test reports, QNN logcat, firmware/SoC, thermal
and memory evidence. The test is skipped when no model path is supplied;
a skipped test is not qualification. This rebuild has **not** been executed
on a physical SM8750 device. No QNN performance or device success claim is
made until that test passes on the target phone/firmware.
