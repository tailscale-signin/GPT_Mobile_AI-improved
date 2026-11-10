# Local Voice Plugin: recognition recovery and embedded speech

## Scope and provenance

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Parent PR: **#816**. Working branch: `fix/audit-execution-recovery-2026-11-09`. The task checkpoint records base `main` at `7db0c735f9e86eaece24513fbaf5c0a624f13822`; source inspection began at branch head `0116890e7e411c544babb4afd6c6d67ff35c3f1b`. Research date: November 9, 2026, Toronto time. See [the task checkpoint](../repairs/voice-local-recognition-task.json) for subsequent implementation and validation results.

Intent: repair Android recognition error 10 recovery and add an explicitly selected embedded recognizer to the existing `voice_chat` plugin. This document records the design and researched alternatives. It does not establish a successful Android build, physical microphone test, or measured performance on the ROG Phone 9 Pro.

The existing [Voice Chat design](Voice_Chat_Plugin_2026-11-02.md), [voice redesign](Voice_GitHub_Status_Redesign_2026-11-02.md), and [recovery work](../engineering/github-voice-recovery-2026-11-03.md) already provide a `VoiceAudio` boundary, a half-duplex `VoiceSession`, selected-profile chat dispatch, private-speech policy, and lifecycle fencing. Extend these boundaries rather than introduce another conversation dispatcher.

## Diagnosis and immediate repair

