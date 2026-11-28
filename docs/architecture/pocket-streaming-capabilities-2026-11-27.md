# Pocket streaming capabilities and comparison evidence

The MediaPipe Pocket implementation uses Tasks Audio **1.2.3** and executes on
CPU. English Pocket is now the fresh-install default. The later compiled Pocket
implementation adds separate experimental GPU/NPU choices and a separate pinned
English pack; see [the implementation follow-up](pocket-default-and-benchmark-coverage-2026-11-28.md).
The MediaPipe packs and their accelerator restrictions remain unchanged. A requested
delegate must never be reported as observed kernel execution.

## Installed voices and languages

The English Pocket pack exposes its eight bundled voices. The optional
multilingual pack exposes fourteen voices across English, French, German,
Spanish, Italian, Brazilian Portuguese and Dutch. Phonon exposes its five bundled
English conditioning voices. Each pack remembers its selection independently.
The runtime still checks `listVoices()` before playback; catalog membership alone
does not qualify a voice's quality on a phone.

The selected voice controls synthesis locale, preview text, dictionary language,
reply language and translation target. Multilingual Pocket's reply-language
chooser uses that pack's language list. Kokoro retains its own per-language voice
preferences. Switching packs does not overwrite another pack's selected voice.

No pack is downloaded by selecting a voice or constructing an engine. The
existing explicit install action, resumable transfer, digest validation, atomic
installation and in-use leases also govern the new bundle.

| Catalog entry | Publisher revision | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Pocket English | `00c866f2397788f514ab6bbcb931be22afc3b58f` | 162,766,856 | `465e0083cd61188772aed76160b07af0dcf59057497d7659f43f3f2b13a174a1` |
| Pocket multilingual | `00c866f2397788f514ab6bbcb931be22afc3b58f` | 760,061,960 | `9c5427cf3fe2d6680f8268e1c06537496be52d818ef12233a461bf193f314b3c` |

