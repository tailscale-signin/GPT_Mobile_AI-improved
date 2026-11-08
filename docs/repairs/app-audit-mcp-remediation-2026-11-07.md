# App audit and MCP remediation — 2026-11-07

## Scope and identity

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Working branch:
`fix/diagnostic-remediation-8597579-2026-11-07`, PR #813. This work began at
`9b0dc9860858b3ef55008723ac63fe992e8c4aca` and incorporates the concurrent
transport/composer refinement `515d9b064eda674feaea1f411b1ee8d4af4a7d0d`.
The base at the start of this work was main
`3ac89ceafd9083f67da30e92f9f93aae27be4600`.

The supplied 120-check audit was produced by main `3ac89ce`, version 0.9.66.6,
debug build 140. Its original 54 passes, one failure, six blockers, ten
unavailable entries and 49 untested entries remain historical evidence. Its
96.3% score correctly describes 26 passing checks among 27 eligible measured
checks. It does not qualify the remaining checks, app-wide reliability or
executed line coverage. No private export, connection name, endpoint, credential
or conversation content is committed with this remediation.

## Implemented repairs

### Discovery authentication and local validation

`withMcpDiscoveryOAuthRefresh` recognizes confirmed HTTP 401 from both the SDK
transport and the modern transport. Only OAuth discovery can enter this helper.
It permits one credential refresh and one subsequent discovery attempt; a
second 401 is terminal. HTTP 403, non-OAuth failures and coroutine cancellation
do not cause that refresh. Tool invocation and uncertain mutation outcomes do
not enter this retry path.

Local endpoint, transport, authentication-type and credential rejections now use
fixed typed validation rules. These contain a safe code and field, with
`local_validation`, `NOT_DISPATCHED` and a correction-required retry decision.
Invalid URLs, rejected credentials and decoding exceptions are not attached as
raw diagnostic payloads. Secret byte buffers are cleared on success and failure.

A failed credential refresh after an already observed discovery 401 retains that
401 and its confirmed request evidence. A later local credential rejection
cannot erase the earlier HTTP exchange. Conversely, a local rejection before
any MCP request cannot inherit a previous server failure's HTTP status or time.

Protocol discovery records the fixed failed validation rule and field, including
requested-version matching, the supported-version list's type, emptiness and
size, and individual version format. HTTP status is retained only from an
observed response. Cached endpoint gates do not manufacture fresh 404 responses
or replace the original failure time. The equivalent cached authentication gate
also reports no new dispatch; an old 401 cannot become a new confirmed request.

### Original observations and saved configuration

MCP health retains the original failure time and a configuration revision built
from saved metadata and opaque encrypted-record revisions. It does not hash or
export decrypted credential content. Unknown historical revisions remain
unknown. Configuration changes invalidate stale recovery authority; the audit
does not attribute a cached error to a new configuration merely because the
connection UID still exists.

An unauthenticated connection ignores any unused retained authentication
reference. Protected endpoint credential records remain independently bound.
Missing required configuration remains distinct from an existing credential
whose revision cannot be verified.

Audit observations retain safe failure phase/category, validation rule/field,
HTTP status, retryability, original observation time and configuration revision.
Display and export use the same allowlists. Unrecognized provider or imported
text is not rendered as a diagnostic code or validation path.

### Audit guidance and explicit repair

The audit has tailored guidance for `MCP_DISCOVERY_SCHEMA_INVALID`,
`MCP_DISCOVERY_CONFIGURATION` and `MCP_DISCOVERY_UNAVAILABLE`, plus local
configuration errors. Protocol-version discovery errors are distinguished from
individual tool argument-schema errors. An unclassified discovery failure does
not become a fabricated timeout, outage or expired-token diagnosis.

Expanded evidence shows the diagnostic code, retryability, failure phase,
original cache time, configuration revision and available protocol details.
Missing cache time/revision is explicitly labeled as missing.

An audit entry opens the exact currently saved connection using its UID. Current
names remain display labels; historical aliases and renamed or replacement
connections cannot redirect the action. Standalone and embedded audit screens
both provide this route. A missing connection retains historical evidence and
has no replacement action.

Opening the repair screen does not check or sign in automatically. The audit's
edit route saves settings without initiating a probe. The separate **Reconnect
and check again** action invokes existing bounded discovery, with local-network
permission handling. OAuth sign-in is a separate explicit action. A successful
discovery check reports catalogue metadata and does not claim tool execution.
Shared connection errors are redacted before they are shortened for display.

Before dispatch and before publishing health, connection checks compare the
authoritative saved row, its opaque credential/configuration revision and the
current check generation. Edits, same-reference credential replacement and a
newer check invalidate an older result. Unknown credential revisions do not
authorize a check. The screen offers **Refresh saved settings** to reload this
local state without sending a probe. The audit's delayed OAuth action likewise
requires the exact accepted saved row when it starts.

