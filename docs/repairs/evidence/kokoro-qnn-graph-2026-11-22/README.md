# Kokoro QNN graph repair — 2026-11-22

Parent: draft PR #2254, `fix/diagnostic-execution-completion-2026-11-22`.
Implementation worktree: `agent/kokoro-acceleration-2026-11-22`, based on
`2fb4f4434fa80ef26455d84f09e45fc425c6b629`.

## Reproduced faults and repair

The exact installed Kokoro model reproduces the reported QNN `GetCapability`
invalid-fusion error on the official x86 HTP emulator. The unmodified QNN 2.7
provider returned valid CPU audio but executed **zero QNN kernels** (2,224 CPU
kernels for the 17-token probe). Provider registration was therefore not evidence
of acceleration.

The adapter preserves the original provider's factory, EP and callback ownership.
It interposes only the public `EpGraphSupportInfo_AddNodesToFuse` API. It uses ORT's
actual normal and implicit dependency edges to split non-convex proposals. ORT
continues to validate the proposals and QNN continues to compile them.

Convexity alone exposed a second reproducible error: QNN omitted the
`/decoder/generator/m_source/l_sin_gen/Slice_output_0` tensor from its graph tensor
map and execution failed with `QNN_GRAPH_ERROR_INVALID_TENSOR`, code 6004. Fixed
text-token length does not determine duration-expanded acoustic tensor shapes.
The adapter therefore admits only nodes with statically resolved tensor
interfaces, leaving dynamic duration/alignment sections to CPU. These boundaries
are derived from type/shape metadata, not fragile node-name exclusions. Audio and
predicted durations still come from one complete model inference.

This is a mixed QNN/CPU graph repair. It does not claim whole-model GPU/NPU support
or an acceleration speedup.

## Observed validation

Pinned app model SHA256:
`75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e`.

Host emulator: official `onnxruntime-qnn==2.7.0`, QAIRT 2.51.0,
`onnxruntime==1.28.0`, Linux x86_64. The host wheel is separate from the Android
runtime: native compilation and the JNI integration gate use the app's pinned
ORT **1.28.2** headers/shared runtime. No application dependency pins were changed.

| Token count | QNN kernels | CPU kernels | PCM samples | Positive durations | CPU duration comparison |
| --- | ---: | ---: | ---: | ---: | --- |
| 17 | 39 | 1,345 | 60,600 | 17 | Exact duration array and sample count |
| 31 | 39 | 1,513 | 75,000 | 31 | Exact duration array and sample count |
| 83 | 39 | 2,137 | 126,000 | 83 | CPU 124,800 samples; maximum token difference 1 unit; total +2 units |

Every case returned finite, nonzero PCM and passed
`sum(durations) * 600 == sample_count`. The 83-token difference is 50 ms over
approximately 5.2 seconds, consistent with duration rounding after different
provider arithmetic. This comparison records differences; it does not assert
waveform or perceptual equivalence. The model's generator also includes noise.
The JSON receipt contains measured RMS values and the exact host adapter binary
hash. Synthetic phoneme-token IDs isolate the graph regression; the separate
native integration gate tests the real pronunciation frontend.

Other completed checks:

- Full native CMake build against ORT 1.28.2.
- Four native CTest checks: frontend pronunciation, executed-kernel evidence,
  3,000 randomized dependency DAGs plus targeted convexity regressions, and API
  ownership/error forwarding including implicit captures and dynamic outputs.
- Exact model through `KokoroNativeIntegration`: audio/timing identity, frontend
  parity, typed JNI failure provenance without phrase text, CPU cannot claim
  acceleration, cancellation, reset and in-flight cancellation.
- Ten JVM behavior tests for measured Auto selection, TTL/rollback/negative
  qualification, failure provenance, one-phrase CPU failover and interruption
  during replacement creation.
- Accelerator factory/cache Kotlin compiled with Kotlin 2.4.20 and Android 14
  framework signatures; changed Kotlin files pass pinned ktlint 1.3.1.
