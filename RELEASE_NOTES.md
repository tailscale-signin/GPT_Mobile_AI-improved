# GPT Mobile AI (Improved) v0.9.12.0

## Highlights

- Queue follow-up prompts while models generate, with safe turn completion and preserved input drafts.
- Redesigned model controls, profile activation, settings, themes, conversation statistics, and document attachments.
- Share identical read-only tool calls across models in a conversation turn, with an Advanced Settings control.
- Expanded MCP marketplace with provider branding, memory and threading categories, and clearer connection requirements.
- Inline expandable tool traces show execution status, duration, argument size, result bytes, and estimated tokens.
- Encrypted, opt-in Fact Vault under Settings. Review, disable, or delete local facts; matching facts are recalled into cloud and LiteRT prompts with visible recall chips.
- Improved location-map initialization, provider integration, local-runtime fallback coverage, and cumulative usage tracking.

## Fixes

- Preserve drafts during document preparation and release queued prompts after the complete multi-model turn.
- Keep in-flight shared tool calls reusable and canonicalize equivalent JSON arguments.
- Avoid learning questions, negations, or quoted statements as affirmative memory facts.
- Prevent unrelated personal memories from crowding out specific matches; apply same-turn exclusions before the recall limit.
- Allow clearing unreadable Fact Vault data.
- Preserve measured tool results and recall references in existing chat timeline storage.

## Installation and artifacts

- Version: **0.9.12.0** (version code **67**).
- Android 12 or newer; target Android 16.
- Package: `dev.melo.gptmobile.improved`.
- Signed APKs: **arm64-v8a**, **x86_64**, and **universal**. Most modern Android phones use arm64-v8a.
- Signed Android App Bundle (AAB) and SHA-256 checksums are produced by the release workflow.
- Updating an existing installation requires the same release signing key. The workflow requires configured signing credentials instead of generating a replacement key.

## Memory and telemetry notes

Fact Vault learning is off by default. Extraction uses simple English patterns, not a new embedding model. Relevant enabled facts are included in requests to the selected provider. Tool token counts are estimates, not billed usage. Gateway payload sizes are shown only when available.

## Validation

The feature and fix commits passed 76 focused regression tests and the debug APK build. Signed release packaging is performed by the release workflow using the repository signing credentials.
