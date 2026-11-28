# Streaming recognition, accelerator routes and caption timing

This integration adds recognition choices to the existing Voice Mode capture and
playback pipeline. The selected recognizer, processor, language and CPU thread
choice persist independently of the selected speech output. It preserves the
existing Moonshine Tiny installation, Pocket/Kokoro playback, output caption
alignment, pitch-preserving output speed and adaptive playback buffering.

The implementation and qualification record are separate. See
[`streaming-recognition-integration-2026-11-28-task.json`](../repairs/streaming-recognition-integration-2026-11-28-task.json)
for the checks actually completed and the outstanding release gates. Host tests
and native builds do not establish physical handset accelerator placement,
acoustic accuracy, battery improvement or thermal behavior.

## Model choices

The catalog is
[`VoiceRecognitionModelCatalog.kt`](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/VoiceRecognitionModelCatalog.kt).
It pins each artifact's immutable source revision, exact size and SHA-256, with
separate identities for different processor exports. Source and license evidence
is in [`voice-recognition-models.json`](../audits/voice-recognition-models.json) and
[`recognition-models.md`](../licenses/recognition-models.md).

| Choice | Installed bytes | Recognition route | Supplied timing |
| --- | ---: | --- | --- |
| Moonshine Tiny | 45,233,659 | Existing Moonshine CPU pack | Text; no word intervals enabled |
| Moonshine Streaming Small | 224,067,582 | Moonshine Voice 0.1.6 CPU | Native word starts and ends |
| Moonshine Streaming Medium | 415,954,967 | Moonshine Voice 0.1.6 CPU | Native word starts and ends |
| Zipformer English | 43,649,301 | Streaming Sherpa ONNX CPU | Token emission starts |
| Zipformer French | 128,227,451 | Streaming Sherpa ONNX CPU | Token emission starts |
| Nemotron 3.5 CPU | 682,215,356 | Streaming INT8 ONNX, 560 ms export | Token emission starts |
| Nemotron 3.5 GPU | 2,594,373,279 | FP32 ONNX with QNN GPU, 560 ms export | Token emission starts |
| Nemotron 3.5 NPU | 649,101,700 | Published SM8750/v79 QNN HTP contexts, 320 ms export | Token emission starts |

These are artifact sizes, not measured peak process memory. The GPU pack contains
external FP32 weights and is substantially larger than the CPU INT8 pack. The NPU
download is a 442,444,790-byte archive, verified before bounded extraction. The
different Nemotron chunk sizes identify different published exports; they are not
an application setting that can be changed while reusing an incompatible graph.

The installed Moonshine Small/Medium packs use the verified downloadable
`26_08_21` artifacts compatible with the locked 0.1.6 Android SDK. A newer model
name alone does not justify changing the pins. Language selection is validated
against the exact model. An unsupported language remains an actionable setup
state; the application does not silently label an English model multilingual.

## Native integration and processor evidence

`StreamingAsr.java`, `streaming_asr.cpp` and `SherpaNativeSpeechDecoder` implement
the streaming bridge in the existing `local-kokoro-runtime` library. The pinned
Sherpa source and the existing static ONNX Runtime/QNN environment are shared with
the runtime's established ownership rules. A second incompatible global ONNX
Runtime instance is not loaded for recognition.

CPU recognition uses the model's online recognizer. The GPU route loads the
separate FP32 graphs and requests the QNN GPU execution provider. Some operations,
including unsupported graph nodes, can still execute on CPU. Runtime evidence
records actual GPU/CPU kernel placement for the first completed invocation of
each prepared component. The route cannot claim GPU success until the encoder
has actually executed QNN GPU kernels; a failed provider request is reported as a
failure rather than a successful CPU fallback.

The NPU route uses the publisher's precompiled encoder, decoder and joiner
contexts for Snapdragon 8 Elite / SM8750, HTP v79. It checks the device identity,
prepares the existing Qualcomm runtime, and requires successful execution of all
three components before marking accelerator execution verified. Other SoCs need
their own verified compatible exports. Selecting this route cannot make a
foreign context compatible.

