# Revision-bound context and worker intelligence

This implementation adds operations to the existing Gateway engineering job. The
parent job remains the only owner of lease, selected model, total budget,
publication and recovery. A worker or optional tool cannot create another
supervisor or waive the normal acceptance and merge gates.

## Operations

| Action | Purpose | Required input |
| --- | --- | --- |
| `worker_wave` | Advance one dependency-ready wave; independent read-only workers run concurrently | `workers` with stable IDs; first admission also fixes role, objective, budget, optional exact paths and dependencies |
| `worker_status` | Read worker states and candidate/evidence identities | None |
| `task_plan` | Decompose existing accepted criteria into dependency-ordered work packages | `items` with ID, accepted requirement IDs, dependencies, role and optional exact source paths |
| `task_plan_status` | Read ready work and observed evidence | None |
| `task_plan_complete` | Attach actual named passing behavior cases at one exact candidate patch | Task `id`, list of `receipt_sha256` values |
| `context_pack` | Export selected immutable blobs into a bounded private artifact | `paths`, optional `format`, `engine`, `max_bytes` |
| `context_summary` | Retain full evidence and return compact extractive working context | Optional `max_bytes` |
| `tool_profile` | Discover only the tools relevant to plan, edit, review, test or retrieval | `profile` |
| `integration_status` | Discover current exact configured MCP tools | None |
| `integration_call` | Execute one allowed tool through the existing durable MCP dispatcher | `integration`, exact `tool`, `arguments`, stable `invocation_id` |
| `next_edit` | Request one local edit suggestion | Exact `path`, `instruction`, optional source line window |
| `next_edit_validate` | Test a retained suggestion using an approved command | `next_edit_id`, `check_index` |

Every action also names the exact `head_sha`; the HTTP workspace command owner
supplies the job revision and durable command identity.

The new roles are `planner`, `editor`, `reviewer` and `retrieval`; retained
`investigator`, `implementation worker` and `test worker` names remain valid.
Up to eight workers retain isolated context. Read-only waves use a bounded thread
pool. The existing inference scheduler still enforces actual local-model
capacity, and its atomic dispatch ledger preserves the shared model-call/token
budget. Check and editing lanes run serially to preserve the exploratory check
allowance. Dependencies advance in a following explicit controller wave.
Workers receive their own objective, plan and retained observations. Ambient
parent repair logs, candidate text, summaries and conversation/history recall
are excluded. A fixed path scope also filters acceptance rows and retained
candidates; requirements spanning another scope remain the parent's duty.
Explicit graph/search actions filter results to the worker's exact source tree.

Per-role route overrides are rejected: this version uses the parent's admitted
selected route. Choosing another model requires another admitted job. This is
reported as `PARENT_SELECTED_ONLY`, not as arbitrary multi-provider dispatch.
A pending call after an interruption reports reconciliation required and is not
silently replayed. Completed worker observations have private artifact references.

## Context and guidance

The graph candidate window is ranked with bounded task-seeded dependency
propagation and file diversity before byte projection. It does not upgrade
lexical/source-level confidence. Full prompt admission remains responsible for
actual token counting and output reserves. Graph views contain no dangling links,
and omitted metadata stays explicitly incomplete.

Instruction and skill manifests expose exact paths/blobs before loading content.
A tool profile is a discovery filter, never a permission grant. Context summaries
are extractive; the complete source receipts remain in private artifacts. Neither
a compact summary nor a green work-package row asserts overall completion.
The board projection has a 256 KiB allowance with explicit omission counts.
Next-edit previews contain at most 2 KiB of UTF-8 text plus immutable artifact
references, and never contain full source or a raw changes object. Oversized tool
schemas retain their exact digest and an explicit omission flag; execution still
validates arguments against the complete current runtime schema.

The built-in context pack supports JSON, XML and Markdown. It admits only complete
selected source files that fit the byte budget; omitted paths retain their blob
identity and reason. Token counts remain unknown unless measured by the actual
model tokenizer. It does not grant edit authority for exported content.

`engine: repomix` executes the real installed Repomix CLI in the admitted,
digest-pinned validation image. It creates a read-only mount containing only the
selected pinned regular files and a host-generated empty config, disables
networking, and never downloads a runtime. Repository configuration cannot become
host configuration. The host must set `workspace_repomix: true` and provision
Repomix in that image. Missing tools or rejected input remain explicit failures;
a mocked executable result does not qualify an installed runtime.
Cancellation, timeout and output-budget failures reap the exact container ID
recorded for the export.

## Optional MCP connections

The repository validation policy may include:

```json
{
  "workspace_integrations": {
    "context7": {
      "enabled": true,
      "allow_remote": true,
      "allow_mutations": false,
      "tools": ["context7_resolve-library-id", "context7_query-docs"]
    },
    "playwright": {
      "enabled": true,
      "allow_remote": true,
      "allow_mutations": false,
      "tools": ["playwright_browser_snapshot", "playwright_browser_take_screenshot"]
    }
  }
}
```

Tool names above are examples, not automatically accepted aliases. Use names from
the host's configured and schema-validated MCP catalogue. The current policy hash
must match the job admission. Context7 library/version selection is supplied in
the actual advertised arguments, and the exact response is retained with its
invocation/source identity. Playwright observations are browser evidence, not
native Android or Windows execution evidence. Browser interactions additionally require explicit host permission, accepted
implementation scope, the current parent lease, current authorization and the
unchanged selected execution binding. They do not grant repository mutation
authority.
Local-only/private jobs cannot send their source or query through these external
tools. Configuration, catalogue and schema changes invalidate execution. The
existing MCP action ledger rejects changed arguments and unknown mutation replay.

The optional `github` integration admits only fixed complementary code-scanning,
secret-scanning and dependency-alert reads from its explicit tool allowlist.
Native overlapping operations and all GitHub MCP mutations remain suppressed.
It does not install or duplicate a global GitHub tool catalogue in every prompt.

## Experimental next edit

Next edit uses the already selected LLAMA/OLLAMA binding, requires its explicit
`LOCAL_ONLY` capability policy and a loopback backend, and keeps normal source
exposure/anchor checks. It does not choose or download another model. The model
must support the application's bounded JSON edit contract. The helper retains a
proposal and a diff; it never applies, commits or publishes automatically.
`next_edit_validate` runs an approved command and produces a normal retained
candidate. A suggestion is not qualified until the ordinary validation/review
pipeline succeeds. Specialized Sweep prompt formats and performance on user
hardware are not claimed by the generic selected-model helper.

## Source references

- Repomix CLI flags and security semantics:
  <https://repomix.com/guide/command-line-options>
- Sweep model's specialized prompt format and local model requirements:
  <https://huggingface.co/sweepai/sweep-next-edit-1.5B/blob/main/README.md>

## Validation boundaries

Behavior regressions exercise true concurrent worker execution, dependency
admission, immutable role/scope and budgets, uncertain-call retention, exact
requirement/check evidence, bounded graph/export/summary projections, local-only
suggestions, and the production exact-MCP router plus its SQLite replay ledger.
The worker race test uses the real shared dispatch-budget transaction: three
simultaneous workers with one discretionary call dispatch exactly one request
while preserving the review and final-report reserves. Worker check receipts are
available to the parent task graph without copying worker source-edit exposure.
Tests use deterministic model/MCP transport fixtures; they do not assert live
provider access, installed Repomix/Playwright runtime readiness or physical-device
performance. Full combined-head CI and publication belong to the parent task.

Validation on this isolated branch passed 66 focused Gateway tests and the
canonical fast validation, including its 616 host-script regressions and metadata,
policy, secret-scan, resource and whitespace checks.