### Honest network accounting and qualification gaps

The audit reports attempted network probes, confirmed dispatches and uncertain
dispatches separately. The old persisted `networkProbesDispatched` field remains
compatible as a conservative budget reservation; it is not treated as proof
that a request reached a server. Interrupted and legacy reservations remain
consumed. A pre-dispatch local rejection does not gain confirmed dispatch evidence.

Configuration, local fixtures, discovery, execution and device evidence remain
separate. Optional disabled and uninstalled features retain their original
status. Historical results are not upgraded by new source code, a longer audit
timeout, a generated qualification plan or successful unrelated CI tests.

The exported qualification worklist binds every retained execution gap to the
exact app build, inventory, accepted plan and available configuration revision.
It identifies concrete harness families, readiness, fixture deadlines and a
one-call default for a named target smoke check. It requires expected assertions,
dated harness artifacts, cancellation/recovery evidence and owned cleanup where
applicable. Separate negative cases and cleanup need their own declared bounds.
The worklist grants no execution authority and cannot import a pass into the
audit score. Independently observed service identity is recorded separately;
an undisclosed third-party build remains unknown without discarding otherwise
supported functional evidence or inventing a remote commit.

### Remaining diagnostic-review defects

GitHub diagnostic intent now requires an assistant/answer/conversation subject.
Questions about why CI, a workflow or a job stopped or was interrupted enter
normal repository investigation rather than returning a previous task's saved
explanation or reusing its acceptance state.

After an unusable optional model-authored progress-summary attempt, the narrator
stops making further attempts for that run. Receipt-derived progress and the
main task continue. Successful summaries keep their existing cadence. A new run
can try the optional narrator again. The selected model, reasoning-off control,
192-token summary allowance and main task authority are not expanded.

The previously failing transport/composer work is preserved from `9b0dc986` and
`515d9b06`. Current-head Android CI must qualify the combined changes; canceled
ancestor jobs are not passes.

## Fresh acceptance on the affected installation

The three target aliases in the supplied audit are scoped to that audit only.
They cannot be mapped to private connections from source code alone.

| Historical check | Required next observation | Completion condition |
| --- | --- | --- |
| check-48 / target-11 | Open its current saved connection; correct any typed endpoint/auth/transport rejection; explicitly reconnect | Fresh completed discovery, followed by an authorized representative tool operation with the expected result |
| check-36 / target-7 | Inspect the retained protocol field/rule; correct server or bridge version negotiation; reconnect | Fresh successful negotiation/discovery with no schema gate, then representative execution |
| check-42 / target-9 | Inspect original HTTP status and configuration/protocol rule; correct the actual endpoint/server/bridge cause; reconnect | Fresh successful discovery and representative execution |

Do not loop the general audit to bypass a nonretryable gate. After correction,
perform the explicit connection check and then start a new audit. Keep the new
app source/build, current configuration revision and independently running MCP
or gateway service identity with the acceptance evidence. A build source commit
does not identify the deployed server revision.

## Execution qualification matrix

All 39 historical execution gaps remain separate from discovery. Start with the
25 targets whose configuration passed. The four disabled and ten uninstalled
targets may remain outside the chosen workflow scope; enabling or installing
them is not required merely to improve a score.

| Historical execution checks | Target group | Required behavioral evidence |
| --- | --- | --- |
| 23, 25, 27, 29, 31 | Five saved configured connections | Authorized read with known expected content, argument validation, cancellation and typed failure |
| 34, 37, 40, 43, 46, 49 | Six MCP connections | Fresh discovery, named authorized tool execution, expected content/schema, pagination and cancellation as applicable |
| 53, 55, 57 | Airbnb and Amazon integrations | Representative search/read, response validation, bounded pagination and provider-error behavior |
| 60, 63 | Crawlers and deep research | Qualify only when deliberately enabled; retain research identity, source evidence, cancellation and persistence |
| 65, 67, 69 | Location, GitHub and Health Connect | Applicable permission lifecycle and scoped reads; GitHub additionally requires final requested repository outcomes |
| 71, 73 | Memory and delegation | Persisted retrieval/restore and selected-model delegation with useful final output |
| 75, 77, 79, 85 | News, files, URLs and web search | Known result, actual content, limits, pagination and cancellation where applicable |
| 81, 83 | Google and voice | Qualify only when deliberately enabled; include authorization/permission and interruption lifecycle |
| 87, 89, 91, 93, 95, 97, 99, 101, 103, 105, 107, 109 | Marketplace targets | Installed intended integration, exact package/configuration, representative execution and expected result |

For a necessary write, use an owned disposable fixture and record the exact
precondition, dispatch receipt, resulting state and verified cleanup. A timeout
or lost response requires remote reconciliation before repeating a mutation.

