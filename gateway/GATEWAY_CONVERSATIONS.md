# Saved gateway conversations and job discovery

Gateway 15 extends the existing ordinary generation worker and engineering supervisor with a shared read catalog, saved user-facing conversations and durable command receipts. The gateway owns accepted work. Disconnecting an Android observer does not issue cancellation or take ownership of worker finalization.

These APIs use the gateway's existing admission policy. In `open_network` mode the principal remains `network:shared`; an installation ID is not a credential or an additional access control mechanism. The `/v1` prefix is optional on every route described here.

## Identity and compatibility

`gateway_id` is a random nonsecret identifier committed to the gateway SQLite state directory. New installations reuse the existing engineering installation identity; an already assigned canonical gateway identity is preserved. It survives a process restart. `instanceId` (or `instance_id` in engineering responses) identifies one gateway process and changes when that process restarts. Job generations, engineering revisions and conversation message cursors do not reset merely because `instanceId` changed. `GET /gateway/identity` returns the installation, process and admitted principal identities; the capability response also retains the `gatewayId` alias.

Canonical identities are `(gateway_id, principal_id, job_kind, job_id)` for a job and `(gateway_id, principal_id, conversation_id)` for a conversation. The endpoint URL locates an installation; it does not establish either identity.

Clients may send the paired preconditions `X-Gateway-Installation-ID` and `X-Gateway-Principal-ID` on chat submission, engineering controls/binding/creation, conversation messages/instructions, and ordinary cancellation. Both omitted preserves legacy callers. Partial or malformed pairs return HTTP 400 `CONTINUITY_IDENTITY_INVALID`; a mismatch against the canonical stored gateway ID or authenticated principal returns HTTP 409 `CONTINUITY_IDENTITY_MISMATCH` before admission or mutation. Authentication and job ownership still come from the server's existing admission policy. These headers grant no authority. The accepted header list and top-level `mutationIdentityPreconditions` advertise this check.

The regular capabilities response advertises strict JSON booleans under `features`:

```json
{
  "job_catalog_v1": true,
  "conversation_sync_v1": true,
  "conversation_message_parts_v1": true,
  "durable_detach_v1": true,
  "mutation_identity_preconditions_v1": true,
  "ordinary_conversation_retention_v1": true
}
```

Engineering capabilities also advertise `task_scope_protocol: 1`, `task_scopes: ["read", "plan", "implement"]`, and the conversation/detach booleans. A client must check these before adding the new request fields or routing an ordinary GitHub chat turn through the engineering supervisor. Legacy gateways retain their existing result/evidence recovery paths.

Copying a state directory for disaster recovery intentionally restores its identity. Do not operate two independent writable copies as one installation: this would fork the canonical conversation history. The existing process-wide state-directory lock prevents two writers to the same directory.

## Ordinary chat submission

An ordinary `/v1/chat/completions` request can add:

```json
{
  "model": "selected-model",
  "stream": true,
  "messages": [{"role": "user", "content": "Explain the current design"}],
  "gateway": {
    "client_conversation_id": "stable-client-room-uuid",
    "command_id": "stable-command-id-for-this-exact-payload",
    "profile_id": "original-execution-profile-uuid",
    "store_conversation": true
  }
}
```

The existing `schema` and `budget.totalDeadlineMs` run controls remain supported. `conversation_id` can identify an existing canonical server conversation. `expected_revision` optionally requires an exact current conversation revision. These fields are consumed by the gateway and are never forwarded as model-provider parameters.

The server scopes an inferred client conversation key by the client UUID, execution profile and job kind. Multiple profiles, ordinary generations and engineering runs in one Android room therefore do not overwrite one another's immutable bindings. An explicit `conversation_id` always uses its original profile, model, repository and branch; it is not rebound to the currently selected workspace. Recreated local rooms should keep the canonical server ID.

Negotiated `X-Gateway-Profile-ID` and `X-Gateway-Profile-Name-Base64` headers preserve the original profile's display label. The latter is bounded URL-safe base64 UTF-8 with controls rejected. A conflicting JSON/header profile ID is rejected. Display metadata never grants principal authority, and headers on a history read cannot rebind the saved profile.

