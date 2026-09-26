# GPT Mobile AI (Improved) v0.9.15.0

This release redesigns conversation controls, local model discovery, Memory, and diagnostics. It includes the Free AI platform and web-search fixes merged after v0.9.14.0.

## Conversation and navigation

- Open the wrench in a conversation for Models, Tools, and Response settings, with model search, tool toggles, and creativity controls.
- Updated the input bar, New chat button, search/settings icons, timestamps, and AI profile colors. Conversation icons no longer have background bubbles.
- Restored a loading bar with one control to reveal or hide tools and provider reasoning. Expanded activity follows the conversation timeline.
- Queued messages appear at the bottom as light italic drafts at 30% opacity, including attachment names. Draft editing, pausing, and removal remain available.
- Added 13 editable expert system-prompt presets and simpler onboarding with advanced setup choices collapsed.
- Removed voice conversation mode, Project knowledge screens, and Settings search. Advanced Settings categories start collapsed.

## Local models marketplace

- Separate installed models from the marketplace opened from the top-right icon.
- Search, discover, authenticate, import, and download models in the marketplace.
- Recommended models account for available RAM, supported architecture, and chipset eligibility, with LiteRT/QNN preference tags.
- Runtime selection, QNN fallback behavior, and performance tuning now live in Local models.

## Memory and documents

- Replaced the Fact Vault settings page with Memory, including searchable, editable, pinned, and reviewable memories, provenance, retention, and privacy controls.
- Added self-contained local graph and document tools for memory search, recall, explicit capture, forgetting, and document retrieval. No external memory server is required.
- Added a library of conversation attachments, original-document previews, searchable text indexing, and controls for removing an index.
- Improved encrypted memory storage with chunked snapshots, recovery behavior, and backup isolation. Forgetting prevents automatic re-learning of removed facts.
- Existing memory preferences and data remain readable. Free AI profiles retain their memory isolation. Legacy Project knowledge database structures remain only for compatibility; their UI and project context injection are removed.

## Tools, search, and limits

- Web search fans out across enabled built-in engines, configured search connections, and recognized MCP web-search tools. Results are combined and duplicate links removed while preserving partial results.
- AI profile settings now support multiple search engines. Tool activity uses themed icons and readable titles.
- Tool approval offers a persistent Always allow choice scoped to the tool connection, with a reset option in connection permissions.
- New model profiles default to 50 tool calls and no app-imposed context or total-token cap. Actual provider and local-runtime capacities still apply.
- As configured limits approach, generation finishes with a response and continuation guidance. Shared execution budgets and individual tool approvals remain enforced during search fan-out.

## Debug and Statistics

- Added colorful per-model usage charts, token totals, request counts, median/p95 latency, first-text-token timing, and measured throughput.
- Optional local log tracking covers app events, model requests, tools, network metadata, and available Android process logs. Logs can be filtered, cleared, and exported.
- Log storage is bounded and credentials are redacted; HTTP request/response bodies are not logged by the network recorder. Logs can still contain app content, so review exports before sharing.

## Installation and validation

- Version **0.9.15.0**, Android version code **71**; Android **12 or newer**.
- Package: `dev.melo.gptmobile.improved`.
- Signed APKs: **arm64-v8a**, **x86_64**, and **universal**. Use arm64-v8a for most current Android phones.
- The release also includes a signed Android App Bundle, `SHA256SUMS.txt`, and exact-source `provenance.json`.
- Feature validation passed **1,047 unit tests**, APK compilation, Android/Kotlin lint, CodeQL, resource preflight, and remote diagnostics.
- The signed release workflow independently validates the exact release commit, checks package/version identity and signing-certificate continuity, and preserves existing published releases.
- Physical-device visual/accessibility checks and QNN/LiteRT execution testing remain outstanding. Automated checks do not replace device testing.
