# Durable GitHub engineering and intelligent batches

GitHub Chat can start a gateway-owned engineering job and reconnect to it after
Android closes, loses its network, or restarts. The gateway makes bounded llama
requests, saves each phase and operation receipt in its SQLite job database,
and owns execution independently of the HTTP request or phone process.

These are additive Gateway 16 workflow capabilities in the existing 14.3
package layout. Install the whole package and manifest through the v14 upgrade
procedure; replacing a single launcher is insufficient.

## Start from Android

1. Configure a llama gateway AI profile and select it in GitHub Chat.
2. Choose **Autonomous engineering → Start job** in the conversation. Enter the
   objective, dedicated working branch and PR base. Starting authorizes reviewed
   changes on that working branch and draft PR publication.
3. New jobs default to **Ultra Maximum**: up to 24 hours, 512 model invocations
   and 2,097,152 aggregate model tokens, with five repair attempts per failure.
   Smaller saved duration and budget choices remain authoritative. The existing
   **Until done** setting has a 72-hour hard limit; all jobs retain the 256-step
   and 20-total-repair bounds. No setting authorizes a merge.
4. Close the app if needed. Keep the gateway host and llama server running.
   Opening the same conversation loads the saved job immediately and fetches
   all missed update pages, without creating a replacement job. **Updates**
   marks new events and keeps their timestamps. The existing GitHub header
   shows milestone progress and elapsed time.
5. Review the PR, full changed-file list and actual check evidence. Only the
   separate **Approve & Merge** confirmation can authorize the exact PR and
   40-character head SHA. The gateway rechecks the head, base, required reviews,
   checks and GitHub merge policy before dispatching a merge. Approval expires
   in 30 minutes; changed evidence rejects it. **Request changes** pauses the
   job; submit the revised objective as a new dedicated job.

Jobs are bound to the saved endpoint, profile and gateway principal. Android
stores the pending create key before dispatch and reuses it after a lost
response. Backup restore maps the local conversation by stable identity while
preserving the original remote request, job ID and authorization binding.

The gateway advertises its supported durations and execution-budget limits.
Android records both the requested and effective allowance before admission;
the saved-work panel labels it **Accepted** only when the server returns the
budget receipt. An older host's 12-hour/256-model-call ceiling is shown as a
lower host allowance rather than a 24-hour Ultra job. Lower engineering modes
request smaller budgets, and explicit profile/run limits remain in force.
Native GitHub's separate 512-tool-call Ultra ceiling is not a model-invocation
count. New non-free profiles use the default profile allowance so that this
native ceiling is reachable; saved caps, including historical 50 or 100, are
not upgraded or bypassed.

New admission stores every effective budget field with the request. A retry
after a lost response reconciles against that accepted snapshot before applying
current defaults. Legacy jobs without a budget snapshot keep their original
three-hour request default and 128-model-call/4,194,304-token runtime fallback.
Selected-model identity, provider output/context limits, cancellation and
verification/final-report reserves remain authoritative.

## Host setup

Follow [V14_UPGRADE.md](V14_UPGRADE.md) for the private listener, durable state
directory and single-process lock. The desktop open-network mode uses its existing shared network principal; deployments using trusted proxy mode may use Tailscale Serve. Durable jobs
must remain enabled. The existing `GATEWAY_JOB_DB` holds engineering jobs,
dispatch intents, receipts and update events. Back it up with the gateway
stopped; retained source and logs require the same protection as the repository.

Autonomous execution uses a GitHub credential configured on the selected gateway
host. The phone's native GitHub connection remains on the phone. Host credential
selection follows this order:

1. `GATEWAY_ENGINEERING_GITHUB_SERVER` selects one existing enabled MCP entry from
   that gateway's configured MCP file. Its protected `env` supplies
   `GITHUB_PERSONAL_ACCESS_TOKEN` or `GITHUB_TOKEN`. An invalid or disabled explicit
   selection stops admission; it never falls through to another account.
2. With no selector, `GATEWAY_ENGINEERING_GITHUB_TOKEN` supplies an explicitly
   configured engineering credential from the host's protected service environment.
