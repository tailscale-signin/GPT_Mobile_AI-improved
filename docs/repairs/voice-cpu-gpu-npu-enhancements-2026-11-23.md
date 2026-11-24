# Kokoro CPU, GPU and NPU enhancement follow-up

Repository: tailscale-signin/GPT_Mobile_AI-improved. Parent: PR #2367,
fix/diagnostic-execution-completion-2026-11-22. This follow-up started at
6d3b9e4500099e34998f8a95c25dd896381c8fd3 and preserves the subsequent shared
provider correction at 6ae1763b96c5d9998b31b4ff941a19ffdb24c49d and readiness
selection at 1cc96b1936c1b3f6442cc89441cce3c6ee77daaa, followed by the narrowly
scoped scanner correction at dcaa851dce7fb7f602ed6d230f24bdf69838c06a.

The supplied phone comparison establishes a CPU baseline and failed GPU audio
for its captured build. It is not a benchmark of this implementation. No physical
Snapdragon, acoustic, battery-saving or target-latency improvement is claimed.

## Production behavior

CPU remains the saved default and is labelled recommended. The two native QNN
choices are labelled experimental. Existing explicit choices remain intact.
Accelerator admission still requires the same voice, rate, model, runtime and
phone's CPU-reference comparison. An invalid unplayed phrase can use the existing
one-time CPU failover; already presented speech is never replayed.

The new native gate rejects even one full-scale or out-of-range PCM sample,
including the sparse eight-clipped-sample pattern from the supplied GPU result.
It checks the original float waveform before JNI delivery or playback conversion,
retains RMS, peak, clipping count and the fixed CLIPPED_PCM reason, and does not
hide corruption by clamping or normalizing it. The Java decoder rejects
contradictory successful/clipped receipts. Comparison protocol v2 requires zero
clipping in both candidate and CPU reference, while retained invalid observations
remain available for diagnosis. The changed runtime identity invalidates stale
accelerator qualification.

CPU prewarming now executes one short fixed public phrase silently after loading
the model. Successful preparation is reused only for the same resident model,
speaker and rate. A live reply cancels queued or active optional inference, and
the serial worker keeps ownership until the native call returns. An attempt-bound
five-second cancellation request cannot stop a later reply. Unknown or elevated
thermal state skips this optional inference. GPU, NPU, Auto, Gateway and SDK
engine preparation retain their existing admission paths. No warmup PCM reaches
playback, captions, reply diagnostics or benchmark observations.

## Coverage of the requested improvements

| Requested improvement | Implementation and qualification boundary |
| --- | --- |
| Reject incorrect GPU duration and corrupt signal | Existing same-inference timing and CPU-reference duration/token/signal checks are retained; the new absolute zero-clipping gate closes the remaining sparse-clipping tolerance. This prevents invalid delivery; it does not prove corrected Adreno arithmetic. |
| Prevent stuttering and buffer underruns | Existing separate synthesis/playback workers, bounded PCM queue, adaptive delivery-gap reserve and caption/playback ownership are retained. New CPU inference preparation moves optional first-use work ahead of a reply. Target-phone continuity still needs playback measurement. |
| Investigate CPU fallback | New bounded operator summaries identify the hottest observed CPU/QNN kernel categories. Existing convex/static-interface partitioning keeps duration-expanded dynamic acoustic sections on CPU. Observed placement is not an unsupported-operator diagnosis or device-utilization percentage. |
| Prewarm on entry | Existing early local model preparation is extended with the cancellable silent CPU inference described above. |
| Generate while playing | Existing independent producer/output workers continue to overlap inference and playback, with bounded queue backpressure and cancellation. |
| Cache compiled graphs | Existing in-process exact-token-shape LRU retains up to three memory-admitted compiled accelerator sessions. Persistent on-disk context export was tested separately and remains disabled after the reproducible failure below. |
| Optimize fixed shapes | Existing specialization and alternating-shape benchmark fixtures are retained. Padding arbitrary duration-expanded acoustic tensors is not enabled without model correctness evidence. |
| Investigate the CPU graph budget | Existing evidence separates active-session estimates, retained-cache estimates, observed available memory and admission reason. The essential active CPU session can exceed the nominal cache budget; that budget is not an allocator-enforced RAM limit. |
| Adaptive buffering | Existing reserve learning uses retained-PCM delivery gaps, excludes callback/queue waits and preserves the six-second queue and bounded reserve. |
| Quantized model comparisons | Existing pinned native INT8 Kokoro and installed LiteRT Kokoro, Phonon and Pocket comparisons are retained. SDK GPU requests are kept separate from proven processor execution and native CPU/GPU/NPU ranking. |
| At least 20 repetitions | Existing synthesis Stability mode collects 20 warm repeats per fixture. Quick and Full remain explicitly smaller; p95 is withheld below 20 eligible observations. |
| Gaps, energy and thermals | Existing AudioTrack underrun/refill and thermal evidence remains. New optional whole-device battery-counter endpoints and power-event observations are persisted and exported per backend. Acoustic gaps and engine-attributable energy remain separate, unmeasured quantities. |

