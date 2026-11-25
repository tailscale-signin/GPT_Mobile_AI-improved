# Voice streaming and preparation

Voice Mode starts with **Kokoro LiteRT streaming · CPU (default)** at **1×**.
Saved engine and rate choices take precedence. The same LiteRT text normalization,
pronunciation dictionaries and voice IDs serve the CPU, GPU and NPU streaming
routes. Reply speech and microphone recognition have independent language settings.

## Model installation and voice selection

The first visit queues Moonshine recognition, the standard LiteRT Kokoro pack and
the selected native Kokoro pack through the Android background installer. Setup
shows combined progress, reuses verified installations and supports cancellation
and manual resume. A new installation selects original FP32 for the native pack.
Phonon and Pocket are available separately.

| LiteRT Kokoro route | Model on the phone | Installation |
| --- | --- | --- |
| CPU, GPU requested, NPU requested | Shared 294,783,298-byte INT8/FP16 pack | Downloaded during initial Voice Mode setup. Switching among these three delegates reuses the pack. |
| GPU-optimized vocoder | Publisher's 294,750,530-byte GPU pack | Prepare from standard Kokoro with the 80,198-byte patch included in the app. The full publisher download is also available. |
| Snapdragon 8 Elite NPU vocoder | 295,602,498-byte pack compiled for SM8750 | Prepare from standard Kokoro with the 2,236,700-byte patch included in the app. A complete verified pack can also be imported. |

Each optional preparation produces a separate model file in local storage.
The installer holds a lease on standard Kokoro while reconstructing the new pack,
then verifies the full output size and SHA-256 before making it available.
The optional packs preserve the pronunciation frontend, acoustic model, voices
and timing support. Their changes are in the vocoder that generates the speech
waveform.

The LiteRT selector offers 54 voices across nine language profiles, including
20 US English and eight UK English voices. It remembers the voice for each
reply language. Native Kokoro offers those 28 English speakers and selects
the US or UK lexicon for the chosen voice. The runtime checks the voice ID
against the loaded pack before synthesis. If a requested speaker is unavailable,
live playback can select another installed voice from the same language profile.

The LiteRT runtime cache distinguishes the model digest, delegate, rate and
streaming settings. A voice change selects the requested speaker for the next
utterance. A rate change prepares an engine with that rate because the SDK sets
the speaking rate when the task opens. CPU thread calibration is available for
native Kokoro.

## Incremental audio and captions

`VoicePhraseEngine` retains complete-phrase native generation and adds an
incremental PCM contract. A streaming producer hands immutable mono Float32
chunks at 24 kHz to a synchronous callback. The existing bounded audio queue
applies backpressure on the synthesis worker. Playback can consume the first
chunk before the producer finishes; the phone does not join all streaming audio
into one utterance-sized array.

`VoiceStreamFrames` validates sample rate, finite PCM, bounded frame counts,
cue order and complete final output. The SDK adapter splits oversized native
chunks before delivery. A bounded text plan preserves the original reply's
caption positions and pronunciation overrides without requiring native Kokoro's
lexicon. Long saved replies are split before inference, including at slow rates.

MediaPipe reports word positions as UTF-8 byte offsets. The mapper converts
them at Unicode code-point boundaries for Kotlin text. When a word begins in a
PCM chunk and ends in a later one, the player publishes the cue as soon as audio
reaches the word’s estimated start. Later chunks extend that same cue. The
highlight follows the AudioTrack presentation clock, including for the first
word. If the stream ends early, a partial cue closes at the last received frame.
The SDK estimates word alignment. Native Kokoro derives its cues from the model’s
token durations.

Stopping playback explicitly cancels the native stream and task. The synthesis
owner retires the handles after the blocking read returns; a playback callback
does not destroy an in-flight native engine. The existing reply, route,
lifecycle, privacy and audio-focus fences remain in force. A late stream cannot
resume or append to a replacement reply.

## Preparation and native CPU calibration

Opening a foreground voice room starts two independent preparations. The phone
checks Moonshine and the selected speech pack. It reuses verified files when their
installation and change stamps match. Kokoro can read its pronunciation dictionary
on the synthesis worker while Moonshine starts the microphone. Once capture owns
the shared native gate, the selected engine can queue construction and eligible
CPU warm-up for the moment capture releases it. The warm-up discards its audio.
Gateway speech preparation requires an accepted turn.

