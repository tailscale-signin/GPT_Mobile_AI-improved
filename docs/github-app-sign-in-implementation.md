# GitHub App sign-in integration and release gate

The October 28 foundation branch adds a separate, unused PKCE helper. The application already has a production authorization implementation in `data/github/GitHubPublicAuthClient.kt` and `GitHubPublicAuthCoordinator.kt`; maintain that implementation instead of adding a second callback or token store.

## Implemented paths

- **Browser authorization:** Custom Tabs, S256 PKCE, an exact verified HTTPS callback, duplicate-query rejection, constant-time state validation, encrypted pending authorization with expiry, and a single-use broker exchange. The callback activity and sign-in button are already connected. A registered client, hosted broker and Android verified-link association are required before enabling this path.
- **Device authorization:** the existing sign-in button opens GitHub's trusted verification page and polls at GitHub's required interval. Device-issued sessions now refresh directly against GitHub with their own registered client ID. They do not send refresh credentials to the project's confidential broker. Browser-issued sessions continue to require that broker.
- **Credential lifecycle:** encrypted access/refresh storage, expiry, account binding, serialized rotation, disconnect, revocation detection and preservation of existing PAT credentials until a same-account replacement is verified. An interrupted refresh requires reconnection and is never replayed automatically. Legacy sessions with no recorded authorization method retain their broker restriction; reconnect through device sign-in to adopt direct refresh.
- **Repository discovery:** signed-in App connections list their accessible installations and each installation's permitted repositories using `/user/installations` and `/user/installations/{id}/repositories`. Suspended installations cannot be selected. PAT connections retain `/user/repos`. Installation switching clears the old repository context and is blocked while there are unsaved/staged edits. Contents permission, repository push membership and local policy jointly determine the workspace edit badge; GitHub enforces the final operation's permissions and repository rules.
- **Execution:** the existing authenticated workspace/tool resolver uses the bound credential. Repository tools run automatically only within the selected workspace connection/repository. Each commit still requires one immutable branch/change-summary approval.

## Registered App and selected installation

On October 28, 2026, the owner approved and registered the public **GPT Mobile AI Workspace** App, slug `gpt-mobile-ai-workspace`, App ID `5516286`, public client ID `Iv23liZ80mi4wzhJIrWM`. Device authorization and expiring user tokens are enabled; OAuth during installation and webhooks are disabled. Installation `176313324` on `tailscale-signin` has **only** `tailscale-signin/GPT_Mobile_AI-improved` selected. The installation UI was checked after completion.

Approved repository permissions are Contents, Pull requests, Actions and Workflows read/write, plus Metadata, Checks and Commit statuses read. No Administration, organization or account permission was requested. Public distribution does not grant repository access until an owner installs the App and a user authorizes it.

The release now defaults to that real public client ID. `GITHUB_OAUTH_CLIENT_ID` remains overridable; a different client has no default App slug. `GITHUB_APP_SLUG` can configure its installation entry point. Workspace onboarding opens the registered App page only for a connection with the matching configured client ID; custom or unconfigured connections retain GitHub's installation settings page.

No client secret or App private key was generated or put in the Android build. GitHub's device authorization endpoint accepted the registered client and issued an expiring device challenge. The secure browser code prompt was declined, so no authorization token was acquired and no authenticated App-token read/write or refresh test was run. Installation permissions and a connector's separate credential do not establish those results.

Device flow needs no broker or callback URL: GitHub documents the callback as optional and ignored for this flow. Browser PKCE remains disabled until `GITHUB_AUTH_BROKER_ORIGIN`, `GITHUB_AUTH_CALLBACK_HOST`, the server secret and the release certificate's `assetlinks.json` are deployed and verified as described in `authentication/README.md`.

## Concurrent merge and release failure

PR #752 was merged at `b36fdedb567fbe874d5539547490ceabdc46b635` while registration/acceptance was in progress, despite the earlier candidate's stated hold. Its signed-release run `40768251585` failed scope validation because the concurrent main-branch diagnostic synthesis document was absent from the scope allowlist. It did not publish a release. The follow-up preserves those diagnostic repairs and explicitly includes their synthesis document in the reviewed scope; it does not weaken the validator or mark live acceptance passed.

## Acceptance required before merge/publication

1. Registration and repository-scoped installation are complete; retain the approved scope.
2. Build with its real public client ID. On a clean Android installation, complete sign-in, declined consent, cancellation and account selection.
3. Verify identity and installation/repository selection, authenticated read, an approved feature-branch commit and its remote receipt, and applicable PR/Actions endpoint access. The selected repository is public: private-repository acceptance needs a separately authorized private test repository. A connector's separate credential is not evidence for the Android App token.
4. Verify expiry/refresh rotation, revoked credentials, loss of installation access, process restart, disconnect and preservation of an existing PAT on failed migration. Do not replay an uncertain write or refresh.
5. Require the final PR head's full CI to pass. The original feature has already been merged concurrently; keep the follow-up unmerged and unpublished until the live checks are recorded.

Deterministic tests exercise matching-client installation onboarding, invalid App-slug rejection, both refresh transports, restart/uncertain rotation, callback/state validation, account boundaries, installation pagination, invalid installation IDs, repository allowlists and read-only installation permissions. They do not establish a successful live GitHub authorization.

References: [App registration and device callback requirements](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app), [GitHub App user authorization](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app), [refreshing user access tokens](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens), [installation API](https://docs.github.com/en/rest/apps/installations).