- `bash scripts/validate-local.sh fast` reached the expected stale app audit
  inventory gate. The parent change regenerates the combined inventory.

Added Robolectric store/identity tests are for the Android CI gate; they were not
executed in this isolated host run. Full Android build and physical-device tests
remain separate gates. **No Adreno GPU or Hexagon NPU device was available in this
workspace, so neither physical backend is claimed qualified here.**

## Reproduction

Build `local-kokoro-runtime/src/main/cpp` with CMake, supplying
`ORT_INCLUDE_DIR` and `ORT_LIBRARY` for the pinned host 1.28.2 archive and a JDK.
Run CTest and compile/run `KokoroNativeIntegration` against the verified model pack.

For the graph regression install the recorded host Python versions, place the
built `libkokoro_qnn_adapter.so` beside that package's
`libonnxruntime_providers_qnn.so`, then run:

```sh
python local-kokoro-runtime/src/testHost/kokoro_qnn_model_qualification.py \
  --pack /absolute/path/to/kokoro-int8-multi-lang-v1_0 \
  --adapter /absolute/path/to/onnxruntime_qnn/libkokoro_qnn_adapter.so \
  --output /absolute/path/to/qualification.json
```

Adding `--original --token-counts 17` reproduces the original invalid fusion and
fails the nonzero-QNN-kernel gate. Do not use a successful host-emulator run as a
phone capability receipt.

## On-device behavior and remaining acceptance

GPU selects one QNN GPU device with `libQnnGpu.so`; NPU selects one QNN NPU device
with `libQnnHtp.so`. Every newly prepared token shape must produce valid audio,
matching timing and executed QNN kernels before its PCM can be published.

Stable factory identity includes model digest, runtime/adapter revision, device
fingerprint digest, selected backend and explicit recheck revision. Capability
keys additionally include lexicon and thread count. The bounded store retains at
most 24 receipts, expires successful measurements after seven days and failures
after six hours, rejects clock rollback, and supports explicit recheck. Auto
requires a CPU baseline and actual accelerator execution, compares two phrase
shapes including preparation, and retains CPU unless the measured improvement is
at least 10%. An accelerator failure retires that engine and retries only the
current unplayed phrase on CPU. Subsequent phrases use that CPU engine.

Physical acceptance must check GPU and NPU separately, multiple voices/token
shapes, warm and cold preparation, finite/nonzero audio, same-inference timing,
real provider kernels, partial CPU participation, stop/reply races, and measured
CPU comparison. If production is slower than audio playback, the buffer UI must
report refilling rather than promise uninterrupted speech.

## Primary implementation references

- [ORT QNN 2.7 source, pinned commit](https://github.com/onnxruntime/onnxruntime-qnn/tree/9d7bc6621895d691eecb3609bdcc1dfa14f8c660)
- [QNN provider's partition construction](https://github.com/onnxruntime/onnxruntime-qnn/blob/9d7bc6621895d691eecb3609bdcc1dfa14f8c660/onnxruntime/core/providers/qnn/qnn_ep_utils.cc)
- [QNN provider's GPU/NPU device mapping and host emulator](https://github.com/onnxruntime/onnxruntime-qnn/blob/9d7bc6621895d691eecb3609bdcc1dfa14f8c660/onnxruntime/core/providers/qnn/qnn_provider_factory.cc)
- [Pinned ORT 1.28.2 publisher archive](https://github.com/csukuangfj/onnxruntime-libs/releases/tag/v1.28.2)
- [Kokoro model's duration prediction and expansion](https://github.com/hexgrad/kokoro/blob/main/kokoro/model.py)

QNN 2.8/QAIRT 2.52 was considered, but the repair is demonstrated with the existing
pinned payload. A broader runtime upgrade would require independent publisher
hash verification and regression coverage for the app's other Qualcomm users;
no speculative version upgrade or regenerated dependency checksum is included.
