# Android 0.9.66.14 — repository recovery and speech reliability

Version code: **148**. Coordinated publication with Gateway **18.1.2**, retaining the Gateway 18 contract.

This release follows `8abda66c9040d2c679cee8fe84452d67125a3fd7` and combines the repository workflow and speech playback repairs reviewed from the supplied diagnostics.

## Changes

### Repository evidence and recovery

- Use the current native task revision for repository map reads and reject a map result when that revision changes during the operation.
- Keep exact immutable comparison pairs fresh while preserving mutable branch, PR and policy freshness checks.
- Reserve selected baseline, decision, head and comparison coverage facts in bounded verification; do not ask again for an already answered baseline.
- Recover explicit schema-budget exclusions with bounded action-scoped discovery and only authorized compatible read facades.
- Schedule bounded changed-source reads from the observed comparison and follow issued continuations; assess comparison acceptance separately from source correctness.
- Preserve valid source ranges in a mixed batch and report directories and other nonregular paths as typed failures.
- Checkpoint phone source indexing after completed files, bound each operation and defer repeatedly slow files with explicit unread coverage.
- Bound the complete search operation, including parsing and cache inspection, and retain a revision-bound continuation when work remains.
- Measure semantic source progress so equivalent repository resolution cannot consume an investigation indefinitely.
- Report pending exact-SHA CI from observed running and queued jobs without requiring an unavailable-log probe.
- Generate a shared closed diagnostic vocabulary for phone interruption, read deadline, nonregular paths and local cancellation; negotiate compatible report service capabilities.
- Retry a known rejected claim-extraction batch at most once within its bounded wait and retain its own checkpoint when safe continuation is not currently available.
- Prepare settled Markdown outside composition and reuse bounded parsed documents with privacy-sensitive cache bypass and live layout/font/theme inputs.
- Use one frame report deadline after threshold or timer flushes and isolate frame, CPU, memory and GC evidence across recording epochs.
- Retain existing safe crash throw-site and GitHub validation evidence, including a real native commit-lookup projection regression, without assigning unproven historical root causes.
- Reject phone index cursors that advance beyond the retained frontier, and keep an earlier header read from suppressing the planned changed-source window.
- Preserve legacy `READ_DEADLINE` report compatibility while requiring service capabilities for newly introduced diagnostic fields.

### Speech playback and benchmarks

- Preserve typed native model-open and playback failures, including their stage, safe code, failed attempt and timeout budget. Local model setup failures remain distinct from invalid fixture text and accelerator failures.
- Expose safely polled native attempt progress and bounded phase deadlines. Retain per-chunk frontend, preparation, synthesis, profile-finalization, validation, cache and actual CPU/accelerator evidence.
- Measure first PCM, queueing, AudioTrack write, play request and presented audio separately. Distinguish startup reserves, refills and terminal drain; use verified recurring costs for startup while retaining complete generation costs.
- Add fresh, resident warm, streaming, changing-shape, sustained and stage-specific cancellation scenarios through the production playback path. Bind warm results to actual engine reuse and validate captions against an independent fixed fixture manifest.
- Offer controlled zero-offset and current-route calibration modes without overwriting saved route calibration. Honor explicitly selected one-through-four thread counts.
- Separate execution completion from slow startup, stalls, thermal limits and incomplete evidence. A playback recommendation requires the complete controlled full suite and verified requested-provider execution; small samples do not imply a p95 result.
- Bound foreground native cleanup while keeping audio and model ownership until the worker actually retires. Keep each warm reply's observer isolated and save progress serially outside the UI thread.
- Export mode-correct schema 3 records with explicit unmeasured capabilities. Preserve historical numeric receipts without restoring stale qualification, and retain complete current evidence in bounded 4 MiB history storage by evicting older whole runs.

## Qualification

Publication requires the exact merged commit to pass every required workflow in `release-scope.json`, signed Android validation, package integrity checks, and release readback. Test outcomes are recorded in the PR and workflow artifacts for their tested revisions.

## Limitations

