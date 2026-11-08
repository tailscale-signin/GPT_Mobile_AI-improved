# Gateway saved-work admission and socket recovery

## Scope and source identity

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Parent branch: `fix/diagnostic-remediation-8597579-2026-11-07` (PR 813).
Isolated working branch: `local/diagnostic-gateway-fixes-2026-11-08`.
Base: `0b5661fbe9652841f2f1f6968bcf5c697db4ba99`.

This change addresses diagnostic review findings 1.1–1.3, 4.1, and the gateway
acknowledgment/incident-classification parts of 5.4. The referenced
capture contains ten `remote_surface_not_admitted` observations. Its reported
Gateway 15.0.0 source begins `ada9fbf3c904`; the version label alone cannot show
that the running PC contains the branch's saved-work route allowlist. No raw
capture, credential, private address, response body, or prompt is stored here.

The branch already admits the identity, conversation, catalog, and command-read
routes for configured remote access. The new server regression invokes the actual
routes behind the real admission middleware and checks both `/gateway/...` and
`/v1/gateway/...`. It preserves key-free LAN access and the existing separate
restrictions on administrative routes and engineering scope.

## Android behavior

`GatewayJobClient` preserves an allowlisted `X-Gateway-Admission-Error` code, or
reads at most 16 KiB of an error document to extract an allowlisted code. Raw
server messages and decoder/transport exception text do not become the public
error or its cause. Unknown codes remain unknown; they are not guessed to mean
that a token is missing.

`GatewayAdmissionException` carries `reason`, `httpStatus`, `wireCode`, `surface`,
`retryability`, `repair`, optional validated source commit/version, and
`phase=PRECHECK` or `RECONCILIATION`. Its `dispatchState` describes the **dependent inference or
action**, not the HTTP read. A received rejection remains a completed GET in
transport diagnostics, while the dependent action can correctly remain
`NOT_DISPATCHED`. A failed read of an existing command receipt instead preserves
`UNKNOWN` and `RECONCILE_ORIGINAL_COMMAND`, because the original submission may
already have been accepted. Existing unsupported-history and expired-cursor catches retain
their typed subclasses. A missing command receipt never authorizes a new command
identity or automatic POST replay.

`safeDetails()` exports only the fixed reason, surface, phase, dependent dispatch,
retryability and repair enums, bounded HTTP status/admission code, and validated
source identifiers. It does not export a response body, URL, token or raw cause.

| Observation | Repair category |
| --- | --- |
| `remote_surface_not_admitted` | Update/restart the intended PC gateway and verify its source and saved-work routes. |
| `scope_not_granted` / engineering scope rejection | Check the existing account's configured jobs/engineering permission. |
| `transport_identity_required` | Check the original connection and configured access mode. Key-free LAN mode remains key-free. |
| `CONTINUITY_IDENTITY_MISMATCH` | Restore the original installation/account and refresh its identity. |
| Unsupported endpoint or incompatible schema | Verify the gateway package/source and required contract. |
| Durable journal unavailable | Restore the PC's durable storage and reconnect to original jobs. |
| Interrupted read | Retry the bounded read; preserve the original saved command for reconciliation. |

Generation availability remains evidence about the selected model. An advertised
conversation capability is now named `conversationSyncSupported`, and independent
action-readiness fields remain unverified by generation polling. The explicit
saved-work diagnostic checks capabilities, identity, conversations, and the Jobs
catalog with GETs under one 30-second deadline. It binds the capability source to
the same installation, process, and principal as the identity response, and binds
both history responses to that same installation/principal. An optional expected
source SHA is compared exactly. Successful history reads do not establish
generation, engineering, or repository-graph readiness, and never authorize a
write.

The existing explicitly requested gateway integration diagnostic now reports
installed source/version and these read contracts before its separate harmless
transport job. Ordinary availability polling does not create a diagnostic job,
load a model, call a tool, or send a completion.

## Socket cleanup and retry ownership

Stale-socket cleanup runs on the I/O dispatcher, including the connection pool's
TLS/socket close. Bounded gateway metadata/result GETs have a dedicated pool;
its eviction cannot evict the ordinary provider/generation/event-stream pool.
Only a proven peer closure triggers idle-pool eviction. Safe reads have one retry
owner and at most two attempts; HTTP rejection, TLS rejection, invalid documents,
and caller cancellation do not receive this retry. Mutation and inference paths
do not acquire a new automatic replay policy.

The model-capacity repository uses the same read pool for its existing
capabilities and `/props` GETs. Its apply-template and synthetic schema inference
POSTs keep the ordinary client and existing behavior. A lost inference response
does not receive a new automatic retry.

