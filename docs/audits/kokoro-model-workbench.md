# Kokoro model preparation and GPU/NPU diagnostics

The app keeps the compact INT8 pack as the default and offers a separate original
FP32 installation. The original pack is downloaded only through an explicit
installation action. Its archive, model, voices, tokens, lexicons and licence are
pinned in `kokoro-original-fp32.json`. The two variants use separate directories,
model hashes and execution identities. A CPU/accelerator comparison must select
the same variant on both sides.

The FP32 model is the publisher's original floating-point export. Converting the
existing INT8 file back to float does not recover the original weights. FP32 ONNX
tensors also do not force FP32 arithmetic inside a GPU backend. This distinction
is kept in both the workbench manifest and the native precision diagnostic.

## Install the isolated development tools

Use CPython 3.12 on Linux x86_64 for the hash-pinned requirements:

```bash
python3.12 -m venv .venv-kokoro-workbench
.venv-kokoro-workbench/bin/python -m pip install --require-hashes --only-binary=:all: \
  -r scripts/requirements-kokoro-model-workbench.txt
```

Publisher wheel identities are recorded in `kokoro-workbench-python.json`.
The `model_workbench` job in `speech-engine-smoke.yml` runs real ONNX preparation,
QNN calibration helpers, CPU inference, intermediate comparisons and stage
extraction. These are synthetic regression fixtures. They are not representative
calibration data, a listening test or a physical Snapdragon benchmark.

All output directories must be new. Generation takes place in a temporary sibling
directory and publishes only after the model and manifest are complete. Inputs
require explicit SHA-256s. External ONNX tensor sidecars, pickle tensors and
manifest path escapes are rejected. Encoded tensor archives and their expanded
payloads each have a 128-MiB limit. Before constructing the ZIP library inventory,
the workbench reads a bounded end record and walks at most 64 actual directory
headers. It rejects inconsistent counts, malformed offsets or lengths, trailing
archive layouts and archive-level ZIP64 overrides. Valid NumPy local ZIP64 size
fields remain supported. Duplicate names, numeric types, NPY header sizes, ranks,
declared dimensions, payload lengths and CRCs are checked before NumPy allocates
arrays. Both validation and loading use the same private archive snapshot, whose
SHA-256 must match the explicit input identity. These admission bounds do not
measure or bound the model runtime and comparison scratch space; select small
intermediates when inspecting large models.

JSON manifests are read once under a 2-MiB bound. Their parsed values and recorded
hashes come from the same bytes, including calibration, overrides, comparison
inputs and stage specifications. Replacing a source pathname during work cannot
relabel the consumed manifest or tensor snapshot.

Inspection and comparison reports cannot alias their model, manifest or tensor
inputs through a direct path, symbolic link or hard link. Reports are staged in a
private sibling file and atomically replaced only after generation, with alias
checks repeated before publication. Input files remain intact on a failed report.

## Inspect and prepare an exact token shape

Set `MODEL` to the independently downloaded and verified original `model.onnx`:

```bash
MODEL=/absolute/path/kokoro-multi-lang-v1_0/model.onnx
MODEL_SHA=b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11
python scripts/kokoro_model_workbench.py inspect \
  --model "$MODEL" --model-sha256 "$MODEL_SHA" --output inspection.json
python scripts/kokoro_model_workbench.py prepare \
  --model "$MODEL" --model-sha256 "$MODEL_SHA" \
  --backend gpu --precision fp32 --token-count 93 --output-dir prepared-gpu-93
```

Token counts include the same boundary tokens used by the app and must be 3–511.
Preparation specializes the actual input shape. It does not add padding, mask
tokens, truncate text or turn a data-dependent duration/output axis into a static
axis. The original export contains recurrent and control-flow operators, so a
static token input alone cannot establish a fully accelerated NPU graph.

For an HTP floating-point candidate use `--backend htp --precision float`. On
supported Qualcomm SoCs the HTP floating-point path uses FP16 mathematics; this
option does not claim true FP32 HTP execution. The GPU candidate likewise records
that no unsupported execution-provider precision key has been sent.

## Calibrate real HTP quantization candidates

Collect representative frontend inputs for each exact token shape, including
the selected speakers, speech rates, punctuation and phrase types. Each NPZ case
contains exactly `tokens` (int64 `[1,N]`), `style` (float32 `[1,256]`) and `speed`
(float32 `[1]`). Use the exact token/voice frontend paired with the original pack.
Do not use padded, random or synthetic test fixtures to claim model quality.

The corpus manifest binds every local case:

```json
{
  "schemaVersion": 1,
  "purpose": "calibration",
  "cases": [
    {"id": "heart-normal-short-001", "file": "heart-normal-short-001.npz", "sha256": "<actual SHA-256>"}
  ]
}
```

```bash
python scripts/kokoro_model_workbench.py prepare \
  --model "$MODEL" --model-sha256 "$MODEL_SHA" --backend htp \
  --precision a16w8 --token-count 93 --calibration corpus-93.json \
  --output-dir prepared-htp-a16w8-93
```

