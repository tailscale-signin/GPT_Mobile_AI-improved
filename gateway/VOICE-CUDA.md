# Optional CUDA speech for Gateway

Android can explicitly select **Gateway CUDA** for Kokoro speech. The phone keeps
its English frontend, pronunciation overrides, source mapping and caption clock.
The selected Gateway receives phoneme token IDs and returns waveform and token
durations from the same model run. Phoneme IDs contain spoken content; they are
not anonymous performance metadata. Neither content nor audio is retained or
logged by the voice service.

Local CPU speech remains the default. CUDA becomes ready only after a real warmup
profile contains executed `CUDAExecutionProvider` nodes. An installed NVIDIA
driver or a provider name alone does not establish acceleration.

## Published Windows installation

The Windows installer includes `voice_worker.py`, setup and manifest helpers,
and `requirements-voice.txt` in its validated source payload. The optional GPU
libraries live in a separate venv. The frozen `Gateway.exe` communicates with
that venv over private stdin/stdout pipes. The worker opens no network listener
and receives no Gateway or provider credentials.

Prerequisites:

- A supported NVIDIA GPU and driver.
- Standard x64 CPython 3.11 through 3.14 with `venv` and `pip`. The commands below use 3.12. The lock selects NumPy 2.4.6 for Python 3.11 and 2.5.5 for newer supported versions.
- CUDA 13.x and cuDNN 9.x libraries available to the process. The pinned
  `onnxruntime-gpu==1.32.0` PyPI build targets CUDA 13.x and cuDNN 9.x according to its wheel metadata. See the publisher's
  [CUDA compatibility table](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html).
- The app-compatible
  [Kokoro multilingual v1.0 pack](https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/kokoro-multi-lang-v1_0.html)
  with floating-point `model.onnx`, `voices.bin`, `tokens.txt`,
  `lexicon-us-en.txt` and the accompanying license. The inspected publisher
  [model revision](https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/blob/f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b/model.onnx)
  has SHA-256 `b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11`.
  The Android int8 pack can contain CPU-oriented operators and is not assumed to
  be an accelerated CUDA model.

After extracting the model pack, run this in PowerShell, adjusting the model
directory and installation root if necessary:

```powershell
$root = Join-Path $env:LOCALAPPDATA 'Programs\GPTMobileGateway'
$version = (Get-Content -LiteralPath (Join-Path $root 'current.json') -Raw | ConvertFrom-Json).version
$source = Join-Path $root "releases\$version\_internal"
py -3.12 (Join-Path $source 'setup_voice_cuda.py') --root $root --model-directory 'D:\Models\kokoro-multi-lang-v1_0'
```

The helper creates `addons/voice-cuda/venv`, installs the exact wheel versions
and hashes in the optional lock file, hashes the selected model and voice files,
and qualifies a warmup through the exact private-pipe path used by `Gateway.exe`.
Only successful CUDA execution enables `data/voice-cuda.json`. Failed
qualification leaves the previous enabled configuration unchanged. Dependency
installation can require a substantial download; no model is silently downloaded.

Restart Gateway through its existing desktop controls, then select the existing
Gateway connection and Gateway CUDA in Android voice settings. Model warmup runs
in the background and does not delay ordinary Gateway readiness. Voice
capabilities report `VOICE_WARMING` until qualification finishes.

To disable the add-on, set `enabled` to `false` in `data/voice-cuda.json` and
restart. Rerun the helper to change the selected model. The saved add-on is
outside the versioned release directory, so normal upgrades retain it. Worker
code always comes from the currently validated Gateway release. Managed Windows
deployments read this explicit local configuration and ignore inherited
`GATEWAY_VOICE_*` variables.

## Python source deployment

From the `gateway` directory, prepare the optional interpreter and model:

```bash
python3.12 -m venv /absolute/path/to/voice-venv
/absolute/path/to/voice-venv/bin/python -m pip install --require-hashes -r requirements-voice.txt
python prepare_voice_pack.py --directory /absolute/path/to/kokoro-multi-lang-v1_0 --model model.onnx
export GATEWAY_VOICE_MODEL_DIR=/absolute/path/to/kokoro-multi-lang-v1_0
export GATEWAY_VOICE_PYTHON=/absolute/path/to/voice-venv/bin/python
python gateway.py serve
```

Keep the normal Gateway installation and admission configuration. Source
deployments also use the private worker. Without `GATEWAY_VOICE_MODEL_DIR`, the
voice service is disabled and imports no optional GPU dependencies. If the
interpreter variable is absent in a source deployment, the child uses the
current Python interpreter.

## Model and request contract

