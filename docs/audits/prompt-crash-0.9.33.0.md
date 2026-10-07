# Prompt-time ART abort in v0.9.33.0

## Confirmed release defect

The October 6 trace contains an ART fatal thread dump (`runtime.cc:769`),
PID 5037, aborting TID 5250. Its pasted portion omits the initiating JNI error.
Waiting executor/renderer/network threads in that dump do not establish a
deadlock or a network error.

Independent inspection of the published `app-arm64-v8a-release.apk` confirms
that the MediaPipe JNI contract is broken by release shrinking. APK SHA-256:
`b7a5d2e709ce166531e76e3d3e4c30b0f8b8e294da92ef13b28c36766f77df7c`.

| JNI entry point | Published APK definition |
| --- | --- |
| `Packet.create(long)` | Renamed to `b` |
| `Packet.getNativeHandle()` | Renamed to `c` |
| `Packet.release()` | Renamed to `d` |
| `PacketListCallback.process(List)` | Removed from the interface; implementation renamed |
| `MediaPipeException(int, byte[])` | Original class name missing |
| `ProtoUtil.SerializedMessage.typeName/value` | Original class/field definitions missing |

`tasks-core:1.0.0` contains no AAR consumer rules or JAR ProGuard rules.
Preserving only `native` methods does not preserve Java entry points used by
native workers. MediaPipe's [framework rules](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/java/com/google/mediapipe/framework/proguard.pgcfg)
explicitly preserve callback interfaces, Packet methods, exception construction
and SerializedMessage fields. Its [native graph callbacks](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/java/com/google/mediapipe/framework/jni/graph.cc)
look up `process` by name and invoke it through JNI.

## Repair and prevention

- Preserve the MediaPipe SDK's classes, interfaces and members in R8. Remote
  statistics implementations remain removed by `PrivateMemoryRuntime` before
  shrinking; release APKs now also run the telemetry exclusion check.
- Verify the text encoder's critical Java/JNI signatures and fields before
  native initialization. Missing bindings become a Java linkage failure handled
  by the existing semantic-memory fallback, without entering the unsafe graph.
- Check actual DEX class-data definitions in APKs and bundles, including callback
  implementations and static method flags. Identifier strings or dangling
  references cannot make this check pass. The check rejects the published APK.
- Save opt-in, redacted startup/first-input markers synchronously on the semantic
  owner thread. Native aborts cannot be caught by a coroutine exception handler;
  durable markers help distinguish creation from first-input failures. Regular
  inference does not add a disk sync on each request or each indexed chunk.
- Keep R8 diagnostics as unsigned-build artifacts for subsequent investigation.

## Reproduction and verification

Run the release definition gate on an APK or bundle:

```sh
python3 scripts/check_mediapipe_jni.py /path/to/app.apk
python3 scripts/check_mediapipe_jni.py /path/to/app.aab
python3 -m unittest discover -s scripts/tests
```

Run the real native-memory test against a minified app on Android 16:

```sh
./gradlew :app:connectedNativeSmokeAndroidTest -PnativeMemorySmoke=true \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemoryInstrumentedTest
```

This build inherits release shrinking and uses the separate
`dev.melo.gptmobile.improved.nativesmoke` package and a test signing key. The test
executes the actual packaged encoder and ObjectBox index, checks finite
100-dimensional results and scope/deletion, and repeatedly closes and reopens
native resources across callers. The new PR workflow runs it on an Android 16
x86-64 emulator. An arm64 physical-device prompt check is still needed before
calling the installed-phone crash resolved.

With app log tracking enabled, a healthy first initialization records
`EMBEDDING_ENGINE_STARTING`, `EMBEDDING_ENGINE_CREATED`,
`EMBEDDING_FIRST_INPUT_STARTED`, then `EMBEDDING_FIRST_INPUT_COMPLETED`.
These markers contain no prompt or memory text. Temporary conversations and
disabled tracking suppress them, and exports include the app version and
pending queued events.