Each distinct provider/tool-continuation segment uses its own command ID. A retransmission of the same payload keeps the same ID. Durable admission uses the existing idempotency ledger. Reusing an ID for different content or settings returns a conflict. Client-tool continuations retain the same root job and assistant publication, and save a receipt for the new segment command.

After admission, response headers include `X-Gateway-ID`, `X-Gateway-Principal-ID`, `X-Gateway-Conversation-ID`, `X-Gateway-Command-ID`, and `X-Gateway-Detach-Safe`. A `true` detach header is issued only for committed durable admission. HTTP acceptance of a stream is not published before the admission transaction succeeds.

`store_conversation: false` is mandatory for temporary/private chats and internal helper requests. It prevents their prompt, result, progress events and transcript from entering the durable job/conversation stores. Such a request is not listed in the saved conversation browser and is not given a durable detach promise. Minimal side-effect certainty receipts can still retain identifiers/digests to prevent unsafe replay, but private tool output is omitted.

The optional `X-Gateway-Conversation-Retention: full|none` header supports callers without canonical conversation metadata. `full` requires the original profile ID; absent opt-in defaults to no additional transcript. When JSON `store_conversation` is present, it is authoritative and a contradictory header is rejected before admission. Header-only `none` retains the existing ordinary result-journal policy but creates no saved conversation, title text or message copies; it is distinct from canonical `false`, which also excludes temporary content from the ordinary durable journal. Retention is immutable for an admitted job and included in retry identity. Canonical requests already bind it in JSON, so their existing idempotency receipts survive the header upgrade unchanged.

## Engineering submission and task scope

The existing `POST /gateway/engineering/jobs` accepts the additive fields `task_scope`, `client_conversation_id`, `conversation_id`, `command_id` and `expected_revision`. `command_id` defaults to the existing `request_id`. Execution bindings remain mandatory and bind the exact selected profile/model.

- `read` inspects the selected repository/branch and saves a user-facing answer.
- `plan` inspects source and saves a user-facing plan.
- `implement` retains the existing review, validation, branch publication, draft PR, CI and exact-commit merge-approval workflow.

Read and plan scopes permit the selected branch to equal the base branch. They cannot proceed to implementation/publication phases or invoke a mutating GitHub transport operation. These are deterministic supervisor and transport checks, not permissions delegated to a model prompt. A missing selected branch is rejected instead of silently inspecting a different branch.

`source_branch` and `source_sha` identify the original inspected revision, while `branch`/`working_branch` identify the execution destination and `base` remains the selected PR base. Explicit `implement` at `branch == base` requires a pinned source SHA and derives `codex/gateway-` plus the first 20 hexadecimal SHA-256 characters of canonical JSON `[request_id,repository,source_sha]`. The source ref is rechecked and an unexplained existing derived branch is rejected before model dispatch or publication. Snapshots and command receipts preserve all these fields and the full original `execution_binding`. Follow-ups inspect the last confirmed working commit without replacing the original source binding; implementation additionally requires the working ref still to match that confirmed commit.

Engineering creation saves the user message, assistant publication, canonical conversation and command receipt in the same SQLite transaction as the job. No host job is dispatched before that commit. Existing request-ID retries still return the original accepted job.

## Read APIs

| Route | Result |
| --- | --- |
| `GET /gateway/job-catalog?limit=50&cursor=...&kind=engineering&state=queued` | Unified, scoped projection of both existing job stores. |
| `GET /gateway/conversations?limit=50&cursor=...` | Conversations containing actual saved user-facing history, with original binding and latest job identity. |
| `GET /gateway/conversations/{id}` | Conversation binding, title, timestamps, revision and current job watermark. |
| `GET /gateway/conversations/{id}/messages?limit=100&after_cursor=...` | Changed saved messages after a scoped revision cursor. |
| `GET /gateway/commands/{command_id}` | The original committed receipt after an uncertain acceptance response. |

