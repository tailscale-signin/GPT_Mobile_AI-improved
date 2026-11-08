# Diagnostic and GitHub integration follow-up

## Scope and preserved work

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Branch: `fix/diagnostic-remediation-8597579-2026-11-07`; PR 813.
This follow-up implements the supplied November 7 diagnostic findings and the
additional fixture, CI catalog, pagination, and Android stub-execution requests.
It extends the earlier MCP audit and gateway continuity implementation.

The integration preserves the concurrent backup migration correction at
`bcb72647703b8840ef4ae869804f90399bd0c97f`, the gateway/account-authority
checkpoint at `ba6c275291fd0c4721866aec1f7d1cc741d58705`, and the earlier
gateway-wide Jobs, canonical conversations, stable installation/account identity,
Room 51 persistence, queue ownership, availability ordering, and lifecycle work.
No release, merge, PC installation, account change, or live fixture mutation is
performed by this source change. Optional disabled or uninstalled plugins remain
outside the user's qualified scope until explicitly configured and exercised.

The original JSONL was examined without committing it. It contains 3,324 valid
records: 1,383 primary events, 1,936 continuations, and five duplicate-reference
batches. Private prompts, tokens, account/repository identifiers and unrestricted
tool payloads are not copied into this report or new public diagnostic fields.

## Corrections by finding

| Finding | Source behavior and acceptance boundary |
| --- | --- |
| 1.1: deployed identity endpoint denied | Existing server admission is preserved and covered by real middleware/route tests. Source/version compatibility checks identify the intended package. Updating the user's PC still requires installation and restart of that exact package. |
| 1.2: generic 401/403 and queued closure | Bounded typed admission causes distinguish unsupported surface, scope, identity, storage, configuration and transport. Queue records retain cause, phase, local persistence, remote acceptance, repair and dispatch certainty. Existing uncertain commands retain reconciliation ownership. |
| 1.3: generation Online mistaken for saved-work readiness | Generation, saved conversations/Jobs, engineering and graph are separate contracts. Explicit saved-work GET qualification binds source, installation and principal across identity, conversation and catalog reads. Polling does not start a job or model. |
| 1.4: unsupported strict reasoning reaches Send | Shared exact-model capability qualification runs before queueing strict LLAMA On/Off requests. Unsupported and unverified states keep the draft and offer settings, Recheck and explicit Auto. Neither rechecking nor returning from settings sends the draft. |
| 1.5: helper narration forces Off | Helper policy preserves the user's explicit setting. Auto may use a verified short-response control; otherwise it remains Auto. Narration stays on the selected profile, and factual execution receipts remain available when narration cannot run. |
| 1.6: unchanged deterministic retries | Recovery requires a relevant configuration change or fresh supported capability evidence. Proofs are scoped to the selected model, route, credentials, mode and network epoch and expire after 20 seconds. Frozen Combined requests keep their original setting and saved contributions. |
| 2.1: bound receipts do not deduplicate | Canonical action identity includes account/connection, repository, action, resource and relevant revision. Actual repository-bound receipts are used by planning and recovery. The duplicate-mutation guard remains intact. |
| 2.2: live read after fixture deletion | Existence-dependent reads complete before cleanup and retire after deletion. Cleanup uses exact-ref absence, not another DELETE. An uncertain deletion is reconciled through the existing read-only context recovery action. |
| 2.3: rejected duplicate erases cleanup | Cleanup proof remains attached to the exact fixture instance. A later duplicate rejection cannot supersede proven cleanup. Changed/recreated resource identity requires new evidence. |
| 2.4: incomplete acceptance hidden by digest | Bounded per-criterion diagnostic records retain state, evidence classes/counts, typed blocker, next action and digest. Historical criteria missing from the uploaded export are not invented. Successful API calls do not substitute for complete acceptance. |
| 2.5: repository graph unavailable | Graph failures expose bounded prerequisites and preserve commit qualification. Native reads remain useful without claiming graph coverage. Actual host configuration/storage/service repair still requires a fresh graph result. |
| 3.1: unusable Combined writer passes | The actual repository qualifies the writer's exact route and controls before opening the work budget or contributor fan-out. Authority is captured before reading planning policy and checked after awaited qualification. A policy or profile edit cannot qualify an older snapshot. |
| 3.2: unsent ordinary request retains reservation | A trusted local pre-inference rejection is propagated through request preparation and accounting. It releases tokens and monetary reservation only when every physical attempt is proven unsent and no execution evidence exists. Missing receipts alone remain uncertain. |
| 3.3: repeated small contribution caps | Initial contribution allowances account for accepted scope, verbosity/research and reasoning needs while honoring profile, turn and hard user ceilings. Continuation preferences and participant identity are preserved. This is not a promise that every output avoids truncation. |
| 3.4: insufficient provider credit | The affected profile receives a billing-specific action. Other contributions are retained. No provider substitution, credit purchase or unchanged automatic retry is introduced. |
| 4.1: recovery closes sockets on main | Pool cleanup runs on the I/O dispatcher. Bounded metadata/result GETs use a separate pool; apply-template, inference and mutations retain their existing replay policies. Safe reads have one retry owner. |
| 4.2: Jina DNS and separate cancellation | Existing per-connection DNS state, bounded backoff and network-change recovery are preserved. The documented endpoint remains unchanged. A catalog-owner cancellation is not relabeled a transport failure. Actual DNS repair requires the affected network. |
| 4.3: recognition service cannot start | API 33+ qualifies the intended recognition request and locale before listening. Language installation, unsupported configuration, service failure and permission are distinct. The 5/15/30-second cooldown and explicit online-recognition consent remain. |
| 4.4: stale TTS engine | Null/failed voice catalogs retire the stale engine; a valid empty catalog remains valid. Generation guards, explicit reinitialization and idempotent cleanup retain text/captions and prevent old callbacks restoring a retired engine. |
| 5.1: rendering stalls | Existing sampling, conflation, off-main preparation and bounded rendering remain. Review found no additional reproducible renderer defect for a speculative patch. Physical release/profileable frame measurements remain necessary. |
| 5.2: oversized accounting coroutine | Stream-event accounting, receipt construction, settlement and persistence are extracted from the collecting coroutine. The packaged DEX gate now explicitly requires and measures the actual ledger coroutine and accounting stages. Final APK measurements are required. |
| 5.3: checkpoint contention | Contributors share a checkpoint write gate. Replaceable progress coalesces while waiting; terminal drains have priority, and cancellation transfers rather than leaks a permit. Existing database ownership and terminal guards remain authoritative. |
| 5.4: lost diagnostic certainty | Typed phase/cause/dispatch evidence reaches terminal records; per-criterion acceptance is exported. Structured admission failures and DEBUG StrictMode network violations are actionable. Metadata acknowledgment distinguishes accepted, not requested, rejected before processing and not confirmed. |
| 6: privacy and recovered conditions | Personal-data restrictions, selected-profile routing, mutation uncertainty, duplicate-action protection and explicit Stop semantics remain. No OOM, ANR, crash, GPU defect or accepted-job loss is inferred from this capture. |

