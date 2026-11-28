# Streaming voice quality and translation-only mode

## Scope

This change extends PR #2651, `feature/windows-native-benchmark-kit`, with pitch-preserving streaming speed, revisable captions, pronunciation corrections, additional verified Pocket voices, and strict translation-only request routing. It preserves the native Kokoro renderer's own speed control. Hardware qualification and acoustic accuracy are recorded separately from host implementation tests.

The implementation addresses the source of the reported high pitch: MediaPipe synthesis now runs at its normal rate, and a bounded audio tempo processor changes the duration of the resulting PCM. The same processor supplies a time transform for captions. Merely changing playback sample rate or dividing all timestamps by the selected speed would not preserve both pitch and the actual output timeline.

## Audio and caption pipeline

1. The selected streaming SDK produces normal-rate, mono, 24 kHz floating-point PCM and source-text timing metadata.
2. `MediaPipeWordCueMapper` validates source/UTF-8 ranges, retains timing provenance, and accepts newer estimates for a bounded history. SDK timing remains `ESTIMATED`; its presence is not acoustic alignment evidence.
3. `VoiceTempoProcessor` uses the pinned AndroidX Sonic algorithm with pitch fixed at one. Processing is incremental, uses bounded input batches, and retains stream-local state. At 1×, the PCM bypass is immediate. Native Kokoro does not pass through this processor.
4. `VoiceTimelineTransform` maps input frames to actual produced output frames using observations from the tempo algorithm. Interpolation within an operation is an estimate, not a new claim of phoneme accuracy. Final words remain provisional until more source audio or end-of-stream resolves the output boundary. The terminal mapping includes drained output without moving previously finalized intervals.
5. The existing shared silence limiter applies its retained-frame mapping to original cues and subsequent revisions. `VoicePcmCaptions` updates future cues only; an already consumed word cannot jump backward because a late estimate arrived.
6. AudioTrack's presented-frame clock remains authoritative for captions. Caption animation uses the current word's remaining time and freezes with playback pause or refill.

Tempo applies to streaming Kokoro, Pocket, multilingual Pocket, and Phonon across the existing 0.5×–2× control. SDK task/cache identity stays at synthesis rate 1×, so a rate change can reuse verified resident model weights. Playback and benchmark identity additionally include the selected rate and the exact PCM processor identifier. Legacy SDK-speed receipts cannot qualify the new pipeline.

Cancellation discards the stream's processor instead of flushing a tail into a later reply. Slow playback reduces the admitted source-frame limit to preserve the existing maximum output duration. Existing queue backpressure, source bounds, model lease ownership, and actual native retirement remain required.

### Caption calibration

Calibration identifies an observed output route using its category, device type, sample rate, and an opaque hash of Android's device address or product description. Different Bluetooth or USB devices can retain different offsets. Raw addresses and product strings are not exported. Legacy category offsets remain a fallback. A product-only identity cannot distinguish two physically identical accessories, and a route without identifying data remains unknown for comparisons.

Caption timing exports distinguish native timing, SDK estimates, and newer revisions. A revision count describes metadata activity; it does not measure alignment error. Independent acoustic observations are required for that measurement.

## Pronunciation and vocabulary

Personal corrections retain the readable source for captions and apply one bounded substitution before synthesis. The dictionary accepts up to 512 entries per shared or regional profile, with up to 1,024 entries after shared/regional merging. Corrections override canonical-equivalent inherited spellings without recursively reapplying replacements.

The settings editor adds a word/phrase and spoken-spelling form, an original-to-spoken text preview, and UTF-8 import/export through Android's document picker. Imports validate version, language, size, canonical duplicates, and term bounds, and remain staged until **Save pronunciations**. File contents do not become diagnostics. The preview shows text preprocessing; the selected voice still determines acoustic pronunciation.

Built-in technical terms, initialisms, names, and localized readings are expanded. English fallback applies only to English dictionaries. Shared French and Portuguese dictionaries have separate regional correction profiles, including France/Canada and Brazil/Portugal/Azores. A pronunciation profile changes readings; it does not manufacture a regional voice or change the underlying model's accent. Native Kokoro retains its own phonetic frontend and numeric normalization.

## Verified voice options and accelerator limits

| Installed pack | Exposed voices | Languages | Packaged execution |
| --- | ---: | --- | --- |
| Pocket English | 8 | English | CPU |
| Pocket multilingual | 14 | English, French, German, Spanish, Italian, Brazilian Portuguese, Dutch | CPU |
| Phonon | 5 | English | CPU |

Each pack saves its own voice selection and resolves its own supported language and preview. The optional multilingual bundle has an immutable publisher revision, exact size, and SHA-256 verification. Installation remains explicit. Model licensing and source-voice attribution are displayed separately where applicable.

The shipped MediaPipe 1.2.3 integration supports Pocket and Phonon on CPU. Adding a GPU or NPU selector that the SDK ignores would not implement acceleration. Smaller upstream checkpoints and alternative LiteRT conversions are not interchangeable with this incremental `.litertlm` runtime. Their admission requires a compatible converter/runtime, verified artifacts, incremental streaming and cancellation support, and measurements on the actual device. See [the Pocket capability record](pocket-streaming-capabilities-2026-11-27.md) for pinned sources and the model/voice inventory.