`prepare_voice_pack.py` uses the publisher's `hello` dictionary entry for warmup.
Its `voice-manifest.json` pins `model_sha256`, `voices_sha256`, protocol
`kokoro-timed-v1`, frontend `kokoro-english-v1`, 24 kHz audio and 600 samples per
predicted duration unit. It does not add a second G2P implementation.

The frontend label is bound to the Android pack's exact symbol-to-token-ID
mapping, including its space symbol. Preparation and every worker startup check
the canonical mapping SHA-256
`ef67465bdf5b8a147574207fdf8eaba3d7b913161440f0896bb0dd90c04ac3f6`.
Line ordering and CRLF/LF differences are accepted; remapped IDs are rejected.
The model hash remains independent, allowing compatible floating-point and
int8 exports to have different weights and file digests.

The runtime requires a self-contained ONNX file with `sample_rate=24000`,
`style_dim=510,1,256`, and a valid `n_speakers`. Native input order is `int64`
token IDs, `float32` style, `float32` speed; output order is `float32` waveform
then `int64` durations. Style selection is `styles[speaker, token_count - 2]`.
External-data sidecars, missing timing outputs, nonfinite audio, nonpositive
durations and `sum(durations) * 600 != waveform_frames` are rejected.

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/gateway/voice/capabilities` | GET | Readiness, pinned model hash, provider evidence and limits |
| `/gateway/voice/synthesize` | POST | One bounded phrase with source word-token spans |
| `/gateway/voice/cancel` | POST | Cancel an opaque request ID owned by the caller |

Synthesis requests contain exactly `protocol`, `frontend_id`, `request_id`,
`token_ids`, `word_tokens`, `speaker`, `speed`, and `model_sha256`. The model hash
must match capabilities. Responses echo the identity fields and return
`sample_rate`, `frames_per_duration`, `pcm_f32le` as base64, `durations`,
`word_tokens`, `execution_provider`, `executed_cuda_nodes` and measured
`synthesis_ms`. Word spans must be ordered inside the PAD-delimited token array.
Speed is bounded to 0.5–2.0.

Limits are 511 token IDs, 64 source words, 16 KiB request bodies, 30 seconds of
24 kHz mono audio per phrase, eight total pending requests and two per admitted
principal. One GPU worker runs at a time. The 25-second deadline includes queue
time. Cancellation terminates the exact ORT `RunOptions`; disconnects and
shutdown also cancel outstanding work. Short, bounded in-memory ID tombstones
handle cancellation arriving before submission.

All routes retain Gateway's `chat` grant and transport, Host, Origin and browser
CSRF checks. They grant no tools or engineering authority. Cancellation is
principal-scoped and does not reveal another principal's requests.

## Evidence and troubleshooting

`ready: true` requires valid warmup audio/timing and a positive CUDA node count
from that session's actual ORT profile. The profile is deleted after qualification.
`evidence_scope: warmed_session` means this is startup execution evidence, not a
per-request benchmark. The same warmed session serves later requests with ORT's
automatic whole-session CPU fallback disabled and CUDA presence rechecked on
each generation. Individual graph operators may still run CPU kernels.

| Reason | Action |
| --- | --- |
| `VOICE_DISABLED` | Complete optional setup or select local CPU speech. |
| `VOICE_CUDA_UNAVAILABLE` | Check the interpreter, driver, CUDA and cuDNN versions. |
| `VOICE_CUDA_NOT_EXECUTED` | Select a compatible floating-point export; no CUDA nodes executed. |
| `VOICE_PACK_INVALID` / `VOICE_TIMING_INVALID` | Check model metadata and waveform/duration outputs. |
| `VOICE_FRONTEND_MISMATCH` | Use the exact Android-compatible token-symbol mapping; regenerate the manifest. |
| `VOICE_PACK_HASH_MISMATCH` / `VOICE_MODEL_CHANGED` | Requalify the selected model and refresh capabilities. |
| `VOICE_QUEUE_FULL` | Allow current requests to drain. |
| `VOICE_TIMEOUT` / `VOICE_WORKER_UNAVAILABLE` | Check GPU availability and restart Gateway. Stalled children are terminated. |

No hardware speedup is claimed without measurements from the selected PC and
network. Compare Android's first-audio latency and warm/cold percentiles against
local CPU speech; network transfer and warmup can outweigh faster synthesis for
short phrases.

The lock uses exact publisher release hashes, including
[ONNX Runtime GPU 1.32.0](https://pypi.org/pypi/onnxruntime-gpu/1.32.0/json).
Profiling and cancellation use the documented
[ONNX Runtime Python APIs](https://onnxruntime.ai/docs/api/python/api_summary.html).
