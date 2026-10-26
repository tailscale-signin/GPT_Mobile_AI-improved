# Research recovery integration and GitHub sign-in

## Production changes

- ResearchModelCoordinator classifies completed provider payloads with ProviderOutcomeClassifier before schema acceptance. Empty reasoning completions retain their usage/outcome, fail the stage, and try another pinned, authorized endpoint.
- Rejected HTTP 400/413/415/422 configurations are not dispatched again with the same prompt, cap, model and accepted turn. Persisted model events restore these exclusions after restart. A changed packet/cap remains eligible; an error for one turn cannot block another.
- AndroidResearchWorkflow checkpoints provider observations before the next operation. A deferred cursor stays durable until its caller commits accepted claims and packet receipts. Existing atomic session/task persistence, authority fences and source-version receipts remain authoritative; this change does not add another database/state machine.
- Free HTTP 402 responses with an explicit Retry-After use the existing durable provider quota store. The typed scheduling decision retains HTTP status, exact retry deadline and nonacceptance proof. HTTP 402 without a reset does not invent an automatic retry or switch to a billing-capable route.
- An empty, interrupted or failed Research writer delivers retained, source-linked evidence with an EVIDENCE_ONLY receipt and a failed/partial terminal outcome. It does not redo acquisition, claim successful synthesis, or discard useful validated paragraphs. Permission failures and cancellation cannot reveal cached evidence.
- Corrected inherited ktlint violations in the original reliability helpers/tests.

## GitHub App OAuth sign-in

Native GitHub connections offer **Sign in with GitHub** and **Personal access token**. OAuth uses GitHub's device authorization flow, not MCP discovery. Existing GitHub tools, repository workspace, builds and downloads consume the same encrypted bearer credential.

1. Register a GitHub App and enable **Device flow** in its registration.
2. Configure only the repository permissions needed by the app: metadata, Contents, Pull requests, Issues and Actions as appropriate. Install it on the repositories the user selects. GitHub App user tokens have the intersection of the user's and app's access.
3. Supply its public client ID using `-PGITHUB_OAUTH_CLIENT_ID=...`, the equivalent environment variable, or the connection's GitHub App client ID field. No client secret or private key goes in the APK.
4. Save the connection, select **Sign in**, open GitHub, and enter the displayed code. The app polls no faster than the returned interval and increases its interval on slow_down.
5. Approval stores only the access token in SecretVault through the repository's verified credential replacement path. Account changes revoke prior tool grants. Pending device credentials stay in memory; cancellation, expiry or a changed/deleted connection prevent storage.
6. Token refresh is not automatic in this version. Reconnect when a GitHub App user token expires or is revoked. Disconnect/clear removes the local credential; users revoke remote authorization in GitHub settings.

The OAuth HTTP client has no body logger, does not follow redirects, pins the GitHub endpoints, validates the verification origin/path and sanitizes protocol errors. No authenticated end-to-end sign-in is claimed without a registered client ID and user approval.

Official protocol references:
- https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app
- https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens

## Validation and outstanding acceptance

Local: 191 repository script tests passed; Android regex and Room schema checks passed; ktlint 1.3.1 passes changed Kotlin files. The 12 original reliability tests passed in a standalone Kotlin harness using minimal assertion stubs; this is not the Android/JUnit integration suite.

New Android/JUnit regression cases cover reasoning-only fallback; persisted rejected requests and turn isolation; allowance/reset scheduling; empty/thrown writer failures; authorization/cancellation boundaries; device polling/backoff, denial, expiry, cancellation, origin validation and error redaction. Android execution remains a CI gate: this host cannot download the Gradle distribution and has no Android SDK/JDK 21 build environment.

Still required before merge/release:
- Full Android compile, unit tests, lint and emulator acceptance at the final branch head.
- Live provider smoke tests for BridgeNode, Vireonix, OVHcloud, BlockRun, CehPoint and Pollinations. Shared handling is implemented; external availability and successful end-to-end generation have not been established here.
- Registered GitHub App sign-in on a device, private repository access and expiry/reconnect acceptance. Automatic refresh remains future work.
- Full process-death/exact-once final publication matrix and physical-device UI frame-time measurements.
- Combined regression matrix: frozen contributions, final synthesis, attribution colours, continuation and recovery. Existing exact-quote/numerical/citation checks do not establish semantic entailment for every prose claim.
- CombinedSynthesisPlanner.prepare now hands an immutable context to a separate freezeAndPersist suspend stage. The packaged DEX gate checks both real methods and their generated state machines against ART's 16,383-code-unit threshold. Confirm packaged sizes in CI and profile remaining rendering hotspots on the physical device.

## Full public web tools for Free providers

All active Free providers can use the authorized public web-search tool catalog, the multi-engine `web_search` facade and the crawler plugin. Tool controls are visible even when the provider has no native function-call support. Explicit user/profile/chat disables and plugin selection still apply.

Native routes keep their native calls. Text-only routes use FreePromptToolSession and the existing strict prompt-call parser. It advertises the selected authorized names/schemas as text, serializes actual tool results as untrusted user data, and emits typed calls to AgentRunner only after a complete generation. AgentRunner retains execution ownership, schema validation, budgets, approvals and tool-result handling. Interrupted, unknown or malformed commands never execute. The bridge does not introduce hidden retry requests or pretend a provider has native function calls. Native tools/tool roles are not sent to text-only providers.

Regression coverage runs each active text-only Free route through mocked search, read and crawler rounds followed by a final answer, checks actual result replay and tests chunked commands, interrupted/failed commands, disabled tools, unknown names, ordinary prose and cancellation. Personal tools still require their existing recipient-specific consent; a public web-tool grant does not unlock memories, health data, location or private repositories. External provider success remains subject to live smoke tests and quotas.