## Translation-only mode

Every accepted voice utterance in translation mode takes one dedicated path. The app captures the UI-selected destination and current source text; it does not infer whether a question or command should instead become an assistant task.

The provider receives a translation instruction and one escaped `source_text` JSON string. The contract requires questions to remain questions, commands to remain translated commands, and text already in the destination language to retain its wording. Requests to answer, search, calculate, cancel, switch language, or ignore translation are themselves source material. Only the application's language control changes the destination.

This path excludes conversation history, saved persona, personal-data recall, research, tools, delegation, Combined planning, and spoken task-cancellation interpretation. The gateway also recognizes persisted translation metadata when a caller omits transient tool configuration. Translation output is literal content: first-person claims are not reclassified as actions performed by the assistant, and control-looking strings such as progress markers remain text. Structured tool calls remain rejected before execution. Ordinary chat keeps its existing action and output guards.

The application now enforces translation-only routing and suppresses conversational/task fallbacks. Translation faithfulness still depends on the chosen language model and recognition transcript; host tests cannot prove perfect translation for every possible utterance. A provider failure is surfaced as a failure rather than silently answering or executing the source request.

## Measurement and selection

Benchmark plans cover installed bundled voices, native-language fixtures, and 0.75×, 1.25×, 1.5×, and 2× playback. Current-settings cases use the selected voice where it belongs to the tested pack.

Streaming latency comparisons require matching model/runtime/voice/rate/processor, fixtures, device, stable observed output route, caption provenance, and complete playback/cancellation evidence. Missing observations remain unknown. A measured streaming latency candidate is shown separately from the existing native Auto recommendation.

Optional independent quality observations bind to that exact configuration. Current acceptance targets are pitch deviation ≤3%, acoustic word-boundary P95 error ≤120 ms, and human-rated pronunciation ≥98%, with at least 20 pitch observations, 100 word boundaries, and 20 pronunciation ratings. These are admission targets, not measured results of this change. SDK-estimated timestamps cannot populate the acoustic field. No new backend is automatically preferred on the basis of synthetic tests or missing quality evidence.

## Validation and remaining device work

Focused tests cover tempo duration and pitch on synthetic waveforms, callback partitioning, stream cancellation, mapped caption boundaries and revisions, end-of-stream drainage, dictionary projection and exchange files, voice catalogs, comparison/export identity, and translation routing/literal output. Native SDK smoke matrices include the new multilingual bundle and verified voice enumeration. The task receipt records the executed checks and any build constraints.

Executed host checks passed: 152 combined voice JVM cases, 49 engine/factory/model-store cases against the actual SDK 1.2.3 classes and official Android API jar, and the canonical host gate's 850 Python cases with one skip. Source fingerprints still matched after integrating the incoming PR changes. The independent caption suite also passed 98 cases; these suite counts overlap and must not be added together as a unique test total.

Full Android compilation did not complete in this workspace. The default combined compilation exhausted its 3 GiB heap. The repository already separates generation and compilation in CI and requires verified memory headroom for its larger bounded compiler processes; the actual local preflight could not establish that headroom. Those limits were retained. The new repository, runner, provider, ViewModel and Android audio regressions remain for full CI/device execution.

The workspace has no attached Android device. The real Linux MediaPipe smoke is blocked before inference by missing required CPU topology files under `/sys/devices/system/cpu`; no topology was fabricated to bypass this prerequisite. Handset JNI smoke, listening tests, output latency on Bluetooth/USB routes, sustained thermal behavior, and physical GPU/NPU placement remain unmeasured.

The next device acceptance run should use identical source fixtures and voices at 1×, 1.25×, 1.5×, and 2×; include questions and spoken commands in translation mode; exercise pause/refill/cancel/route changes; and retain independent acoustic references. Acoustic forced alignment remains a separate integration: the existing SDK estimates and transcription runtime do not supply a verified forced-alignment API. It should be introduced only with a real compatible aligner and latency/error measurements, retaining the bounded revision and already-heard-word rules above.

## Source and implementation references

- [AndroidX Sonic source at the pinned revision](https://github.com/androidx/media/blob/8c6678b657ede1e7883fc164ef73ed483c7796c3/libraries/common/src/main/java/androidx/media3/common/audio/Sonic.java), with [local source/license receipt](../audits/voice-sonic-source.json) and bundled Apache-2.0 notice.
- [AndroidX SonicAudioProcessor API](https://developer.android.com/reference/androidx/media3/common/audio/SonicAudioProcessor), documenting independent speed/pitch and media/playout duration mapping.
- [Google MediaPipe text-to-speech documentation](https://developers.google.cn/edge/mediapipe/solutions/audio/text_to_speech).
- [Pinned Pocket model card](https://huggingface.co/litert-community/pocket-tts/blob/00c866f2397788f514ab6bbcb931be22afc3b58f/README.md).
- [Implementation task receipt](../repairs/voice-streaming-quality-2026-11-27-task.json).
