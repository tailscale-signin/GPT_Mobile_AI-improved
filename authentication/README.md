# Project-hosted GitHub authorization

This service is independent of Gateway 15.0. A user does not operate a gateway to
sign in. GitHub sign-in and API operations require internet connectivity, including
when the AI model runs on the phone.

## Implementation status

The broker implements a confidential authorization-code exchange with S256 PKCE,
durable ten-minute state, HTTPS/origin checks, request size and rate limits,
bounded upstream calls, a broker-only callback, and two-minute opaque return
tickets. Codes, tickets and completed outcomes are encrypted at rest; no raw
credentials or callback query strings are written to logs. It does not register
a GitHub App, deploy a domain, or publish Android verified-link configuration.
The Android bridge implements Custom Tabs, strict app-link and state validation,
encrypted pending transactions, account binding, serialized refresh rotation and
disconnect. Both sides require reconnection after an uncertain refresh outcome.
The existing Android device flow remains available. Public code-flow sign-in is
disabled by default until the project configures its registered client, broker
and callback host. Do not advertise it as released until clean-device sign-in,
refresh rotation and the registered deployment pass acceptance tests.

## Deployment configuration

Register a **public GitHub App owned by this project**, using its registered HTTPS
callback URL. Keep its private key and confidential client secret on the server.
Configure branding, a published privacy policy, support URL and installation setup
in GitHub. Request only metadata read, contents read/write and pull requests
read/write for the initial engineering release; add issues and Actions permissions
only when those tools are enabled. Administration is a separate elevated path.
Changing an installation's permissions requires the relevant account or
organization to approve the change; `can_push` cannot prove Actions access.

Set these server environment variables using the host's secret/configuration
manager, not a committed `.env` file:

| Variable | Value |
| --- | --- |
| `GITHUB_APP_CLIENT_ID` | Registered public client ID |
| `GITHUB_APP_CLIENT_SECRET` | Confidential secret, server only |
| `GITHUB_APP_CALLBACK_URL` | Broker-only HTTPS URL ending in `/oauth/github/callback` |
| `GITHUB_ANDROID_APP_LINK_URL` | Verified Android HTTPS URL ending in `/oauth/github/complete`; defaults to the broker origin with this path |
| `GITHUB_AUTH_PUBLIC_URL` | Public HTTPS broker origin |
| `GITHUB_AUTH_STATE_DB` | Persistent private SQLite file |

Install the reviewed, hash-pinned ASGI dependencies with
`python -m pip install --require-hashes -r gateway/requirements.txt`.
From the repository root, launch
`uvicorn authentication.app:from_environment --factory --host 127.0.0.1 --port 8041 --no-access-log`.
Place an HTTPS reverse proxy in front. Forward the original HTTPS scheme and host
only through a specifically trusted local proxy. Do not publicly expose this
loopback listener or trust arbitrary forwarded headers. Disable request-body,
response-body and query logging at the proxy and any observability integrations.
Android sends secrets only in bounded JSON POST bodies. GitHub sends its code
to the broker callback; that query must never be logged. Every response is
`no-store` with `no-referrer`.

Rotate the client secret in the host's secret manager, restart the service and
verify `/health/ready`. Existing pending transactions expire after ten minutes;
never retain old confidential secrets on phones. Rollback stops this service and
retains the Android device-flow path. No gateway runtime files are modified.

## Client contract

1. Android generates an S256 verifier, a random client-session ID and a digest of
   the selected connection. It stores the pending transaction in `SecretVault`.
2. POST `codeChallenge`, `clientSessionId` and `accountIntent` to
   `/v2/github/oauth/start`. Store the returned state and expiry. The authorization
   URL sends GitHub to the **broker callback**, not an Android activity.
3. GitHub redirects to `/oauth/github/callback`. The broker validates state and
   creates a bound, opaque ticket. Its redirect to `/oauth/github/complete` carries
   only `state` and `ticket`. Duplicate query keys, wrong paths and expired state
   fail before any exchange.
