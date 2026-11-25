# Voice streaming and preparation

New Voice Mode profiles use LiteRT Kokoro streaming on CPU at 1×. Saved engine
and rate choices take precedence. The Kokoro GPU and NPU options request their
respective delegates through MediaPipe Tasks Audio 1.2.3. They use the same
model, voices, pronunciation overrides and incremental text path as CPU.
Phonon, Pocket, native Kokoro and Gateway CUDA remain selectable.

## Model installation and ownership

The first visit to Voice Mode schedules Moonshine recognition, LiteRT Kokoro,
and the selected native Kokoro pack through the Android background installer.
Existing verified packs are reused. Setup reports the combined progress and
offers cancellation and retry. After cancellation or manual removal, a user
can resume the download from setup. Phonon and Pocket are separate downloads.

`MediaPipeSpeechCatalog` pins each pack's publisher revision, filename, byte
count and SHA-256. The shared Kokoro pack is 294,783,298 bytes. The installer
checks the size and digest before making the download available to the engine.
A native-use lease protects files mapped by a running engine. Switching
between the Kokoro streaming delegates reuses the installed model and gives
each delegate its own runtime cache.

The resident engine identity includes the model digest, runtime, selected
delegate, voice and exact speaking rate. MediaPipe exposes speaking rate at
engine creation, so a rate change creates a new matching engine before another
preview or reply. The public SDK has no CPU thread-count setting. Native CPU
thread controls are therefore hidden for these engines.

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

MediaPipe word positions are UTF-8 byte offsets, not Kotlin UTF-16 indices. Cue
conversion accepts code-point boundaries and retains surrogate pairs. Its word
times are estimated, and `VoiceWordCueProvenance.ESTIMATED` remains distinct from
native Kokoro's duration-derived alignment. Both use the existing measured
AudioTrack presentation clock for movement. Estimated word alignment does not
become exact because the presentation clock is measured.

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

Kokoro's acoustic model normally runs on CPU and its vocoder can request
GPU or NPU. If the accelerator task fails to open during playback, the app
tries the same pack on CPU and reports the fallback in Voice settings.
Benchmarks measure the requested configuration without this app-level retry.
MediaPipe does not expose executed-kernel placement, so results identify
the delegate requested and leave the effective processor unknown. Native QNN
has its own CPU-reference comparison and performance ranking.

The standard Kokoro vocoder failed a host compilation for Snapdragon 8 Elite
(SM8750) with LiteRT 2.4.0 and QAIRT 2.52.0. The Qualcomm compiler rejected
the per-channel weight dequantization in the graph. On that phone, selecting
LiteRT NPU can therefore produce CPU-only speech. The separate native Hexagon
engine qualifies accelerator execution against CPU audio on the device.

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

## Primary upstream references

- [MediaPipe Text-to-Speech](https://developers.google.com/edge/mediapipe/solutions/audio/text_to_speech/index)
- [MediaPipe 1.2.3 source and release](https://github.com/google-ai-edge/mediapipe/releases/tag/v1.2.3)
- [Pinned Kokoro publisher bundle](https://huggingface.co/litert-community/Kokoro-82M/tree/bf1c2405642aa310bd210205c22d3ae1abc253fb)
- [Pinned Phonon publisher bundle](https://huggingface.co/litert-community/phonon-31m-en/tree/ee374c33f0f6f8ed1d9f761e21cdb97df6accfe8)
- [Pinned Pocket publisher bundle](https://huggingface.co/litert-community/pocket-tts/tree/00c866f2397788f514ab6bbcb931be22afc3b58f)

### Kokoro processor and model selection

Voice Mode offers original FP32 Kokoro weights through the native CPU, Adreno and Hexagon routes. The native graph can split between QNN and CPU. Phone benchmarks report the kernels that actually ran. Hexagon on SM8750 executes supported floating-point partitions with reduced precision. Fresh voice settings select the original native pack. Existing saved model selections continue to take precedence.

The default LiteRT stream uses the publisher’s shared INT8/FP16 bundle. The optional GPU vocoder has a separate verified download. For Snapdragon 8 Elite, choose **Kokoro LiteRT · Snapdragon 8 Elite NPU vocoder**, tap **Import verified SM8750 pack**, and select `kokoro_sm8750.litertlm`. This build accepts the 295,602,498-byte model with SHA-256 `04ee7076b0c8e5b11239ba5ed8ee7a3540984acb17936441b904196154fc0ebb`. Its 17 compiled HTP partitions contain 273 operations from the vocoder. Remaining operators, the acoustic model and the text frontend retain their original implementation. Both specialized routes support incremental input, voice selection and timed PCM. The optional CPU recovery path uses the independently verified standard pack. Device placement remains unobserved for the SDK.

The host recipe below uses the publisher GPU bundle at revision `bf1c2405642aa310bd210205c22d3ae1abc253fb`, LiteRT 2.4.0 headers and Python package, and QAIRT 2.52.0.261104 host libraries. The C policy limits compilation to the bounded residual blocks to avoid retaining the long-output graphs in the compiler. It forwards operation support decisions and compilation to the Qualcomm plugin. The script checks the source digest, the input and output signature, and the dispatch count. The Android installer verifies the complete packaged bytes before publishing them.

```sh
python scripts/build_kokoro_sm8750_pack.py kokoro_int8_gpu_fp16.litertlm kokoro_sm8750.litertlm \
  --litert-source /path/to/LiteRT --qairt-lib /path/to/qairt/lib/x86_64-linux-clang
```

In **Speech benchmark**, choose native or shared LiteRT Kokoro, then **Set up 1× CPU, GPU and NPU comparison**. This selects all six audible workloads at exactly 1× with zero caption offset. Specialized packs also have independent benchmark entries. Protocol 6 records playback-clock word coverage separately from UI render callbacks, and stop-to-silence separately from confirmed worker retirement. Listening trials use the installed pack and request the selected backend without app-level fallback.
