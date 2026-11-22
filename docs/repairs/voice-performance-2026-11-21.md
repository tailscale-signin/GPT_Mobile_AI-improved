# Voice performance and speech backends

This change implements the 21-item voice menu approved for PR #2126, targeting
Android 0.9.66.12 (build 146) and Gateway 18.1.0. Local CPU speech remains the
default. Hardware paths are explicit experiments with execution qualification,
bounded cancellation and an honest provider display.

## Feature map

| # | Delivered behavior | Main implementation |
| --- | --- | --- |
| 1 | Reuse a verified installation generation instead of repeatedly hashing an unchanged voice pack. Installation changes invalidate the cached generation. | `LocalVoiceModelStore` |
| 2 | Keep the Moonshine model resident while creating a fresh native stream for each turn. Fresh streams avoid retaining the pinned SDK's completed-line state across turns. | `MoonshineStreamDecoder`, `MoonshineVoiceRecognizer` |
| 3 | Retain the voice runtime across navigation and configuration changes. Capture and playback stop immediately when leaving; idle models have a bounded 30-second grace period. | `VoiceRuntimeViewModel`, `VoiceSession` |
| 4 | Offer Quick, Natural and Thoughtful listening, adaptive recognition cadence and an explicit done-speaking action. | `VoiceListeningPolicy`, voice tuning settings |
| 5 | Offer 20, 40, 80 and 100 ms microphone batches with bounded reusable sample arrays. | `VoiceCaptureBufferPool`, `AndroidVoiceAudio` |
| 6 | Deliver the final transcript before expensive native teardown completes, while the native gate serializes cleanup and the next stream. | `MoonshineVoiceRecognizer`, `VoiceNativeEngineGate` |
| 7 | Start with a short speech phrase and adapt subsequent phrase size to observed production rate and queued audio. | `VoiceSpeechBuffer`, `VoiceAdaptivePlayback` |
| 8 | Measure repeated CPU runs before selecting 2–4 threads for Auto. Retain a conservative default and respond to thermal pressure. | `VoiceCpuCalibration`, `KokoroVoiceOutput` |
| 9 | Adapt playback buffering to actual synthesis rate and underruns, with bounded route-aware low-latency playback. | `VoiceAdaptivePlayback`, `KokoroVoiceOutput` |
| 10 | Move PCM using offset/length views and bounded batches instead of repeatedly copying whole phrase arrays. | `KokoroPcmBatches` |
| 11 | Interrupt an active ORT run, reset termination before reuse, and cancel pending backend qualification as well as playback. | `TimedKokoro`, `VoicePhraseEngineFactory`, `KokoroVoiceOutput` |
| 12 | Separate high-frequency amplitude/caption signals from session state and consume waveform animation in the drawing phase. | `VoicePresentationSignals`, `VoiceWave` |
| 13 | Reuse unchanged caption measurements and append new layout incrementally, preserving the five-line presentation, fade and active-word style. | `VoiceCaptionLayoutCache`, `VoiceLiveCaptions` |
| 14 | Reuse unchanged reply projections instead of repeatedly traversing the entire reply history. | `VoiceReplyProjectionCache`, `VoiceConversationContent` |
| 15 | Offer hands-free interruption with an echo-aware monitor, microphone ownership, pre-roll and stale-turn rejection. | `VoiceInterruptionMonitor`, `VoiceSession` |
| 16 | Save a bounded personal pronunciation dictionary while retaining the original displayed text and source word mapping. | `VoicePronunciationOverrides`, `KokoroSpeechText` |
| 17 | Use AudioTrack timing when available, retain a playback-head fallback, and save caption adjustment per audio route. | `VoicePlaybackClock`, `KokoroVoiceOutput` |
| 18 | Show bounded warm/cold latency history, synthesis rate, queued audio, underruns, provider, route, thermal and frame observations; add a voice rendering journey to the existing benchmark/profile gate. | `VoicePerformanceHistory`, `VoicePerformancePanel`, `VoiceBenchmarkJourney` |
| 19 | Offer an experimental Qualcomm Adreno GPU provider, with native plugin checks and actual executed-node qualification before reporting acceleration. | `VoiceSpeechBackend`, `kokoro_execution_provider.h` |
| 20 | Offer the equivalent experimental Qualcomm Hexagon HTP/NPU provider, including unsupported-graph fallback to CPU. | `VoiceSpeechBackend`, `kokoro_execution_provider.h` |
| 21 | Offer an explicitly selected Gateway CUDA backend with authenticated, bounded synthesis and a separately installed GPU worker for both source and packaged Windows Gateway deployments. | `GatewayVoicePhraseEngineFactory`, `gateway/v14/voice.py`, `gateway/v14/voice_process.py` |

## Controls and defaults

Voice settings expose listening style, microphone batch size, interruption,
pronunciations, caption alignment, a CPU calibration action and the performance
panel. Speech processing offers CPU, Qualcomm GPU, Qualcomm NPU and Gateway CUDA.
Backend preference loading finishes before a saved backend is activated; writes
outlive the settings screen and stale connection lookups cannot replace a newer
selection.

CPU calibration is an explicit local measurement. It does not contact a Gateway.
The chosen result is based on repeated measured runs, including a minimum
improvement threshold, rather than assuming that more threads are faster.
Performance history separates actual providers and cold/warm samples. A phrase
that falls back from QNN to CPU is recorded as CPU.

