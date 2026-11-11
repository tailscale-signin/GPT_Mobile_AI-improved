# Adaptive GitHub creativity

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Working branch: `fix/audit-execution-recovery-2026-11-09`; PR #817. Implementation parent: `79249cfb76a5eb7ed6f9ad6151d054122fd909db`. Base branch: `main` at `3a1f44bb3dff7dbe10a839f1cad7b408b34aadeb`.

GitHub chat Settings now has Adaptive creativity, enabled by default, with a separate Manual choice and a Design creativity slider initially at 80%. These settings belong to the selected profile's GitHub preferences. Ordinary chat settings and the saved provider profile are unchanged. Changing creativity does not select another model, endpoint, credential or reasoning mode.

Each admitted client request carries its settings through helper requests. Trusted workflow roles choose the stage; repository text, tool output and model proposals cannot change execution authority. The request varies temperature and leaves `top_p` unset. Advanced Ollama options are resolved to the same values; OpenRouter options cannot override the request policy. Existing OpenAI and Anthropic model restrictions continue to omit unsupported controls. OpenRouter uses advertised support only, and unknown/custom routes use stage instructions without assuming support. LiteRT NPU uses runtime defaults and stage instructions.

| Stage | Adaptive temperature at 80% | Selection |
| --- | ---: | --- |
| GitHub operations | 0.05 | Tool-bearing execution and continuation |
| Coding | 0.20 | Repair helpers and Gateway coding agent |
| Verification | 0.10 | Claim extraction and review |
| Architecture | 0.64 | Architecture options |
| UI/UX design | 0.94 | Design options |
| Summary | 0.45 | Grounded progress summaries |
| Decision options | 0.64 | Alternative approaches |
| Decision selection | 0.10 | Requirements, evidence, tests, risk and maintenance |

The slider varies design from 0.70–1.00, architecture/decision options from 0.40–0.70, and summaries from 0.25–0.50. Execution, coding and verification remain fixed. Manual uses 0.20–1.20 for all stages, subject to model support. These are requested parameters, not claims of determinism or measured quality. Debug request snapshots label them `REQUESTED_NOT_CONFIRMED`.

## Planning and recovery

Explicit design, architecture and alternative-selection requests receive a bounded, text-only options pass followed by a precise selection pass. Options must have distinct IDs, proposals, tradeoffs and assumptions. Selection must reference an existing option and include rationale, risks and required tests. Truncated responses, invalid structures and tool calls stop the pass with a distinct failure. Provider failures retain their original code, and cancellation remains cancellation.

Completed stages are saved separately in the encrypted workspace checkpoint, bound to the task, repository context, authorization revision and sampling settings. Resume retries the unfinished stage. Both stages use the selected profile and existing invocation ledger, context limits and total execution budget. Only the normal execution path can dispatch tools. Options and selection remain explicitly untrusted proposals, never receipts or user approvals; the existing evidence verifier still gates the final answer. Simple status checks and ordinary repairs do not acquire extra brainstorming calls.

## Durable Gateway jobs

The updated Gateway advertises `sampling_policy_protocol: 1`. New app jobs freeze the policy in the accepted request. Older jobs retain their original generation parameters; retries that omit a policy retain the accepted policy. New submissions require the advertised protocol, with an explicit update instruction for older Gateways. Reconnecting an existing job remains available.

Planner, coding agent, reviewer and report roles resolve sampling separately. Every physical request checks the expected sampling values, unchanged reasoning, model, endpoint and execution owner. Receipts retain the policy and stage. Adaptive planning checkpoints use the same durable owner lease and reject stale owners. The package manifest includes the new module. This requires deploying the updated Gateway; repository tests do not update the user's Windows installation.

## Qualification

Host tests exercise settings migration, Manual mode, provider wire requests, nested Ollama options, unsupported controls, interrupted selection, corrupt or cross-task checkpoints, invalid tool attempts, unchanged model identity, lease checks and durable planning retention. The companion validation receipt records exact results and report digests.

Temperature calibration on each actual selected model, release-build UI performance, ASUS Voice route startup with both packs installed, Kokoro warm/cold latency and deployed Windows recovery remain separate live acceptance work. No formal Unified Reliability case or release attestation is promoted by this change.
