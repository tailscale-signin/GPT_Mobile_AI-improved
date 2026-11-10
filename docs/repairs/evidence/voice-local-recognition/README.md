# Local voice recognition validation evidence

The final standalone Kotlin/JUnit validation compiled all 13 production files in `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/` and ran all 13 voice test classes: **101 tests passed, zero failures, and zero Kotlin compilation warnings**.

The manifest captures SHA256 hashes for all 26 production and test files before the final compilation. The exact file set and every hash were checked again after testing and immediately before preserving this evidence; all matched. Hashes identify the tested source independently of subsequent documentation commits.

## Files

- `all-voice-tests.log`: final combined JUnit output, including `OK (101 tests)`.
- `all-voice-validation-receipt.json`: result counts, execution boundary, and unchanged-source check.
- `all-voice-tested-sources.json`: repository-relative source paths and hashes, test class names, and the upstream Moonshine AAR SHA256.

## Execution boundary

Compilation used the actual upstream `ai.moonshine:moonshine-voice:0.1.5` AAR's `classes.jar`, Android API 34 classes, Kotlin 2.4.20, and actual project test dependencies. Android behavior tests ran with Robolectric 4.17 using API 32/34 Android runtimes and Mockito 5.24.0 supplied as its supported premain Java agent. The complete voice production sources were compiled together; the unrelated `AppLogRecorder` dependency was replaced with a no-op logging boundary.

The Moonshine recognizer tests inject fake native decoders and fake microphone capture. Model-store tests use controlled download fixtures. The tests cover application lifecycle, routing, model integrity and installation, bounded audio queues, cancellation, and recovery; they do not execute Moonshine native inference or validate physical microphone behavior, real model accuracy, ASUS device performance, or network model downloads. This is focused JVM/Robolectric verification, not a complete APK/Compose build or physical-device acceptance.

No production or test files were changed, and no tests were rerun, when preserving this final evidence.
