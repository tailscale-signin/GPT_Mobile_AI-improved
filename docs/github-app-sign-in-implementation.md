# GitHub App sign-in implementation

Status: **foundation only**. This branch introduces the Android PKCE authorization-request and state-validation helper. It does **not** yet provide a connected sign-in button, callback handler, token exchange, installation selection, or working repository writes.

## Required GitHub App setup
- Register a public GitHub App, enable user authorization, set a verified HTTPS callback URL and Android App Link.
- Repository permissions: Contents RW, Pull requests RW, Actions RW only where necessary, Workflows RW if workflow edits are needed, Metadata read.
- Install the app on explicitly selected repositories; authorization by itself does not grant access to uninstalled repositories.
- Configure the public client ID through a non-secret build configuration. Do not embed a GitHub App private key.
- For published clients, use authorization code + PKCE S256. Follow GitHub's current client-secret requirements and perform secret-dependent token exchange through a trusted backend. Do not hardcode shared credentials in the APK.

## Integration tasks
1. Wire the GitHub Workspace Settings 'Connect GitHub' action to GitHubAppAuthorization.start and Android Custom Tabs.
2. Register a verified HTTPS App Link callback and validate exact expected redirect origin/path and state before accepting a code.
3. Persist pending state and verifier with bounded lifetime; reject replay, concurrent mismatches, and stale callbacks.
4. Exchange authorization code for an expiring user access token using the code_verifier; store and rotate tokens securely; revoke/disconnect.
5. Show GitHub identity, installations, repository selection, and read/write permission diagnostics.
6. Migrate legacy PAT configuration without silently discarding credentials.
7. Route all read/write GitHub tools through the authenticated account, enforce repository allowlisting, branch protections and approval for destructive actions.
8. Test denied authorization, state mismatch, stale callback, refresh expiry, missing installation, 401/403, network failure, account switching and clean logout.
9. Run unit tests, Android lint, instrumented sign-in tests and CI before merging.

References:
- https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app
- https://github.blog/changelog/2025-07-14-pkce-support-for-oauth-and-github-app-authentication/