Android defines `SpeechRecognizer.ERROR_TOO_MANY_REQUESTS` as numeric error **10**, distinct from recognizer-busy error 8. The public API does not publish a universal retry-after interval. It also says the recognizer is unsuitable for continuous recognition, requires calls on the main thread, and requires waiting for results or an error before starting another request after `stopListening()`. [Android contract](https://developer.android.com/reference/android/speech/SpeechRecognizer).

The supplied message establishes a service rejection; it does not identify the installed recognition provider, its internal quota, or prove overlapping app requests. Preserve the numeric error, provider/mode, attempt ID, failed stage and whether listening actually started. A countdown is the app's retry policy, not a promise that Android has reset its limit.

Required behavior:

1. Retire the failed attempt, cancel its queued callbacks and prevent automatic retry for error 10.
2. Keep admission pacing across screen recreation. Repeated taps, unmute, or returning to the screen must not bypass it.
3. Preserve partial text for review. Switching speech engines must not resend a chat turn or silently discard the draft.
4. Offer local recognition setup in the Voice settings. Once its model is installed, it is an independent recognition path; do not repeatedly probe the rejected system service to establish local readiness.
5. Keep Android speech as an explicit compatibility option. A local-engine failure must not silently switch to a network-capable recognizer.

## Engine decision

**Implemented path: pinned Moonshine English streaming ASR, using its low-level transcriber with app-owned microphone capture.** The exact `v0.1.5` source was inspected at `234f60faa0eb388b01cdf7e60aca232af37aefda`; physical-device qualification remains required. The reason is straightforward official Maven packaging and a compact streaming model, not an unmeasured accuracy or speed advantage on this device. **Sherpa-ONNX is the preferred broader expansion candidate**, particularly for multiple recognizers, local TTS and model-specific Qualcomm acceleration.

| Candidate | Evidence and fit | Tradeoff for this change |
| --- | --- | --- |
| [Moonshine](https://github.com/moonshine-ai/moonshine) | Official Android library; streaming ASR; English model packs; inspectable Java/JNI integration | Pin source, artifact and model together. Avoid the affected convenience microphone lifecycle in 0.1.5. |
| [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) | Kotlin/JNI, streaming and offline ASR, VAD, TTS, Android examples and Qualcomm QNN support | Wider model/configuration surface and runtime packaging; a good next engine behind the same boundary. |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | C/C++ Whisper implementation, quantization, Android example, CPU and selected GPU backends | Requires native build/JNI integration and utterance/window management; valuable multilingual or gateway option. |
| [Vosk](https://github.com/alphacep/vosk-api) | Offline Android/Java recognition and incremental results; compact language-specific packs | Attractive small baseline; compare conversational accuracy on the same recordings before choosing it over newer models. |
| [Qualcomm QIDK](https://github.com/qualcomm/qidk) | Vendor Android ASR demonstrations and model deployment examples | An integration reference, not a universal drop-in speech service or proof of compatibility with this app's packaged QAIRT. |

Upstream benchmarks use different datasets and hardware. Do not combine their WER figures into a ranking or present a desktop result as phone performance.

## Moonshine packaging and lifecycle

The implementation pins official `ai.moonshine:moonshine-voice:0.1.5`; [current upstream quickstart](https://github.com/moonshine-ai/moonshine/blob/main/docs/quickstart.md) may document newer APIs. Preserve the reviewed version instead of mixing a moving `main` source example with an older binary.

The [official Maven 0.1.5 AAR](https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.5/moonshine-voice-0.1.5.aar) was downloaded and compared with its publisher's `.sha256`: **24,321,396 bytes**, SHA-256 `ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571`. This is dependency provenance, not successful application execution.

For the first model, use **Tiny Streaming English**, with the inspected `quantized_26_08_21` pack: exactly **45,233,659 bytes across eight required files**, excluding native runtime, temporary downloads and inference memory. It is a 34-million-parameter model; parameter count is not installed byte size. The [pinned upstream metadata](https://github.com/moonshine-ai/moonshine/blob/234f60faa0eb388b01cdf7e60aca232af37aefda/core/moonshine-model-file-metadata.generated.cpp) supplies file sizes and CRC32C checksums. The application's SHA-256 values instead come from Git LFS object IDs in the [official mirror at immutable revision `0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae`](https://huggingface.co/moonshine-ai/moonshine-voice-assets/tree/0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae/model/tiny-streaming-en/quantized_26_08_21), with small ordinary Git files fetched and hashed separately. The [release license](https://github.com/moonshine-ai/moonshine/blob/234f60faa0eb388b01cdf7e60aca232af37aefda/LICENSE) records model provenance. Other languages and larger models require separate selection and validation.

[Upstream issue #223](https://github.com/moonshine-ai/moonshine/issues/223) reports a native destroyed-mutex crash in Android 0.1.5 when `MicTranscriber.stop()` is immediately followed by `close()`. The maintainer identifies a fix on the development 0.1.6 branch; that does not establish that the pinned 0.1.5 binary contains it. Use `AudioRecord` plus the lower-level `Transcriber`, serialize native operations, stop capture promptly, and release the transcriber only after its in-flight decode has completed. A timed join followed by unconditional native destruction is insufficient. Generation checks prevent stale UI callbacks but cannot make freeing a live native object safe.

Record the Maven AAR and associated POM/module SHA-256 values in `gradle/verification-metadata.xml` after checking the publisher artifacts. Retain strict verification; do not add blanket trusted-artifact patterns. Inspect packaged native ABIs, ELF alignment and library names. Existing build rules select `arm64-v8a`/`x86_64` and already own QAIRT 2.51.0; do not resolve native conflicts using an unexplained `pickFirst`.

Model download should be an explicit setup action. Use app-private storage, bounded downloads, verified individual files, a temporary installation directory and atomic promotion only after every file passes. A cancelled or incomplete installation remains unavailable. Keep model identity/version/license in metadata and verify readiness again before inference. Downloading a model uses the network; recognition after setup must work without network access. Do not download executable replacements through the model manager.

## Sherpa-ONNX expansion

[Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) provides a useful common runtime for streaming and nonstreaming ASR, voice activity detection, speech output and Kotlin/Android integration. Its [streaming Android example](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt) captures mono PCM through `AudioRecord`, feeds a recognizer stream, obtains partial results, detects endpoints and resets the stream. This bypasses the `SpeechRecognizer` service API and its request quota.

The inspected current release is [v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1), October 26, 2026. Its [official release assets](https://github.com/k2-fsa/sherpa-onnx/releases) include `sherpa-onnx-2.0.1.aar`, listed at 47.2 MB with SHA-256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`. This is a multi-ABI artifact size, not the final APK increase. An official [AAR source project](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxAar/README.md) is available. JitPack is unnecessary: an audited release download or a reproducible, pinned build is sufficient. A flat-file dependency needs its own checksum-enforced acquisition task; Maven verification metadata does not authenticate an arbitrary local AAR.

A compact English baseline is [`sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17). Its selected INT8 encoder, decoder, joiner and tokens total approximately 44 MB, based on the [file inventory](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/tree/main). It is English-only and its model card reports Apache-2.0. Its age and LibriSpeech training mean noisy conversational performance must be measured; compactness alone does not establish best accuracy.

For optional English, Portuguese and Arabic recognition, Sherpa supports multilingual [Whisper ONNX packs](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html). Start evaluation with non-`.en` `base` or `small`, paired with VAD, and measure each required language separately. Models ending `.en` are English-only. A VAD-triggered utterance decoder and an intrinsically streaming transducer have different latency/endpoint behavior; expose that distinction through configuration and measurements. The initial Moonshine English pack must refuse unsupported locales instead of silently interpreting them as English.

Avoid bundling another incompatible `libonnxruntime.so` alongside the selected runtime. [Upstream issue #3291](https://github.com/k2-fsa/sherpa-onnx/issues/3291) documents collisions when a separate ORT Java package is introduced. Resolve versions/build composition explicitly before adding a second ONNX consumer.

## Qualcomm acceleration and alternatives

The [Sherpa QNN documentation](https://k2-fsa.github.io/sherpa/onnx/qnn/index.html) explicitly includes **SM8750**, relevant to the ROG Phone 9 Pro. [Streaming Android QNN examples](https://k2-fsa.github.io/sherpa/onnx/android/apk-qnn-asr-streaming.html) use HTP and specific converted Zipformer packs. This supports investigating an NPU path; it does not mean the compact CPU pack, Whisper, or every supported model can be accelerated by changing one provider flag.

A future accelerated backend needs a matching model, SDK/runtime libraries, HTP dependencies and observed successful execution. Reuse the app's QAIRT ownership/provenance checks. Keep CPU fallback available and report why it was selected. Do not infer active NPU execution from capability advertising or the presence of a Qualcomm chipset.

The [QIDK Whisper Android example](https://github.com/qualcomm/qidk/blob/master/Solutions/NLPSolution3-AutomaticSpeechRecognition-Whisper/README.md) describes a fixed-shape, quantized encoder on Snapdragon DSP and an FP32 TFLite decoder. Its split design illustrates preprocessing, encoder/decoder and runtime integration work that a complete voice adapter requires. The newer [ASR/LLM/TTS example](https://github.com/qualcomm/qidk/tree/master/GenAI-Solutions/ASR-LLM-TTS) is also relevant; model/runtime combinations still require validation against this application's version.

[whisper.cpp](https://github.com/ggml-org/whisper.cpp) remains useful for multilingual transcription and a PC gateway mode. Its MIT runtime supports CPU inference and quantized models, with an [Android example](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android). A gateway deployment moves recognition to the PC; it is not phone-local and must clearly identify where microphone audio goes.

[Vosk](https://alphacephei.com/vosk/) provides offline streaming recognition and Android/Java integration. Its [model table](https://alphacephei.com/vosk/models) lists a 40M English mobile pack and separates model licenses. Consider it for compact devices and vocabulary-constrained tasks. Compare errors on actual conversational input; do not infer comparative quality from model size.

## Speech output, licensing and privacy

Speech recognition, speech output and chat inference are separate choices. **Local ASR does not make a cloud-selected LLM local.** Preserve the selected AI profile and existing chat dispatch. Show recognition location separately from response-generation location. Keep raw microphone buffers transient; existing transcript persistence follows chat behavior. The first embedded-ASR change may retain Android TTS, subject to the existing offline-voice/network-consent checks. Do not label the whole session offline if any selected component needs the network.

Check runtime code, model weights, tokenizer/phonemizer and voice-data licenses independently. Moonshine's [current model inventory](https://github.com/moonshine-ai/moonshine/blob/main/docs/models/available-models.md) identifies MIT streaming models while retaining a noncommercial license for older non-English nonstreaming models. That does not authorize substituting a different pack without recording its own license. Sherpa's Apache-2.0 runtime does not make every supported third-party model Apache-2.0; Vosk's model table likewise contains several licenses.

Sherpa [v2.0.0 removed `espeak-ng` and `piper-phonemize`](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.0). Its [migration discussion](https://github.com/k2-fsa/sherpa-onnx/issues/3731) describes lexicons or an external phonemization pipeline. Old Piper/Kokoro tutorials cannot establish compatibility with 2.x. [Kokoro documentation](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html) also limits its documented v1.0 integration to English/Chinese despite other voices in the underlying model. Qualify each language/frontend/voice pack before presenting it in the plugin.

## Integration and acceptance

Keep one recognition owner and one native execution queue. Engine selection belongs in Voice settings with installed/download/progress/error states and an explicit local label. A missing model opens setup; unsupported language remains actionable. Local setup must not force a provider switch or erase existing chat. Continue using the session's foreground, permission, mute, draft review, playback and disposal gates. Preserve half-duplex audio initially; reliable barge-in requires separately tested echo handling.

The actual Maven AAR was inspected, including its Java API signatures and six native ELF libraries. Those libraries passed structural checks for 16 KiB load-segment alignment; this does not establish that Android can load them together with the full application's other native libraries. Release rules retain the JNI entry points and JNI-accessed transcript DTOs against R8 shrinking. Three native/setup instrumentation checks are wired into the existing API 34/36 acceptance path; they have **not been executed** in this workspace.

The focused host validation passed **101 tests across 13 suites**. Thirteen production voice files compiled against real Android 32/34, Robolectric and Moonshine 0.1.5 API artifacts without warnings or errors; all 26 production/test file hashes still matched the precompile snapshot. The new local-backend tests substitute controlled microphone/native decoders. These checks establish lifecycle and recovery behavior, not native transcription quality, on-device runtime compatibility, Compose rendering, or successful full release APK execution. See the task checkpoint for the validation receipt and build-environment limits.

Required automated regressions cover error-10 manual recovery; duplicate starts; stale callbacks; draft retention; backend selection without system-service fallback; corrupt/missing/cancelled model installation; permission/background cancellation; serialized stop/release; and one submitted message per final utterance. Test behavior through controlled audio/runtime seams, not only source-string assertions.

Device acceptance must record exact app SHA, model fingerprint, runtime, device and backend. Test airplane-mode recognition after installation, 30 conversational turns, silence, rapid open/close, microphone privacy toggle, Bluetooth changes, interruption, rotation and screen lock. Measure partial/final latency, sustained memory and thermal behavior while the selected local LLM is also running. ROG Phone 9 Pro ASR performance, NPU acceleration, battery impact and audible voice quality remain **not measured** until those runs occur.