## Additional GitHub integration requests

### Fixture ownership and read-only recovery

Deletion tracking distinguishes pending, completed and verified cleanup. Restored
pending cleanup first proves fresh access to the exact immutable origin commit
under the original account/repository, then reads the exact disposable ref. Only
that owned absence may be an expected teardown 404. A generic missing ref, changed
account, inaccessible repository, wrong SHA or failed read remains blocked or
failed. Recovery never re-enters DELETE. Both reads retain normal transport,
scheduler, rate-limit and diagnostic accounting.

The real commit tool's argument schema now advertises its implemented owned
`delete_fixture_branch` operation with the required branch/head/origin fields.
Tests invoke the actual bound facade and argument contract, rather than supplying
recipe-only receipts that would miss repository enrichment or schema rejection.

### Disposable CI workflow

`.github/workflows/integration-test.yml` is a manual disposable fixture with a
read-only repository token, exact disposable-ref and commit checks, bounded logs,
short artifact retention and controlled success, first-attempt failure, and
cancellation-window modes. It does not check out user code or use repository
secrets. Its receipts explicitly say that application acceptance and physical
device execution were not tested. Adding the file does not prove that a live
account has permission to register, dispatch, read, rerun or cancel the workflow.

### Catalog and pagination

Discovery binds the authorized catalog to its version and access scope before
using a complete denominator. CI capability enumeration includes supported
aliases; it does not assume the historical denominator of twelve. Recent
issue/release sampling is bounded, continuation filters retain exact scope, and
selected-revision reads receive priority. Existing page/tool limits are retained.
Unvisited pages remain explicit incomplete coverage; summaries are not treated
as full enumeration.

