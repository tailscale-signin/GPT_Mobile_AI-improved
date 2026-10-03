# GPT Mobile AI (Improved)

An Android app for cloud and on-device AI conversations, model profiles, local memory, document retrieval, and user-controlled tools. Forked from [GPT Mobile](https://github.com/chungjungsoo/GPT_Mobile).

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Android 12+](https://img.shields.io/badge/Android-12%2B-green.svg)](app/build.gradle.kts)

## v0.9.30.0

The release branch adds a connected memory and workspace experience. See the [implementation and validation record](docs/audits/approved-roadmap-implementation.md) for scope, evidence and remaining physical-device gates. Local OCR, app-owned voice, accessibility expansion and localization expansion are excluded from this release's approved roadmap.

| Area | Available flow | Limits and status |
| --- | --- | --- |
| Conversations | Streaming, multiple profiles, reasoning controls, queued questions, delegation, non-destructive edits and branch comparisons | Follow-ups join compatible delegation turns at a request boundary; other messages remain queued. No universal mid-stream provider steering. |
| Navigation | Bottom anchoring, unread-response focus, scroll-up interruption of following, retry after interrupted/error responses, wide-screen list/detail | Fold, rotation, frame-time and sustained streaming behavior require physical-device validation. |
| Local memory | Explicit facts, repeated topics, review/pins, corrections, merge/split, provenance, project scopes, encrypted fact authority | Rule extraction is immediate. Optional enrichment waits for an idle loaded local model, uses bounded inference and may be deferred. |
| Semantic memory | Bundled local encoder with ObjectBox HNSW; Room provenance/graph; LangChain4j complete-turn context windows | Current bundled encoder is English-oriented. Multilingual topic aliases do not establish multilingual semantic parity. Token counts are estimates. |
| Documents | Persistent lexical + semantic retrieval, shared project documents, source links, scope checks and deletion tombstones | Broad questions use a bounded, explicitly partial sample. Extraction limits apply; scanned-document OCR is not implemented here. |
| Projects | Instructions, default model/tool profile, linked conversations, shared documents and personal-memory opt-in | Deleting a project removes its memories and shared copies; conversations survive and return to personal scope. |
| Workspaces | Tasks, context receipts, research evidence, branches, recipes, measured model guidance, spending, GitHub review and plugin configuration | Receipts reflect assembled client requests; provider-side processing is outside the record. Remote results may be unavailable after server expiry. |
| Plugins and MCP | Plugin-specific settings, validated portable options, resources/prompts, form input, scoped expiring grants and catalog-change revocation | Exported setup excludes credentials, active bindings and permission grants. Required connection details must be entered again. |
| Modern MCP | Explicit 2026-07-28 transport, legacy SDK transport, negotiated durable tasks | Tasks are an optional draft extension. Only supported form inputs are advertised; no sampling, URL elicitation or task notification subscription. |
| GitHub | Repository tools and a review workspace with SHA-linked checks, complete-file edits, local proposals and expected-head checked commits | Editor: 20 files, 2,000 lines per loaded file. PR file catalogs are live; check results belong to the inspected SHA. |
| Budgets | Shared token/currency reservations across concurrent delegates; price provenance and expiry; reported-usage reconciliation | Estimates are not invoice limits. Unknown prices block enforced monetary budgets. Tool fees/cache discounts are not priced automatically. |
| Recipes | Editable recipes, paused manual review, optional WorkManager scheduling through the existing queue | Scheduling is off by default, deferrable, and disables tools/delegation. Local-only is rechecked at dispatch. Remote schedules need spending and output caps. |
| Settings | Search, changed-feature filter, effective overrides/reset, presets, theme/profile/plugin layouts and Debug / Statistics / Benchmark tabs | Presets disclose changes before applying. Recommendations describe measured runs, not universal speed guarantees. |
| Share-in | Android text/URL/file sharing and selected text, project/conversation chooser, draft/summarize/local knowledge actions | No provider request or upload on receipt. Imports are bounded to 10 files, 25 MiB each, 50 MiB total and 16,000 shared text characters. |
| Privacy | Temporary conversations, optional device-unlock gate, screenshot/recents protection, deletion controls and storage information | Temporary conversations use local persistence while active, then are purged on leave/startup. This is not forensic secure erasure or an encrypted chat database. |
| Optional gateway | Paired-device authentication, revocation, device-owned durable jobs and explicit network binding | Defaults to loopback. See [gateway setup](gateway/README.md). The gateway is not needed for on-device memory. |

## On-device and network behavior

| Capability | Network required? |
| --- | --- |
| Fact capture, review, lexical retrieval and existing project documents | No |
| Bundled semantic memory encoder/index | No |
| LiteRT-LM inference and enrichment | No after a compatible model/package has been downloaded |
| Cloud model profiles, web search, remote MCP, GitHub and connected memory plugins | Yes, to their configured services |
| Ollama or gateway profiles | A reachable configured server; these are not on-device inference |

The selected model and enabled tools determine where message/attachment context is sent. Local memory's cloud-recall control is independent of the provider choice. A local model can still call network tools if the user enables them. ObjectBox, Room and LangChain4j run in the app; no Python/Mem0 server or second vector engine is required for the local memory path.

Supported adapters include OpenAI, Anthropic, Google, Groq, OpenRouter, NVIDIA, OpenAI-compatible APIs, Ollama, the optional gateway and LiteRT-LM. Available models and provider features depend on the configured service. Qualcomm QNN/NPU packages require a compatible device and packaged runtime; unsupported NPU packages are not generic CPU/GPU fallbacks.

## Data and backups

- Android Keystore-backed `SecretVault` protects credentials, authoritative fact memory and memory-bearing context receipts.
- Room chat/history/derived graph data, imported files and local vector indexes have app-private storage protection; they are **not all application-level encrypted**. App lock protects entry, not database bytes.
- Automatic Android cloud backup and device transfer are disabled/excluded. Use the explicit selective backup/export flow; encryption and selected data sections matter.
- Temporary sessions disable memory capture/indexing/recall and pause diagnostic logging. Temporary rows and their files are excluded from portable backups and purged on leaving/restart. App-private WAL/storage remnants are not a secure-erasure guarantee.
- Deletion removes dependent runs/approvals/context, cancels pending enrichment and deletes unshared app-owned attachments. Optional learned-memory deletion removes memories supported by that chat, including jointly supported facts, to avoid retaining unwanted evidence. It does not delete a remote provider's records.
- Anonymous daily cost records remain after conversation deletion so deletion cannot reset an active spending allowance. Profile/model/conversation attribution and content are removed.
- Plugin configuration export omits credentials, URL paths/queries, grants and active bindings. Re-enter endpoints and secrets during setup.

## Build and test

Use JDK 21, the checked-in Gradle wrapper, Android SDK platform 37.0 and build tools 37.0.0. Minimum Android version is 12 / API 31. Versions are pinned in [the catalog](gradle/libs.versions.toml); Room uses explicit migrations and exported schema 33.

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

GNU General Public License v3.0; see [LICENSE](LICENSE). Upstream and third-party notices remain applicable. This release corrects the README badge to match the existing license; it does not relicense the project.
