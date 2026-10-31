# Authorization and durable background work

Repository: `tailscale-signin/GPT_Mobile_AI-improved`

Branch: `feat/github-operation-resilience-2026-10-31` (PR #768)

Main baseline: `be5d55930df8be6caad2380eea71b2f9a6bb0419`

Implementation baseline: `c83923cb6a1e7949c17a5a0b8cc52202fb4fb5ea`

The additional request supplies a 2,103-record investigation of
`app-diagnostics-143454062468766618.jsonl`. Its export header identifies app
commit `c8a01397a437a6fd9a4fb43c8f5bfd01335ecf3c`, the merge candidate for the
preceding production source. Selected rejection records independently confirm
`PERSONAL_DATA_LOCAL_ONLY`, `dispatched=true`, and a missing error code. The
reported run counts are historical observations, not measurements of this patch.
Raw logs, private prompts, personal facts and endpoint addresses are not included.

## Authorization and recovery

Model privacy and external-service authorization are separate decisions. A local
Llama/Ollama profile can use an explicitly authorized GitHub connection. Actual
local-only source material still cannot be sent to GitHub or another external
service merely because the model runs locally. The shared provenance decision
now drives initial and cold catalog visibility, dispatch and saved-result reuse.
A blocked GitHub request reports an actionable authorization message before
generation, including when fresh memory recall introduces local-only context
after the initial catalog was built.

Provenance denials carry typed codes. Both budgeted and direct runner paths retain
`dispatched=false`; child statistics discard them before dispatch accounting.
Tool receipts and completion diagnostics expose `PRECHECK_REJECTED`, `COMPLETED`,
`FAILED` and `UNKNOWN_OUTCOME`, while actual GitHub HTTP admission records
`DISPATCHED`. Prechecks are not transport failures, unknown mutations or provider
circuit failures. Repeated schema/policy refusals do not poison the reliability
counter. A corrected argument can still be admitted; an authorization denial
stops the tool for the turn. Policy codes cannot become stream recovery merely
because an accompanying error mentions EOF or a connection reset.

Existing retry controls retain bounded exponential transport/read recovery,
rate-limit cooldowns, local `Connection: close` recovery and cancellation. A lost
write response requires reconciliation, never automatic mutation replay.

## Requested findings

| Priority / finding | Implementation and verification boundary |
| --- | --- |
| P0: GitHub blocked by model privacy | Shared provenance preflight, catalog filtering and a clear GitHub-mode block before model dispatch. Local inference alone is allowed; local-only saved data retains its restriction. Local/remote profile and resolver regressions cover that distinction. |
| P0: policy refusal reported as dispatched | Typed authorization failures remain unsent through budget, direct runner and child statistics. Structured execution state and retryability appear in the envelope and durable tool diagnostics. |
| P0: recovery retries impossible work | Deterministic refusals cannot enter stream recovery or provider failure circuits. Behavior tests remove a refused schema on the next model round without an unknown write or circuit closure; four repeated budgeted attempts perform only one precheck. Existing HTTP retry tests retain transport/5xx bounds. |
| P1: catalog/dispatch mismatch | The same source/service decision is used for initial and cold discovery, execution, and saved-result reuse. Fresh recall is checked again before GitHub generation. Permissions are not inferred from tool names or model locality. |
| P1: autonomous assistance off | Local GitHub autonomous instructions remain enabled by default. Remote/disabled profiles now show unavailable without exposing job controls. A dedicated GitHub policy diagnostic distinguishes this mode from the independent on-device Assist pipeline; `ASSIST_OFF` alone does not describe GitHub autonomy. Saved host jobs still require a verified service and accepted job setup. |
| P1: missing job objective | Batch checkpoints now persist the accepted objective, stage, repository, branch, pinned commit, retry policy, observed PR number and last verified execution receipt. Restore reconciles this context and retains policy refusals without replay. The old `objectiveStatus` is explicitly a checkpoint-load latency objective; leaving that optional target unconfigured is not a missing task. Existing review/engineering stores retain protected drafts and remote-write reconciliation. |
| P1: unreliable Llama `/props` | Retain the bounded five-second handshake, safe probe retry and route-scoped cache. Read the bounded body once. An explicit matching model can confirm context without an optional chat template; missing template evidence cannot confirm reasoning control. Unavailable capacity continues through conservative request admission, not assumed serving settings. |
| P1: incomplete model receipts | Every observed invocation records a structured provider receipt with request ID and its source, finish reason, usage and sources, admitted context provenance, transmitted output cap, reasoning state, runtime and accelerator. Standard response request IDs reach accounting. Missing server evidence is explicit and yields a partial receipt; a client correlation ID is not presented as a server ID, and estimates are not reported usage. |
| P1/P2: deferred semantic memory | Persist reference/digest before WorkManager admission even without a loaded model. A bounded literal local extractor handles eligible first-person statements without inference, while durable waiting state and a fallback receipt prevent hot re-enqueue loops. Startup and runtime-availability recovery retain pending work. Fallback does not mark the semantic stage complete or load a model. Learning, deletion and temporary-chat controls remain authoritative. |
| P2: Examine no-op ambiguity | The existing durable outbox survives restart. Add outbox enqueue, worker admission receipts, dequeue receipts and bounded queue observations before/after work. Distinguish `EMPTY_QUEUE`, `PENDING_UNPROCESSED`, waiting and completed work, including disabled/foreground policy. An empty worker has no report to deliver; real report delivery remains scoped to individual job receipts. |
| P2: local transport reset | Preserve bounded connection health, timeouts, exponential retry and route-scoped fresh-connection fallback. Completed generation is not replayed merely because a stream closes later. Existing mock transport/capacity regressions remain required. |
| P2: rendering jank | Keep catalog/recovery/publication preparation off the main thread and existing coalesced streaming updates. Disable chat backdrop blur while generation is active, including forced inline glass, then restore idle behavior. A Compose regression checks the streaming/idle transition. Actual device frame-time improvement is not measured here. |
| P3: platform warnings | Retain idempotent native cleanup, hardware fallbacks and optional tensor/platform classification. No unsupported foreground JobInfo flag is set by application code. WorkManager/OEM Autofill, IME and monitor warnings are not treated as application crashes; app changes cannot remove vendor platform messages. |

## Validation

The focused host harness compiles production runner, checkpoint, catalog, native
GitHub and retry code with inert Android/Room boundaries. The context bundle
exercises real Ktor mock transports, bounded metadata, receipts and literal
memory extraction. Android resolver/provenance, Compose, WorkManager and memory
repository behavior are qualified by current-head Android CI, not by those host
fixtures. `Required verification`, GenieX packaging, security, native memory,
APK and emulator checks must all pass before merge. There is no physical-device
or deployed-server qualification claim.

Local verification: 416 runner/GitHub behavior tests and 108 context, network,
receipt and memory-contract tests passed. The canonical fast command passed 278
repository tests and resource, dependency, architecture, secret and regex checks.
Changed Kotlin passes ktlint 1.3.1; the complete PR diff passes whitespace checks.
Resolver/provenance, memory repository and Compose additions still require the
complete current-head Android check set.
