# Approved roadmap implementation

Branch: `v0.9.30.0`. Draft PR: [#592](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/592). Implementation date: 3 October 2026.

This extends the [original 20 requests](release-0.9.30.0-feature-checklist.md) and the [repository investigation](repository-investigation-2026-10-03.md). **Local OCR, voice, accessibility expansion and localization expansion are excluded** (roadmap IDs 14, 17 and 18). Existing functionality in those areas is preserved.

The table records implemented code paths, not a claim that every exploratory suggestion or physical-device acceptance criterion has been established. The remaining limits below are part of the review scope.

## Implementation map

| IDs | Implemented behavior | Main entry points |
| --- | --- | --- |
| 01–02 | Loopback-default gateway, deliberate bind configuration, hashed revocable device tokens, single-use pairing, authenticated control/proxy/job endpoints and per-device job ownership. Canonical launcher and clean hash-locked installation tests. | `gateway_security.py`, `gateway_v12.py`, Server pairing |
| 03 | Project creation/editing/deletion, conversation links, instructions, default profile with existing tool bindings, shared document copies and explicit personal-memory sharing. Project and branch scopes reach capture, enrichment, retrieval and graph tools. | Settings → Projects; `MemoryScopeResolver`, `ProjectWorkspaceRepository` |
| 04 | Persistent FTS4 lexical index plus existing local encoder/ObjectBox document vectors; reciprocal-rank fusion, diversity, hash invalidation, scope checks, deletion tombstones and source links. Broad questions disclose sample coverage. | `DocumentSearchIndex`, `MemoryDocumentRepository`, `LocalSemanticMemory` |
| 05–08 | Phrase/alias topic evidence, multilingual guard fixtures, delayed battery-aware local enrichment, provenance/source inspection, conflict history, corrections, merge/split, bulk review and local synthetic memory evaluations. | Memory settings; `RecurringTopicLearning`, `MemoryEnrichmentWorker`, `MemoryQualitySuite` |
| 09 | Modern MCP request metadata, read-only version discovery, bounded JSON/SSE parsing, ID matching, catalog pagination, form input and negotiated durable task recovery. Legacy SDK transport remains available. | `ModernMcpTransport`, `McpClientManager`, Tasks |
| 10 | One-hour conversation/schema/action/resource grants, catalog-change invalidation, grant inspection/revocation and action previews. Resource arguments are hashed rather than retained in permission labels. | Tool approval dialog; Workspace → Plugins; `ScopedToolGrant` |
| 11 | Editing earlier user input forks a conversation while preserving the original. Completed evidence is copied, ancestry recorded, memory excludes post-fork evidence, and branches can be opened/compared. | Chat edit; Workspace → Branches |
| 12 | Benchmark module for cold start, 1,000-message scrolling, streaming and Baseline Profile generation; manual trusted-device workflow; identity-checked JSON comparison script. | `macrobenchmark/`, device workflow, `check_performance_results.py` |
| 13 | Searchable feature toggles/settings destinations, changed-only filter, effective profile/conversation overrides, reset controls and previewed Balanced/Quiet/Battery saver presets. | Settings discovery panel |
| 15 | Temporary conversations, entry lock, screenshot control, backup exclusion, private-log suppression, centralized deletion and storage accounting/cleanup preview. Startup removes interrupted temporary sessions. | Privacy/storage; `ConversationDeletion`, `ProtectedActivity` |
| 16 | Consolidated automatic PR validation, strict Gradle SHA-256 verification, hash-locked Python dependencies, pip update monitoring, Room 33 export and expanded migration fixtures. | CI, `verification-metadata.xml`, `RoadmapMigrationMatrixTest` |
| 19 | Text/URL/file share targets and selected-text actions; conversation/project/profile destination, ask/summarize/save-to-knowledge choices, bounded URI import, encrypted staging and durable drafts. Incoming content never auto-sends. | Android Sharesheet; `ShareIncomingActivity` |
| 20 | Wide-screen list/detail layout with folding-feature handling; conversation content survives pane movement. Draft writes are isolated from background response snapshots. | `AdaptiveConversationLayout`, Main/chat navigation |
| 21 | Profile guidance shows measured suite outcomes, device/backend/accelerator, latency, memory and thermal observations, with conservative recommendations and no automatic unsupported accelerator selection. | Workspace → Models; existing profile benchmarks |
| 22 | Price provenance/expiry, currency snapshots, atomic turn/day cost reservations across delegates, actual-usage reconciliation when reported, unknown-price blocking under enforced budgets and usage breakdowns. | Workspace → Budgets; `SpendBudget`, `InvocationLedger` |
| 23 | Immutable receipts at actual request assembly: included history IDs, facts, documents, attachments, tools, limits, settings and request hashes. Next-request exclusions preserve old receipts. Memory-bearing contents stay encrypted. | Response context action; Workspace → Context |
| 24 | Retained tool evidence and failed-read states, pinned source/excerpt/claim/time/kind, explicit unavailable/truncated previews and reviewed refresh requests preserving earlier evidence. | Workspace → Evidence |
| 25 | Unified running/queued/paused/waiting/unknown task view, compatible follow-up explanation, saved remote handles, polling/cancellation/input and review-before-restart paths. Original writes are not automatically replayed. | Workspace → Tasks; `RemoteTaskStore` |
| 26 | Versioned plugin configuration with legacy migration, strict validation, secret-free export/import, setup re-entry checklist and catalog permission-change reporting. Only HTTPS origins are portable. | Workspace → Plugins; `PluginConfiguration` |
| 27 | Repository/branch/PR review, current SHA/checks, changed files, file edits with bounded diffs, stale-head detection and explicit expected-head commit confirmation. Existing local edits survive refresh/open attempts. | Workspace → GitHub; `GitHubReviewPanel`, `ReviewDiff` |
| 28 | One canonical gateway with compatibility launcher; dedicated scope, index, privacy, share, budget, workspace and task-storage components. Existing queue, memory authority, secret vault and execution paths are reused. | Data repositories and feature panels |
| 29 | Corrected feature/privacy/runtime/build matrix, GPL-3.0 metadata, Room 33 docs and experimental/device limits. Removed unsupported voice and guaranteed-performance claims. | `README.md`, `docs/validation.md` |
| 30 | Editable/cancellable recipes, reviewed paused manual launches and optional deferrable WorkManager scheduling. Saved local-only/provider/budget constraints are rechecked at dispatch; scheduled work disables tools/delegation. | Workspace → Recipes; `RecipeScheduler`, durable queue |

## Data and upgrade behavior

- Room advances **32 → 33** with exported schema. New records carry workspace evidence, project options, branch ancestry, temporary state, durable attachments and currency accounting.
- Migration fixtures exercise published schema 10 and schemas 27–32 through 33, preserving representative messages, active profile references, run/tool evidence, credential references, disabled-profile values, FTS, foreign keys and sequence allocation. Legacy message/profile defaults are repaired without resetting values. Existing plaintext credentials survive the old provider-connection migration until startup verifies their vault write and clears the legacy value. This does not claim fixtures for every historic version or credential state.
- Facts and memory-bearing request receipt contents use the encrypted secret vault. Room metadata/derived graph, imported documents and vector indexes remain app-private, not universally application-encrypted.
- Temporary chats are excluded from automatic memory and portable backups. Central cleanup handles memory, vectors, approvals, workspace records and files not referenced elsewhere. OS backup/device transfer is disabled; explicit selective backups remain available. Active temporary data can exist in Room; this is not forensic secure erasure.
- Anonymous daily cost totals survive deletion so deleting a chat cannot reset a spending allowance. Message/model/profile identity is removed from those retained cost records.
- Project deletion removes project memory/shared copies and detaches conversations. Branches retain their own write scope and exclude post-fork evidence.
- Plugin exports omit credentials, grants, bindings and URL paths/queries. Non-HTTPS endpoints are omitted and require setup through the existing connection screen.

## Validation

Commands, dependency maintenance and device acceptance steps are in [validation.md](../validation.md).

- Full JVM/Robolectric suite: **1,449 passed; zero failures, errors or skips**, including credential-preserving upgrades and signed-URL permission-label regression checks.
- Fresh hash-locked gateway environment: **22 tests passed**, `pip check` passed, including FastAPI startup, authentication, revocation, pairing and cross-device isolation.
- Performance comparison script: **3 tests passed**.
- Resources: **25 XML files passed**. Regex: **173 literal expressions checked**, 25 dynamic expressions skipped, zero errors.
- Debug, benchmark target and unsigned minified/resource-shrunk release APKs, instrumented-test APK and macrobenchmark test APK built with strict dependency verification. Debug, benchmark and release APK native/model integrity checks passed for arm64-v8a, x86_64 and universal variants.
- Android lint passed with zero errors (1,625 warnings and 12 hints remain). ktlint 1.3.1 passed on all 95 changed/new Kotlin files; resource/regex, Room schema history and whitespace checks passed.

## Remaining limits and device work

- No device/emulator was attached. Native inference, Keystore behavior, touch/scroll/unread placement, lock/share lifecycle, folding/resizing, thermal performance and actual NPU execution need representative devices before marking the PR ready.
- Benchmark/profile-generation fixtures are supplied; **no measured Baseline Profile or performance improvement is claimed**. Generate/filter a production profile and compare on the same physical device.
- Memory evaluation measures synthetic lexical/exact-identifier recall and safety/scope guards, not semantic paraphrase or multilingual model quality. Aliases do not turn the English-oriented encoder into a multilingual model.
- Broad document questions receive bounded, coverage-labelled extracts. Full hierarchical model-generated summaries and semantic quality evaluation remain further work; missing text is not presented as summarized.
- Settings discovery covers the feature registry and destinations; override inspection covers reasoning, delegation and tools, not every nested plugin field.
- Adaptive layouts provide conversation list/detail and branch comparison. A persistent source pane and comprehensive keyboard/predictive-back device acceptance are not claimed. Accessibility expansion is excluded.
- Model guidance explains measurements; it does not autonomously tune models or prove a task-specific routing policy. Budget estimates are not invoice ceilings and do not automatically price tool fees/cache discounts.
- Evidence pinning and reviewed refresh are implemented; automatic publisher/duplicate grouping and a full citation-resolution engine are not claimed. GitHub checks describe the inspected SHA, not an unsubmitted proposal.
- Recipes are deferrable, tool-free and budget guarded. They do not guarantee exact times, inherit write grants or perform unattended mutations.
- Modern MCP implements the documented negotiated capability set, not universal conformance. Unsupported extensions fail explicitly; ambiguous writes are not automatically retried.