### Android execution bridge

The two instrumented bound-transport cases use the actual Android tool facade,
schema validation, repository binding and GitHub client over Ktor MockEngine.
They cover a bound read and deletion followed by read-only restart reconciliation.
The strict device acceptance runner selects their exact methods and verifies
retained runtime/source evidence. Records are labeled `CURRENT_DEVICE_STUB_EXECUTION`
and `live_account_execution=false`, with the installed build revision, SDK/device,
monotonic elapsed time and a separate synthetic repository revision.

Executing their bodies in a host test is useful regression evidence but is not an
Android device execution claim. A device/emulator run and its retained artifacts
are required before that part of qualification is marked complete.

## Reasoning and dispatch invariants

The capability service's 20-second window owns freshness. Once it expires, the
network probe invalidates the longer-lived capacity/template cache and performs
fresh capability/metadata reads. A reported model and the same fingerprint are
required before strict controls are qualified. A template plus an inferred model
name can still describe legacy route allocation, but cannot renew selected-model
readiness. Capability checks never create a job, load a model or generate text.

Strict reasoning is qualified before an optional schema inference probe. The
local rejection type is created at that trusted boundary. A provider error whose
text happens to contain the same reason code does not establish no dispatch.
Earlier ambiguous attempts, generated output, tool calls and measured usage
prevent a later local rejection from erasing a reservation or execution evidence.
Gateway command-lookup failures retain uncertainty and the original command ID.

## Verification and remaining deployment qualification

At the source publication checkpoint, the following local checks completed:

| Group | Result |
| --- | --- |
| Gateway admission/network actual-source tests | 177 passed. |
| Accounting, reasoning, contributor budgets and checkpoints | 132 passed, including fresh model/template replacement after expiry. |
| Integrated repository, providers, coordinator and cancellation | 157 passed, including the actual writer-before-fanout and configuration-race paths. |
| UI, settings, Send/recovery and speech/TTS | 83 passed; one additional API 32 fallback case compiled but requires the unavailable local framework. |
| GitHub fixture/catalog/acceptance and Android stub bodies | 251 passed; ordinary schema remains 997 estimated tokens under its unchanged 1,000-token gate. |
| Canonical gateway gate | 629 Python cases: 628 passed and one environment skip for no bindable LAN IPv4 interface; seven browser cases passed. All 178 packaged source hashes remained stable. |
| Canonical fast checks | 404 passed, plus 1,328 literal regex checks and 33 resource XML checks. The 77 dynamic regexes remain outside static evaluation. |
| Formatting and scope | 122 changed Kotlin/Gradle files passed; exact allowed paths, release notes, architecture and 2,306-file audit inventory validated. |

Focused test receipts are recorded in the companion task file. These groups have
overlapping dependencies and must not be added into a single coverage percentage.
The integration additionally requires generated Android/Hilt/Room compilation,
complete current-source unit tests, the strict device runner, packaged DEX gates,
server package validation, formatting, resource/regex checks and regenerated
architecture/audit inventory. Pending or cancelled checks are not passes.

The original 96.3% audit score remains a score of 26 passing measured checks out
of 27 measured checks. It does not qualify all 120 checks, the 49 untested rows,
or the source inventory as executed line coverage. The original three MCP
connections require fresh discovery and representative authorized invocation;
run-local aliases must be resolved through the audit's saved connection names.
The 25 configured integration targets and ten external suites still need their
own required behavioral evidence, tied to exact app and independent service
identities. Disabled/uninstalled optional features are configuration scope.

For the user's PC, verify the package manifest's exact source commit, preserve
configured key-free LAN mode, restart the intended gateway when active work
permits, and run the saved-work contract check from the same phone connection.
Identity, conversation and catalog reads must agree on installation/principal and
the intended source. Then qualify selected-profile generation, required tools,
durable jobs and explicit cancellation under disconnect/restart conditions.
Physical speech/TTS services, private accounts, billing, DNS and release-build
frame latency cannot be repaired or certified by source-only fixtures.