4. Android saves the opaque ticket before POSTing `state`, `ticket`,
   `codeVerifier`, `clientSessionId` and `accountIntent` to
   `/v2/github/oauth/redeem`. The broker atomically claims the exchange. A completed
   result is encrypted and committed before acknowledgment; a lost response can
   be redeemed again by the same bound client without another GitHub code exchange.
5. If the broker loses the upstream response after dispatch, it returns
   `RECONCILIATION_REQUIRED`; it never exchanges that code again. Android retains
   useful pending state and offers explicit recovery/reconnection. After a verified
   outcome, it saves credentials and the observed `/user` identity before clearing
   the pending ticket. Refresh uncertainty still requires reconnecting.

The v1 endpoints remain available for existing exchange/refresh clients. The new
Android flow does not resume a v1 callback transaction: it clears obsolete pending
state and starts the separated callback contract. Existing saved account,
repository, branch, draft and profile state are retained. Rotating the server
secret invalidates pending encrypted outcomes; complete or expire pending sign-ins
before rotation, or reconnect afterward.

For Android builds, configure `GITHUB_AUTH_BROKER_ORIGIN`, the registered
`GITHUB_OAUTH_CLIENT_ID`, and `GITHUB_AUTH_CALLBACK_HOST`. The app captures only
`https://<host>/oauth/github/complete`. The broker-only `/oauth/github/callback`
**must not** match its intent filter. Neither the client secret nor an App private
key belongs in the APK. Missing broker configuration disables this code flow and
retains the existing device-flow alternative; enterprise API connections use their
explicit HTTPS origin and Advanced credentials.

Publish `/.well-known/assetlinks.json` on the Android return host with the actual
application ID and release signing certificate SHA-256. Verify the packaged APK,
not source defaults, using `scripts/qualify_github_auth.py`. The checker validates
its extracted manifest, installed package link-verification evidence and the
server's Digital Asset Links document. A correct document alone does not prove a
successful account sign-in; clean-install consent, process-death recovery and
organization authorization remain device acceptance scenarios.

## Verification

In the pinned ASGI environment, run
`python -m unittest authentication.test_github_broker authentication.test_app authentication.test_ticket_broker`. Tests cover concurrency,
restart, expiry, wrong verifiers, replay, uncertain exchange, redaction, hostile
origins, HTTPS, request limits and rate limiting. Live app registration, consent,
refresh rotation, organization SSO, passkeys and device acceptance remain separate
release requirements.

## Hosted Android issue reporting

The Compact report dialog and Debug Mode prompt send approved reports to
`POST /v1/issue-reports` on a project-owned HTTPS origin. The reporter runs
independently of the OAuth broker. It creates issues only in
`tailscale-signin/GPT_Mobile_AI-improved` and returns the issue number and the
submitted report ID. The public HTTPS intake keeps per-client and global rate
limits; the server authenticates GitHub publication with its dedicated App.

Register a dedicated GitHub App, grant repository `Issues: write` and install
it on the app repository. Set the following in the reporter's environment.

| Variable | Value |
| --- | --- |
| `ISSUE_REPORT_ORIGIN` | Public HTTPS origin, such as `https://reports.example.org` |
| `GITHUB_REPORT_APP_ID` | Reporting GitHub App ID |
| `GITHUB_REPORT_APP_SLUG` | Reporting App slug used to match its issues during reconciliation |
| `GITHUB_REPORT_INSTALLATION_ID` | Installation ID for the app repository |
| `GITHUB_REPORT_PRIVATE_KEY_FILE` | Path to the reporter's server-only RSA private key |
| `GITHUB_REPORT_RATE_KEY` | Secret with at least 32 bytes of key material for hashing client addresses |
| `GITHUB_REPORT_STATE_DB` | SQLite file on a persistent private volume shared by the reporter's workers |

Use the same hash-pinned dependencies as the OAuth broker and run
`uvicorn authentication.issue_report_app:from_environment --factory --host 127.0.0.1 --port 8042 --no-access-log`.
Place the reporter behind the trusted HTTPS reverse proxy. Configure the proxy
to forward the canonical host and scheme, pass client addresses only from a
trusted hop, limit bodies to 8 KiB and keep report bodies out of access logs.
Check `/health/ready` against that origin. The database retains report IDs,
payload digests, cooldowns and issue receipts. The issue body goes to GitHub.
A request with an uncertain GitHub outcome remains pending while the service
looks for the original issue.