## Operator diagnostics

The native parser reads only valid executed-kernel events and their top-level
operator type. It retains a fixed vocabulary, maps unknown names to Other and
opaque fused QNN nodes to QnnPartition, and exports up to three hottest categories
per provider within the existing 512-byte discovery channel. Each record carries
kernel count and profiled microseconds. Explicit scope/reuse markers distinguish
the first inference of a graph shape from a later reuse, and omission markers
retain the fact that additional categories exist. Node names, graph hashes,
paths and raw driver messages are excluded.

## Persistent compiled-context investigation

The pinned QNN 2.7 implementation supports context-model generation, including
mixed graphs, so this feature is not classified as categorically unsupported.
However, the exact application ORT 1.28.2, QNN 2.7, QAIRT 2.51 host emulator,
cached adapter binary and pinned Kokoro model failed context generation with a duplicate
definition of /Cast_5_output_0. The otherwise identical context-disabled control
returned 60,600 finite PCM frames with zero clipping and observed 39 QNN/1,345 CPU
kernel events. Those are host-control observations, not phone performance data.
The adapter's exact binary digest is recorded, but its source-to-binary build
provenance was not independently established in this experiment.

Enabling the persistent cache would therefore replace a working mixed execution
path with an initialization failure in the qualified control. It remains off.
The source/hash-bound receipts in the adjacent evidence directory record the
failed experiment and successful control. A future exporter repair must preserve
model metadata and CPU fallback, pass cold generation and reload comparisons,
and add device/runtime/model/shape binding, atomic storage, bounded disk retention
and invalid-cache recovery before activation.

## Battery observations and engineering targets

BatteryManager's remaining-energy counter is optional and reports nanowatt-hours.
Unsupported values remain unknown. The report distinguishes missing counters,
unknown power state, charging, incomplete monitoring, interrupted observation,
counter increase/reset and unchanged counters. A qualifying observed decrease is
reported only as a whole-device counter change over the backend publication
window. Display, background work, preparation, reporting, counter resolution and
unobserved power transitions prevent attributing that change solely to an engine.
No energy winner, inferred wattage or zero-consumption claim is produced.

The UI and JSON expose the requested engineering goals separately from results:
preparation-to-first-PCM below 800 ms, warm first PCM below 400 ms, long-speech RTF
below 0.50, zero clipping and playback interruptions, and more than 80% of profiled
kernel time on the accelerator where feasible. The share goal is advisory and
does not create a qualification requirement or describe hardware utilization.
Native completed-chunk timing, SDK callback timing and presented playback time
remain distinct measurements.

## Validation

The task checkpoint and adjacent receipts record the exact source, test commands,
outcomes and boundaries. Seven pure warmup methods and six actual-worker
Robolectric cases pass. Energy and Android battery-source controls pass 20 cases
(including API 31 and 34), and report/reference/export/store/tracker controls pass
69 methods. Native C++/Java contracts and eight host reference-comparison cases
pass. The ordinary-method inventory preserves every previous method and identifies
96 method names across eleven classes for final hosted qualification. Canonical
fast validation passes 711 host tests, generated metadata, credential-pattern,
dependency, Android-regex and resource checks; pinned ktlint passes all 29 changed
Kotlin files. The integration receipt binds the source snapshots and test logs. Focused pure/native controls and actual Android worker
regressions are separate from full project CI. The mandatory local Android memory
admission requires 8,960 MiB of verified headroom; this workspace did not qualify,
so full Gradle compilation is left to the ordinary hosted pipeline. No gate,
memory requirement, assertion or device qualification is waived.

Primary references:

- [Android BatteryManager API](https://developer.android.com/reference/android/os/BatteryManager)
- [Pinned QNN provider source](https://github.com/onnxruntime/onnxruntime-qnn/tree/9d7bc6621895d691eecb3609bdcc1dfa14f8c660)
- [ONNX Runtime EP context design](https://onnxruntime.ai/docs/execution-providers/EP-Context-Design.html)
