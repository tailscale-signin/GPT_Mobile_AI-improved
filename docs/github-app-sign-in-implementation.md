# GitHub App sign-in integration and release gate

The October 28 foundation branch adds a separate, unused PKCE helper. The application already has a production authorization implementation in `data/github/GitHubPublicAuthClient.kt` and `GitHubPublicAuthCoordinator.kt`; maintain that implementation instead of adding a second callback or token store.

## Implemented paths

- **Browser authorization:** Custom Tabs, S256 PKCE, an exact verified HTTPS callback, duplicate-query rejection, constant-time state validation, encrypted pending authorization with expiry, and a single-use broker exchange. The callback activity and sign-in button are already connected. A registered client, hosted broker and Android verified-link association are required before enabling this path.
- **Device authorization:** the existing sign-in button opens GitHub's trusted verification page and polls at GitHub's required interval. Device-issued sessions now refresh directly against GitHub with their own registered client ID. They do not send refresh credentials to the project's confidential broker. Browser-issued sessions continue to require that broker.
- **Credential lifecycle:** encrypted access/refresh storage, expiry, account binding, serialized rotation, disconnect, revocation detection and preservation of existing PAT credentials until a same-account replacement is verified. An interrupted refresh requires reconnection and is never replayed automatically. Legacy sessions with no recorded authorization method retain their broker restriction; reconnect through device sign-in to adopt direct refresh.
- **Repository discovery:** signed-in App connections list their accessible installations and each installation's permitted repositories using `/user/installations` and `/user/installations/{id}/repositories`. Suspended installations cannot be selected. PAT connections retain `/user/repos`. Installation switching clears the old repository context and is blocked while there are unsaved/staged edits. Contents permission, repository push membership and local policy jointly determine the workspace edit badge; GitHub enforces the final operation's permissions and repository rules.
- **Execution:** the existing authenticated workspace/tool resolver uses the bound credential. Repository tools run automatically only within the selected workspace connection/repository. Each commit still requires one immutable branch/change-summary approval.

## Registration prepared for review

Registration is not complete. The proposed App is **GPT Mobile AI Workspace**, owned by `tailscale-signin`, with the repository homepage, device authorization enabled, expiring user tokens and inactive webhooks. Public installation permits other accounts to install the app; it does not grant access until each account explicitly authorizes an installation.

The requested workspace permissions are Contents and Pull requests read/write, Actions read/write for workflow operations, and Metadata, Checks and Commit statuses read. Workflow-file modification is a separate **Workflows write** permission: automatic approval review rejected preparation of that privileged permission, so it remains unselected pending explicit approval. Actual endpoint access must still be verified. No Administration, organization or account permission is requested.

No client secret or App private key belongs in the Android build. After registration, configure the public `GITHUB_OAUTH_CLIENT_ID` for release builds. A user-supplied GitHub App client ID can still be saved on its own OAuth connection. The device path requires no broker or callback host. Browser PKCE remains disabled until `GITHUB_AUTH_BROKER_ORIGIN`, `GITHUB_AUTH_CALLBACK_HOST`, the server secret and the release certificate's `assetlinks.json` are deployed and verified as described in `authentication/README.md`.

## Acceptance required before merge/publication

1. Finish the reviewed App registration and install it on an explicitly selected test repository.
2. Build with its real public client ID. On a clean Android installation, complete sign-in, declined consent, cancellation and account selection.
3. Verify identity and installation/repository selection, private repository read, an approved feature-branch commit and its remote receipt, and applicable PR/Actions endpoint access. A connector's separate credential is not evidence for the Android App token.
4. Verify expiry/refresh rotation, revoked credentials, loss of installation access, process restart, disconnect and preservation of an existing PAT on failed migration. Do not replay an uncertain write or refresh.
5. Require the final PR head's full CI to pass. Keep the feature unmerged and unpublished until the live checks are recorded.

Deterministic tests exercise both refresh transports, restart/uncertain rotation, callback/state validation, account boundaries, installation pagination, invalid installation IDs, repository allowlists and read-only installation permissions. They do not establish a successful live GitHub authorization.

References: [GitHub App user authorization](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app), [refreshing user access tokens](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens), [installation API](https://docs.github.com/en/rest/apps/installations).
