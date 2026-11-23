# Optional coding-agent runtimes

The Gateway can delegate an accepted implementation task to an explicitly
configured coding agent. The existing engineering job remains the execution
owner. The phone and desktop queue commands; the Gateway drains them under the
same job lease. Reading the workspace does not start, qualify, or poll a provider.

## Supported execution paths

| Runtime | Executable transport | Source binding | Special controls |
| --- | --- | --- | --- |
| Codex | `codex app-server` JSON-RPC in a retained isolated container | Sealed commit checkout | Exact-turn steering, events, cancellation, candidate capture |
| GitHub Copilot cloud | Existing durable Copilot task service | Branch preflight; completion is not source attestation | Start and observe; no invented remote cancellation API |
| Copilot SDK | Installed `microsoft-copilot-sdk` v2 interface and native runtime in empty mode | Sealed commit checkout | Three bounded custom file tools, events, follow-up after a turn ends, isolated cancellation |
| Jules | Official `v1alpha` HTTPS API | Branch preflight; import additionally verifies `gitPatch.baseCommitId` and exact blob context | Plan approval, activities, follow-up; no advertised cancel operation |
| Cursor | Official `/v1/agents` and runs API | Explicit starting commit; candidate import verifies GitHub commit/tree/blob objects | Exact-run observation/cancel, steer with explicit attribution limits, SSE resume, artifacts |
| OpenHands | Installed `openhands.sdk` and tools SDK | Sealed commit checkout | Events, follow-up, iteration/budget controls, isolated cancellation |
| Cline | Installed `@cline/sdk` with bounded checkout tools | Sealed commit checkout | Exact-anchor edits, bounded checks, event stream, isolated cancellation |
| Claude Agent SDK | Installed `claude-agent-sdk` and Claude runtime | Sealed commit checkout | File tools, events, follow-up, budget/turn controls, isolated cancellation |
| Kiro / Devin | Installed `kiro-cli acp` / `devin acp`, stable ACP v1 | Sealed commit checkout | Negotiated model selector, session updates, follow-up, isolated cancellation |
| Aider | Installed Aider CLI through the isolated Python driver | Sealed commit checkout | One-shot tasks, streamed output, isolated cancellation; no active-turn steering |

`READY` is a retained setup result, not evidence that a paid task succeeded.
Local image qualification verifies that the configured executable and SDK are
installed, without calling a model. Its scope is `RUNTIME_ONLY`; the account and
selected model must also work when the session is explicitly started. ACP
runtimes that cannot advertise and confirm the exact configured model are
rejected before a prompt is sent. Provider-managed Jules models must be selected
as `provider-default`. No compatibility, account entitlement, cost, or latency
claim is inferred from a fake-provider test.

## Host configuration

The desktop's host-configuration editor manages a dedicated `coding-agents.json`
file. A headless Gateway sets `GATEWAY_AGENT_CONFIG` to its absolute path. The
phone cannot supply executable commands, image names, credential values, or API
endpoints. Configuration writes use a revision comparison and atomic replacement.

```json
{
  "schema_version": 1,
  "agents": {
    "codex": {
      "enabled": true,
      "repositories": ["owner/repository"],
      "principals": ["the-exact-gateway-principal"],
      "model_id": "the-explicit-installed-model-id",
      "credential_reference": "env:CODEX_AGENT_API_KEY",
      "image": "registry.example/approved-codex@sha256:REPLACE_WITH_64_HEX_DIGEST",
      "max_seconds": 1800,
      "max_runs": 3,
      "max_iterations": 24,
      "max_budget_usd": 2.0
    },
    "jules": {
      "enabled": true,
      "repositories": ["owner/repository"],
      "principals": ["the-exact-gateway-principal"],
      "model_id": "provider-default",
      "credential_reference": "env:JULES_AGENT_API_KEY",
      "max_seconds": 1800,
      "max_runs": 3,
      "max_iterations": 24,
      "max_budget_usd": 2.0
    }
  }
}
```

Replace the illustrative image digest and model ID before saving. Use exact
principal and repository allowlists. Credentials remain in host environment
variables; the settings file contains references. `environment_references` can
map up to 12 specific runtime environment variables to `env:NAME` references.
It cannot replace process trust variables such as `PATH` or `PYTHONPATH`.
Git/GitHub token variables are rejected. Kiro and Devin accept only explicit
`environment_references` for the authentication supported by their installed
runtime; a generic `credential_reference` is rejected because it has no defined
ACP mapping. GitHub Copilot cloud always uses the existing host service account
and rejects independent account/environment settings. Cloud HTTPS adapters
reject local image, executable, and environment settings.
An optional `command` is an administrator-owned executable argument array,
except for Copilot SDK, whose sealed file-only bridge cannot be replaced.