The multilingual identity was checked against the publisher's immutable
[Hugging Face metadata](https://huggingface.co/api/models/litert-community/pocket-tts/revision/00c866f2397788f514ab6bbcb931be22afc3b58f?blobs=true).
Voice and locale lists come from its [pinned model card](https://huggingface.co/litert-community/pocket-tts/blob/00c866f2397788f514ab6bbcb931be22afc3b58f/README.md).
Phonon's list comes from its [pinned model card](https://huggingface.co/litert-community/phonon-31m-en/blob/ee374c33f0f6f8ed1d9f761e21cdb97df6accfe8/README.md).

Model and source-voice licenses are distinct. The voice picker shows the known
source attribution, including the non-commercial terms for Cosette (Expresso)
and Jean (EARS), and links to the publisher. See [Kyutai's voice-source license
table](https://huggingface.co/kyutai/tts-voices) and the voice links in the
[upstream Pocket README](https://github.com/kyutai-labs/pocket-tts).

## Benchmark behavior

The full campaign includes the multilingual route, every bundled Pocket/Phonon
voice's PCM-readiness case, and playback at 0.75, 1.25, 1.5 and 2 times normal
speed. Its current-settings case now uses the selected voice when it belongs to
the tested pack. An unrelated Kokoro voice cannot silently stand in for a Pocket
selection, and a Portuguese voice cannot be sent to the English-only pack.

The report adds a **measured streaming latency candidate** separately from the
existing qualified native recommendation. It compares only routes in one report
with matching model, voice, speaking rate, runtime, device, fixture sequence and
the same opaque output-device and PCM-format identity. Completed cold, warm, streamed and cancellation trials,
zero controlled caption offset, no active underruns and observed caption
provenance are required. At least five matching warm trials are required for a
median; a percentile remains unavailable until the existing minimum sample count
is reached. A missing value is never treated as zero.

Cue provenance counts deduplicate projected source ranges. Newer corrections
increment a separate revision counter. Native duration cues and estimated SDK
cues remain distinguishable. Caption manifest coverage measures whether text
boundaries were presented; it does not establish acoustic word accuracy.

Optional independent pitch, acoustic-word and human pronunciation observations
are bound to the exact backend, model, runtime, PCM processing version, voice,
rate, output-device identity and fixture hashes. Reports preserve absent observations as unknown. The product thresholds
(pitch deviation at most 3%, word-timing P95 at most 120 ms, pronunciation pass
rate at least 98%) are acceptance targets, not achieved handset measurements.
At least 20 pitch pairs, 100 aligned words and 20 rated words are required before
those optional observations qualify a quality verdict. These fields are not
populated from the SDK's own estimated timestamps. No automatically captured
acoustic or human quality result is claimed.

The descriptive latency candidate does not change the saved Auto backend. The
existing native qualification remains unchanged. Output identity hashes the
Android device address (or product descriptor when no address is exposed),
device type and PCM format. Raw names and addresses are never exported. Different
Bluetooth devices and any observed route transition invalidate comparison; a
legacy generic route bucket alone is insufficient. Android devices which expose
neither address nor product cannot qualify a comparison. Product-only identity
cannot distinguish otherwise identical devices, so its precision remains limited
by the platform information available. Losing identity invalidates a receipt
instead of silently retaining the previous route.

The report records the app tempo processor identity separately from the SDK
version. Historical streaming results without this identity cannot qualify a
comparison with the new pitch-preserving path. SDK task/cache preparation stays
at 1.0 speed; requested listening speed is applied by the app tempo processor.
Playback reports identify engine PCM callbacks as occurring after app tempo and
before pause trimming. Historical SDK-named fields remain decoder-compatible,
with explicit processing scope and correctly named export aliases; an absent or
unrecognized processor identity never implies the new processing path.

Caption calibration separates preference hydration from a deliberate slider
edit. Explicit edits bind to the next observed AudioTrack route, including an
edit made while idle after changing devices. Hydration cannot overwrite another
device's saved offset, and fixed benchmark offsets do not consume queued user
edits. Losing AudioTrack's route invalidates the recorded device identity.

## Upstream capabilities outside the packaged runtime

[Google's TTS documentation](https://developers.google.cn/edge/mediapipe/solutions/audio/text_to_speech)
states that Pocket and Phonon execute on CPU, while Kokoro's GPU path can offload
the vocoder. Adding a GPU enum to the Pocket route would not create acceleration.

[Kyutai's upstream project](https://github.com/kyutai-labs/pocket-tts) supplies
smaller English models and links to alternative runtimes. They are not the
pinned 100M MediaPipe bundles. A tiny-model option requires a compatible model
conversion and runtime integration, with its own voice embeddings, tokenizer,
checksums, cancellation and streaming tests.

The publisher's [Pocket-TTS-LiteRT conversion](https://huggingface.co/mlboydaisuke/Pocket-TTS-LiteRT)
uses separate graphs and host-side autoregressive/codec orchestration. Its stated
device measurements decode after generation, rather than providing this app's
incremental reply stream. The publisher also records device-dependent placements
and decoder quality differences. It is not interchangeable with a MediaPipe
`.litertlm` bundle. Integrating that path requires implementing and validating the
stateful host loop, streaming decoder, GPU/NPU tensor ownership, fallback and
native cancellation. That separate integration is now recorded in the
[implementation follow-up](pocket-default-and-benchmark-coverage-2026-11-28.md).
It supplies incremental PCM with explicit requested placement; physical GPU/NPU
quality and performance remain unqualified, and the graphs provide no word timing.

The Linux checkpoint smoke now enumerates and synthesizes every advertised
Pocket and Phonon voice with language-specific fixtures. The Android JNI matrix
includes the multilingual bundle, requires its full voice enumeration and a
French Estelle stream, and retains the existing input/cancellation/recovery
checks. Both continue to verify the immutable model bytes and runtime identity.
This workspace cannot execute the published Linux runtime because its required
`/sys/devices/system/cpu/{possible,present,online}` topology files are absent;
that smoke remains blocked here rather than bypassing its prerequisite.

Host tests cover catalog admission, persistence/selection, locale projection,
comparison evidence, missing-data rejection and export round trips. Actual
handset synthesis, acoustic quality, accelerator performance and thermal behavior
require device trials with these exact model/runtime identities.