- Unified Reliability v2 is not complete; this scoped release does not mark the broader milestone or unexecuted scenarios as passed.
- Physical Android performance, selected-provider behavior and the user's Windows/Gateway network require live device/service qualification. Automated fixtures do not establish those conditions.
- External provider rate limits, account credit balances and unavailable servers require their service or configuration conditions to recover; client changes improve classification, safe continuation and diagnostic evidence.
- The recorded QNN graph qualification used the official x86 HTP emulator and observed 39 QNN kernels per tested token shape with remaining graph work on CPU. It did not run on physical Adreno GPU or Hexagon NPU hardware and does not establish on-device acceleration, speedup, thermal behavior or battery use.
- Kokoro GPU/NPU execution is partial and device/model/runtime dependent. A registered provider or selected hardware label is not proof of accelerator execution; failed qualification or stale measurements retain CPU until a successful device recheck.
- Physical audio-route latency, caption alignment, interruption recovery, long-reply continuity and frame/memory behavior still require speaker, wired and Bluetooth replay on the target phone. Bounded buffering cannot guarantee uninterrupted speech when synthesis remains slower than playback.
- Gateway CUDA remains optional and requires a reachable, compatible configured Gateway. Local fixtures do not qualify the selected desktop GPU, account, network or live service.
- The host QNN probes verify finite nonzero PCM and timing from the same inference, not waveform or perceptual equivalence. The 83-token probe differs from its CPU reference by 50 ms of total predicted duration; the recorded host emulator uses ORT 1.28.0 while native/JNI validation uses the pinned application ORT 1.28.2.
- Phone repository relationships are bounded lexical candidates, not compiler-resolved references or executed-test evidence. Unscanned, unsupported, generated and dynamic code can leave relationships unknown; current GitHub authorization and connectivity are required, and physical-phone performance remains unqualified.
- Optional coding agents require installed pinned runtimes, explicit host configuration and compatible provider accounts. Protocol fixtures and host builds do not qualify paid provider execution, installed SDK compatibility, model quality or remote cancellation when the provider does not support it.
- Older provider-wait checkpoints without protected replay-content and result-semantics evidence require recovery review; completed work is retained and writes are not automatically repeated to manufacture missing proof.
- Engineering comparisons apply only to their frozen suite, evidence lane, task families, budgets and selected profile settings. Incomplete, incomparable or statistically inconclusive results do not establish a universally superior model.
- The Python coding pack uses a bounded Python interpreter. Repository-native fixtures execute Kotlin/JVM, SQLite and declared workflow semantics; they do not claim Android Compose instrumentation, Room processor coverage or arbitrary production-repository correctness.
- Live GitHub benchmarking requires an explicitly authorized disposable repository, immutable fixture base, appropriate account permissions and the declared runtime. Direct phone execution needs a provisioned Actions fixture bank; repository-native Actions additionally needs the prepared self-hosted runner and pinned image.
- Generation identity is an authenticated client observation, not independent proof of the provider's internal model identity. Missing or ambiguous token usage, price, retry or settings evidence remains unknown; external CI and service charges are not inferred.
- Silent inference and audible playback are separate speech measurements. First PCM records a completed native chunk; presented audio uses the production AudioTrack timestamp or playback head. Caption checks measure observed word coverage and clock consistency. Acoustic speaker latency, perceptual word alignment, battery energy and hardware utilization percentages remain unmeasured. GPU/NPU results require compatible real hardware and actual accelerator execution; unsupported or failed trials retain their observed status.
- The separate native cancellation/failure/reuse regression uses pinned application ORT 1.28.2 with the official QNN 2.7 host emulator, explicit host device/STL ports and a deterministic fault after real kernel execution. It establishes session/profile lifecycle behavior; physical GPU/HTP performance and Android packaging remain separate qualifications.
- Prepared Android compilation requires at least 8,960 MiB of observed RAM/cgroup headroom for its fixed 6 GiB heap. The existing 8 GiB container default is refused; an operator must explicitly approve sufficient resources. The preflight does not reserve future memory.
- No device speedup is claimed for the Markdown or index changes. The added UI fixture and deterministic runtime regressions do not replace measurement on the target phone.
- The historical HTTP 422 commit lookup and NoSuchElementException reports did not retain enough evidence to prove their original root causes. This release preserves their bounded diagnostic capture and regression coverage; it does not declare those incidents reproduced or fixed.