Job catalog pages carry `schema_version`, `gateway_id`, `principal_id`, `server_now`, `items`, `counts`, `counts_complete`, `has_more`, `next_cursor` and `complete`. Counts cover the permitted inventory rather than only the current page. `complete` distinguishes a final page from an incomplete inventory. Kind/state filters and cursor scope cannot be silently changed between pages.

Each job item contains its job kind and ID, conversation ID when available, short title, original profile/model/repository/branch, normalized scheduling state, engine phase, accepted/queued/started/finished timestamps, queue reason and allowed actions. A queue position is omitted when the server does not have a reliable measured position across the applicable lane. Sorting the catalog does not create a scheduling guarantee.

Timers use server acceptance time, server observation time and terminal finish time. Acceptance includes queue, CI and other waits. `started_at` means execution was actually claimed; it is not substituted with creation time. Clients can interpolate a live display using a monotonic local clock, but must label cached/offline observations and avoid asserting ongoing work without fresh server evidence.

Conversation messages use stable `message_id`, `turn_id`, `publication_id`, role, visible content, status, revision and creation time. An assistant publication is updated in place from queued/running to its retained result. Read pages can contain updates to an already seen message, so clients must upsert by canonical conversation/message ID rather than append duplicate bubbles. The submitted visible user/assistant history is imported once when the server conversation is first created; system instructions, provider reasoning and raw tool arguments/results are not presented as chat messages.

Message pagination uses a snapshot watermark and at most 1,900,000 encoded JSON bytes per page, including escaping and the envelope. Logical messages are stored fully, then served in UTF-8 parts of at most 256 KiB without splitting a code point. Every part includes `logical_message_id`, `part_index`, `part_count` and the SHA-256 of the full logical content. `revision` remains the logical publication revision; multipart physical `message_id` values distinguish each revision and part. A client commits parts and cursor together, validates all part metadata and the full content digest, then projects one logical message. Large messages use bounded readers instead of placing the whole text in a SQLite/Compose row.

Catalog cursors use monotonic admission sequence snapshots rather than wall-clock timestamps, so a later backdated admission cannot leak into an earlier page set. All cursors are installation-key signed and scoped to the authenticated principal/resource/filters. After the last message page, the returned cursor starts the next refresh. A restored older watermark or a publication changed during partial delivery returns HTTP 409 `cursor_expired`: hydrate the snapshot and read the retained transcript again. A different principal, installation or conversation is invalid. Neither case permits re-executing a model request. Snapshot and page `history_complete`, `history_limitation` and `gap_details` remain explicit even on an empty tail; missing parts or publications never become complete history.

Saved conversation, command and catalog reads use a read transaction after schema initialization. A failed new journal write does not require those already committed results to become unavailable.

Old jobs without a saved transcript remain available through the existing evidence/result routes. Their prompts are not invented and they are not listed as full saved conversations. The superseded continuity-v1 schema is detected and imported in one transaction, retaining its original installation/conversation/job/command IDs and an unchanged archive of its old tables. Complete actual user/assistant text is imported with integrity checks; private reasoning and raw tools are excluded. Missing or damaged history is marked incomplete and excluded from the full-history browser. The old wire is not advertised as a second protocol. Old ordinary queue records without a retained payload and proof of no dispatch are not automatically rerun.

## Follow-up messages and instructions

`POST /gateway/conversations/{id}/messages` supports two explicit command kinds. Both require engineering authority, a stable `command_id` and exact `expected_revision`.

An active engineering job accepts `kind: "instruction"` with `content`. The instruction is committed once and remains pending until a safe worker checkpoint. Instructions cannot be injected into an already dispatched operation, cannot change task scope or execution binding, and cannot authorize a merge. An instruction encountered before a later write requires review when it cannot safely be applied to the current phase. An instruction arriving during terminal completion is retained visibly for follow-up rather than silently discarded.