The selected AI profile also prepares as soon as the room and its profile policy
are known. A public cloud profile can open the shared HTTPS connection with an
unauthenticated, content-free request. Server-side model residency is unknown.
A selected on-device model uses the existing foreground preload preference and
resource checks. Selected llama.cpp Gateway models use the existing exact-model
preparation controller. Private rooms, metered-network preferences, and
cancellation apply to speculative contact.

New voice turns ask for a short first sentence that answers the question. Auto
playback queues a complete short clause immediately. During a provider pause,
two complete words become eligible after 120 ms from the first stable words.
Later phrases adapt to the audio already buffered. This interval controls text
buffering. Actual playback also depends on the provider, synthesis and output
device.

For a short lookup with a matching web search already running, Voice may say
“I’m checking the weather in Toronto.” The topic comes from the user's words.
A ready answer takes priority over an unheard update. An audible update finishes
once while the answer continues to generate. Progress stays out of the saved
answer and replay. The Performance panel records accepted-request-to-search-update
and accepted-request-to-first-answer playback separately.

Native CPU calibration snapshots the voice and selected rate, tests 1-4 threads,
rotates and reverses candidate ordering across runs, and uses short varied warm
fixtures. It checks cancellation and temperature around each preparation and
measurement. Missing thermal observations, an interrupted run, heat, or a failed
trial retain the previous result. Its key includes the protocol, native runtime,
model, voice, lexicon and rate. The displayed slowest trial is not represented as
a statistically established p95.

AUTO native phrase planning uses observed recurring synthesis cost per spoken
word and queued audio. It reevaluates the next unspoken phrase before native
generation, preserving source ranges and native input limits. Fixed performance
modes retain their explicit limits. A queue reserve can absorb variability; it
cannot make a persistently slower-than-playback engine sustain uninterrupted
speech.

## Gateway binary transport

A compatible Gateway advertises `kokoro-pcm-stream-v1`. The phone uses the exact
`POST /gateway/voice/synthesize/stream` route only after that capability is
observed; older servers retain the existing JSON route. The new endpoint uses a
bounded metadata header, little-endian Float32 frames and an explicit terminator.
The phone checks request/model/token identity, timing, sample rate, frame limits,
finite values, truncation and trailing content before treating the stream as
complete. Headers do not count as first PCM.

The private sidecar-to-Gateway boundary also transports binary audio, avoiding
Base64 expansion throughout the new route. Slow clients retain their queue
admission until the stream drains. Cancellation remains principal-scoped, and
the exact route uses the existing chat-scope authorization and private
conversation guards.

The current CUDA model still produces a complete neural phrase before transport
begins. Its metadata says `generation_mode=complete_phrase`. Transport streaming
reduces serialization and delivery overhead; it is not claimed as incremental
neural generation.

## Evidence and qualification

The LiteRT Kokoro acoustic stage runs on CPU by default. Selecting an
accelerator requests it for eligible vocoder operations. MediaPipe reports the requested
delegate but provides no per-kernel placement receipt. An SM8750 host compiler
run with LiteRT 2.4.0 and QAIRT 2.52.0 rejected per-channel weight dequantization
in the standard pack. On that phone, the **Snapdragon 8 Elite NPU vocoder** option
uses a separately compiled model with 273 vocoder operations across 17 HTP
partitions. Actual partition execution, speech quality and speed require a
physical-device run. Native Hexagon has its own CPU-reference qualification and
reports observed provider kernels.

If an accelerator fails during setup or before it delivers audio or timing,
live playback can retry once on the standard CPU pack at the same rate. Voice
settings report the change. For incremental text, the player can replay a bounded
prefix while the listener has heard none of it. After audio or a cue has been
delivered, the failed stream stops to avoid repeating speech. A later synthesis
can use CPU. Benchmarks and listening trials keep the requested route so a CPU
retry cannot be scored as an accelerator result.

Native QNN playback, including manual GPU/NPU selection, requires complete paired
CPU and accelerator observations from the same current comparison workload,
voice, rate, runtime and device. A missing, stale, incomplete or inconsistent
pair selects CPU and explains that an explicit Recheck is needed. Recheck stages
its requested candidate set before granting admission, regardless of candidate
order. Ordinary first-phrase self-validation cannot grant that admission.
Numerical audio consistency is separate from Auto's thermal and speed-ranking
requirements. This checks a representative workload, not every future utterance
or perceptual equivalence, and adds no hidden per-phrase CPU reference pass.

The native profile parser uses bounded structured parsing instead of repeated
regular-expression scans. A host parser microbenchmark is not a phone latency
measurement: ORT profiling finalization, graph preparation, synthesis and
playback have separate costs.

