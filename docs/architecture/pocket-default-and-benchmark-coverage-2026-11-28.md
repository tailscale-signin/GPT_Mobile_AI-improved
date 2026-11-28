# Pocket default and voice benchmark follow-up

## Scope and evidence

The cancelled build-153 campaign showed fast Pocket first-PCM production while
its device playback, interruption and speed trials remained pending. Native HTP
repeatedly prepared five token shapes, exceeding its previous residency budget;
the same context working set exceeded the previous disk budget. Those observations
motivate the changes below. They do not establish the performance of the changed
runtime or qualify the new accelerator paths on a physical phone.

## Default and accelerator selection

New or invalid backend preferences select English Pocket CPU. Existing valid
preferences remain authoritative. First-launch setup installs Moonshine, English
Pocket and the selected native Kokoro model through the existing resumable bundle
owner. The one-time installation marker is retained, including explicit cancellation.

`POCKET_GPU` and `POCKET_NPU` use a distinct LiteRT CompiledModel adapter, not a
delegate flag applied to the CPU-only MediaPipe task. Their shared English graph
pack is pinned to publisher revision `e0e68d4fb79feb529d7d0f8d0bbfad8d15a18a1e`,
with individual sizes and SHA-256 digests in `PocketCompiledSpeechCatalog`.
The upstream tokenizer has its own pinned revision and digest. Downloads retain
the existing atomic installation, integrity checks, cancellation and model leases.

Both choices request acceleration for the autoregressive language model and
waveform decoder; the decoder transformer explicitly runs on CPU. LiteRT 2.4.1
opens the graphs, and the Qualcomm route uses the matching LiteRT compiler and
dispatch libraries with the existing QNN 2.52 runtime prerequisites. Selection or
successful audio does not prove that every graph or operator ran on the requested
processor. The report keeps the requested delegate separate from an unknown
observed provider. Unsupported compilation remains an explicit failure.

The adapter emits newly decoded PCM during autoregressive generation. It accepts
complete phrases; it does not claim an SDK append-text API. Existing phrase
scheduling, bounded PCM backpressure, tempo processing, cancellation and owner
retirement remain in control. These graphs provide no word timestamps; the
separate caption aligner below derives timing from the generated audio when
captions are enabled. The default MediaPipe Pocket route retains its SDK
estimated word cues. The compiled pack has its own
voice preference and English synthesis profile. It is never substituted for a
multilingual Pocket artifact in a benchmark.

## Caption synchronization

Pocket CPU keeps low-latency SDK streaming. New revisions may shorten a queued or
active word's remaining interval, including the final word, while preserving its
already heard identity and onset. The presentation queue, rather than the SDK
mapper, owns the heard watermark. Fully completed words cannot replay. Silence
trimming and the tempo processor map corrections onto the same emitted PCM frame
timeline. The caption view retimes an existing highlight when the measured clock
or output route changes without restarting the word emphasis.

Compiled Pocket uses the existing Moonshine 0.1.6 runtime with an independently
installed English attention decoder when captions are requested. The model pack
is 77,748,675 bytes and reuses verified microphone-recognition assets under a
lease when available; the additional decoder is 32,515,016 bytes. Installation is
explicit alongside the compiled speech pack. It does not modify the microphone
recognizer's installation or initiate a download during playback.

The wrapper splits text into short phrases, retains bounded actual 24 kHz
post-tempo PCM, and requests final audio-derived word timings before releasing
that phrase to playback. It maps exact normalized source tokens to their UTF-8
spans and marks accepted cues `ASR_ALIGNMENT`. The runtime's constant confidence
value is not a quality measurement and is never used to qualify a cue. Missing
assets, unmatched text, invalid timing, or the audio buffer bound leave the
original speech available without invented word durations. With captions off,
the wrapper delegates directly without alignment inference or buffering.

This increases caption-enabled startup latency on compiled routes. Audio-derived
alignment is model inference, not independently measured perfect acoustic
boundaries. CPU SDK estimates, native-duration cues and ASR alignment remain
distinct in benchmark exports. Smoke gates require every lexical source word to
have an individual completed cue; first/last cues alone do not establish full
coverage. Physical listening and output-route measurements remain separate from
structural source coverage and playback-clock validation.

## Benchmark completeness and recognition recovery

Quick engine measurements are followed immediately by Pocket/LiteRT cold device
playback, cancellation and recovery, and 0.75×, 1.25×, 1.5× and 2× playback checks.
The exhaustive native, language and sustained sweeps remain in the campaign after
this early coverage. Compiled accelerator experiments cannot delay the initial
Pocket CPU checks.

Every probe has an explicit scope. Dictionary mapping and cue-order checks do not
claim acoustic alignment or pronunciation quality. Stage coverage counts are
separate from pass/fail outcomes; unavailable, interrupted and pending work remains
visible. Partial exports retain completed observations and explain missing samples.

An Android recognition support timeout retires its client and permits one fresh
on-device retry within the original overall deadline. A timeout never counts as
language support, and no late callback can reopen a retired microphone owner.

## Native HTP residency

Compact HTP may retain five exact token shapes only when current free memory is at
least 4 GiB. Lower headroom reduces residency, and unknown headroom retains only the
active shape. The existing 2 GiB estimated cache ceiling and 512 MiB free reserve
remain effective. The context disk budget is 1 GiB, with the existing six-entry,
256 MiB per-entry, identity, digest, storage and atomic-publication bounds.

The native runtime identity changes to invalidate prior qualification and context
receipts. Host regressions cover the observed five-shape working set and disk
eviction pattern. These are cache-behavior tests, not physical HTP speed or memory
qualification. The existing CPU-reference accelerator audio checks remain required.

## Full-turn latency and diagnostic compatibility

`speech_end_to_answer_audio` starts at the detector's retained audio boundary and
ends at the first answer observed by the playback-head or audio-timestamp clock.
It requires the same captured turn's accepted request. A capture can contribute
once; replay, retries, acknowledgment cues, stale callbacks and privacy resets
cannot borrow that boundary. Draft review and request queuing remain included.
An Android utterance-start callback alone cannot qualify a presentation sample.

The dashboard and export report the measure only after a complete sample exists.
It is not microphone-to-speaker loopback latency. CPU/GPU/NPU utilization, energy,
human pronunciation quality and physical accelerator placement remain unknown
unless independently measured.

Compiled Pocket and the new Pocket installation wire values require
`VOICE_POCKET_COMPILED_EVIDENCE_V1`. Older report receivers cannot silently accept
unsupported enum values. The local engine family is `COMPILED_POCKET`, while the
observed execution-provider vocabulary remains unchanged.

## Release qualification

The task checkpoint records the exact base, implementation, verification and
publication receipts. Focused host tests do not replace the exact-head Android,
native, dependency, package and aggregate required checks. The draft PR must be
green at its current head before merge, and publication must satisfy the main
commit's release gates. Physical Pocket GPU/NPU performance is still unqualified.