The published HTP contexts are memory mapped instead of copied into a second
large heap buffer before loading. Their immutable pack and SoC identities remain
part of ownership and cache identity. This is a concrete graph-loading memory
optimization, not evidence of a measured startup, battery or thermal improvement.

Provider receipts distinguish `cpu`, `qnn-gpu`, mixed `qnn-gpu+cpu`,
`qnn-htp-context` and `not_executed`. Preparing a model without inference never
qualifies accelerator execution. Canceled and failed streams invalidate success
receipts. Recognition audio duration is derived from accepted real input frames;
the decoder's final zero-padding is excluded.

## Installation, memory and lifecycle

`RecognitionModelRegistry` shares one store for each exact model/processor pack.
The legacy Tiny store is reused so existing capture and output-caption users keep
the same installation lease. Downloads are explicit and cancellable/resumable;
complete artifacts are checked before activation. Archives reject unexpected
paths, links, sparse entries, oversized data and incompatible extracted files.
Removing a pack requires confirmed native retirement and no active leases.

The entry screen and Voice settings expose the same model, processor, language
and thread controls. Selecting a different ASR model still permits installation
of the chosen speech output; recognition setup cannot hide a missing Kokoro
playback pack. Source and license links appear beside installation, and complete
model/native notices are included in the app's existing license screen.

The memory admission policy uses current Android memory availability and an
explicit conservative estimate, never a fabricated RSS measurement. Embedded
Nemotron CPU initialization reserves three installed model sizes plus 512 MiB
because weight deserialization and graph optimization can hold multiple copies.
Other CPU/NPU packs reserve twice installed bytes plus 256 MiB; GPU reserves
twice installed bytes plus 512 MiB. A low-memory signal or insufficient known
headroom prevents loading and offers a smaller model. These estimates do not
guarantee successful execution on every device.

All recognizers use the existing serial capture worker, global native gate and
microphone lease. Capture stops immediately on cancellation, while the owner
retains model and stream resources until native work actually returns. ONNX
Nemotron receives inference termination through its run options. Raw HTP graph
calls check cancellation immediately before and after execution; an in-progress
vendor graph is not forcibly freed or preempted.

Prewarming verifies and prepares the selected model without microphone access.
It releases the stream and retains only a reusable model. A language, vocabulary,
listening-style, processor or model change retires incompatible state. Callbacks
are fenced by capture generation and stream revision.

If native destruction cannot be confirmed, the model's file lease remains pinned
and a process-wide retirement guard prevents another recognizer from loading.
Queued work checks this guard again after acquiring the native gate. Removal
completion is never reported after uncertain cleanup. Restart is the recovery
for this state; asynchronous close is not treated as proof of retirement.

Benchmark admission first holds session audio, then requests both recognizer and
speech-output retirement. It waits for both acknowledgments before any benchmark
model allocation. Output acknowledgment follows the synthesis and playback
workers, with unchanged generation and no quarantined model/preparation. The
benchmark keeps its ownership lease during a bounded, cancellation-resistant
handoff: user/lifecycle cancellation can prevent measurement, but cannot release
ownership while native cleanup is uncertain. Timeout or failed registration
quarantines the retained owners and reports the blocked state. Duplicate or late
callbacks cannot start a second campaign.

## Paired translation integration

The concurrent bidirectional translation feature retains each language's selected
Pocket Multilingual voice and its accepted-turn direction. Explicit source
languages use the selected local recognizer whenever that model supports the
language, including Zipformer French and Nemotron's supported language set. An
explicit Android recognizer choice stays authoritative. Automatic recognition of
either language continues to use the compatible Android offline recognition
service; the local Nemotron prompt is not mislabeled as verified automatic
language detection.

