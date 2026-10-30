# GitHub gateway upgrade 14.3

This version pairs the Android GitHub workspace's native tools with a direct,
client-owned model round. The gateway recognizes the declared `github_context`,
`github_search`, `github_read`, `github_pr`, `github_ci`, `github_watch_checks`,
`github_stage_patch`, `github_commit`, `github_write`, and `github_release` tools,
including their `__github_api` names. Natural-language repository text cannot
activate this route. The app retains credentials, mutation approval, branch
selection, durable checkpoints and tool execution.

## Speed and stability

- Skip unrelated desktop MCP catalog discovery on these requests, including
  short follow-ups. Preserve the supplied tools, prompt, reasoning and output
  limits. Return model tool calls to Android for execution.
- Reuse the existing worker-local HTTP connection pools and supervised MCP
  sessions. Keep bounded workers, deadlines and durable request/action receipts.
- Gateway-managed immutable reads can share one in-flight producer and reuse
  results across jobs. Admission and schema validation occur first. Cache identity
  includes paired principal, connection/configuration revision, schema, exact
  tool binding and arguments. Only reviewed read operations with immutable SHAs
  qualify. Mutable PR/CI/branch observations and writes bypass this cache.
- Cache limits: 8 MiB, 64 entries, 16 in-flight producers, 30-second follower
  wait. Cancellation detaches a follower without repeating or cancelling the
  producer. Failures and MCP errors are not cached. No automatic tool retry is
  introduced; ambiguous writes still require reconciliation.
- Bounded result envelopes retain exact structured `continuation_call`,
  `correction_call`, search identity and cursor fields. If the receipt cannot fit,
  return a capacity error rather than silently deleting continuation data.

## Diagnostics

The authenticated `/gateway/admin/status` response now includes `github`:

| Field | Meaning |
|---|---|
| `modelRounds.counts` | Admitted requests, active/success/failed rounds, discovery skips and known token totals |
| `modelRounds.returnedToolCalls` | Returned calls per recognized native operation; not proof of execution |
| `modelRounds.p50Ms`, `p95Ms` | Nearest-rank latency across the last 256 completed model rounds |
| `modelRounds.successRate` | Successful model rounds / completed rounds, or null before observations |
| `modelRounds.usageComplete` | Whether every completed round supplied valid prompt/completion usage |
| `immutableReads` | Cache hits, joined reads, misses, failures, evictions, retained bytes and active producers |

Counters reset on gateway restart, contain no prompts, source, tokens or repo
names, and describe the gateway process. They exclude Android tool execution and
admission queue delay. They are not an intelligence score or an end-to-end SLA.

## Upgrade and acceptance

Install the complete reviewed package using the existing v14 installer and
manifest validation, then restart `gateway.py`. Existing pairing and journal
formats remain compatible. Roll back by reinstalling the previous complete
package; do not mix modules across versions. No new dependency is required.

Run `python gateway/check_gateway.py`, then exercise a paired phone against a
local model: discover workflows, page a filtered search, stage and reject an
edit, approve an edit on a disposable branch, reconnect a read and reconcile an
interrupted write. Compare equivalent warm/cold requests using the same model,
context and hardware. Numerical speedup, memory and reliability claims require
these measured runs; none are assumed by this change.
