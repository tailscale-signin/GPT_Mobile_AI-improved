# Gateway 16 — Full Integration Batch Mode

**Target:** `fix/voice-chat-crash-and-diagnostics-2026-11-11` · PR #819
**Status:** Opt-in contracts, durable stage projection, bounded local validation parallelism and Windows controls implemented. Device and mixed-workload qualification remain.

## Decision and ownership

Introduce three GitHub Gateway execution modes that do **not** change creativity, model identity or authority.

- **Standard:** existing legacy-compatible path.
- **Smart:** existing adaptive bounded read execution.
- **Full Integration:** opt-in durable engineering stage graph and host-conservative GitHub API scheduling.

`EngineeringService` and `EngineeringStore` remain the single owner. The new
`engineering_batch_plan` is a projection of their existing journal, not a
parallel agent capable of reissuing GitHub mutations or approving merges.

## Capabilities and policy

The Gateway advertises `execution_batch.protocol_version=1` and supported
modes only to authorized engineering callers. Android checks the mode before
saving the request. The host must enable Full through Windows Advanced →
Execution. Existing `settings.json` remains schema 1; host policy lives in
`data/execution-policy.json`. It does not grant per-device identity on a
shared-key-free LAN. Existing job identity/model/repo/branch/budgets remain frozen.

The Windows Advanced settings expose a local validation worker cap (1–4, default 1).
The cap is copied into each newly admitted Full job. Parallel execution also requires
adjacent command indexes to be explicitly grouped by the repository's host-approved
validation policy. Every grouped check runs in its own network-disabled container and
copy of the same pinned, patched checkout. Ungrouped checks remain sequential; failure
in a group retains every sibling receipt and prevents later groups from starting.
The Windows GitHub Jobs dashboard shows the saved phase, state and stage count.

Full requests carry `execution_mode: "full"` and
`batch_policy_version: 1`. The host rejects unknown modes/versions and
unapproved limits. `resume_after_transient_limits` defaults on for Full jobs and
can be explicitly disabled per job; confirmed work is retained either way. No
native remote-provider batching or automatic model fallback is enabled. Saved
engineering jobs currently accept local Llama/Ollama profiles only, so provider
batch endpoints would not serve this execution path and remain unavailable.

## Workflow

1. Bind exact model/profile/account and inspect named repository/source.
2. Verify host-approved acceptance, runtime and approved checks before writes.
3. Persist initial PLAN stage and advance a versioned DAG as the existing
   engineering supervisor moves through READ, IMPLEMENT, REVIEW, COMMIT,
   OPEN_PR, CI, PROMOTE, MERGE_READY, MERGE and VERIFY.
4. Read bundles stay bounded to 20 authorized operations and checkpoint
   successful siblings; dependencies do not run while prerequisites defer.
5. GitHub API requests share a one-in-flight-per-credential governor;
   mutations remain behind `EngineeringOperations` dispatch receipts.
6. CI failures lead to bounded fingerprinted repair attempts, not an
   unbounded restart. Evidence gates completion.
7. Merge remains a separate exact-head authenticated user action.

## Recovery matrix

| Condition | Authorized response |
|---|---|
| Provider 429 / secondary 403 | Persist cooldown; park affected safe read or stage |
| Previous successful sibling read | Reuse receipt without reissuing the HTTP request |
| Unknown GitHub write outcome | Reconcile original dispatch before retry |
| 401 / permission denied | Pause for explicit input, never switch credentials |
| Invalid schema / policy | Reject preflight without model/tool dispatch |
| Android closed | Gateway-owned job keeps running; Jobs reconnects to receipt |
| Gateway restarted | Restore known-undispatched stages; fence stale workers |
| Deadline, step or token cap reached | Preserve partial evidence; request fresh authority |

## Interfaces and visibility

`GET /gateway/engineering/jobs/{id}/batch-plan?after=0&limit=20`
returns sanitized paged stage receipts under the existing principal check.
`batch_progress` in the job catalog powers a compact in-chat and Jobs
display, including the current phase, active/waiting state and completed
stage count. Do not present a dynamically expanding graph as a fixed %
complete or a cached snapshot as live.

## Qualification checklist

- [x] Host opt-in mode policy, strict contract and legacy default.
- [x] Android mode selector, capability gate and optional request encoding.
- [x] Durable stage projection and principal-scoped inspection.
- [x] Typed batched read cooldowns with sibling receipt preservation.
- [x] Per-credential GitHub API singleflight pacing.
- [x] Host-approved parallel validation groups with isolated sandboxes,
      persistent per-command receipts and a configurable 1–4 worker ceiling.
- [x] Windows execution controls and durable batch progress in the Jobs dashboard.
- [ ] Verify mixed GitHub/llama workload fairness and physical device UX.
- [ ] Qualify Windows installer / Gateway deployment / Android acceptance CI.
- [ ] Benchmark local continuous inference slots vs existing GPU settings.
- [ ] Physical Android 16 performance and Voice Chat qualification requires a
      connected target device; no real-device benchmark is claimed here.
- [ ] Native provider batching is unavailable until remote engineering profiles
      and provider retention/consent contracts are supported.

No claim of complete production performance, device reliability or cloud
cost reduction is made until measured qualification is recorded.
