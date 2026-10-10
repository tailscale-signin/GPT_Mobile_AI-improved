# Local runtime / Qwen3 implementation and log disposition

Branch: `feat/local-runtime-qwen3-log-fixes-2026-10-10`.
Baseline: `8f5a6fdd7`. Input: the supplied diagnostic log and
`Local_AI_Runtime_Qwen3_Design_2026-10-10.md`.
The raw diagnostic log is intentionally not committed.

This branch implements the LiteRT/Qwen foundations and an opt-in GenieX preview.
It does **not** certify a device, claim legacy Genie support, or complete every
later phase of the design. Release gates and remaining work are explicit below.

## Diagnostic findings

| Finding | Change or disposition |
| --- | --- |
| OpenRouter upstream rejects `max_tokens=256000`, supplies a 131072 completion ceiling inside nested SSE JSON | Decode the nested `data:` error and narrowly accept its token-limit parameter. The existing endpoint/model/routing-scoped ceiling learner can now consume it. Arbitrary echoed request fields remain excluded. Regression covers the supplied error shape. |
| Benchmark replies exhaust 512 tokens while reasoning is enabled | The benchmark copy and request constraints both disable reasoning. Suite version is now 3; config fingerprints and all comparison selectors prevent mixing old scores. Saved user profiles remain unchanged. Models requiring reasoning need another cohort. |
| Repeated missing URL reads from parallel profiles | Serialize identical URL requests with bounded lock stripes; cache exact-URL 404/405/410 failures for five minutes, capped at 256 entries. Return an actionable alternative-source error. |
| Invalid marketplace/nearby enum arguments | Validation now includes the allowed enum values, bounded in number and length. No guessed entity IDs or silent substitutions. |
| MCP invalid refresh grant repeats after restart | Persist reauthorization-required state with the encrypted OAuth credential. Reconnect replaces the credential; the app does not keep replaying a revoked grant. Existing per-session auth backoff remains. |
| Memory enrichment exhausts retries while local runtime is busy | Runtime contention and absence of a loaded model remain waiting prerequisites. A separate persistent counter bounds actual inference failures; prerequisite waits do not consume it. |
| Log exports hold the diagnostic writer lock for seconds | Snapshot bounded log bytes and batch queued lines under the lock; transform JSONL and write exports outside it. Unique export filenames prevent simultaneous exports overwriting one another. |
| Remote gateway connection refused / llama unexpected end-of-stream | Existing bounded transport recovery remains. Phone code cannot start the remote service or restore an interrupted upstream socket. Partial/error semantics must remain visible. |
| Output-limit continuation / repeated continuation without progress | Existing bounded text-only continuation, no-progress detection and tool non-replay protections remain. This branch improves the missing completion-ceiling diagnostic and local output admission rather than increasing retry limits. |
| Jina/Firecrawl authorization failures | Existing connection health/backoff remains; valid credentials or reauthorization are required externally. No authentication bypass. |
| Tool-call budget reached | The user-configured limit remains enforced. More calls are not automatically authorized. |
| Location permission missing | A user-granted Android permission is required; failure remains explicit. |
| Missing source, Amazon network failure or unavailable price | No fabricated result. Existing structured partial/failure responses remain. Caching prevents repeated missing-source attempts. |
| ASUS vendor finalizer `SecurityException` for a restricted setting | OEM/framework issue in the captured stack, not permission the app can legitimately grant itself. No bypass or global exception swallowing. |
| IME/inactive input, HWUI/dequeueBuffer, SLF4J provider and JobInfo warnings | No corresponding application crash was demonstrated by the supplied log. Native/UI device profiling is still required before attributing a defect to these warnings. |
| Large `AgentRunner.executeLoop` compiler warning / UI jank | The export lock hotspot is fixed. Splitting the large agent state machine and device frame profiling remain follow-up work; this branch does not claim these performance warnings are eliminated. |

## Runtime changes

- Pin LiteRT-LM Android to **0.18.0**, with AAR/POM verification checksums and
  refreshed packaged-native hashes. Read the actual 0.18 `ModelInfo` API before
  initialization: context ceiling, backend, modality, thinking, function calling,
  speculative support, minimum runtime and SoC. Validate file integrity first.
- Keep context capacity distinct from completion length. Admission reserves prompt
  and tool space and bounds the default local reply to 512 tokens or a quarter of
  effective context. The user sees the effective budget. Existing history/tool
  compaction and native context enforcement remain; exact tokenizer occupancy is
  not exposed by this change.
- Fail unsupported tools, vision and speculative settings explicitly. New profiles
  use catalog sampling/output defaults and disable tools when artifact inspection
  reports chat-only support. Existing profiles are not rewritten.
- Bound callback queues to 64 events and split text into 4096-character deltas.
  Overflow terminates with an error rather than silently losing text or growing
  memory without bounds. Accepted deltas drain; generation is cancelled when needed.
- Expose estimated/native metric provenance, first visible answer latency, decode
  duration and segment identity. Native LiteRT counters still describe the last
  segment; they must not be presented as cumulative multi-tool totals.
- Persist native interruption evidence by artifact/runtime/firmware/backend.
  Native aborts leave a durable journal entry. Normal completion, ordinary errors
  and cancellation clear the active marker. Switching models or updating only the
  APK does not erase a quarantined tuple. An interruption is not proof of SIGSEGV:
  an OS process kill can also leave evidence.

## Qwen3 catalog

All three entries are in the same `qwen3-0.6b` family. Discover groups variants;
users can expand the family to compare them. Precision and candidate status are
visible. No on-device verification badge is inferred from a catalog entry.