### Report context and rollout compatibility

The ready response preserves `service: "gpt-mobile-issue-reporter"` and
`version: 1`, and advertises `reportContextVersions: [1, 2, 3, 4, 5]`. Clients with a
context-bearing report check support for that report's schema and any retained
review extensions before marking it dispatched or sending the POST. An older
service without all required versions has not declared
support: the app retains the approved report and displays a native
service-update requirement. It must not silently strip context or change a
previously submitted payload. Deploy the updated reporter before shipping a
hosted reporting route so current reports and retained legacy reports remain supported.

Exact-model preparation captures extend the closed schema-5 diagnostics with
bounded load/wake states, typed backend failures and hashed operation/profile/model
identities. The updated ready response advertises
`reportDiagnosticFeatures: ["GATEWAY_MODEL_PREPARATION_V1", "REVIEW_COMPARISON_V1"]`. The app requires the first
feature only when the approved capture contains these extension fields (including
their omission declarations). A reporter advertising schema 5 without this feature
does not establish support: the app returns `REPORT_SERVICE_UPDATE_REQUIRED`
before POST, keeps the approved bytes and disables automatic retries. Deploy this
reporter update before distributing these captures through the hosted route.
Native GitHub reporting remains a separately selected route and does not depend
on this service feature; the app never changes destinations or strips fields to
work around an older reporter.

`REVIEW_COMPARISON_V1` declares support for the public report codes
`REVIEW_INPUT_REQUIRED` and `GITHUB_REVIEW_COMPARISON_REQUIRED`. The app checks this
feature when either code appears as the report error, a retained original failure,
or an exact code token in a bounded event, including reports without context.
Older reporters stay blocked before POST with the same service-update requirement.

The optional top-level `context` object has a schema version, a bounded client
occurrence timestamp, a fixed capture-source enum, and `unavailableFields`.
Optional observations use only fixed stage/provider/operation/recovery enums,
typed HTTP status and relative retry delay, dispatch/retry/reconciliation
booleans, known selection-field names, and SHA-256 correlation identifiers.
`unavailableFields` must list every absent optional observation. The service
rejects unknown fields, arbitrary text, malformed hashes, contradictory
availability declarations, and unsupported schema versions. Raw prompts,
messages, URLs, tool arguments, model names and original run/job/call IDs are
not accepted in this context. The existing approved manual description remains
a separate, bounded, redacted user-authored field.

Schema 1 remains unchanged. Schema 2 adds fixed GitHub acceptance stop/recovery
codes and the optional integer fields `requiredCriteria`, `verifiedCriteria`
and `remainingCriteria` (0 through 100,000). Supplied counts must be consistent;
missing counts stay unknown and are never replaced with zero. The app selects
schema 2 only when it has this acceptance metadata without review extensions.
Schema 3 adds the `review_comparison` and `approval` stages and the
`SELECT_REVIEW_BASELINE` and `RESTORE_REVIEW_COMPARISON_EVIDENCE` recovery actions.
New reports use schema 3 when any of these values is present. Versions 2 and 3
share the acceptance-count and exact `unavailableFields` contract. No schema
accepts criterion text.

Schema 4 adds closed local recovery and claim-extraction reasons and their recovery
actions. The public issue code remains `CHAT_FAILED`; `reasonCode` identifies the
specific local recovery refusal or retained extraction failure. An earlier provider
429 remains a separate `originalFailureCode: PROVIDER_RATE_LIMITED` and optional
`originalHttpStatus`, read only from explicit typed interruption fields. Current
dispatch, invocation, retry and remote-acceptance facts are never copied from that
earlier interruption. Checkpoint binding, checkpoint digest and resume-claim IDs
are published only as separately domain-separated SHA-256 hashes. The optional
`ownershipFence` is an integer from 0 through 9,223,372,036,854,775,807. Missing or
malformed observations stay unavailable; raw checkpoint values, claim IDs,
provider messages and free-text recovery details are not accepted.

