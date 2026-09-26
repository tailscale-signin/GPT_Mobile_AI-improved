# Web search: “User not found” investigation

Based on main `8862e99c`, 2026-09-26.

## Confirmed app defect

OpenRouter's provider settings saved changes through `updatePlatformV2`, even when the AI profile used a shared `providerConnectionUid`. The profile repository intentionally discards profile-level credentials for shared connections. As a result, saving a replacement key or URL could leave the old connection active while the settings page displayed the new value.

The fix updates the referenced provider connection, invalidates the resolved-profile cache through the existing repository, and keeps the credential in the vault. It updates all profiles linked to that connection without touching other connections. Empty credentials explicitly clear the key. Loading an existing profile no longer displays an unrelated standalone fallback key after its connection key was cleared.

## Distinguishing the failing connection

- An AI-provider HTTP 401, including one arriving in an HTTP 200 event stream after a tool round, now identifies the AI platform connection and points to its API settings. OpenRouter is named when it is the configured host.
- A configured Firecrawl, Perplexity or Exa search HTTP 401 identifies that search provider and points to Tool Connections. Those credentials are separate from the AI profile's platform key.
- HTTP 403 gives access/permissions guidance. Neither error silently disables tools or changes the selected service.
- Provider response bodies and API keys are not echoed in the new authentication messages.
- Pasted search credentials and saved OpenRouter keys have surrounding whitespace trimmed.

## Recovery on the phone

1. In platform settings, open the OpenRouter connection used by the affected AI profile, re-enter the intended OpenRouter API key and save. On an older app, edit the shared platform connection directly: the separate OpenRouter provider settings page had the save bug described above.
2. Try a plain chat request, then a web-search request. A search-only failure can come from the separate search connection or a remote MCP server's upstream credentials.
3. If the message names a configured search provider, update that provider's credential under Tool Connections. An OpenRouter key does not replace an Exa, Firecrawl or Perplexity search credential.
4. If a valid key that previously worked still fails, check the provider's status and retry later. Do not delete the account based on this wording. Do not share API keys in logs or screenshots.

The reported device failure was not reproduced against the user's account; no device credentials or request log were available. The save defect is confirmed from the repository and covered by regression tests. This change cannot renew an expired/revoked key or repair a remote MCP server's configuration.

## Regression coverage

- Real `SettingRepositoryImpl` with a fake provider DAO/vault: replacing and clearing the shared key, changed base URL, cache invalidation, linked-profile resolution, reload, and isolation from another connection.
- Legacy profile clearing and prevention of stale standalone credential fallback.
- Authentication failures through Chat Completions, Responses, and streamed error events with tools enabled; unrelated errors remain intact.
- Dedicated search-provider authentication messages and pasted-key whitespace.

Kotlin formatting and resource preflight pass locally. The full Android test/build workflow runs on the pull request.

## Provider references

- [OpenRouter authentication](https://openrouter.ai/docs/api/reference/authentication)
- [OpenRouter incident explanation: infrastructure failures also produced misleading “User not found” errors](https://openrouter.ai/blog/announcements/openrouter-outages-on-february-17-and-19-2026/)