Recognition dictionaries use the capture language, while output substitutions use
the destination voice's language. Changing a pair, source language or ASR model
invalidates capture ownership before another listen. Asynchronous translation
preparation retains unchanged source timings and rejects late draft preparation;
edited or explicitly replaced text cannot inherit timings from different words.

## Recognition timing and captions

`VoiceRecognitionTranscript` is the input-audio timing contract. Every snapshot
contains the capture stream identity, monotonically increasing revision, exact
display text, accepted input frame count, sample rate and timing provenance.
Source ranges use Kotlin/Android UTF-16 character offsets and cannot split a
surrogate pair. Timings outside accepted audio, malformed ranges, unsupported
token encodings or text disagreement are rejected while recognized text remains
usable.

Sherpa token emissions supply starts. A following token does not establish the
previous word's acoustic end, so the application leaves those ends unknown.
Moonshine Streaming word intervals retain the SDK's absolute stream coordinates;
line offsets are not added a second time. Small/Medium enable word timestamps and
disable speculative decoding for the compatible timestamp path. SDK placeholder
confidence values are not presented as measured confidence.

Endpoint notifications request microphone drain. Only completion after queued
audio and the native final flush can submit a terminal transcript. Revised
Moonshine word timings from the final flush replace endpoint snapshots, including
when the line's text was already marked complete. Late, foreign-stream and stale
revisions are rejected before they can mutate retained text. A blank terminal
flush preserves useful partial text and its valid timing.

The session carries input timing into live captions and the translation source
view. The latest observed token/word can be emphasized as an arrival cue. The
timing dialog shows an immutable snapshot of actual starts and supplied ends.
Editing the recognized text invalidates stale ranges. No recognition timing is
reused as the reply's playback clock: output highlighting continues to follow
presented reply audio, its existing caption alignment and pitch-preserving speed
transform.

The pronunciation dictionary retains its existing output behavior. For Moonshine,
written dictionary forms are also passed to the SDK's comma-delimited keyterm
interface, with bounded entries and incompatible delimiters excluded. The pinned
Sherpa packs do not advertise contextual vocabulary bias; the UI states that
limitation instead of pretending a post-transcription replacement is a native
recognition hint.

## Recognition benchmarks and automatic CPU selection

The existing quick Pocket/LiteRT screening remains early in the full campaign.
The expanded recognition stages follow it and use only already installed packs;
missing packs produce explicit unavailable evidence and never start downloads.

The recognition fixture loop delivers real 16 kHz audio in paced 40 ms batches.
Preparation, warmup, first partial, terminal result, trailing completion, native
busy time, deadline lateness and real-time factor have separate measurements.
Reference words and recorded results provide recognition error evidence. Typed
endpoint and terminal events are distinct, and timing cannot claim audio beyond
what the fixture loop accepted. No local French accuracy measurement is invented
from the English reference fixtures: the French stage remains unavailable until
a verified French fixture and reference are installed in the benchmark manifest.

Automatic CPU selection evaluates a matched 1/2/4-thread cohort, limited by device
CPU count, with an unscored warmup and the same scored fixtures for each candidate.
It requires complete, accurate, thermally eligible results and verified CPU
execution. Comparable results within the tie band select fewer threads. Receipts
bind model/pack, runtime, fixtures, protocol, device/OS/ABI and CPU limit and expire
after 30 days. An incomplete, stale or mismatched receipt cannot change the
automatic choice; the fallback uses up to two threads. This recognition
calibration is separate from the existing speech-output CPU calibration.

Campaign/resume identity includes the model export, runtime, language, CPU thread
choice, fixture set and recognition protocol. Cancellation and thermal
interruption retain completed evidence without treating unfinished fixtures as
measured successes; remaining stages stay pending for a qualified resume.

Battery samples describe the whole-device charge window and its availability.
Thermal samples describe observed device thermal state, including the final
flush. Neither is per-model energy attribution, a controlled battery-life study,
or proof that a fast short fixture will remain fast during sustained listening.