Local images must already be present and pinned by SHA-256 digest. Gateway
uses `--pull=never`; it never installs, downloads, or upgrades an SDK/runtime.
An approved engineering validation policy and exact-source mirror must also
exist for the repository. Images need Git, Python 3 with tar extraction filters,
the chosen runtime and its dependencies, and a usable `PATH` for UID 65534.
Cline additionally needs Node and `@cline/sdk` resolvable from
`/opt/gateway-agents/package.json`. Copilot SDK needs its native runtime
preinstalled; automatic runtime download is disabled.

The agent owns only its isolated checkout and home. Container root controls
the session socket, request ledger and source manifest; the agent process runs
as UID 65534. The image is read-only, resources and lifetime are bounded, and no
host filesystem, Docker socket, shared worktree, or GitHub host credential is
mounted. The configured model credential and explicit environment references
are provided to the selected runtime. Copilot's GitHub inference credential is
passed directly through SDK authentication, removed from the bridge environment,
and excluded from the native runtime environment. Copilot uses `mode='empty'`,
an exact `custom:` whitelist of three checkout tools, disabled project discovery,
and rejection of every runtime permission request. There is no shell, network,
GitHub, or built-in tool available to its model. Claude uses an explicit five-tool
file whitelist and no project settings/hooks or MCP servers. Remote inference
uses the container's bridge network.
Runtime checks are exploratory and do not replace host-approved validation.

## Commands and authority

All commands carry `head_sha` and the workspace envelope's repository, job,
revision and authenticated principal binding. A queue `request_id` is accepted
on every command. Actions that can start new inference require the queue's
server-injected user-command identity; a model cannot manufacture authority by
returning `consent: true`.

| Action | Arguments beyond `head_sha` and queue `request_id` |
| --- | --- |
| `agent_qualify` | `backend` |
| `agent_start` | `backend`, exact configured `model_id`, exact accepted `objective`, `consent: true` |
| `agent_compare` | two to eight distinct configured `backends`, exact `objective`, `consent: true` |
| `agent_status`, `agent_events`, `agent_artifacts`, `agent_import` | `run_id` |
| `agent_steer` | `run_id`, `message`, exact `expected_remote_run_id` |
| `agent_cancel`, `agent_approve_plan` | `run_id` |

Capability rows advertise only the operations available to that configured
runtime. Every delegation also requires an implementation-scoped job, explicit
user consent, and host principal/repository authorization. `LOCAL_ONLY` jobs
cannot delegate to these remote-inference runtimes. The existing phone privacy
boundary prevents private conversations from opening engineering workflows;
an arbitrary server `privacy_scope` string does not grant remote permission.
The selected supervisor profile is never silently replaced by an agent model.

Run identity includes the job principal, repository, source, configured model,
configuration and credential fingerprint. Credentials/configuration changing
blocks existing-run provider access instead of redirecting it to another
account. A dispatch receipt is committed before every provider mutation.
Connection loss preserves `OUTCOME_UNKNOWN`; retrying the same request observes
its retained session or correlates a unique provider session. Missing or
ambiguous observations never authorize a second creation request.
Cursor lost-start recovery additionally requires a complete inventory with only
the original run: another client's follow-up is never adopted as the task.

HTTPS exchanges have a parent-supervised process lifetime, not only a socket
inactivity timeout. The JSON exchange has a 45-second total bound and an SSE
page a 20-second bound, including DNS, connection headers, and trickling bodies.
The parent cancellation/deadline is checked every 200 ms while waiting. A killed
write remains uncertain. Credentials and request bodies travel over stdin and
never appear in executable arguments or provider-error diagnostics. The frozen
Windows Gateway uses its explicit internal `agent-http-worker` entry mode.

## Retention, candidates and cleanup

Session IDs and operation receipts live in the existing durable engineering
journal. Prompts, complete bounded responses, event pages and captured candidate
content use the existing encrypted artifact store. The workspace projection
contains bounded metadata and scoped artifact references, not credentials or
host paths. Local control processes survive a Gateway connection/restart until
their configured deadline; uncertain writes can be reconciled against their
retained operation ledger. A missing container is reported as `RUNTIME_LOST`
and is never restarted by a read.