`a16w8` uses unsigned 16-bit activations and unsigned 8-bit weights through ORT's
QNN quantization configuration; `a8w8` is a separate candidate. Optional
`--overrides tensor-overrides.json` supplies the actual QNN helper's
`init_overrides` map for measured sensitive tensors. The report binds that file's
hash. The workbench preserves original model metadata across preprocessing and
quantization; ORT 1.28's preprocessing metadata conversion is handled through a
temporary metadata-free copy, with the original metadata restored afterward.

Each variant must separately pass duration, token timing, signal consistency,
listening and sustained device measurements. No workbench command grants app
playback admission or changes the CPU baseline.

## Locate the first observed GPU divergence

Choose a few intermediate tensor names from inspection of the ONNX graph,
particularly around the duration path and the boundary of a suspect partition.
The trace copy gives stochastic operators explicit seeds. It also records that
adding graph outputs can change partitioning, so an observed mismatch is evidence
to narrow the investigation, not proof that the immediately preceding operator
is defective.

```bash
python scripts/kokoro_model_workbench.py trace \
  --model "$MODEL" --model-sha256 "$MODEL_SHA" \
  --tensor '<actual intermediate name>' --tensor audio \
  --seed 7 --output-dir diagnostic-model
python scripts/kokoro_model_workbench.py capture \
  --model-manifest diagnostic-model/manifest.json \
  --case phrase.npz --case-sha256 '<actual SHA-256>' \
  --backend cpu --output-dir cpu-capture
python scripts/kokoro_model_workbench.py capture \
  --model-manifest diagnostic-model/manifest.json \
  --case phrase.npz --case-sha256 '<same actual SHA-256>' \
  --backend qnn-gpu --qnn-plugin /absolute/path/libonnxruntime_providers_qnn.so \
  --qnn-backend /absolute/path/libQnnGpu.so --output-dir gpu-capture
python scripts/kokoro_model_workbench.py compare \
  --reference cpu-capture/manifest.json --candidate gpu-capture/manifest.json \
  --atol 0.00001 --rtol 0.001 --output first-divergence.json
```

The plugin, backend, Python runtime and accelerator must be compatible with the
execution platform. Linux Python cannot load an Android `.so`. `qnn-htp` requires
an observed NPU device; `qnn-emulator` explicitly selects the CPU-hosted HTP
emulation device and labels its scope. Every QNN capture requires observed QNN
kernel events in its ORT profile. Provider discovery alone is insufficient.
Keep these local tensor artifacts private if the chosen corpus contains personal
speech inputs; the command does not upload them.

Comparison binds the same source model, instrumented model, input archive and
ordered tensor names. Shape, type and nonfinite failures are explicit; integers
compare exactly. The first reported divergence follows graph order among the
selected outputs. Re-run an uninstrumented full-model comparison after any fix.

## Extract explicitly chosen static stages

After real partition profiling identifies a useful boundary, supply the exact
tensor names and model hash. The workbench refuses to guess an architectural
split from operator names:

```json
{
  "schemaVersion": 1,
  "sourceModelSha256": "<actual source SHA-256>",
  "stages": [
    {"id": "decoder", "inputs": ["<actual decoder input tensor>"], "outputs": ["audio"]}
  ]
}
```

```bash
python scripts/kokoro_model_workbench.py stages \
  --model "$MODEL" --model-sha256 "$MODEL_SHA" \
  --specification stages.json --output-dir extracted-stages
```

Extraction runs the ONNX checker and preserves metadata. Its manifest explicitly
sets `recompositionValidated=false`. The app's single-model Kokoro renderer does
not silently adopt extracted stages. A staged production renderer requires
verified duration/front-end contracts, tensor handoff, whole-utterance equivalence
and measured benefit on the target phone before that architecture is selected.
No custom kernel is selected until profiles identify an unsupported or expensive
operation whose replacement can preserve those contracts.

## Verify the native GPU precision transport

`scripts/native/qnn_gpu_precision_probe.cpp` calls the real SDK graph API with
`QnnGpuGraph_CustomConfig_t.precision`; it does not invent a QNN EP option. It builds
an add/subtract residual graph with a non-output intermediate and disables node
optimizations for the diagnostic. FP32, FP16, hybrid and user-provided modes are
explicit. The probe rejects a non-GPU or incompatible backend interface and
reports every failed QNN call. FP32 residual error above `1e-6` returns exit code 2.

```bash
python scripts/build_qnn_gpu_precision_probe.py \
  --ndk /absolute/path/android-ndk-r29 \
  --qairt-sdk /absolute/path/qairt/2.52.0.261104 \
  --output-dir gpu-precision-probe
```

The build verifies the pinned SDK header digest, NDK revision, ARM64 output,
16-KiB ELF alignment and Android system dependencies. It emits a source/binary
receipt marked `COMPILED_NOT_EXECUTED`. On a compatible Android phone, copy this
executable and the matching QAIRT GPU runtime libraries to an isolated test
directory, then run the executable with an absolute `libQnnGpu.so` path and one
of `fp32`, `fp16`, `hybrid`, `user`. Set `LD_LIBRARY_PATH` to that same directory.
Keep all QAIRT libraries in that process at one version. Record the device and
library hashes with the output. Successful execution verifies this small graph's
configuration transport; it neither patches the app's EP nor qualifies Kokoro.