3. With neither setting, exactly one enabled `github-official` or `github-codefirst`
   entry is used automatically. Multiple enabled candidates require an explicit
   selector. Unrelated MCP entries and process-wide `GITHUB_TOKEN`/`GH_TOKEN`
   values are never searched for a substitute.

The managed desktop launcher preserves these two explicit engineering settings
while retaining its own listener, identity, consent and state-directory settings.
Use the desktop's selected MCP configuration file, or its default `data/mcp.json`.
Apply environment settings to the desktop supervisor/service process that launches
the gateway, then use the normal restart procedure after active work permits it.
An updated package must be installed on the host for this default selection to apply.

Missing, disabled, malformed or conflicting credentials remain unavailable.
Reusing an MCP credential also preserves its read-only/lockdown restrictions and
GitHub destination: a connection configured for an enterprise host is not sent
to this backend's `api.github.com` endpoint. Credentials are resolved for every
request and never included in job snapshots or Android backups. Jobs stop if the
GitHub account changes. This local configuration check does not verify remote
GitHub authorization or repository access.

For Tailscale principals, explicitly add `engineering` to that user's
`GATEWAY_TRUSTED_USERS` scopes alongside the existing chat/jobs/tools grants.
This grants autonomous branch-writing access using the configured host account;
grant it only to users authorized for that account's repositories. Existing
chat or tool grants alone do not grant engineering. Direct loopback admin is
allowed. Legacy remote bearer identities are not automatically elevated.

`GET /v1/gateway/engineering/capabilities` reports availability, current
principal, limits and the notification transport. Use the existing `LLAMA_BASE`
for inference. The engineering worker reuses gateway slot admission and
cancellation-aware model transport; it does not add unbounded parallel model
requests or register a model-facing merge-approval tool.

The versioned `readiness` object separates journal access, the configured host
GitHub connection, and the running engineering worker. `code_graph.enabled`
reflects the graph service's own prerequisites; graph reads do not require the
background job worker. Its `checks.graph_host_github_connection` is separate from
engineering's `host_github_connection`: read-only host GitHub configuration can
serve source maps without authorizing engineering writes. The graph adapter
allows GitHub GET/HEAD requests only, with no request body or engineering job.
Lockdown-filtered connections remain unavailable because the graph does not
implement that filter. These local checks leave GitHub network/repository access
and model inference unverified until the corresponding operation runs. A working
chat endpoint therefore cannot establish engineering readiness. Android caches
the handshake for ten seconds by authenticated route, network generation and
observed gateway instance; an observed restart invalidates that identity's cache.

Saved jobs and evidence remain readable when the host GitHub connection or worker
is unavailable and the journal can still be read. Reconnecting a pending create
reuses its persisted request ID and exact payload. If the gateway already saved
that request, it returns the original job even when new work cannot be admitted.
Changing the payload under an existing request ID is rejected.

## Workflow, recovery and evidence

The persisted phases are PLAN, READ, IMPLEMENT, REVIEW, COMMIT, OPEN_PR, CI,
PROMOTE, MERGE_READY, MERGE, VERIFY and COMPLETED. NEEDS_INPUT and CANCELLED
retain completed evidence. The coding agent and tools-disabled reviewer make
separate llama calls. A deterministic supervisor enforces duration, step,
connection, repeated-patch and per-failure repair budgets. A supervisor cannot
grant approval. The default worker advances jobs serially so one inference slot
remains a reasonable starting configuration.

Reads use pinned Git blobs, bounded immutable context caching, repository
instructions and code excerpts. Exact anchors and expected blob SHAs protect
partial file views. Edits are limited to 20 UTF-8 files and 512 KiB per patch.
One Git tree/commit publishes coordinated edits with a non-forced ref update;
default/base branches and unrelated existing PRs are rejected. Crashes during
commit, PR creation, draft promotion or merge are reconciled from remote
evidence. Unknown outcomes pause instead of repeating a write. Cancellation
prevents further work, but cannot undo an already dispatched GitHub request.

CI runs on GitHub after branch publication. There is no arbitrary local shell
executor in this feature, and model suggestions are never reported as executed
tests. The worker fetches actual failed workflow/job logs for the exact head,
applies a reviewed targeted patch, then waits for the new checks. A full,
paginated check inventory and GitHub's merge policy must be known. Missing,
cancelled, failed or required skipped checks do not count as success. Required
reviews, conflicts, draft status and unknown policy continue to block merging.
Repositories without observable CI require human intervention. A required merge
queue is not bypassed through the REST merge endpoint.

