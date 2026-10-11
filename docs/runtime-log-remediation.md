# Runtime log remediation — 0.9.41.0 build 110

The user-provided analysis of 15,978 log rows reports 12 failed local requests (five Gemma 4 QNN preflight blocks and seven GPU loads ending in an unsupported CPU fallback), zero successful local generations, and the 30 findings below. This branch addresses the code and documentation implications against `feat/qnn-full-reliability-v2-2026-10-11`. The original log attachment was not re-captured during implementation; counts and historical observations are the supplied evidence, not new measurements.

## Priority repairs

`LocalRuntimeRouter` now intersects ordered fallback candidates with the installed artifact's declared backends. Missing capability metadata never authorizes a different export. A GPU-only file therefore cannot enter CPU fallback. `LocalLoadFailure` retains each backend, stage, exception and skipped candidate; its cause is the first actual load failure. The IPC worker and LiteRT adapter preserve that chain instead of replacing it with a generic final error.

Update (2026-10-11): at the user's request, the filename-wide Gemma 4 QNN block has been removed. Discovery, selection, profile creation and pre-send checks now admit these exports to the runtime. The informational record is `UNVERIFIED`, not `QUALIFIED`. SoC matching, package/library integrity checks and evidence-based native-crash quarantine still apply. See [QNN unblock investigation](qnn-unblock-investigation-2026-10-11.md) for the directly reviewed log and remaining validation limits.

The catalog now contains SHA-256 and upstream revision values for all 28 artifact entries, verified against publisher revision metadata; see [catalog audit](validation/catalog-hash-audit-2026-10-11.json). Existing download/integrity verification consumes these hashes. A checksum matching an imported package's self-declared manifest establishes internal consistency; only an independently trusted publisher checksum establishes publisher identity. Structural validity remains distinct. A changed mutable upstream URL fails checksum verification until the catalog is deliberately reviewed and updated.

## Findings mapped to implementation

