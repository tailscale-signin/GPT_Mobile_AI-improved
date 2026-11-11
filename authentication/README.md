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
`tailscale-signin/GPT_Mobile_AI-improved` and returns the issue number.

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

Set the GitHub repository Actions variable `ISSUE_REPORT_ORIGIN` for the
published APK workflows. For local builds, pass the HTTPS origin as the Gradle
property `-PISSUE_REPORT_ORIGIN=…` or environment variable `ISSUE_REPORT_ORIGIN`.
Builds without an origin show the availability message in the report dialog.
Validate with `python -m unittest authentication.test_app`. The hosted HTTP
tests use a simulated GitHub publisher.