GitHub is polled every 30 seconds while awaiting CI. An optional authenticated
webhook relay may POST to `/v1/gateway/engineering/webhook` with the GitHub HMAC
signature and `GATEWAY_ENGINEERING_WEBHOOK_SECRET`. It is only a wake-up hint;
fresh GitHub reads remain authoritative. No public ingress, webhook or cloud
notification service is provisioned automatically.

Android WorkManager checks retained jobs on a 15-minute background schedule and
uses existing private decision notifications for merge readiness, escalation
and completion. This works without an open conversation, but Android can delay
background work or suppress notifications. It is **not FCM push**. App
force-stop also stops Android sync until reopened; the gateway continues.
Foreground reconnect normally polls every five seconds. Gateway events retain
the complete history; Android keeps the latest 10,000 meaningful updates per
job. Identical CI polls do not produce duplicate timeline entries. The gateway
admits at most 32 unfinished jobs and 4,096 retained jobs, then requires host
history archival rather than silently deleting receipts.

## Intelligent tool batches

GitHub Chat, Research and Examine use adaptive parallel execution for explicitly
reviewed reads. The initial window is up to five, bounded by the existing profile
limit and a maximum of 20. Errors, throttling and slow responses shrink it;
healthy completions grow it. Writes, questions, context selection and unknown
operations are serial barriers. A failed or deferred prerequisite blocks its
dependent operation. Independent results reach the UI as they complete and
retain model order in the final tool exchange. Existing permission, budget,
checkpoint and full-receipt review boundaries remain in effect.

Gateway-owned MCP conversations can use `gateway_batch` with 1–20 exact
advertised read-tool calls and `depends_on` call IDs. The complete schema/DAG is
validated before dispatch, repeated reads share receipts, and the journal binds
each batch to its principal, job, tool call and connection revision. Only
transient reads retry with bounded backoff; permission and contract errors do
not retry. Native Android tool ownership remains unchanged.

Continuous inference batching is separate from tool batching. Keep one llama
slot initially; benchmark two only for real concurrent inference demand. The
suggested continuous-batching, 1024 batch, 256 micro-batch and flash-attention
settings remain benchmark candidates, not measured recommendations for a
particular GGUF or RTX 5070 Ti. This change does not modify running llama flags.

## HTTP recovery contract

All routes are authenticated and principal-scoped, with optional `/v1` prefix:

| Route | Purpose |
| --- | --- |
| `POST /gateway/engineering/jobs` | Idempotent create with repository, branch, base, objective, model, request_id, chat_id and limits |
| `GET /gateway/engineering/jobs` | List this principal's retained jobs |
| `GET /gateway/engineering/jobs/{id}?after_revision=N` | Snapshot plus up to 200 later events, next_revision and has_more_updates |
| `POST /gateway/engineering/jobs/{id}/cancel` | Stop future work at the exact current revision |
| `POST /gateway/engineering/jobs/{id}/request_changes` | Pause and clear approval |
| `POST /gateway/engineering/jobs/{id}/approve` | Explicit UI action with exact revision, PR and head SHA |

Approval also requires `X-Gateway-User-Action: approve-and-merge`. That marker
does not replace authenticated identity, fresh policy evidence or exact SHA
validation. The coding model receives no endpoint or tool for granting approval.

After a network error, recover the same job/request and cursor. Do not use a new
request ID to retry an uncertain creation. Restore the original gateway/account
if the binding changes. Check permissions and host connectivity separately for
403 responses; batching does not repair authentication or unsupported models.

## Saved chat turns and scoped work

Supported Android clients can send GitHub chat turns through the same supervisor with explicit `read`, `plan` or `implement` scope. Read/plan permit the selected branch to equal the base and cannot publish changes or merge. The Jobs tab and server conversation browser use the canonical conversation and immutable execution binding rather than a current Android room number. See [GATEWAY_CONVERSATIONS.md](GATEWAY_CONVERSATIONS.md) for the additive routes, queued instructions, cancellation confirmation and detached-worker behavior.