Meaningful regression coverage includes model integrity and leases, native
stream cancellation, byte-to-character mapping, bounded long-reply planning,
caption provenance, calibration ordering and interruption, early-preparation
policy, Gateway framing/backpressure/privacy, and stale provider removal.
Publisher model smoke checks exercise real checkpoint compatibility on their
declared host. Android project compilation, instrumentation and final release
verification remain separate gates. Target-phone speed, audible quality,
acoustic latency, battery use and accelerator execution require actual device evidence.

`Speech engine model smoke` runs all three immutable checkpoints with the real
published SDK on Linux. `Minified native memory smoke` builds one R8-processed
application and test APK pair, then exercises each checkpoint separately through
that same packaged Android JNI. Both workflow names are required by the release
scope on the final main commit. A verified download without inference, a skipped
instrumentation method, or an absent completion receipt cannot satisfy them.

## Native processor selection and model builds

Native Kokoro offers the original FP32 weights and the compact INT8 model on CPU,
Adreno and Hexagon. On Adreno, the original native model requests FP32 when
creating the QAIRT graph. On SM8750, Hexagon uses reduced precision for supported
floating-point partitions. Native QNN may split a graph between CPU and the
accelerator. The phone benchmark records the kernels it observes.

The host recipe below uses the publisher GPU bundle at revision `bf1c2405642aa310bd210205c22d3ae1abc253fb`, LiteRT 2.4.0 headers and Python package, and QAIRT 2.52.0.261104 host libraries. The C policy limits compilation to the bounded residual blocks to avoid retaining the long-output graphs in the compiler. It forwards operation support decisions and compilation to the Qualcomm plugin. The script checks the source digest, the input and output signature, and the dispatch count. The Android installer verifies the complete packaged bytes before publishing them.

```sh
python scripts/build_kokoro_sm8750_pack.py kokoro_int8_gpu_fp16.litertlm kokoro_sm8750.litertlm \
  --litert-source /path/to/LiteRT --qairt-lib /path/to/qairt/lib/x86_64-linux-clang
```

To reproduce the two patches stored in `app/src/main/assets/voice/`, supply the
verified standard model as the source. The generator checks each input digest,
replays the instructions and verifies the complete output against its target.
The full SM8750 model has SHA-256
`04ee7076b0c8e5b11239ba5ed8ee7a3540984acb17936441b904196154fc0ebb`.

```sh
python scripts/build_kokoro_streaming_delta.py kokoro_int8_fp16.litertlm kokoro_int8_gpu_fp16.litertlm kokoro_gpu.ksm
python scripts/build_kokoro_streaming_delta.py kokoro_int8_fp16.litertlm kokoro_sm8750.litertlm kokoro_sm8750.ksm
```

To rebuild the native Adreno FP32 policy, use the same QAIRT headers and Android NDK as the app. The builder exercises graph creation, precision changes, configuration preservation and provider rejection against the public interface before producing the Android library.

```sh
python scripts/build_kokoro_gpu_fp32.py --ndk /path/to/android-ndk-r29 \
  --qairt-sdk /path/to/qairt/2.52.0.261104 --output libkokoro_qnn_gpu_fp32.so
```

In **Speech benchmark**, choose native or shared LiteRT Kokoro, then **Set up 1× CPU, GPU and NPU comparison**. This selects all six audible workloads at exactly 1× with zero caption offset. Specialized packs also have independent benchmark entries. Protocol 6 records playback-clock word coverage separately from UI render callbacks, and stop-to-silence separately from confirmed worker retirement. Listening trials use the installed pack and request the selected backend without app-level fallback.

## Primary upstream references

- [MediaPipe Text-to-Speech](https://developers.google.com/edge/mediapipe/solutions/audio/text_to_speech/index)
- [MediaPipe 1.2.3 source and release](https://github.com/google-ai-edge/mediapipe/releases/tag/v1.2.3)
- [Pinned Kokoro publisher bundle](https://huggingface.co/litert-community/Kokoro-82M/tree/bf1c2405642aa310bd210205c22d3ae1abc253fb)
- [Pinned Phonon publisher bundle](https://huggingface.co/litert-community/phonon-31m-en/tree/ee374c33f0f6f8ed1d9f761e21cdb97df6accfe8)
- [Pinned Pocket publisher bundle](https://huggingface.co/litert-community/pocket-tts/tree/00c866f2397788f514ab6bbcb931be22afc3b58f)
