# Project-hosted GitHub authorization

This service is independent of Gateway 15.0. A user does not operate a gateway to
sign in. GitHub sign-in and API operations require internet connectivity, including
when the AI model runs on the phone.

## Implementation status

The broker implements a confidential authorization-code exchange with S256 PKCE,
durable ten-minute single-use state, HTTPS/origin checks, request size and rate
limits, bounded upstream calls, and no credential journaling. It does not register
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
| `GITHUB_APP_CALLBACK_URL` | Exact registered Android HTTPS app-link callback |
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
The broker itself accepts secrets only in JSON POST bodies and sends `no-store`.

Rotate the client secret in the host's secret manager, restart the service and
verify `/health/ready`. Existing pending transactions expire after ten minutes;
never retain old confidential secrets on phones. Rollback stops this service and
retains the Android device-flow path. No gateway runtime files are modified.

## Client contract

1. Generate a fresh random 43–128 character verifier on Android. Encrypt it in
   `SecretVault` with the selected connection/account and callback configuration.
2. POST `{"codeChallenge":"<base64url SHA-256 verifier>"}` to
   `/v1/github/oauth/start`. Store returned `state`, `redirectUri` and expiry in the
   same encrypted transaction. Open only the validated GitHub authorization URL
   in a Custom Tab.
3. Accept only the exact registered, verified HTTPS callback. Reject duplicate
   query keys, mismatched/expired state and unexpected callback paths. Cancellation
   or denied consent deletes the pending verifier. Never log callback URLs.
4. POST `state`, `codeVerifier` and `code` to `/v1/github/oauth/exchange`. The server
   binds the verifier to the original challenge and consumes state atomically
   before exchanging the code. A timeout requires a new sign-in, not code replay.
5. Encrypt the returned credentials, verify `/user` account identity and bind
   repository selection to that account. Do not resume writes until the original
   operation and immutable approval have been reconciled.

For the Android build, configure `GITHUB_AUTH_BROKER_ORIGIN`, the registered
`GITHUB_OAUTH_CLIENT_ID`, and `GITHUB_AUTH_CALLBACK_HOST` as project build properties.
The callback is `https://<host>/oauth/github/callback`; it must exactly match the
registered URL. Neither a client secret nor a GitHub App private key belongs in
Android build properties. Omitting the broker origin disables the public callback
activity and retains the existing device-flow option.

Publish `/.well-known/assetlinks.json` on the callback host with the real Android
application ID and release signing certificate fingerprint. Test verified-link
routing on a clean installation; a custom scheme alone does not meet this contract.

## Verification

Run `python -m unittest authentication.test_github_broker` and, in the pinned ASGI
environment, `python -m unittest authentication.test_app`. Tests cover concurrency,
restart, expiry, wrong verifiers, replay, uncertain exchange, redaction, hostile
origins, HTTPS, request limits and rate limiting. Live app registration, consent,
refresh rotation, organization SSO, passkeys and device acceptance remain separate
release requirements.
