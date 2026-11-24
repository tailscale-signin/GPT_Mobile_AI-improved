# Voice runtime rebuild, export recovery and sustained qualification

This change addresses the reported voice export crash and the CPU/GPU/NPU
benchmark findings. The supplied results remain a prior-build baseline: invalid
GPU output cannot qualify, and mixed accelerator/CPU execution alone does not
establish a useful end-to-end speed improvement.

## Runtime and model work

The packaged QNN execution-provider plugin advances to 2.8.0 and its shared QAIRT
runtime to 2.52.0. ONNX Runtime stays at the validated 1.28.2 version. The LiteRT
Qualcomm dispatch library was rebuilt against the matching QAIRT headers in the
hosted candidate workflow, then checked against its immutable build receipt.
The binary is 642,088 bytes with SHA-256
`8a8b31aa09b5f0998955014ee4c8e2811f993461cfc797d95b30e3999b417e06`.
Publisher checksums, dependency locking, native inventory, dispatch provenance
and APK verification remain part of the build contract. The new inventory also
accounts for the additional HTP architecture files in the published QAIRT AAR.

Original FP32 and compact INT8 Kokoro weights have separate verified installations,
saved choices, native factories, CPU references and cache identities. Changing
the model retires the previous owner before publishing a replacement. Voice
drafts survive the change. The default compact model remains available.

The model workbench provides reproducible exact-shape specialization, QNN
quantization candidates using supplied representative calibration, explicit
subgraph extraction, and seeded intermediate-output comparison. Every generated
artifact binds the exact input hashes. Metadata is preserved, inputs are not
silently padded or truncated, and diagnostics require observed provider execution.
The native GPU precision probe uses the SDK's graph configuration API. Generated
candidates do not replace production weights or grant audio admission.

See [model workbench](../audits/kokoro-model-workbench.md) and the
[runtime promotion receipt](../audits/qnn-runtime-promotion-2.52.json).

## Native preparation and persistent contexts

HTP contexts are retained in an application-owned private cache, with exact
model, runtime, provider-binary, device, token-shape and option identity. Cache
files have digests, bounded size and eviction, atomic publication, canonical-path
validation and nonblocking process ownership. A context is published only after
valid native PCM/timing and provider-profile observations. Loading a cached graph
does not bypass CPU-reference playback admission.

The Android cache directory is opened without following links, checked against
the application UID and directory type, restricted to mode 0700 and checked again
for stable device/inode/path identity. A failed check disables disk persistence.
Slow context hashing, publication and owner retirement do not hold the short
cancellation lock. Completion rechecks cancellation under that lock before
publishing audio and Ready state, so cancellation accepted during finalization
cannot be reset or reported as a completed reply.

Invalid, stale or unloadable contexts receive one bounded source-model retry.
GPU context persistence stays disabled where the backend cannot establish
support. Engine opening, graph compilation, disk-context loading, resident graph
reuse, inference and cache fallback have distinct evidence fields. Java evidence
ABI v6 retains readers for older versions; absent observations stay unknown.

The paired QNN 2.8/QAIRT 2.52 host regression creates a small mixed QNN/CPU graph,
saves an embedded context and reloads it. It checks metadata, input/output order,
CPU nodes and initializers, real provider events and numerical equality. This
validates the narrow context mechanism. It does not qualify the full Kokoro
graph, a physical phone, listening quality or a performance gain.

A separate required host job builds the current native/JNI code with the exact
production model/frontend files, ONNX Runtime 1.28.2 and QNN 2.8/QAIRT 2.52 inputs.
It exercises full-model context store, load and corruption recovery. The model
contains unseeded random vocoder nodes, so the controls compare token and duration
identity plus finite, unclipped, non-silent signal statistics; they do not require
bitwise waveform equality or claim perceptual equivalence. Preparing the inputs
or passing the lightweight signal controls does not qualify that full-model job.

The first full-model run exposed duplicate initializers in retained CPU control-flow
subgraphs during context serialization. The repair prepares a CPU BASIC snapshot
with the same runtime, then uses the supported Compile API callback to embed
initializers with explicit full QNN optimization. The first inference loads the
serialized context itself before publication. The private source snapshot has a
512 MiB post-write bound and requires 512 MiB of additional free-space admission;
it is removed before context inference and on every exit. This is not an in-flight
size cap, and retained-cache limits remain unchanged. Cancellation at the source-
preparation boundary prevents compilation or an uncached fallback; later checks
prevent further work or publication.

