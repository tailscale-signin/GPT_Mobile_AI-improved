# Gateway availability and persistent conversations

Implementation task: `gateway-persistent-jobs-2026-11-07`.

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Base and working branch: `fix/diagnostic-remediation-8597579-2026-11-07`
- Initial implementation base: `3c455ff7573fc2a3dc84ff4983f5593991b246e0`
- Integrated concurrent branch fixes: `2ddeff93db245b6571d2fde1bd4fd007faec8e77`
- Integrated concurrent gateway implementation: `5acf0eaed06ed34d7972752e40c8ffd733eacea3`
- Integrated recovery and identity hardening: `446f43e396c28cece0f2e6f5d6bfb2cb40bdcf5e`
- Preserved Room fixture cleanup: `7b346cc9cc0388af9b647d617f89ab8244a2be97`
- Preserved native-font fixture correction: `0582f3428736261ee0399e52fc9be4ce4c7db10c`
- Design source audit: `515d9b064eda674feaea1f411b1ee8d4af4a7d0d`
- Intent: implement model-specific online gateway promotion, a gateway-wide Jobs
  tab, a Gateway conversation browser between Chats and Favorites, persistent
  conversation reconnect, and lifecycle/durability repairs.

## Execution contract

The gateway owns execution after durable admission. Android owns observation and
explicit user controls. A phone lifecycle event never grants remote cancellation
or permission to replay a prompt. Reconnect resolves the original command, job,
conversation, principal and execution binding.

The selected model/profile remains fixed. A gateway process epoch invalidates
connection freshness but does not reset a durable job cursor. Queue acceptance
precedes worker admission: accepted work waits for capacity. Ordinary generation
and engineering keep their existing execution engines and control boundaries.

## User experience

Fresh readiness for the selected model promotes a gateway profile within the
current filters. Its themed name fades slowly; a steady green dot and Online
label communicate availability. Reduced motion keeps the name static.

The profile list uses verified readiness for the exact selected model and
authenticated route, with a 10-second observation cadence and a 20-second
freshness window. A cached success is not refreshed merely by reading it again.
At most two probes run concurrently, and subscriptions stop with the screen.
Rows do not move under a touch, scroll, or keyboard focus; accessibility users
can explicitly apply an updated order. The name uses a six-second opacity cycle
only when the faintest frame preserves text contrast. The green indicator and
Online label remain steady.

After a successful connection, the Home menu shows **Gateway** between **Chats**
and **Favorites**. This destination queries the server's conversation inventory,
supports multiple configured gateways and pagination, and opens the canonical
remote conversation. A later disconnect keeps already loaded history visible
with its last-sync state. Connection availability and readiness for a new model
request are separate: retained conversations remain useful while the gateway is
draining or the selected model is unavailable. Shared network-mode identities
are labelled as a shared gateway account.

Configured GitHub gateways expose Chat, Jobs, Status and Settings. Jobs retain
cached rows offline, group running/queued/attention/recent work, and show total
elapsed time since acceptance. Stale observations are labelled rather than
presented as confirmed current execution. Opening a job retrieves its saved
conversation; it never creates another job.

Normal Send uses a supported host workflow with explicit read/plan/implementation
scope. A phone-only tool retains its pending handoff and reports Waiting for this
device. User Stop is explicit and distinguishes requested from confirmed
cancellation. A gateway shutdown follows safe checkpoint recovery and does not
claim that an interrupted model computation continued.

Jobs also provides an explicit Stop dialog for work whose original submission
reply was lost. The dialog reads the original account and execution identity
before offering cancellation. A requested cancellation offers a read-only
status check; an unknown result offers an explicit check-and-retry action.
Observers never retry Stop or resubmit a prompt automatically.

## Admission and recovery

1. Android persists the user message, assistant slot, run and ownership fence.
2. Engineering admission stores the immutable execution selection and command
   before dispatch. Ordinary generation reserves its exact serialized command ID
   before its model POST. A failed local reservation prevents dispatch.
3. The gateway atomically saves admission, the actual conversation transcript and
   the job receipt before acknowledging durable work. Capacity waiting is a saved
   state; closing the observer does not remove accepted work from its queue.
4. Android associates the returned gateway, principal, conversation and command
   IDs with the original local turn. A lost response is reconciled by reading
   that command ID; an observer never resends the prompt.
5. Transcript imports deduplicate stable message/publication IDs and respect the
   original run's ownership fence. A live stream or a newer generation keeps
   ownership of its assistant slot. Ordinary generation recovery retains its
   existing GitHub/Combined evidence checks before claiming task completion.

A server conversation has one local mirror per canonical gateway/principal/
conversation identity. Opening a row does not replace its repository, branch,
profile or model with the currently selected workspace. Imported engineering
conversations can continue through the original server message endpoint without
inventing a local GitHub context. Requests that explicitly prohibit commits do
not enter an implementation workflow that creates commits.

Source and working branches remain separate. An implementation requested from
the default branch may use a deterministic gateway working branch only when the
original full source commit is pinned. Android verifies that exact derived name
and its source proof in both the initial receipt and lost-reply reconciliation.
An explicitly selected non-default working branch must match exactly.

Temporary chats explicitly send `store_conversation:false` to a compatible
gateway. They do not create durable transcript records or appear in the browser.
Internal helper/reviewer calls do not become visible conversation turns. A cold
Llama/Ollama connection negotiates this contract even when diagnostics are off;
an explicit unsupported endpoint keeps direct-inference behavior.

## Server API