| Artifact | Bytes | Context ceiling | SHA-256 |
| --- | ---: | ---: | --- |
| Qwen3-0.6B.litertlm (INT8) | 614236160 | 4096 | `555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4` |
| Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm | 344671744 | 4096 | `03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856` |
| qwen3_0_6b_mixed_int4.litertlm | 497516544 | 2048 | `7900eb4e7362d88c58782c6f9999bb7a129e03544aa98b8f338ea0cc5d8c22c1` |

Source revision: `litert-community/Qwen3-0.6B` at
`a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76`.
Quick defaults: temperature 0.7, top-p 0.8, top-k 20, output 512.
Unspecified Qwen Reasoning settings use 0.6/0.95/20. Explicit profile settings win.
The MediaTek export is not relabeled as Qualcomm-compatible.

## GenieX preview

Build with `./gradlew -PgeniexRuntime=true :app:assembleDebug`.
The regular build remains the default. The preview has application ID
`dev.melo.gptmobile.improved.geniex`, supports arm64 only, and can coexist with it.

The preview uses the **real pinned GenieX 0.8.0 SDK** for `qairt` and `llama_cpp`.
Its SDK supplies the QAIRT native payload. The regular `qnn-runtime` dependency and
LiteRT Qualcomm dispatch library are excluded from that APK; no duplicate-library
`pickFirst` resolves ABI conflicts. LiteRT CPU/GPU remain available in the preview.
The runtime label follows the loaded artifact, not a renamed LiteRT backend.

The adapter uses the pinned internal JNI callback API because the SDK convenience
Flow ignores failed `trySend` calls. This is an explicitly experimental API
dependency: Kotlin warns that invisible API access is not stable. An upstream
public lossless callback interface is required before general release.

Import a `.localmodel` ZIP whose first member is `manifest.json`, followed by
exactly the manifest's files. The manifest model is `ModelArtifactManifest`:
runtime identity, entry file, context, supported backends, exact SoC for compiled
NPU bundles, tokenizer, precision, source and per-file sizes/SHA-256. Examples:

- GGUF: runtime `GENIEX_LLAMA_CPP`, format `gguf`, entry `weights.gguf`, runtime
  version `0.8.0`, backend `cpu` or `gpu`; embedded tokenizer/template must be valid.
- QAIRT: runtime `GENIEX_QAIRT`, format `qairt_context`, entry
  `genie_config.json`, runtime version `0.8.0`, backend only `npu`, exact SoC and
  declared tokenizer. Config context and tokenizer must match the manifest;
  `dialog.engine.model.binary.ctx-bins` must reference declared assets.

Imports reject traversal, absolute paths, executable/library files, duplicate or
undeclared entries, excessive expansion, missing assets and hash mismatches.
QAIRT configuration references are checked recursively, including extension JSON;
the preview only accepts data-only context-binary bundles. Unsupported advanced
layouts must be repackaged/qualified rather than bypassing preflight. Imports
stage, verify and atomically publish a content-addressed directory. Imported
validation badges are discarded. Existing installation directories are not
overwritten while in use.

QAIRT rejects CPU/GPU requests and uses the required `nCtx=0`, `nGpuLayers=0` JNI
configuration. Chat templates are applied once. The preview supports text Quick
Chat with a bounded 1–4096 output budget; tools, constrained JSON, vision, thinking
and speculative decoding are unqualified and disabled. It rejects temperature
zero because this binding interprets it as a default, not greedy sampling. Prompt
admission uses a deliberately conservative UTF-8 byte bound until exact native
tokenizer occupancy is available. Stop reasons distinguish normal completion,
length/context exhaustion, cancellation and unknown/incomplete outcomes.

## Validation and remaining gates

Completed in the available environment:

- Isolated Kotlin 2.4.20 compilation of runtime, routing, holder, journal,
  manifest/import code against the actual LiteRT 0.18 and GenieX 0.8 AAR APIs.
  Android/framework collaborators were stubbed; this is not a full Android build.
- **51 JVM tests**: 20 runtime/package/catalog/error tests and 31 benchmark tests.
- **51 existing Python tests**; resource XML, Android regex and Room schema checks.
- Changed Kotlin files pass ktlint 1.3.1; JSON and dependency-verification XML parse.
- Downloaded SDK host ELF libraries pass the 16 KB LOAD-segment alignment check.
  This does not establish the final APK's packaging or device compatibility.

Full Android Gradle verification was attempted but could not download Gradle 9.8
from this environment. No APK, emulator inference or physical-device result is
claimed. The PR workflow validates the regular build; `geniex-preview.yml` adds
separate preview compilation, unit tests and packaged-native/telemetry audits.

Before release, both builds need full CI, R8/JNI and packaged APK verification.
The target phone must pass cold/warm load, cancel/close, multi-turn context limits,
tool/no-tool behavior, memory pressure, foreground/background and sustained thermal
tests for each artifact/backend. Record firmware, runtime, artifact digest, suite
version and native provenance. Until then all new variants remain candidates.

Still open from the phased design:

1. Private native service processes and Binder death recovery. The separate preview
   APK prevents ABI mixing, but native faults can still terminate its app process.
2. Legacy Genie integration against validated exact SDK headers/libraries. It is
   deliberately rejected rather than simulated through LiteRT or GenieX.
3. Full device qualification matrix, signed catalog distribution, measured ranking
   and persisted verified badges backed by actual device records.
4. Tokenizer-exact context admission and comprehensive multi-segment timing/cache
   telemetry, plus a separate qualified Reasoning benchmark cohort.
5. GenieX Local Assist/native tool qualification and the remaining agent/UI
   performance work identified above.

Primary implementation references:

- https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.18.0
- https://github.com/qualcomm/GenieX/tree/v0.8.0/bindings/android
- https://github.com/qualcomm/GenieX/tree/v0.8.0/sdk/plugins/qairt
- https://huggingface.co/litert-community/Qwen3-0.6B/tree/a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76