The separate `hasExactModelProof` check requires a literal reported model bound
to the same metadata fingerprint before a reasoning-capability action. Legacy
route allocation remains usable when `/props` includes capacity/template data
without a model name, but that inferred name cannot confirm the selected model
for the new capability action. Gateway metadata keeps its stricter explicit
requested/reported model contract.

## Metadata acknowledgment and incident counts

New gateway response events carry one bounded `client_metadata_acknowledgment`
value: `ACCEPTED`, `NOT_REQUESTED`, `REJECTED_BEFORE_PROCESSING`, or
`NOT_CONFIRMED`. A missing or unrecognized acknowledgment is not exported as the
boolean `false`. The client records rejection before processing only when a
recognized admission-middleware header has its documented HTTP status; a bare
403 and a later route-handler rejection do not establish that outcome. The
physical request retains `dispatch_state=response_received`. The matching
server diagnostic projection preserves these values through durable export and
drops arbitrary acknowledgment/admission text.

DEBUG-level StrictMode `NetworkViolation` now counts as actionable network I/O
requiring off-main socket cleanup. Structured identity/admission failures select
the specific source/contract, scope, transport identity, or storage repair;
status alone never identifies a credential fault. Unbound TTS `getVoices`,
`stop`, and `shutdown` warnings retain lifecycle-repair guidance. Fatal evidence
continues to take precedence over these classifications.

## Verify a running PC deployment

1. Use the complete package from the intended reviewed build. Record its exact
   source commit from the package/build provenance. Keep the existing gateway
   settings and durable data; the Android app update does not update a running
   PC engine.
2. Apply the package through the existing desktop update/installation procedure
   and restart the intended engine when its active work permits. For manual
   Python installations, use the complete validated package and the current
   `gateway.py` launcher, as described in `gateway/V14_UPGRADE.md`.
3. Read `GET <configured-base>/gateway/capabilities` through the same connection
   Android uses. Compare `build.sourceCommit` with the intended package SHA.
   `gatewayVersion=15.0.0` on its own is insufficient. Also check the declared
   `gatewayConversations` and `gatewayJobCatalog` contracts and literal feature
   booleans.
4. Through that same connection, read `GET .../gateway/identity`,
   `GET .../gateway/conversations?limit=1`, and
   `GET .../gateway/job-catalog?limit=1`. All must succeed and agree on the
   installation and principal; identity must declare schema/protocol 1. A known
   original command can then be reconciled through
   `GET .../gateway/commands/<original-command-id>`.
5. If a read is rejected, retain the HTTP status and bounded admission code and
   follow its specific repair. Do not add a dummy API key or switch access modes
   to conceal a missing route. Do not replay a lost POST under a new command ID.

These checks establish observed source/contract compatibility at the selected
destination. They do not deploy software, prove that every feature is ready, or
replace physical-phone, real-provider, Windows-installation, and network-path
acceptance. This development task has not measured or changed the user's actual
PC deployment.

## Verification

| Check | Result |
| --- | --- |
| Actual-source Kotlin/JVM group | 177 passed, zero failed; 33 source/test files pinned with no source drift. |
| Actual server routes and durable diagnostic journal | 32 passed, zero failed; 178 isolated gateway package files match the working sources. |
| Kotlin formatting | All 30 changed/new Kotlin files pass ktlint 1.3.1. |
| Android regex validation | 1,316 literal patterns checked, 77 dynamic patterns skipped, zero errors. |
| Resource validation | 33 XML files passed; icon and bundled provenance checks passed. |
| Canonical fast gate | 399 of 400 script tests pass; committed inventory freshness awaits shared regeneration. |

The companion task file records exact focused-test receipts and pending shared
gates. The actual-source host uses real Kotlin/serialization dependencies and
unchanged compiled application dependencies. HTTP fixtures use Ktor MockEngine;
the pool-isolation test uses real loopback sockets. The server tests use actual
admission middleware, route handlers and SQLite journals with external model
execution disabled. A separate source copy regenerates its package manifest for
testing; the shared release manifest remains reserved for integration.

The full Android/Hilt build and physical-phone StrictMode/TLS behavior remain
separate gates. Canonical `validate-local.sh fast` currently reaches 399 passing
script tests and one failure in the committed audit-inventory freshness check.
The task deliberately leaves that generated inventory to the integrating owner;
the integration fast gate must be rerun after regeneration. Resource validation,
regex validation and `git diff --check` pass independently. No PC deployment or
physical network repair is inferred from these controlled tests.