Kotlin paths refer to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`.

| # | Finding | Repair / disposition and verification boundary |
| ---: | --- | --- |
| 1 | Gemma 4 QNN deliberately blocked | Filename-wide rejection removed on 2026-10-11 at the user's request; actual runtime prerequisites remain enforced. Device execution is still unverified. |
| 2 | GPU fallback attempts unsupported CPU | Capability intersection in `LocalRuntimeRouter`, explicit skipped-candidate records; GPU-only regression test. Different compiled exports remain separate downloads. |
| 3 | Original GPU exception discarded | Ordered `LocalLoadFailure`, first cause retained, IPC serialization and adapter error propagation. Multi-failure regression test. |
| 4 | Blocked package repeatedly runnable | Persistent blocked model-card and model-picker explanations, disabled blocked selections/profile creation, save guard and pre-send rejection. Existing profiles receive the same actionable preflight result. |
| 5 | Trusted hash missing | 28 publisher SHA/revision pins and audit; existing hash verification used. Unknown imports retain unverified provenance, never receive an invented trusted hash. |
| 6 | No actual QNN execution evidence | Terminal rejection diagnostic with `executionAttempted=false`; private-worker journal, loaded runtime/dispatch identities, stage checkpoints and native output metrics for admitted work. No device execution observed here. |
| 7 | Tool counts disagree between layers | Adapter-input/chat-only mode and per-tool registration/omission reasons; native plan separately reports selected and omitted tools. Counts describe different stages. |
| 8 | Prompt estimates disagree | Native planner labels stage, unit and UTF-8 estimator, retained history and system input. Tokenizer measurement remains false when unavailable; native prefill count is recorded after actual execution. |
| 9 | Desktop llama.cpp stale connection EOF | Existing bounded no-output retries retained; observed private-endpoint close enables temporary `Connection: close`, with response-body cleanup retained. Transport and outer benchmark retry time are recorded separately. Validate against the affected server. |
| 10 | MCP DNS/EOF/timeouts/broken pipes | Existing network-epoch invalidation, transport recreation, bounded backoff and non-replay rules retained; academic gateway adds explicit owner/fallback/cancel limits. Actual VPN/lifecycle recovery needs device verification. |
| 11 | Recoverable 400/404 discovery | Recovered probe result logged as discovery negotiation, not terminal connection failure; existing per-endpoint protocol cache retained. Final unrecovered errors retain details. |
| 12 | Optional GET stream 405 | Optional capability outcome in redacted SLF4J diagnostics; supported POST path remains usable. A failed required operation is not hidden. See MCP protocol references below. |
| 13 | LLM7 allowance exhausted | Cooldown exception includes reset instant/seconds; benchmark `QUOTA_BLOCKED` stops unchanged suite retries and is excluded from quality/speed ratings. No quota bypass. |
| 14 | OpenRouter privacy policy 404 | `POLICY_RESTRICTED` classification and corrective endpoint guidance; `POLICY_BLOCKED` benchmarks stop unchanged suite retries. Existing privacy preferences are preserved. |
| 15 | Groq GPT-OSS uses budget before final | Fixed 512-token speed window; correctness uses 8,192 for GPT-OSS, 2,048 otherwise. Groq uses supported low effort when reasoning is off, medium when on; requested effort/cap recorded. |
| 16 | Partial benchmark output | Provider finish reason retained; deliberate speed cutoff, output exhaustion and interrupted/partial correctness have distinct outcomes. Truncated correctness is not a success. |
| 17 | Estimated token rates/effective settings | Reported usage/native timing preferred; estimated counts remain labeled. Requested settings and server confirmation are separate; server settings stay unverified unless actually confirmed. |
| 18 | Oversized completeChat coroutine | Preparation/recovery, authorization, memory, delegation and context finalization split into suspend functions. Build verification covers source correctness; ART optimization and release performance still require profiling. |
| 19 | UI stalls/skipped frames | Streaming coalescing, bounded backpressure, worker-process inference and existing off-main repository work reduce hot-path work; chat focus teardown added. No measured frame-time improvement claimed. |
| 20 | Allocation/GC pauses | Warm engine/conversation reuse diagnostics, bounded chunk/evidence buffers and schema/catalog reuse; heavy academic I/O stays off the UI path. PSS and release allocation profiling remain device gates. |
| 21 | Duplicate diagnostics | Stable event IDs and capture source; exports preserve raw copies and mark duplicates to avoid counting the same record twice. |
| 22 | Native loader probe missing implementation | Classify with final successful loader outcome. A caught pre-load probe is not evidence of a packaging failure; native packaging validator retained. |
| 23 | FTS5 missing, FTS4 succeeds | Detect SQLite compile capability before attempting FTS5; preserve and cache successful FTS4 fallback. Search readiness is the terminal outcome. |
| 24 | ASUS finalizer SecurityException | Firmware/framework observation; no fake app permission fix. Device/firmware identity accompanies qualification and crash evidence. |
| 25 | SLF4J NOP | Android SLF4J 2 provider registered; opt-in WARN/ERROR goes through existing redaction; noisy lower levels stay off. |
| 26 | Restricted ro.hardware.chipname | Existing supported `Build.SOC_MODEL`/device identification retained; do not introduce restricted system-property reads. Unknown is allowed. |
| 27 | Optional feedback tensors disabled | Preserve working embedding path; warning does not make subsequent successful embeddings fail. No requirement for feedback tensors is introduced. |
| 28 | Inactive keyboard/IME callbacks | Explicit Compose focus teardown on chat disposal; navigate/rotate/IME device checks remain required. |
| 29 | READ_DEVICE_CONFIG unavailable | Retain accessible frame-timing/thermal APIs and existing performance harness. No privileged permission request is added. |
| 30 | Finalizer resource warning | Explicit worker/session cancellation and native terminal barriers; stream bodies closed, child parsers bounded/terminated, semaphores released. A warning alone is not reported as a proven leak. |

## QNN baseline and promotion procedure

1. Install the catalog's publisher-verified `Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm` baseline on SM8750. Confirm its 1,280-token context limit; use a short prompt without tools or vision.
2. Keep the pinned LiteRT-LM 0.18.0 / QAIRT 2.51.0 packaging. Verify the actual loaded Qualcomm dispatch and QAIRT libraries rather than assuming installation means execution.
3. Capture artifact SHA, dispatch SHA, runtime versions, SoC, firmware, initialization stages and first successful output from the isolated worker. A preflight rejection is not a QNN execution test.
4. Run cancellation, worker-death, memory and sustained comparisons under [G1–G8](gpu-npu-execution-plans.md). Retain raw failed trials as well as successful ones.
5. Gemma 4 can now attempt execution without a filename allowlist entry. Promote its evidence to qualified only after the exact tuple passes the hardware gates. An admitted model, successful initialization or a single answer does not establish sustained reliability. A crash during an actual native operation remains recorded by the per-tuple journal.

The historical log cannot identify the first GPU failure because that exception was overwritten. The new attempt chain is intended to capture the next failure accurately. No inference is made that missing libraries, corruption, context overflow or a DSP crash caused that original capture.

## Primary protocol and runtime references

- [Google Gemma 3 Qualcomm NPU sample](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/qualcomm/gemma3/npu/README.md) for the SM8750 baseline, and [LiteRT NPU deployment](https://developers.google.com/edge/litert/next/npu) for the dispatch environment.
- [MCP 2025-11-25 transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) and [2026-07-28 transports](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports): GET behavior depends on the negotiated revision; an optional legacy GET refusal must not invalidate working POST.
- [Groq API reference](https://console.groq.com/docs/api-reference): GPT-OSS reasoning effort low, medium and high.
- [OpenRouter error handling](https://openrouter.ai/docs/api-reference/errors-and-debugging): provider/routing restrictions need response detail, not status-code-only retry classification.
