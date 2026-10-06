# GPT Mobile AI (Improved)

An Android AI workspace for cloud and on-device models, combined multi-profile conversations, research delegation with isolated review, local memory, document retrieval, multi-engine web search, and permission-controlled plugins and MCP tools. Includes a native GitHub workspace, detailed execution diagnostics, and an optional authenticated llama.cpp gateway. Forked from [GPT Mobile](https://github.com/Taewan-P/gpt_mobile).

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Android 12+](https://img.shields.io/badge/Android-12%2B-green.svg)](app/build.gradle.kts)

## Current source and development status

**Source version on `main`: 0.9.31.0**, as configured in [app/build.gradle.kts](app/build.gradle.kts). This overview describes repository source, not a guarantee that an installed APK or published release contains every change.

**Feature and correction snapshot: October 6, 2026.** The combined-chat, permissions, diagnostics and workspace improvements in [PR #608](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/608) are merged. The newer recovery, chat-UI and telemetry-removal work in [draft PR #609](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/609) is **not yet merged** and is listed separately below.

See the [implementation and validation record](docs/audits/approved-roadmap-implementation.md) for the earlier approved roadmap, evidence and remaining physical-device gates. Local OCR, app-owned voice, accessibility expansion and localization expansion were excluded from that roadmap; do not treat them as newly delivered features.

## Features available on main

| Area | Available flow | Limits and status |
| --- | --- | --- |
| Conversations | Streaming, multiple profiles, reasoning controls, queued questions, non-destructive edits and branch comparisons | Follow-ups join compatible delegation turns at a request boundary; other messages remain queued. No universal mid-stream provider steering. |
| Combined responses | Permanent themed profile buttons open each model's streaming or saved response, including the lead profile's original answer | Buttons slide into place over one second with 500 ms staggering. Status dots show generating, completed or failed runs. The composer fades out over two seconds while inspecting a source and returns in 500 ms from Combined or Back. |
| Delegation and review | Worker research, an isolated reviewer, configurable delegation, bounded execution and final synthesis | At 100% delegation, the worker prepares the task, the reviewer checks its evidence, and the primary performs a final synthesis without tools. This is not a guarantee of correctness, zero primary-model usage or a particular token saving. |
| Navigation and onboarding | Bottom anchoring, unread-response focus, scroll-up interruption of following, retry after interrupted/error responses, wide-screen list/detail and a four-step first-run flow | Transparent conversation controls and themed icons are merged. Fold, rotation, frame-time and sustained streaming behavior still require physical-device validation. |
| Local memory | Explicit facts, repeated topics, review/pins, corrections, merge/split, provenance, conversation scopes and encrypted fact authority | Rule extraction is immediate. Optional enrichment waits for an idle loaded local model, uses bounded inference and may be deferred. |
| Semantic memory | Bundled local encoder with ObjectBox HNSW; Room provenance/graph; LangChain4j complete-turn context windows | The bundled encoder is English-oriented. Multilingual topic aliases do not establish multilingual semantic parity. Token counts are estimates. |
| Documents | Persistent lexical and semantic retrieval, indexed documents, source links, scope checks and deletion tombstones | Broad questions use a bounded, explicitly partial sample. Extraction limits apply; scanned-document OCR is not implemented here. |
| GitHub workspace | Repository/file filters, browsing, breadcrumbs, guarded complete-file editing, per-file unstaging, branches, commits, pull requests and SHA-linked review checks | Hidden when the GitHub API plugin is unavailable or disabled. Editor: 20 files, 2,000 lines per loaded file. PR file catalogs are live; checks belong to the inspected SHA. Commits use expected-head checks. |
| Plugins and MCP | Plugin-specific settings, portable setup options, resources/prompts, form input and saved tool/provider approvals shared across models | Saved approvals survive catalog refreshes and can be revoked in Plugins/Tools. Credentials, active bindings and permission grants are not exported. Connection and authentication requirements still apply. |
| MCP compatibility | Modern and SDK/legacy transports, discovery fallback for supported older servers, and negotiated durable tasks | Missing discovery versions or method-not-found responses can fall back to standard initialization. Incompatible versions, malformed responses and authentication failures are not silently accepted. Durable tasks remain an optional draft extension; no sampling, URL elicitation or task notification subscription is advertised. |
| Web research | Multi-engine search across selected compatible, enabled and authorized engines, with URL deduplication and source provenance | Shared budgets, bounded concurrency, per-engine timeouts, permissions and cooldowns still apply. Partial results survive individual engine or optional crawl failures. Repository, memory and file searches are not treated as general web searches. |
| Diagnostics | Readable research/review cards, memory provenance inside expanded debug details, exact provider/model token comparison and individual request details | Duplicate request IDs are removed from totals; model version, quantization and case distinctions remain separate. Estimated usage stays marked as estimated. External DNS/server failures remain visible rather than being counted as successful work. |
| Response export | Export the selected AI response as Markdown or plain text without prompts, thoughts, tools or diagnostics | On this main snapshot, both formats still use a `.md` filename. The `.txt` correction for plain text is in draft PR #609. |
| Budgets | Shared token/currency reservations across concurrent delegates, price provenance and expiry, reported-usage reconciliation | Estimates are not invoice limits. Unknown prices block enforced monetary budgets. Tool fees/cache discounts are not priced automatically. |
| Recipes | Editable recipes, paused manual review and optional WorkManager scheduling through the existing queue | Scheduling is off by default, deferrable, and disables tools/delegation. Local-only is rechecked at dispatch. Remote schedules need spending and output caps. |
| Settings | Search, streamlined AI and Plugins/Tools categories, compact provider sections, theme/profile/plugin layouts and Debug / Statistics / Benchmark tabs | The former Effective Settings and Presets panels were removed in the recent settings simplification. Benchmark recommendations describe measured runs, not universal speed guarantees. |
| Share-in | Android text/URL/file sharing and selected text, conversation chooser, draft/summarize/local knowledge actions | No provider request or upload on receipt. Imports are bounded to 10 files, 25 MiB each, 50 MiB total and 16,000 shared text characters. |
| Privacy controls | Temporary conversations, optional device-unlock gate, screenshot/recents protection, deletion controls and storage information | Temporary conversations use local persistence while active, then are purged on leave/startup. This is not forensic secure erasure or an encrypted chat database. Additional telemetry removal remains in draft PR #609. |
| Optional gateway | Paired-device authentication, revocation, device-owned durable jobs, explicit network binding and bounded completion recovery | Defaults to loopback. See [gateway setup](gateway/README.md). The gateway is not needed for on-device memory. Updating the Android app does not update a separately running PC gateway. |

## Recent merged corrections

[PRs #606](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/606), [#607](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/607) and [#608](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/608) include the following reliability and presentation changes:

- **Completion handling:** finish Android response collection at the terminal event, flush buffered text, and prevent late events from replacing a completed answer. Empty/reasoning-only completions have at most one answer-only repair within the original deadline and remaining output allowance; completed actions are not replayed.
- **Gateway tool ownership:** separate gateway-owned and client-owned execution, constrain incompatible batches and reject unsafe partial/mixed batches rather than executing them. Failures remain explicit when safe recovery is unavailable.
- **Search and accounting:** remove the fixed two-engine web-search cap while retaining consent and execution limits; combine exact provider/model usage across roles and retries, deduplicate invocation IDs, and correct unspent delegate-token accounting.
- **Chat and tools:** preserve combined source responses and status after synthesis, replace streamed planner/reviewer JSON with readable debug cards, retain saved tool approvals across models/catalog refreshes, and keep them revocable in Plugins/Tools.

MCP negotiation fixes in [PR #603](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/603), [#604](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/604) and [#605](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/605) add legacy initialization paths for supported older servers, inconclusive discovery responses and method-not-found discovery errors without bypassing authentication failures.

For gateway changes, deploy the matching server and companion modules and restart the gateway; do not replace only `gateway.py` or assume an APK update changes the server. See [the upgrade guide](gateway/V13_UPGRADE.md) and [completion recovery notes](gateway/COMPLETION_RECOVERY.md). These changes do not repair external DNS outages, expired credentials or incorrect service endpoints.

## Pending improvements — draft PR #609

The following work is implemented on `feat/recovery-combined-ui-2026-10-06`, **not in `main` as of this snapshot**. Follow [PR #609](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/609) for its current review and validation status.

| Area | Changes awaiting merge |
| --- | --- |
| Interrupted-work recovery | Preserve partial answers, tool arguments/results, research, errors and generation checkpoints; make saved recovery context available to the next turn and reuse completed calls without repeating writes. |
| Combined output and routing | Preserve unique content from every selected profile, remove exact duplicates, refresh generating-chip transitions, and correct general web requests incorrectly routed to GitHub tools. |
| Home, navigation and archive | Themed backgrounds/search/header controls, redesigned conversation settings, back fades, profile-label interactions, drag-to-pin/reorder/unpin, improved unread positioning, a half-height archive sheet and lossless compressed archive storage. |
| Exports, quick replies and titles | Use `.txt` for plain text and `.md` for Markdown, improve the export dialog, generate short quick-reply labels that send complete questions, and request a concise single-line subject title on the first response. |
| Privacy cleanup | Remove obsolete telemetry collectors/screens, provider attribution headers and the bundled memory SDK's remote logging/DataTransport components; keep local debug diagnostics opt-in and reduce unnecessary persisted invocation history. This does not remove requests to explicitly configured cloud models or network tools. |
| Memory details and generation time | Simplify memory debug groups with icons/theme colours and show `Worked for 25m 37s` at the top left inside the expanded AI bubble. Use theme colour at 30% opacity, a one-second fade-in and a 0.5-second fade-out, including combined-worker generation time. |

The PR records successful unit tests and debug APK builds, but Android lint remains blocked by dependency checksum verification and device/emulator visual and gesture checks have not been run. Those results are branch-specific, not a claim that the pending features are released or fully device-validated. Dependency verification remains enabled.

## On-device and network behavior

| Capability | Network required? |
| --- | --- |
| Fact capture, review, lexical retrieval and existing indexed documents | No |
| Bundled semantic memory encoder/index | No |
| LiteRT-LM inference and enrichment | No after a compatible model/package has been downloaded |
| Cloud model profiles, web search, remote MCP, GitHub and connected memory plugins | Yes, to their configured services |
| Ollama or gateway profiles | A reachable configured server; these are not on-device inference |

The selected model and enabled tools determine where message/attachment context is sent. Local memory's cloud-recall control is independent of the provider choice. A local model can still call network tools if the user enables them. ObjectBox, Room and LangChain4j run in the app; no Python/Mem0 server or second vector engine is required for the local memory path.

Supported adapters include OpenAI, Anthropic, Google, Groq, OpenRouter, NVIDIA, OpenAI-compatible APIs, Ollama, the optional gateway and LiteRT-LM. Available models and provider features depend on the configured service. Qualcomm QNN/NPU packages require a compatible device and packaged runtime; unsupported NPU packages are not generic CPU/GPU fallbacks. A configured llama.cpp gateway is a server connection, not on-device inference.

## Data and backups

- Android Keystore-backed `SecretVault` protects credentials, authoritative fact memory and memory-bearing context receipts.
- Room chat/history/derived graph data, imported files and local vector indexes have app-private storage protection; they are **not all application-level encrypted**. App lock protects entry, not database bytes.
- Automatic Android cloud backup and device transfer are disabled/excluded. Use the explicit selective backup/export flow; encryption and selected data sections matter.
- Temporary sessions disable memory capture/indexing/recall and pause diagnostic logging. Temporary rows and their files are excluded from portable backups and purged on leaving/restart. App-private WAL/storage remnants are not a secure-erasure guarantee.
- Deletion removes dependent runs/approvals/context, cancels pending enrichment and deletes unshared app-owned attachments. Optional learned-memory deletion removes memories supported by that chat, including jointly supported facts, to avoid retaining unwanted evidence. It does not delete a remote provider's records.
- Anonymous daily cost records remain after conversation deletion so deletion cannot reset an active spending allowance. Profile/model/conversation attribution and content are removed.
- Plugin configuration export omits credentials, URL paths/queries, grants and active bindings. Re-enter endpoints and secrets during setup.

Do not interpret the privacy controls or pending telemetry cleanup as a guarantee of zero network traffic, universally encrypted storage, or a complete security audit.

## Build and test

Use JDK 21, the checked-in Gradle wrapper, Android SDK platform 37 and build tools 37.0.0. Minimum Android version is 12 / API 31. Versions are pinned in [the catalog](gradle/libs.versions.toml); Room uses explicit migrations and exported schemas.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
python3 scripts/check_local_runtime_apk.py app/build/outputs/apk/debug/*.apk
./gradlew :app:assembleDebugAndroidTest
```

Dependency verification checks resolved artifacts against [reviewed checksum metadata](gradle/verification-metadata.xml). Gateway installation uses exact versions and SHA-256 hashes. See [validation and dependency maintenance](docs/validation.md) before updating either lock. A checksum bootstrap is trust-on-first-use, not independent proof of publisher identity.

Device-required checks:

```bash
./gradlew :app:connectedDebugAndroidTest
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

The benchmark fixture is included only in the benchmark variant. It contains synthetic conversations and makes no model/network requests. Baseline Profile generation and performance comparison require real measured outputs; no generated profile or 60/120-fps guarantee is claimed. See [device validation](docs/validation.md#device-validation).

Release signing is configured separately. Do not ship a benchmark/debug APK as a production release.

## License

GNU General Public License v3.0; see [LICENSE](LICENSE). Upstream and third-party notices remain applicable. The README badge reflects the existing license; it does not relicense the project.