Candidate import is a separate explicit action. It verifies the accepted source
and unchanged validation policy, safe regular-file paths and modes, original
blob IDs and the coordinated change budget. Jules patches require exact context
with no fuzzy application. Cursor import additionally requires the current
agent's latest run, one isolated `cursor/` branch, an exact-source ancestor,
complete target tree, and unchanged branch/run identity after capture.
Local candidates must carry the exact completed session/turn identity; steering
invalidates the previous capture atomically at dispatch, even if its response is
lost. Root-owned source inventories ignore generated files while checking every
tracked path. Mode changes, executable new files, links and nonregular types are
rejected explicitly. Descriptor-relative reads cannot follow a raced parent
directory link into the root control files. Candidate IDs use the existing
64-hex checkpoint contract; unexecuted imports have `check_index: null`.
Imported candidates have `UNVERIFIED` status and `complete_validation: false`.
They do not alter the shared branch or become passing CI, approved changes,
merge permission, or release evidence.

Cursor steering has no server-side expected-run precondition. Its response can
say `queued` without identifying the future run; even `steered` delivery may
become a follow-up. If attribution cannot be confirmed, the workspace retains
`UNVERIFIED_FOLLOWUP`, the original run identity and the observed latest run;
candidate import is blocked. This adapter does not claim atomic steering or
silently select an unrelated later run.

Agent comparisons are labeled `AGENT_SYSTEM` and retain task/source/policy,
runtime/model/configuration identity, declared budgets and separate candidates.
They require identical declared budgets. Some providers expose no enforceable
cost/iteration limit; unknown usage stays `null`. These experiments are not
model-only benchmarks and produce no score until the normal candidate-check
path supplies comparable evidence. There is no automatic winner or merge.
Every child admission is checkpointed. If a later runtime cannot start, the
experiment returns `PARTIAL` with its retained run IDs and typed failures;
retrying the same experiment does not repeat paid starts.

The authoritative controller calls `advance(job)` to observe at most one due
run per step with bounded backoff. Explicit delegations hold ordinary phase
progress while active. `cancel_all(job)` operates under a live parent lease.
After parent cancellation invalidates that lease, `stop_parent(job)` requires
the exact stored cancelling/terminal revision, principal and source; it can
only clean up retained containers or request a once-journaled remote stop.
Active jobs are not stopped merely because the Gateway restarts. Unsupported
or unconfirmed remote cancellation is reported as `REMOTE_CONTINUES`.
Local container names and ownership labels are retained before creation. A
lost create response is resolved by that identity. Preparation rechecks the
parent claim before/after staging, and stale workers compensate by disposing
their own container when cancellation races the retained result. Cline checks
disable SDK retries, honor `abortSignal`, kill their process group, and finish
within their check limit even when a descendant keeps an output pipe open.

## Primary protocol references

- [Codex app-server](https://learn.chatgpt.com/docs/app-server)
- [Cursor cloud API](https://cursor.com/docs/cloud-agent/api/endpoints)
- [Jules sessions](https://jules.google/docs/api/reference/sessions/), [sources](https://jules.google/docs/api/reference/sources/), [activities](https://jules.google/docs/api/reference/activities/)
- [ACP v1 initialization](https://agentclientprotocol.com/protocol/v1/initialization), [session config options](https://agentclientprotocol.com/protocol/v1/session-config-options)
- [Claude Agent SDK Python](https://code.claude.com/docs/en/agent-sdk/python)
- [OpenHands software-agent SDK](https://github.com/OpenHands/software-agent-sdk)
- [Microsoft Copilot SDK Python](https://github.com/microsoft/copilot-sdk/blob/main/python/README.md)
- [Cline SDK](https://docs.cline.bot/sdk/overview), [SDK reference](https://github.com/cline/sdk-skill)

The adapter tests exercise real pipe-based JSON-RPC sessions and durable
lease/retry/candidate behavior with substituted provider transports. They do
not spend provider credits or certify that an absent optional image/account
has been installed.

Protocol surfaces reviewed on 2026-11-22: Cursor v1, Jules v1alpha, stable ACP
v1, Codex app-server JSON-RPC, and the Copilot Python SDK v2 grouped keyword API.
The Copilot client/tool source blobs inspected were
`3a95ca824ce3bc737a05d24692360e266c9cde8b` and
`3ed6fde06abd2e37170e93fc16b32c83df74b2cf`.
Claude, OpenHands and Cline interfaces were checked against their current
official documentation/source; OpenHands `LocalConversation` exposes both
`max_iteration_per_run` and `max_budget_per_run`. No installed version of these
optional SDKs was live-tested here. Local qualification records sanitized actual
runtime/installed Python distribution versions from the pinned image; Cline's
module availability is checked without inventing a package version. The focused
tests include a real stalled subprocess cancellation, a real Cline
child/grandchild abort, source/candidate integrity, credential boundaries,
lease loss, uncertain writes, SDK constructor fixtures, and checkpoint restore.