Paused `NEEDS_INPUT`/`MERGE_READY` and `CANCELLING` jobs reject new instructions before admission. An explicit revision-checked new message may supersede an unleased paused job atomically, clearing its old merge approval and preserving its operation receipts, confirmed head and bounded visible context. Active or cancelling workers cannot be replaced this way. A queued instruction does not silently restart paused work.

A new turn uses `kind: "message"`, `content`, and an `engineering_request` containing the ordinary validated job fields and the original exact execution binding. Rechecking the selected configuration must preserve that binding, including its original privacy scope on another device. The immutable conversation repository, branch, profile and model must match. Creating a second concurrent job in the same conversation is rejected; use an instruction for active work. A successful message creates a distinct job attempt while retaining the canonical conversation.

## Queue and process ownership

The shared catalog is a read projection. There is no third model executor. Engineering continues through its existing durable serial supervisor and checkpoint/operation journals. Ordinary generation keeps its existing single-flight worker and inference scheduler.

An ordinary durable admission records its pending payload, deadline and queue intent before it can acquire service capacity. A full admission queue is rejected before acceptance. An already accepted job waits for worker capacity, with cancellation and its admitted deadline, rather than being failed merely because another worker is busy. The admitted upstream/model binding is rechecked before first dispatch; a changed route is rejected without moving the request to that provider. Its admitted deadline also applies while waiting for the existing model scheduler.

On gateway restart, only a committed queued record with proof that no worker dispatched is eligible to execute. The payload is removed and dispatch is recorded atomically before model/tool execution starts. A dispatched ordinary job interrupted by gateway restart is conservatively marked failed; the server does not blindly rerun possibly side-effecting work. Engineering keeps its existing per-operation reconciliation and exact approval checks.

Both streaming and non-streaming finalization belong to the worker rather than its HTTP observer. Losing an Android process, closing a view, cancelling an ASGI observer or hitting an Android foreground-service timeout does not itself send an explicit server stop.

A worker whose terminal commit failed stays discoverable even after the normal completed-worker TTL. Later stream/non-stream retries receive an uncertain durable-commit error and cannot replace it with another execution. A successfully committed result evicted from memory is rehydrated under its original generation before an idempotent retry is served.

An explicit engineering stop of an actively leased job first returns `CANCELLING`; the worker confirms `CANCELLED` when it stops. Unclaimed queued jobs can cancel immediately. A cancelling job cannot accept further instructions. Previously dispatched external operations keep their recorded or uncertain outcomes; cancelling a gateway job does not assert that those external effects were undone.

A client-owned tool handoff remains `waiting_for_client_tool`, shown under needs attention. It is retained and resumable, but does not falsely claim that the gateway can execute a phone-only tool or use a phone-only credential while the phone is absent.

Readiness responses distinguish current server time from the selected-model probe's original `observedAtMs` and monotonic `observationAgeMs`. Serving cached evidence does not make its observation fresh. The bounded readiness cache also retains its transport object so a reused Python object ID cannot lend a new connection old evidence.

## Retention and validation

Active, queued and waiting records are pinned independently of terminal cache pressure. Ordinary terminal job/event retention may expire the execution cache without deleting the saved conversation. A retained result tombstone produces HTTP 410 instead of permitting an old command to be treated as a new generation. Conversation history remains scoped by the original admitted principal and engineering permission; revocation never reassigns it to another identity.

Regression coverage in `tests/test_gateway_conversations.py` and `tests/test_gateway_v14_continuity.py` exercises real SQLite transactions and ASGI routes, including schema-import rollback, bounded multipart integrity, explicit retention gaps, signed cursor recovery, pinned-source checks, lost-response replay, one assistant publication, full visible history import, scoped catalog paging, private chat exclusion, process identity changes, durable queue execution after phone detachment, generation-fenced worker finalization, restart dispatch proof, active retention, read-only task transport denial and authorization isolation. Existing jobs/engineering tests continue to cover strict journal failure, client-tool handoff, independent supervision, operation reconciliation, event paging and exact merge approval.

These host tests do not constitute physical Android force-stop, Windows installer, LAN/firewall or real-provider qualification. Record those observations against the exact gateway package and APK before making corresponding release claims.