Version 4 also retains a closed reason for locally rejected tool calls, including
exhausted allowance, duplicate suppression and unavailable checkpoints. Those
reasons require `source: TOOL_RESULT`, `stage: tool_precheck`, `dispatched: false`
and no positive reconciliation requirement. An otherwise unknown tool code may
use the existing `TOOL_FAILURE` category while preserving these non-dispatch facts;
known codes such as `INVALID_ARGUMENTS` stay unchanged. New recovery fields and
reasons are rejected under versions 1 through 3. Version-4 payloads require explicit
version-4 readiness before the dispatch marker or POST; unsupported services do not
receive a stripped or downgraded report.

An earlier app revision used schema 2 for those review extensions. Its approved
payloads remain unchanged: the app requires readiness support for both their
declared version 2 and the version-3 review contract, then posts their original
bytes. An older reporter advertising only `[1, 2]` is blocked before submission;
the report stays retryable after a service update. An endpoint advertising only
`[3]` also cannot accept these retained v2 reports, because it has not declared
support for their wire version. The updated reporter accepts retained extended-v2
payloads as well as v3, preserving existing issue-body digests and reconciliation.
Original v2 acceptance reports still need only version 2 at runtime. Native
GitHub reporting continues to render and hash the exact approved payload without
hosted-service negotiation. Invalid reports rejected with HTTP 422 remain
non-retryable; unknown GitHub outcomes still require reconciliation.

The rendered issue shows captured causal facts and unavailable observations
separately. A missing field does not establish success, non-dispatch or safe
retry. The full JSON request still has an 8 KiB maximum. Legacy reports without
`context`, and existing schema-1 through schema-3 reports, retain their original rendering so
previously queued report digests remain valid.

Successful submissions return `{"reportId":"<original UUID>","number":42}`.
Context-bearing submissions require that matching report ID before the app
marks a report sent. A legacy report without context may accept an older
number-only receipt when `reportId` is absent; an explicit null, malformed or
mismatched ID is never confirmation. Every retry uses the same approved UUID
and payload. A
matching completed report returns its original issue number; changed content
under that UUID returns `409 REPORT_ID_CONFLICT`. Rate limits return `429`, and
upstream cooldowns or uncertain publication return `503`, with `Retry-After`
when known. An uncertain publication only reconciles the original GitHub write;
it never creates a replacement issue automatically. No browser handoff or
alternate unauthenticated publication route is needed.

The app also supports a native authenticated GitHub route. It is enabled by default
(`ISSUE_REPORT_GITHUB_ENABLED=true`) and uses an existing, verified public GitHub
connection with write-capable policy. The dialog displays the selected public
author before Send. Multiple accounts require a choice; no account offers Connect
or review GitHub and Save for later. Saved reports retain the approved account ID,
connection and exact payload. A native create that loses its receipt is never
repeated: automatic and manual retries only search for the original report marker,
exact body and account. Empty or delayed search results remain unconfirmed.
Explicit authentication/permission rejection can be retried after repair because
GitHub rejected that create. Credentials remain in the existing encrypted vault.

The hosted route is optional when native GitHub reporting is enabled. To offer it,
deploy this reporter and its persistent database, then set repository Actions
variable `ISSUE_REPORT_ORIGIN` to the real public HTTPS root. For local builds use
`-PISSUE_REPORT_ORIGIN=…` or the environment variable. Verify read-only readiness with
`python3 scripts/check_issue_reporting_configuration.py --probe-service`.
Never use a placeholder origin: the APK appends `/v1/issue-reports` itself.
Approval pins this service origin so an update cannot reroute an uncertain write.
If native reporting is disabled, both debug/review and release packaging require
the configured service. Only local debug builds can explicitly opt out with
`-PallowUnconfiguredIssueReporting=true`; CI and release packaging reject that
exception. No new service deployment, repository variable change or live issue
submission is implied by passing fixture tests. Validate the service with
`python -m unittest authentication.test_app`. The hosted HTTP tests use a
simulated GitHub publisher.
