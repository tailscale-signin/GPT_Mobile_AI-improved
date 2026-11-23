# Speech benchmark and first-clause delivery

The Voice settings benchmark compares the installed Kokoro model on CPU, Adreno GPU and Hexagon NPU. It runs only after an explicit user action and does not download models or send text to a service. Each selected backend gets a fresh engine and the same fixed short, medium and long phrases, voice, rate and CPU thread count. Quick runs each shape once cold and twice warm; Full uses five warm repeats. Normal voice playback is paused by the screen owner before starting.

## Measurements and interpretation

- Session preparation is measured separately from phrase generation. The first use of each phrase shape includes any deferred graph preparation. The OS file cache is not cleared.
- The native API returns a complete phrase. First PCM therefore means that return, not a fictional earlier streaming callback. The first sample also records elapsed preparation-to-PCM time. Playback latency, acoustic latency, underruns and battery energy are explicitly unmeasured.
- Each sample records audio duration, real-time factor, actual provider, validated durations, thermal status and first-shape kernel counts/times. Warm p50/p95 and per-shape samples remain visible. A profile captured on the first inference is never relabeled as a new warm-run kernel trace.
- Benchmark factories bypass automatic calibration and CPU recovery. Missing libraries, graph errors and CPU-only execution become explicit backend outcomes. Mixed accelerator/CPU graphs can pass only with actual accelerator kernel evidence and validated audio.
- A recommendation requires complete paired workloads, a measured CPU baseline and no recorded moderate thermal state. An accelerator must improve total measured work, including engine/graph preparation, by at least ten percent. Saved runs retain voice, rate, threads, model digest, runtime, app build and local device binding. Runs with different current settings/builds are labeled accordingly, and benchmark results do not silently overwrite Auto's differently scoped calibration cache.

## Lifecycle and persistence

One in-app benchmark owns the measurement at a time. Native operations share the existing native engine gate and installation-use lease. Cancellation is forwarded to engine preparation/inference, completed samples are retained, and unfinished backends remain unmeasured. Backgrounding or leaving settings requests cancellation; cleanup releases the engine and file lease. Settings disposal also clears the screen's running flag so voice mode cannot stay disabled after the test leaves composition.

The last five runs are atomically saved in app-private, backup-excluded storage. In-progress runs restore as interrupted after process death. Oversized/corrupt history cannot manufacture a result or prevent a new save. JSON export includes fixed identifiers and numeric measurements but excludes conversation text, audio, native exception messages, device serials and the local device-binding hash.

## Speech efficiency changes

The first streamed phrase now prefers a completed clause, with an eight-word fallback for long unpunctuated openings. A short complete sentence can start immediately, and Auto retains its bounded pause flush. Follow-up chunking, bounded PCM production and native duration alignment remain intact. The speech projection owns the length-limit notice, preventing an extra unmapped or duplicated spoken suffix. Explicit full-details playback selects the same projection used by its captions.

Auto's lightweight comparison now uses three phrase shapes. Its three-sample score includes the total workload cost, avoiding a median that could discard expensive initial preparation. Legacy two-sample receipts remain conservative, and the existing ten-percent threshold remains enforced.

## Verification

The accompanying task checkpoint records host regressions, Android/Compose signature compilation and canonical fast validation. No physical-phone speedup or thermal-efficiency result is claimed by this change. The in-app benchmark produces those observations on the actual device; hosted Android tests, required checks and release publication are owned by the integrating task.
