# Research study cards and GGUF recovery

This branch implements the two October 18 design documents on top of main d1da61ae. Research discovery, evidence appraisal, conversation citations and native inference continue through the existing application pipelines.

## Research changes

- Keep a durable collection namespace in message evidence bindings, independent of generated citations and their 120-occurrence parsing bound. The namespace stays stable while text streams. Discoveries retain immutable source/version references and provider provenance.
- Render one collection per answer, with three compact themed cards and a virtualized full collection. Essential text stays opaque; the card surface uses an 85% theme tint. Show scoped conclusions, limitations, coverage, pending states, updated assessments, progress, separate component scores and paper access.
- Persist global and conversation crawler identities. Explicit examination overrides take precedence, followed by the conversation setting and global default. Missing or disabled profiles require selection; there is no implicit online replacement.
- Use the existing application-owned resumable examination coordinator for manual, automatic and explicit new revisions. Add URL-only retrieval through the existing guarded source reader, overlapping extraction chunks, outcome-level review packets and saved pending assessment artifacts.
- Append immutable conclusion/assessment artifacts after validating passage identities, source hashes, domain schemas and outcome scope. Calculate the five-domain components deterministically; the overall score is their minimum. Missing evidence, integrity concerns and contradictions prevent numeric ranking.
- Keep source capture, actual inspection and scoring eligibility separate. Only a complete structured article body with observed required sections and justified absence of figures/supplements can satisfy the text reader's full-scope gate. HTML without that evidence and articles with uninspected figures or supplements remain limited. A successful fetch alone never qualifies as a complete review.
- Commit assessments, archive selection and terminal job state in one Room transaction. Preserve prior completed reviews on failure/cancellation and reject late completion after a newer completed job. A persistence retry reuses checkpointed validated artifacts.
- Add many-to-many work/study-family relationships, migrate ResearchDatabase 2 to 3, and export/validate portable snapshot version 3 while accepting versions 1 and 2. Restored reviews are historical and require a new integrity check before ranking. Settings schema 1 remains compatible through optional fields.
- Make archive review status and score sorting reactive. Retractions suspend display and ranking without rewriting historical citations or evidence.

## GGUF changes

- Fence load and generation cancellation with operation identities prepared before dispatch. Native entry observes pre-entry cancellation. Delayed cancellation cannot stop a new operation; native resource ownership remains shared until return or verified process death.
- Emit redacted native stage/counter observations on the original JNI caller thread. Forward them over the existing versioned wire, correlated with request/session/generation/sequence. Log observations in the main sink under its existing logging/private-session policy. Repetitive native batches are sampled; text credits do not gate control packets.
- Record actual compiler/build identity, runtime revision, model digest, ABI, accelerator, context, prompt/decode threads and batch sizes. Exact tokenization and the final input-plus-output context guard remain native.
- Use the shared deadline controller in the production adapter. Retain a partial native receipt on timeout, distinguish unknown counters from measured zero, reject late text and preserve user CancellationException. First-visible, no-progress and bounded-operation policies remain separate.
- Arm the existing 10-second worker termination bound immediately when an internal deadline requests cancellation. Keep leases through cleanup; unload a faulted engine before reuse and carry engine health across IPC. Normal EOS/output limits preserve healthy warm engines and exact-token prefix reuse.
- Expose queue/inspection/loading phases to the progress watchdog and chat/notification UI. Cancelled waiters remain isolated by the existing fair holder queue.
- Build diagnostic native inference with optimization and debug symbols. Keep an explicit CMake unoptimized-stepping option and portable instruction baseline. Add final compile-command verification, Debug/Release native CI coverage and native build identity.
- Add advanced prompt-thread, batch and microbatch tuning behind an expandable control. Validate settings at the native boundary and include them in runtime qualification identity. Zero thread settings resolve consistently as Auto.
- Prepare FileProvider export URIs on the I/O dispatcher.

## Verification and release gates

Automated verification covers conservative score arithmetic, missing evidence, critical overrides, cancellation races, partial receipts, actual JNI CPU generation, Jinja/ChatML, prefill cancellation, stale cancellation, exact prefix reuse and configured batch sizes. Room migration, archive recovery, research contracts, runtime lifecycle and progress tests belong to the Android unit suite. Native compilation flags are checked against the compile database rather than inferred from the APK label.

Physical-device qualification remains required for the affected GGUF model. Retain its installed manifest/digest, APK/native build identity, prompt and effective settings; run CPU production-service inference, inject a stall, verify worker cleanup and a successful independent request, then compare at least five cold and twenty warm runs. Vulkan requires a separate compatible-device result; host CPU smoke is not Vulkan qualification. No speed or affected-phone success claim follows from host tests.

Expert comparison of the proposed scientific rubric also remains a release gate. Numeric scores describe a scoped app assessment, not scientific truth, GRADE certainty or an independently validated instrument. Native PDF/figure/supplement access continues to depend on existing reader/gateway capabilities; inaccessible material remains explicit.

The existing discovery engine budgets, continuation cursors, canonical cache, archive FIFO capacity and permission checks remain authoritative. The UI exposes archive saving and retention behavior. No complete-corpus discovery claim is made.