Hands-free interruption is opt-in. The implementation uses supported echo
cancellation, retains a short pre-roll and rejects stale microphone generations.
The Android system recognizer cannot accept that captured pre-roll, so this mode
requires the local recognition path. Actual speaker/room/Bluetooth behavior still
requires a physical-device test.

## Qualcomm provider qualification

The normal QAIRT Android variant includes the official QNN execution-provider
plugin and its matching runtime libraries. The independent GenieX preview does
not combine those libraries with its own bundled native distribution; unavailable
speech providers report that condition and retain CPU speech.

The pinned Kokoro export has a dynamic token dimension. Each QNN session uses the
exact token count of the phrase, without padded buckets that could change audio
or word timing. A new token count replaces the previous specialized graph. That
compilation cost is part of the measured synthesis path and can outweigh any
kernel improvement.

Readiness requires a real warmup and an ORT profile with a positive-duration
`QNNExecutionProvider` node execution. A provider name, an installed library or a
session initialization event is insufficient. The profile parser rejects
cross-event matches and malformed evidence. Qualification profiles are deleted.
Unsupported initialization or phrase shapes fall back to CPU before any audio
from that phrase is played. Cancellation remains cancellation rather than a
request to start a CPU retry.

This is a prototype, not a claim that the bundled model runs faster on a ROG
Phone 9 Pro. Operator support, graph preparation, memory and thermal behavior
must be measured on the actual phone before recommending either QNN option over
CPU.

## Gateway CUDA deployment and privacy

The Android client keeps the same native English frontend and source mapping.
It sends pronunciation token IDs to the selected, already configured Gateway and
receives bounded PCM and token durations. These tokens contain spoken content.
The client and worker verify the canonical token-symbol mapping digest so a
different export cannot silently reinterpret the phone's pronunciation IDs.
Privacy Mode is checked at every synthesis dispatch; cancellation remains
available if Privacy Mode is enabled after a request starts. Redirects and
automatic synthesis retries are disabled.

The Gateway routes retain the existing `chat` admission, transport, Host, Origin
and browser CSRF checks. Cancellation is scoped to the admitted principal.
Requests have strict schemas, model identity checks, queue bounds, deadlines and
audio/timing validation. No new network listener or engineering authority is
introduced. The GPU subprocess receives no Gateway credentials.

The Windows installer includes the helper sources and the hash-locked optional
requirements. GPU dependencies live in a separate interpreter environment, so
ordinary Gateway startup does not depend on CUDA, NumPy or ONNX Runtime. Setup
qualifies the actual private-pipe worker before enabling its saved configuration;
a failed setup retains the previous configuration. Warmup runs in the background.

See [Optional CUDA speech for Gateway](../../gateway/VOICE-CUDA.md) for Windows
and source deployment instructions, the compatible floating-point model pack,
wire contract and troubleshooting. CUDA readiness requires executed GPU nodes
from the same warmed session used for later requests. This evidence does not
claim that every operator runs on the GPU or that a network round trip is faster
than local synthesis.

## Validation evidence

The implementation includes focused regressions for model residency, stream
lifetime, recognition cadence, pooled capture, interruption routing, phrase
scheduling, PCM bounds, cancellation, caption timing, pronunciation, incremental
presentation, persisted settings, stale selection races, privacy dispatch and
Gateway protocol boundaries.

The native host integration uses the real pinned Kokoro model and ONNX Runtime.
It validates nonempty audio, duration/frame agreement, frontend parity,
termination before and during a native run, and successful reuse afterward.
The host CTest suite also checks pronunciation fallback and executed-provider
evidence. Both Android ARM64 and x86_64 native variants compile and link with the
pinned NDK; native library alignment and dependency provenance are checked.

Gateway tests cover admission and principal-scoped cancellation, bounded queues,
timeouts, invalid model and timing data, actual-profile interpretation, private
subprocess cancellation, pipe bounds, background readiness and setup rollback.
A fresh interpreter with site packages disabled can import the setup and worker
helpers without importing FastAPI, NumPy or ORT.

The current check results and publication receipt are tracked in
[`voice-performance-2026-11-21-task.json`](voice-performance-2026-11-21-task.json).
The integrated diagnostic timing reader also uses indexed attribution instead of
repeated whole-history searches when an expanded diagnostic trace is displayed.
Its 19 actual-source tests retain first-event, ownership, timestamp-tie,
fingerprint and overflow behavior. A deterministic 4,096-event incomplete-history
regression reduced event reads from 16,789,504 to 32,768. This measures bounded
lookup work; it is not a phone latency or voice rendering benchmark.

Host tests and compilation do not establish phone latency, microphone acoustics,
battery use, physical frame pacing, QNN kernel compatibility or CUDA throughput.
No physical phone or CUDA device is attached to this implementation environment.
The benchmark journey is included for the existing device-validation workflow;
its inclusion is separate from observing a device run.

## Concurrent branch work

Incoming commits are fetched and compared before integration and publication.
The implementation preserves the concurrent build-146 test compilation fixes
and the follow-up release-scope repair. The manifest includes all new voice,
Gateway, native and benchmark paths and retains the existing release limitations.
Publication follows the repository's draft PR, exact-commit checks, protected
merge and coordinated signed-release workflow.