| Historical suite | Required environment and assertions |
| --- | --- |
| 111 — Android shell | Exact APK/device/runtime; startup, recreation, background work, process death, persisted queue and resume |
| 112 — Model providers | Requested/observed profile and model, real generation, first output, useful final text, stream terminal reason, tools, malformed responses, rate limits and cancellation |
| 113 — Local inference | Actual model loading/weights, memory admission, prefill, useful output, cancellation, unload and observed supported backend |
| 114 — Network | Disconnect/reconnect, DNS/connection failure, bounded timeout/retry, partial output and terminal stream behavior; no ambiguous mutation replay |
| 115 — Voice/media | Real permissions, recording, playback, focus/interruption, cancellation and media ownership |
| 116 — Native bridge | Packaged ABI/library load, initialization, ownership/lifetime, memory errors and native-to-Kotlin propagation |
| 117 — Gateway software | Independent deployed identity, actual inference/tools, selected profile, durable jobs, gateway restart and saved-result recovery |
| 118 — Authentication service | Independent deployed identity, login/callback, expiry/refresh, invalid input, replay rejection and real Android handoff |
| 119 — External integrations | Authorized actual requests/responses, pagination, typed failures and owned writes/cleanup when required |
| 120 — Other production | Enumerate the residual source-inventory components; assign named behavioral checks and exact runtime identities before claiming coverage |

At the incorporated `515d9b06` source inventory, `other_production` is concrete:
62 files covering shared model/default/backup/theme DTOs and the Anthropic,
Google, Groq and OpenAI request/response DTOs. The relevant behavioral checks
include `ProviderAttachmentSerializationTest`,
`ProviderToolRequestSerializationTest`, `OutputAllowanceSerializationTest`,
`ChatCompletionTransportTest`, `ProviderToolRejectionTest`,
`GatewayGenerationProvenanceTest`, `SettingRepositoryConfigBackupTest`,
`BackupProtectionSerializationTest`, `ThemeAndBackupDefaultsTest` and
`ThemeHistoryTest`. Run their exact-build results and map assertions to the
corresponding fields and transport behavior. This identifies an executable
starting suite; it does not assert that every DTO field or all 62 files were
instrumented or that historical check-120 has passed.

Complete GitHub-task acceptance requires the requested external result, such as
an exact commit, PR and successful required checks. A few API calls do not prove
that outcome. Durable-job qualification must interrupt meaningful stages,
restart the relevant components and reconcile uncertain mutations. Physical
scrolling, focus/IME, first-load latency, frame stalls, themes/font scales and
screen-reader behavior require actual interaction measurements. Persisted data
qualification requires migrations, restart boundaries, realistic volumes and
privacy assertions.

## Validation record

The following focused suites execute actual production Kotlin with explicitly
inventoried host boundaries. The GitHub suite reproduced six failures before its
repair. The supplemental audit service run also reproduced the unused-auth
revision regression and missing-configuration reason mismatch before correction.

| Local verification | Result | Scope |
| --- | --- | --- |
| MCP transport, recovery, OAuth, validation, diagnostics and revision | 94 passed | 13 test classes; includes queued sign-in, no-refresh-on-403 and interrupted mutation cases |
| Audit core, persistence models, runner, plugin source, export, qualification and pure presentation | 73 passed | Serialization compatibility, honest score/counters, privacy and frozen-plan evidence |
| Audit services | 28 passed | Entire service test class, including cache provenance, local rejection, credential changes and actual gateway route handling with explicit HTTP-client/Android host boundaries |
| Connection ViewModel | 35 passed | Full class, including 11 new saved-row, credential-revision, overlapping-save and stale-result regressions |
| GitHub continuity, task trust, recovery action, narrator and progress | 53 passed | Five test classes; six reproduced regressions repaired |
| Canonical `validate-local.sh fast` | 398 passed | CI/dependency/secret policies, script regressions and resource validation |
| Android regex/resource checks | Passed | 1,284 literal patterns; 77 dynamic patterns explicitly skipped; 30 XML files |
| Changed Kotlin formatting | Passed | All 53 changed/generated Kotlin files |
| Generated metadata and exact release scope | Passed | 2,244 inventoried files across 26 areas; 1,048 exact allowed paths |

Compiled-source hashes are reconciled for focused hosts. Host boundaries include
Android lifecycle/Room integration, settings, telemetry and selected resolver or
provider dependencies; these focused results do not establish live-service,
Android UI or physical-device qualification. The three new full resolver wiring
regressions and Compose navigation/action regressions require canonical Android
execution. The 77 dynamic regex cases are not claimed as statically checked.

Full Android unit tests, audit/UI tests, lint, heap checks, API34/API36 device
regressions, review APK packaging and required verification must be read from
the final published commit. Local source checks and ancestor CI do not replace
those results. No fresh affected-phone or private MCP-server outcome is claimed
by this repository change.