The clean source-bound run now passes all eight native test targets and all four
actual JNI lifecycle/context cases. It verifies initial store, resident reuse,
fresh disk loading, corruption recovery, and cancellation without orphan profiles
or source snapshots. All tested inference paths retain 39 QNN and 1,477 CPU
kernels. The original-model comparison passes the existing signal-consistency
protocol; the unseeded vocoder still does not establish waveform or listening
equivalence. The [native serialization receipt](evidence/voice-runtime-recovery/native-context-serialization.json)
records the exact source, runtime, model, tests and independent reviews. This is
host-emulator qualification; current Android packaging and final-head workflows
remain separate gates.

## Incremental speech and resident ownership

The SDK path uses its real incremental-input API: one reply stream accepts
successive text, closes input once, and drains PCM with bounded backpressure.
Source/caption mapping, finite cue timelines, cancellation and PCM ownership
remain explicit. Separate phrase streams remain selectable for comparison.

The Linux and Android smoke paths require actual PCM while text input is still
open, before the second fragment and before input completion, for Kokoro, Phonon
and Pocket. They also require open-input cancellation, actual owner retirement
and same-task recovery. The Android v2 receipt rejects missing incremental
evidence, stale source/model/runtime identity and incomplete cue coverage. The
existing sealed-input controls and process deadlines remain in force.

Kokoro Auto/64/128/256 token-bucket choices flow from preferences and benchmarks
to the SDK factory. Bucket selection never truncates a reply. Model, delegate,
bucket and input mode affect resident identity. Supported voice changes reuse
model weights while each utterance captures its actual selected voice; benchmark
reports and factories use the same voice ID.

Failed native close or preparation retains the factory and model lease and blocks
replacement until retirement is established. UI cancellation, timeout or a
returned callback cannot release the asset beneath a live native owner.

CPU Auto scheduling follows the existing shared device governor between replies.
Explicit thread choices and benchmark settings stay fixed. Android performance
hints cover only the application-owned synthesis worker, report actual work
duration, and fail safely if the service is unavailable. They are scheduling
requests, not measured speed claims.

## Export recovery

Speech and performance exports no longer place full JSON in saved UI state or
encode the report on the main thread. They stream bounded records into an atomic
private artifact before opening the document picker. Saved state contains only
a small receipt ID. A recreated process can recover a matching private artifact.

Picker failure, provider write failure or provider-close failure leaves a
retryable artifact. The source is removed only after the destination closes
successfully. Retry copies the existing validated artifact without rebuilding
the report. Export JSON schema 6 carries model/bucket/input/HTP identities,
preparation/cache observations and sustained receipts; older schemas remain
readable with their original qualification limits.

Local benchmark history uses streamed writes under an 8 MiB cap, retaining whole
reports and evicting older runs rather than truncating current records.

## Sustained playback

Playback protocol v4 exposes 1, 5 and 10 minute targets. The same resident engine
repeats a fixed utterance until enough completed, validated audio has reached
playback. Waiting, queued-but-unpresented audio and partial failures do not count.
Each series is bounded by 16 replies, 240 seconds per reply and a total wall limit
of twice the target plus 120 seconds. The existing thermal stop threshold blocks
new sustained replies and retains the interrupted observations.

Reports retain target/observed audio, wall time/limit, completed reply count and
qualification status. Qualification recomputes cumulative evidence from individual
receipts, checks exact fixture and resident-owner continuity, and rejects forged
totals or historical incomplete receipts. Sustained elapsed totals are excluded
from speed ranking because different candidates can perform different repeat
counts; complete sustained qualification is still required for a full ranking.

## Verification and publication contract

Focused regressions cover native cache integrity and ownership, evidence ABI,
SDK API/input mapping and retirement, export recovery, bounded streaming,
model-variant identity and sustained duration. Test manifests identify the source
snapshot and distinguish host checks from Android or physical execution.

The integration also preserves admitted progress publications through the DTO,
collector and display using an opaque publication identity distinct from the
model invocation. Replayed identities cannot allocate another timeline sequence;
distinct admitted events can retain identical text from the same invocation.
The controller owns admission and identity creation. Existing legacy events keep
their fallback comparison and no database migration is needed.

Canonical PR verification, Android/Compose and native builds, model smoke,
minified memory checks, actual-main workflows and signed-release checks remain
required. Concurrent branch updates are merged before an expected-head guarded
append. Successful older runs do not qualify a newer commit.

[The task checkpoint](voice-accelerator-rebuild-2026-11-24-task.json) records
source preparation and the revisions actually checked. The current PR, main
workflow and published-release receipts determine later delivery status. Neither
this document nor a green emulator test claims that the prior GPU mismatch is
fixed for arbitrary speech on the target phone. New on-device reports and
listening checks remain the evidence for that conclusion.
