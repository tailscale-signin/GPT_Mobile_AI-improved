# Speech playback reliability and benchmark protocol

## Scope

This repair follows the build 147 playback capture, whose source tree is the same
as main commit `8abda66c9040d2c679cee8fe84452d67125a3fd7`. It is integrated on
PR #2363 with the concurrent repository evidence and recovery repair. The
machine-readable acceptance record is
`speech-playback-reliability-2026-11-23-task.json` in this directory.

The capture showed a failed GPU attempt without its native stage, a CPU run with
13,750 ms to first presented audio, and an HTP run with 25,797 ms to first
presented audio and two buffer restarts. These are observations of that capture,
not performance results for this repair. The HTP generation wall time included
lazy graph preparation; it was not a measurement of pure inference throughput.

## Native ownership and evidence

The native runtime exposes a separate atomic progress snapshot for watchdogs and
cancellation probes. Its attempt identifier, phase sequence and elapsed times
belong to the active generation. Completed attempts freeze their terminal time.
Readers do not inspect mutable model/cache evidence during native work, and the
Java lifetime read lock prevents a concurrent close from freeing the handle.
Cancellation and progress polling do not wait behind a queued close writer.

Opening a model retains a typed configuration, frontend-load or model-open code,
its safe stage and native error number. Phrase validation remains distinct from
local setup failures and accelerator failures. A new generation clears prior
success evidence before checking cancellation, so a rejected attempt cannot
borrow the preceding audio/profile receipt.

Each completed chunk can retain frontend, graph preparation, synthesis,
profile-finalization and validation timing; shape-cache hits, occupancy and
evictions; profile identity/reuse; and actual CPU/accelerator kernel evidence.
The runtime identity changes with this native behavior, invalidating old device
qualification records. Existing bounded graph-cache limits remain in force.
No input padding, artificial accelerator preference or unmeasured cache increase
is introduced.

## Playback boundaries and deadlines

Playback records separate first-PCM, software-queue, first AudioTrack write,
play request and first presented-frame events. Reserve observations distinguish
startup from refill and retain the target, accepted audio, elapsed wait and exit
reason. Underrun deltas identify startup, active playback, refill, pause,
cancellation and terminal drain where observed; an end-of-input drain is not
automatically a producer stall.

A live native graph preparation has a 90-second phase budget, synthesis has a
30-second budget, and the complete native attempt has a 120-second ceiling.
Frontend and output validation phases are also bounded. The separate model-open
and pipeline-idle paths retain their bounded factory policy when a live native
attempt is unavailable. A timeout records its stage, scope, limit and elapsed
time without inventing a native error or completed inference.

Startup reserves may use verified recurring work that excludes the current
call's one-time graph construction and profile finalization. Missing or
inconsistent evidence keeps the conservative complete wall cost. Refill policy
and recorded generation cost retain the complete cost; this does not promise
uninterrupted playback when production remains slower than consumption.

## Reproducible playback protocol

Protocol 3 supports six executable scenarios:

| Scenario | Execution |
| --- | --- |
| Fresh session | A newly owned engine for each sample; the operating-system file cache is not cleared. |
| Resident warm session | A warmup reply followed by repeated replies through the same output and native owner. |
| Streaming arrival | Fixture sentences arrive at a recorded interval through the production input queue. |
| Changing phrase shapes | Different fixed fixture lengths run through the same owner; each chunk reports actual cache behavior. |
| Sustained playback | Repeated fixture text exercises a longer continuous reply. |
| Cancellation | Separate fresh sessions target preparation, observed native inference, and presented playback. |

QUICK and FULL use explicit bounded sample counts. The FULL six-scenario suite
contains 24 samples per backend, including warmup and cancellation probes. The
export retains each sample and chunk rather than implying a percentile from a
small sample. Percentiles require at least 20 relevant observations.

Caption expectations are built from the frozen fixture segments independently
of production caption generation. Prepared and observed ranges must cover that
manifest, with no unexpected ranges or clock violations. This detects an omitted
word even when the producer and caption observer omit the same word.

Controlled calibration fixes the caption offset at zero without reading or
writing the user's saved route overrides. Current-settings mode records the
effective route offsets and epochs. Explicit thread counts from one through four
are honored; automatic CPU calibration retains its own two-through-four policy.

Execution completion, verified requested-provider execution and playback quality
are separate results. Completed playback may still be slow, stalled,
thermally limited or incomplete for comparison. Only a complete, controlled FULL
suite with all required evidence can recommend a playback backend. A successful
single fresh-session smoke test cannot establish a comparative recommendation.

## Bounded cleanup

Foreground cleanup waits at most five seconds for the output's native and audio
executors. If they have not retired, the result records `PENDING` and later
backend samples stop. An independent retirement observer continues to own the
pending worker. The benchmark audio lease and the external model-use lease stay
held until actual retirement; cancelling the screen or an observer cannot
release them early. The pending state is observable and does not qualify a
cancellation probe or a playback comparison.

## Export and validation limits

Schema 3 puts playback lifecycle states and playback samples in their own
fields. Playback-only runs do not export successful zero-cost synthesis sample
rows. Historical reports remain readable but cannot regain current-protocol
qualification merely by being saved again. Unknown or unavailable measurements
remain explicit, and bounded history storage evicts older reports before
truncating current evidence.

First presented audio is based on AudioTrack timestamps or its playback head.
Neither that clock nor caption coverage measures acoustic speaker latency,
perceptual word alignment, battery energy or utilization percentages. Native CPU
and QNN host-emulator tests establish only the behavior exercised by those
tests. Physical Adreno/Hexagon performance, route behavior and Android packaging
remain separate qualifications. The acceptance record distinguishes focused
host tests, complete hosted Android checks and final signed publication.