## Qualification boundaries

The required host-native gate loads the exact CPU model fixtures through the
production Java/C++ bridge with JNI checks. Nemotron remains a mandatory case;
inadequate or unknown runner memory fails the gate instead of becoming a pass or
an implicit skip. Android builds, unit/Compose tests, shrinker/native checks and
all other protected workflows must qualify the final published head.

Physical Adreno and SM8750 HTP testing is still necessary to establish usable
processor placement, latency, interruption behavior, battery and thermal results.
Acoustic word-boundary accuracy requires recordings with independently checked
ground truth. This document and implementation do not substitute for those
measurements or authorize release before the user's required gates pass.

## Implementation and regression navigation

The bounded feature map retains the existing microphone/recognition entry and
adds search terms for these models. The repository guide links this detailed
implementation and regression map without expanding the compact manifest beyond
its fixed budget.

| Concern | Production entry | Behavior regressions |
| --- | --- | --- |
| Packs and installation | `VoiceRecognitionModelCatalog`, `RecognitionModelStore` | `VoiceRecognitionModelCatalogTest`, `RecognitionModelStoreTest`, `RecognitionModelArchiveTest` |
| Selection and retirement | `VoiceRecognitionConfiguration`, `VoiceRecognitionRetirement`, `VoiceRecognitionMemoryPolicy` | `VoiceRecognitionControllerTest`, `VoiceRecognitionSelectionTest`, `VoiceRecognitionMemoryPolicyTest` |
| Capture and native execution | `MoonshineVoiceRecognizer`, `MoonshineStreamDecoder`, `SherpaNativeSpeechDecoder`, `StreamingAsr.java`, `streaming_asr.cpp` | `MoonshineVoiceRecognizerTest`, `SherpaNativeSpeechDecoderTest`, mandatory production JNI host fixtures |
| Input timing and translation | `VoiceRecognitionTranscript`, `VoiceSession`, `VoiceRecognitionTimingsDialog`, `VoiceLiveCaptions` | `VoiceRecognitionTranscriptTest`, `VoiceRecognitionSessionTest`, `VoiceDraftPreparationTest`, `VoiceRecognitionUiTest` |
| Settings and language routes | `VoicePreferenceStore`, `VoiceRecognitionSettings`, `SpeechLanguages` | `VoiceRecognitionPreferenceTest`, `VoiceRecognitionTranslationPreferenceTest`, `VoicePreferenceStoreTest`, `VoiceTranslationRecognitionSelectionTest`, `SpeechLanguagesTest` |
| Benchmark retirement | `VoiceBenchmarkHandoff`, `VoiceSpeechBenchmarkViewModel`, `KokoroVoiceOutput` | `VoiceBenchmarkHandoffTest`, `VoiceFullBenchmarkViewModelTest`, `DeferredKokoroOutputTest`, `KokoroStreamingOutputTest` |
| Recognition measurements | `VoiceBenchRecognition`, `VoiceRecognitionBenchmarkProtocol`, `VoiceRecognitionCpuCalibrationRunner` | `VoiceRecognitionBenchmarkCaseTest`, `VoiceRecognitionBenchmarkResumeTest`, `VoiceRecognitionCpuCalibrationTest` |

Actual executed scopes and exclusions are retained in
[`recognition-native-integration.json`](../audits/recognition-native-integration.json),
[`recognition-output-handoff.json`](../audits/recognition-output-handoff.json),
[`recognition-session-integration.json`](../audits/recognition-session-integration.json),
[`recognition-benchmark-integration.json`](../audits/recognition-benchmark-integration.json)
[`recognition-translation-integration.json`](../audits/recognition-translation-integration.json),
[`recognition-translation-preferences.json`](../audits/recognition-translation-preferences.json)
and [`voice-recognition-models.json`](../audits/voice-recognition-models.json).
