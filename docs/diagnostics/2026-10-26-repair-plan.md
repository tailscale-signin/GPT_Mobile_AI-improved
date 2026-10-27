# Diagnostic repair plan — 2026-10-26

Target branch: `fix/local-tools-answers-gguf-gpu-2026-10-26`

This is an implementation tracking document, not a claim that fixes are complete. Validate against the uploaded 0.9.61.0 build 129 diagnostic export and current branch before closing tasks.

## P0 — Native GGUF GPU
- [ ] Reproduce Vulkan `mul_mat_vec_q4_k_f32_f32` `createComputePipeline ErrorUnknown` on Snapdragon 8 Elite.
- [ ] Record actual GPU offload count, Vulkan capabilities, driver version, shader variant and quantization.
- [ ] Gate unsupported shaders using device/backend capability tests, with explicit CPU fallback and visible reason.
- [ ] Verify process exit before client reconnect and prevent reuse of stale native state; maintain generation IDs.\n- [x] Check active native operation ownership as well as coroutine completion after cancellation timeout (not yet device-tested).\n- [x] Make service-side termination requests idempotent using an atomic guard, reject new commands during termination, and recheck after acquiring the operation lease (not yet tested).
- [ ] Test prefill, token generation, cancellation during shader failure, restart and repeated model loads.

## P0 — Android sharing and import
- [ ] Trace failing FileProvider authority and exported URI; verify configured paths cover the actual shared file.
- [ ] Add intent read grants and ClipData when needed; verify receiving application can open the URI.
- [ ] Use ContentResolver streams for incoming content URIs; copy into app-private storage with size limits and cancellation.
- [ ] Add cross-process share/import instrumentation tests. Avoid broad FileProvider root-path exposure.

## P1 — Research Examine
- [ ] Integrate optional usage normalization into the live Chat Completions response adapter; do not reject otherwise valid content for missing terminal usage metadata.
- [x] Add isolated optional-usage normalization helper and eight regression test cases (tests not yet executed).
- [ ] Separate transport, provider payload, output schema, grounding and persistence errors.
- [ ] Preserve grounded evidence and citations; do not invent sources when validation fails.
- [ ] Checkpoint source extraction and appraisal stages independently; retry only failed stage.
- [ ] Adapt output budget and chunking to measured provider context limits.
- [ ] Contract-test Pollinations openai-fast omitted usage and valid completion.

## P1 — Providers, crawler and tools
- [ ] Classify HTTP 502 as transient; use bounded backoff with jitter and respect rate limits.
- [ ] Contract-test BridgeNode, Cehpoint, BlockRun, reasoning controls and malformed provider payloads.
- [ ] Scope health and cooldown by provider/model/operation, not a global flag.
- [ ] Classify crawler TLS/redirect/protocol errors and continue research with other sources.
- [ ] End-to-end test tool discovery, model selection, JSON arguments, execution and reinjection for local/free models.

## P1 — Combined and UI
- [ ] Preserve contributor IDs, accepted unique claims and attribution through checkpoint and final publication.
- [ ] Verify one atomic final answer, coverage of contributors, output allowance and no redundant writer retries.
- [ ] Profile Compose streaming recomposition, text layout and glass blur during concurrent generation.
- [ ] Benchmark frame stalls against 60/120/165 Hz targets and record p95/p99 frame times.

## P2 — Lifecycle and observability
- [ ] Address InputConnection/IME lifecycle warnings where reproducible.
- [ ] Replace protected system-property reads with supported capability detection.
- [ ] Capture native operation IDs, process exits, crash/ANR history and backend fallback reasons.

## Merge gates
- [ ] Unit, integration, lint and Android builds green.
- [ ] Emulator sharing/import and provider contracts green.
- [ ] Physical Snapdragon GPU reproduction and recovery tests green.
- [ ] No regressions to existing branch GitHub tools, Research, Combined or local runtime.
