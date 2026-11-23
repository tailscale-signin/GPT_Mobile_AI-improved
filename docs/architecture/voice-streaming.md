# Voice streaming and preparation

The phone retains native Kokoro CPU, qualified QNN GPU/HTP, and the optional
Gateway CUDA route. Voice settings also offer MediaPipe Tasks Audio 1.2.3 engines:
Kokoro on CPU, Kokoro with the GPU delegate requested, Phonon English, and Pocket
English. The default remains native CPU. Selecting an engine does not download a
model or enable a remote service.

## Model installation and ownership

`MediaPipeSpeechCatalog` pins each publisher repository revision, filename,
expected byte count and SHA-256. The Kokoro bundle contains explicit dequantize
operators and supports CPU as well as a requested GPU delegate. It is separate
from the native ONNX Kokoro pack. Phonon and Pocket have independent packs and
their own default voices.

`MediaPipeSpeechModelStore` reuses the existing voice asset installer. Downloads
begin only after an explicit install action. Transfers retain the installer's
HTTPS, byte limit, resume validation, digest verification and atomic promotion
rules. A native-use lease prevents removal while an engine maps model files.
Changing voices or rates does not download a model. The screen checks only the
selected pack instead of hashing every installed engine during a settings
change. Model attribution is included in the app's license assets.

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

Local preparation can start while a completed spoken turn is finalizing, being
reviewed, or awaiting its answer. It still waits for recognition teardown at the
shared native gate. Remote Gateway preparation requires accepted admission.
Withdrawing eligibility invalidates queued warmup without discarding an already
prepared idle model. Generation checks run before and after gate acquisition so
an old queued warmup cannot become eligible when a later turn uses the same
model key.

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

Selecting the MediaPipe GPU delegate is a request. The SDK exposes no executed
provider or kernel receipt, so the application leaves effective processor
evidence absent. Diagnostic stage series identify the invoked model family as
MediaPipe Kokoro, Phonon or Pocket. They do not inherit a previous native CPU/GPU
receipt. These choices are excluded from the native ONNX CPU/GPU/NPU paired
ranking, which has a different model and workload identity.

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
acoustic latency, battery use and GPU execution require actual device evidence.

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