| Surface | Purpose |
| --- | --- |
| `GET /gateway/job-catalog` | Principal-scoped running, queued, attention and recent jobs, canonical conversation IDs, counts, timestamps and opaque pagination. |
| `GET /gateway/conversations` | Saved server conversations containing actual user history. |
| `GET /gateway/conversations/{id}` | Immutable conversation binding and current revision. |
| `GET /gateway/conversations/{id}/messages` | Cursor-based transcript deltas with stable message and publication IDs. |
| `GET /gateway/commands/{id}` | Read-only reconciliation of a previously reserved command. |
| `POST /gateway/conversations/{id}/messages` | An explicit new message or instruction, bound to the original conversation and expected revision. |
| `POST /gateway/engineering/jobs` | Durable engineering submission with `read`, `plan` or `implement` scope. |

The same routes accept the existing `/v1` prefix. Capabilities advertise
`job_catalog_v1`, `conversation_sync_v1`, `durable_detach_v1`, and engineering
`task_scope_protocol:1`. Ordinary admission adds a `gateway` metadata object to
the existing chat-completions request only after negotiation. Response headers
retain the existing job ID and add gateway/principal/conversation/command IDs
and an explicit detach-safe receipt. These IDs identify saved work; they do not
grant authority to read or control another account's jobs. Paired installation and
principal headers add verified mutation preconditions; a mismatch is rejected
before admission or control. Negotiated retention headers and canonical request
metadata use one privacy rule, including visible synthesis and temporary/helper
exclusions. Contradictory retention signals are rejected before scheduling.

The gateway stores an installation identity independently of its per-process
instance ID. Active and waiting ordinary jobs are pinned against result-cache
eviction. Terminal result/event retention and durable conversation history are
distinct: expiry of a replay/result cache does not erase the saved chat history.
Server restart recovery replays only work known not to have been dispatched;
uncertain side effects retain their existing reconciliation and approval gates.

## Android persistence and lifecycle

Room schema **51**, with upgrade paths from **49** and both prior **50** layouts, adds normalized gateway jobs, conversations, messages,
engineering bindings/events, submissions and migration receipts. Existing
engineering bindings migrate from feature preferences with a checksum marker;
the preference copy is cleared only after the transaction succeeds. Remote
transcripts remain cached independently of local presentation and retain their
pagination cursor until every page is imported. Version 51 adds bounded transcript
parts and recognizes both concurrent version-50 layouts. Verified identities and
original local bindings migrate; ambiguous legacy rows remain archived as retained
evidence and require a read-only server reconciliation. The published version-50
schema remains byte-for-byte unchanged. Large transcripts use 256 KiB UTF-8 parts
with a full-message digest; the ordinary chat view remains bounded and the saved
transcript reader retrieves larger messages in pages.

Retained conversations cannot silently switch to a new account, endpoint, profile
or model. Send validates the original binding before consuming the draft and
again before persistence/dispatch. Retry cannot replace a saved gateway command.
Legacy transport-only job metadata does not falsely claim canonical conversation
ownership or lock ordinary and Combined chats.

The foreground service timeout interrupts local observation instead of invoking
the user's Stop action. Explicit Stop sends a server cancellation request and
reports whether cancellation was requested, confirmed, already terminal or still
unknown. Closing a screen, losing connectivity, timing out an Android service,
or superseding a local observer never implies remote cancellation.

Both the Android client and the gateway package must be updated to expose the
complete experience. Older gateways keep their existing inference behavior and
retained evidence; the app does not claim complete remote history or autonomous
execution when the corresponding capability is missing.

## Delivery evidence

The final canonical gateway gate passed all 51 isolated suites: 619 Python test
cases (618 passed and one existing no-LAN-interface skip), plus seven passing
JavaScript browser checks. The 50,000-event diagnostic stress test completed 782
commits. Gateway 15.0.0 package validation covers all 177 source files. The real
HTTP capability fixture contains 32 top-level fields and 98 features; its gateway
and Android copies are byte-identical.

The final host gate (`bash scripts/validate-local.sh fast`) passed all 400 tests,
CI and dependency policies, secret scanning, resource validation (33 XML files),
icon/provenance checks, and the Android regex scan (1,305 literal patterns; 77
dynamic patterns explicitly excluded). Ktlint 1.3.1 passed the changed Kotlin source and test files, including the
separately adapted backup and DAO fixtures. Exported Room schemas match
the committed version-51 migration history.

The final Android KSP/KAPT generation pass, production Kotlin/Java compilation
and unit-test source compilation succeeded. The clean focused JVM/Robolectric
run passed **652 cases across 108 classes**, with zero failures, errors or skips.
All case identities from the preceding run were retained. Coverage includes
submission and cancellation, discovery and account isolation, server history and
job controls, Room migrations and backups, process-recovery boundaries, prompt
queues, composer lifecycle, profile ordering, accessibility and large-text UI.

The managed validation host preloaded the project's pinned Byte Buddy 1.18.2
agent at test-JVM startup because dynamic self-attachment is unavailable there.
This external test configuration changes no production source or dependencies.
Fixture corrections retain the assertions: queue helpers await accepted Send,
engineering capability checks select their explicit original connection, metadata
checks establish a stable instance before testing cache reuse, and the existing
large-text regression uses native font measurement.

The completed gates exercise HTTP and worker lifecycle behavior, Android
persistence and UI behavior, and controlled provider/GitHub fixtures. They do not
establish
physical Android process-death, OEM lifecycle, real-provider, installer, LAN/firewall
or frame-pacing acceptance. Both the updated Android app and gateway package are
needed for the complete experience.

Legacy schema-50 archive tables remain retained. A backup
containing those archive tables is refused before any restore changes if the
fresh destination lacks their schema; canonical conversation and transcript-part
backups remain portable. Unknown legacy execution payloads require server
reconciliation and cannot be resubmitted automatically.
