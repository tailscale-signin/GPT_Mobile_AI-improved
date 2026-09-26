# GPT Mobile AI (Improved) v0.9.14.0

This release brings the expanded local MCP marketplace together with the reliability, memory, workspace and chronological chat improvements merged in PRs #502 and #503.

## Chat and generation

- Reasoning, tool calls, answer segments and follow-ups appear in one downward timeline, in their arrival order. Themed chevrons expand and collapse each entry.
- A public progress bubble appears when generation starts and after every ten completed tool calls, including failures. The model is asked for a brief progress update; a factual activity summary is shown if it does not supply one.
- The prompt queue now persists accepted prompts, attachments and selected profiles. Queued prompts can be edited, reordered, paused or removed, and wait for pending combined responses.
- Live streaming updates are separate from durable checkpoints. Long chats load recent turns first, with older-history loading and full-text search.
- Chat profiles support persistent ordering and quick switching.

## MCP, local models and tools

- Added 29 local AI projects to the marketplace: 23 configurable server connections and six setup guides, with curated brand assets.
- Model delegation can send bounded subtasks to configured on-device or remote profiles, with timeouts, output limits and protection against recursive delegation.
- Remote and native tools share call, concurrency, timeout and output budgets. Explicitly trusted read tools can share matching results across a multi-model turn.
- MCP connections support read-only, ask-for-writes and trusted policies, with reviewable write requests and protection against duplicate dispatch.
- Added resource and prompt browsing, input forms, bounded image/audio handling and visible discovery errors.
- Connection diagnostics, a fixed streaming benchmark and single-use local server pairing help configure compatible servers.

## Memory and document workspaces

- Fact Vault provides integrated capture and recall, editable facts, provenance, confidence, personal/project scopes, relevance ranking and scoped forgetting.
- New vaults start with memory enabled. Existing saved preferences, including opt-outs, are preserved. Capture, recall and cloud recall can be configured separately in settings; cloud recall may include selected saved facts in requests to remote providers.
- Automatic capture uses the original user message, excluding appended attachments, model answers and tool output. Fact contents are encrypted locally and recalled text is redacted from persistent tool traces.
- Project/chat document workspaces retain searchable document chunks, bounded source excerpts and private citation links. Replacing or deleting a source updates retrieval.
- Context estimates cover prompts, memory, documents, history, tools and the response reserve. Usage reporting links primary, delegated and synthesis requests to their parent run and labels estimated usage.

## Reliability and privacy

- Default backups exclude credentials and Fact Vault contents. Including either requires explicit selection and password encryption; restored queued work starts paused.
- HTML previews start with scripts disabled. Interactive previews continue to block external network access, file access, frames, navigation and form submission.
- Gateway recovery handles pending responses, cancellation, current credentials and stale results.
- Secret-bearing endpoint URLs and diagnostic values receive additional storage and redaction protections.
- Added searchable settings, accessible expansion controls and an optional foreground voice conversation loop.
- Retired the incompatible Node MCP companion prototype.

## Installation and release artifacts

- Version **0.9.14.0**, version code **69**.
- Android **12 or newer**; package `dev.melo.gptmobile.improved`.
- Signed APKs for **arm64-v8a**, **x86_64** and **universal**. Use arm64-v8a for most current Android phones.
- Signed Android App Bundle (AAB), `SHA256SUMS.txt` and `provenance.json` accompany the APKs.
- Publication requires unit tests, debug/release lint, Android resource and AAR compatibility checks on the exact source commit, followed by package/version and signing-certificate continuity checks.

## Validation and known limits

The merged implementation passed **1,012 unit tests**, Android lint, Kotlin formatting, APK compilation, CodeQL and remote diagnostics before release packaging. The signed release workflow performs its own validation on the commit recorded in `provenance.json`.

- Older saved conversations without event ordering cannot recover their exact historical ordering.
- Pending combined-response synthesis resumes when its chat is reopened; queued prompts wait for it.
- Context budgets and token usage remain estimates when a provider does not report them. Device benchmarks measure the actual device and configured model.
- Physical-device checks such as TalkBack/large fonts, Bluetooth and call interruptions, process death with queued attachments, WebView isolation and signed upgrade behavior are not replaced by the automated test results.

See `docs/audit-implementation.md` for the implementation and validation ledger covering all 22 audit findings.