## Rebuild and promote a coherent runtime candidate

`qnn-runtime-candidate.yml` builds the existing exact LiteRT/LiteRT-LM source pin
against the verified QAIRT 2.52 headers. It downloads the complete SDK to verify
the upstream SHA-256 before extracting only headers, verifies all candidate Maven
artifacts, builds with the pinned NDK/Bazel, checks the dispatch ABI/dependencies
and uploads an exact receipt. The SDK is not uploaded as an artifact.

```bash
python scripts/build_qualcomm_dispatch_candidate.py \
  --work-dir /absolute/path/candidate-build \
  --output-dir /absolute/path/candidate-result \
  --ndk /absolute/path/android-ndk-r29 --jobs 2
```

The candidate does not edit production pins. Promotion must update the QNN EP,
shared QAIRT dependency, matching dispatch binary, Gradle verification/locks and
native inventory together, then pass the existing APK checks. Device execution
and audio admission remain separate from successful source compilation.

The QAIRT 2.52 candidate passed its actual hosted build and was promoted as one
coherent dependency bundle, with the ORT 1.28.2 CPU baseline preserved. Exact CI
run/artifact identity, publisher checksums, binary hash and the separate host
context result are recorded in `qnn-runtime-promotion-2.52.json` and the
[dispatch provenance](litert-qualcomm-dispatch-build.md). The original candidate
descriptor is retained unchanged as an immutable build input.

Repeat the small QNN 2.8 context regression without a phone:

```bash
python scripts/prepare_qnn_host_probe.py --output-dir /absolute/new/qnn-host
LD_LIBRARY_PATH=/absolute/new/qnn-host \
  python local-kokoro-runtime/src/testHost/kokoro_epcontext_compatibility_probe.py \
    --libraries /absolute/new/qnn-host --ort-version 1.28.0 \
    --output /absolute/new/qnn-context-receipt.json
```

Use the hash-pinned workbench Python environment above. Preparation verifies the
whole publisher wheel, extracts only individually hashed host libraries, and
does not install its Python package. The fixture requires QNN and CPU events,
preserves metadata and CPU initializers, and compares a newly loaded embedded
context against the original execution. It has no physical-device or listening
admission effect.

The official QNN EP 2.8 source is pinned to
`6c9a5e22a795d1576e056a73751a0f2f39d06894`. Its Android source-build entry point is
`python qcom/build_and_test.py --qairt-sdk <sdk> --ort-prebuilt <ort-sdk> build_ort_android_aarch64`.
That upstream build also fetches ORT 1.29 source even when an ORT prebuilt is
supplied, and installs additional compiler/Hexagon tooling for its broader build
and test suite. In this workspace the pinned Microsoft ORT source endpoint
returned HTTP 404. The initial local LiteRT Bazel build also ran out of disk
while extracting TensorFlow; the isolated hosted dispatch build subsequently
passed. A local QNN provider source rebuild is not claimed. The app uses the
verified publisher plugin and the actual rebuilt matching LiteRT dispatch.

Primary specifications used: [QNN EP 2.8 documentation](https://github.com/onnxruntime/onnxruntime-qnn/blob/v2.8.0/docs/execution_providers/QNN-ExecutionProvider.md),
[official source-build workflow](https://github.com/onnxruntime/onnxruntime-qnn/blob/v2.8.0/docs/execution_providers/build.md),
[GPU graph configuration API](https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-10/api-rst_file_include_QNN_GPU_QnnGpuGraph_h.html),
and [ORT quantization diagnostics](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html).


## Investigating collapsed token durations

Use `trace --duration-path` on the exact model from the failed run. It selects up to twelve typed prediction checkpoints feeding the native duration output, followed by token durations and waveform. Capture one pinned token, style and rate input on CPU and GPU using that diagnostic model, then run `compare` to find the first observed difference. Repeat both captures with the original FP32 variant. Separate variant pairs carry different model hashes.

Capture manifests include per-provider kernel microseconds and the 24 most expensive provider/operator pairs. For the HTP path, compare CPU `ConvInteger` time with QNN convolution placement on a calibrated candidate prepared from the original FP32 weights. Instrumentation can alter placement. Repeat the uninstrumented comparison before selecting a model for playback.

On the phone, compare **Production phrases** with **Production phrases, alternating lengths**. Stability collects 20 warm repetitions per length. Full playback with all six workloads and controlled-zero caption calibration exercises presentation, cancellation and five minutes of sustained playback. Compare the same model, voice, rate, device and thread count. A stored HTP context carries a private process marker. After restarting the app, a validated disk load can report `HIT_PREVIOUS_PROCESS`. An in-memory reuse reports `RESIDENT`. The marker stays in the private cache.
