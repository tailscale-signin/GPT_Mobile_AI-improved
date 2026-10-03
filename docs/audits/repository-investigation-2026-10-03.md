# Repository investigation and feature roadmap

**Reviewed:** 3 October 2026. **Branch:** `v0.9.30.0`. **Source commit:** `281c38f3f93140979ea3b44c0ed95f9826cb8269`. **Draft PR:** [#592](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/592).

The best next investment is to finish the connections between memory, documents, projects, and conversation history, while adding device performance evidence and fixing the gateway's setup and access controls. The repository already contains substantial functionality; several opportunities below complete existing foundations rather than introduce another parallel system.

This is a source-based product and engineering investigation, with targeted checks and upstream documentation review. It is not a device usability study, a penetration test, or a claim that every line has been audited. No application code was changed for this investigation.

**Scope and evidence**

The inspection covered the Compose navigation and major settings/chat screens; Room entities and migrations; memory capture, vectors, graph and document retrieval; provider/context accounting; agent/delegation and queue flows; MCP and GitHub integrations; LiteRT/QNN routing; backup boundaries; the Python gateway; dependencies; and CI.

The checkout contains 599 main Kotlin/Java source files, approximately 97,929 source lines, one Android Gradle module, 204 JVM test source files, and seven instrumented test source files. These are inventory counts, not coverage measurements.

| Verification | Result | What it establishes |
| --- | --- | --- |
| Existing local JVM/Robolectric XML reports | 1,424 tests; zero failures, errors, or skips | The preceding implementation's local suite passed; it was not rerun for this documentation-only audit. |
| Gateway contract suite, rerun during investigation | 17 passed | Selected pure functions and response contracts work. The tests extract functions using Python AST; they do not install or start the complete server. |
| Android regex check, rerun | 163 literal patterns checked; 24 dynamic patterns skipped; zero errors | Checked literal patterns pass the repository's compatibility rules. |
| Room schema check, rerun | Passed | Exported schemas match the committed migration history. |
| Resource preflight, rerun | Passed; 25 XML files checked | The preflight's selected XML/resource checks pass. |
| PR CI, final observed snapshot | PR Validation, Remote diagnostics, CodeQL, Kotlin lint, Build Android APK and Generate Debug Version succeeded. Build Unsigned Release was skipped. | These results are for the reviewed commit; skipped release work is not counted as a passing release build. |
| Device/native/performance execution | Not performed in this investigation | Native inference, semantic quality, TalkBack, large fonts, gestures and sustained thermal performance remain device validation work. |

**Already implemented and worth preserving**

- ObjectBox HNSW fact retrieval with an on-device sentence encoder; encrypted authoritative memory; recurring-topic evidence; conservative deduplication; retention controls; source metadata; edit/pin/review/delete; deletion tombstones; optional local model extraction. Cloud recall is off by default.
- LangChain4j token-window selection with app-owned cost estimates, complete-turn retention and separate system/tool/output budgeting.
- Durable queued prompts, request-boundary delegation follow-ups, persisted agent runs, and gateway job recovery. These are not the same as arbitrary mid-stream provider steering.
- Multi-provider profiles, reasoning controls, delegate/reviewer roles, separate crawler selection, and bounded search/crawl orchestration.
- MCP tool/resource/prompt browsing, OAuth, form elicitation, output checks, connection diagnostics and persistent tool approvals.
- GitHub ETag and blob caching, GraphQL repository context, rate-limit observation, and branch-head-checked multi-file commits.
- Assistant response revisions, whole-conversation duplication, favorites, message search and windowed chat loading.
- LiteRT/QNN fallback and hardware/thermal controls, model validation/downloads, native diagnostics, profile benchmarks, and encrypted portable backup options.

The [release checklist](release-0.9.30.0-feature-checklist.md) and [memory implementation notes](../ON_DEVICE_MEMORY_V0930.md) describe the current release. New recommendations should extend these paths.

**Priorities**

P0 means fix before distributing or exposing the affected component. P1 means high-value next work. P2 means valuable expansion after foundations. P3 means exploratory. Effort is relative: S = localized work, M = several coordinated components, L = a subsystem or significant device work. These are not delivery commitments.

| ID | Priority | Improvement | Effort | Finding type |
| --- | --- | --- | --- | --- |
| 01 | P0 for gateway network use | Gateway authentication and deliberate network exposure | M | Confirmed source gap |
| 02 | P0 for clean gateway installation | Correct gateway dependencies and startup tests | S | Confirmed source gap |
| 03 | P1 | Finish project workspaces and project memory | L | Existing foundation is disconnected |
| 04 | P1 | Persistent hybrid document retrieval | L | Confirmed retrieval limitation |
| 05 | P1 | Phrase-based, multilingual recurring-topic learning | M–L | Confirmed heuristic limitation |
| 06 | P1 | Move optional memory enrichment out of response startup | M | Confirmed serial work; latency impact unmeasured |
| 07 | P1 | Memory provenance, corrections and conflict history | M | Extend existing controls |
| 08 | P1 | Measured memory quality and retrieval benchmarks | M | Evaluation opportunity |
| 09 | P1 | MCP protocol compatibility and capability reporting | L | Verified SDK/specification gap |
| 10 | P1 | Narrower remembered permissions and action previews | M | Extend existing approvals |
| 11 | P1 | Non-destructive conversation branches | L | Confirmed editing limitation |
| 12 | P1 | App-specific performance and native device gates | M–L | Confirmed verification gap |
| 13 | P1 | Searchable settings and effective-setting explanations | M | UX integration opportunity |
| 14 | P1 | Localization and accessibility completion | M–L | Confirmed localization gaps; device audit needed |
| 15 | P1 | Consistent private-session, deletion and storage controls | L | Extend existing privacy features |
| 16 | P1 | Simpler, reproducible CI and dependency management | M | Confirmed duplication/setup gaps |
| 17 | P2 | Local OCR and page-aware document sources | M–L | Confirmed extraction limitation |
| 18 | P2 | Voice input and read-aloud, then interruption-aware voice | L | No current speech implementation found |
| 19 | P2 | Android share-in and selected-text actions | M | No receive intent filters found |
| 20 | P2 | Tablet/foldable conversation and source panes | M–L | Adaptive layout opportunity |
| 21 | P2 | Explainable model routing and device tuning recommendations | L | Extend existing runtime/benchmark systems |
| 22 | P2 | Money-aware usage and delegation budgets | M | Token limits exist; currency accounting absent in ledger |
| 23 | P2 | Per-response context inspector | M | Extend existing context notices |
| 24 | P2 | A persistent research evidence workspace | M–L | Extend existing citations/tool traces |
| 25 | P2 | Visible task recovery and capability-aware follow-ups | M–L | Extend existing queues/gateway recovery |
| 26 | P2 | Versioned plugin configuration and compatibility checks | M | Extend existing plugin settings |
| 27 | P2 | GitHub review workspace with evidence and diffs | M–L | Extend existing API operations |
| 28 | P1/P2 | Reduce duplicate implementations and large-file coupling | L, incremental | Confirmed duplication; some candidates need reachability proof |
| 29 | P1 | Accurate feature, privacy and release documentation | S | Confirmed documentation mismatches |
| 30 | P2/P3 | Reusable task recipes and optional scheduled read-only work | L | New feature proposal |

**01 — Gateway authentication and network exposure**

Evidence: [gateway_v12.py](../../gateway/gateway_v12.py) sets `GATEWAY_HOST = "0.0.0.0"`. Its FastAPI construction and job-list/result/cancel, MCP-restart and chat routes have no application authentication dependency or middleware in the inspected source. A catch-all proxy also forwards requests to the local backend. This makes access depend on the host's firewall, reverse proxy or private-network configuration; it does not prove that a deployed instance is publicly reachable.

Add authenticated pairing, revocable per-device tokens, job ownership, an explicit bind address, and a deliberate LAN/private-network setup flow. Protect control and job endpoints as well as completions. Keep loopback as the default until network access is configured. Validate tokens rather than merely parsing a bearer header.

Acceptance: unauthenticated clients cannot list/read/cancel jobs or restart MCP; one paired device cannot read another's jobs; token revocation takes effect; documented private-network access works without weakening the default.

**02 — Gateway installation and startup coverage**

Evidence: v12 imports `requests` and `requests.adapters`, but [requirements.txt](../../gateway/requirements.txt) declares FastAPI, HTTPX, Pydantic and Uvicorn only. [README](../../gateway/README.md) still instructs users to run v10, while the [installer](../../gateway/install_gateway_v12.ps1) selects v12. [Contract tests](../../gateway/tests/test_gateway_v12.py) execute selected AST-extracted functions and therefore do not detect missing server imports or startup failures.

Declare the actual runtime dependencies, lock a tested set, make the canonical entry point unambiguous, and add clean-environment import/startup/health tests with model/MCP processes stubbed. Add pip dependency updates to the existing Dependabot configuration.

Acceptance: a fresh supported Python environment can install only the declared requirements, import the canonical app, start its test lifespan and answer a health request. Test teardown leaves no processes running.

**03 — Finish project workspaces**

Evidence: [KnowledgeEntities](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/knowledge/KnowledgeEntities.kt) and [KnowledgeDao](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/knowledge/KnowledgeDao.kt) already define projects, instructions and chat links. No production caller of `saveProject`, `attachChat` or `projectForChat` was found. Normal [chat orchestration](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt) calls memory without a project scope, and [document context](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/knowledge/MemoryDocumentRepository.kt) calls `scopedChunks(chatId, null)`.

Add a project selector, shared instructions/documents, project-specific model/tool presets, and explicit personal-memory sharing. Thread a resolved scope through automatic capture, local enrichment, graph tools, document retrieval, delegation and backup. A project must not silently inherit unrelated project facts.

Acceptance: two projects with contradictory preferences retrieve their own facts; a shared project document is available across linked chats; personal-memory sharing is visible and controllable; project deletion has a clear document/memory policy.

**04 — Persistent hybrid document retrieval**

Evidence: [DocumentRagEngine](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/DocumentRagEngine.kt) implements keyword and optional vector search, but [MemoryDocumentRepository](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/knowledge/MemoryDocumentRepository.kt) supplies chunks without embeddings and calls keyword search. It rebuilds the in-memory index per query. Cross-document search preselects chunks using SQL `LIKE` and limits them before ranking. General requests fall back to the first five chunks, which is not a whole-document summary strategy.

Reuse the existing on-device embedding infrastructure for a separately versioned document index; combine persistent lexical retrieval with vector ranking and diversity selection. Preserve document hash, chunk offsets and scope. Add a bounded hierarchical summary path for broad document questions, with transparent coverage when a document is truncated.

Acceptance: paraphrases retrieve relevant passages without shared keywords; exact identifiers remain easy to find; changed documents invalidate stale vectors; removed sources never reappear; long-document summaries disclose their coverage. Compare accuracy and p95 latency at increasing document sizes before raising limits.

**05 — Better recurring topics and multilingual capture**

Evidence: [RecurringTopicLearning](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/RecurringTopicLearning.kt) currently tracks individual words with an English stop list, considers at most 16 distinct terms per message, and retains at most 512 topics. [MemoryLearning](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/MemoryLearning.kt) uses predominantly English phrase patterns and opt-out rules. This can fragment a topic such as “Android development” and gives weak coverage for other languages; the exact quality impact needs evaluation.

Add language-aware phrase candidates, canonical topic aliases, source-grounded entity resolution and semantic clustering. Keep “discusses this topic” separate from “personally prefers this.” Expand opt-out and sensitive-content tests in the same languages as capture. Promote topics using evidence from distinct messages, with recency and confidence controls.

Acceptance: synonymous topic mentions reinforce one topic; multilingual opt-outs work; quoted material and hypothetical statements do not become personal facts; corrections preserve negation and qualifiers.

**06 — Faster, incremental memory processing**

Evidence: the chat path awaits `prepareTurn` and `enrichTurn` before preparing the main provider request. [FactVaultRepository](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/FactVaultRepository.kt) serializes the snapshot when it changes and refreshes derived stores. [MemoryGraphRepository](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryGraphRepository.kt) avoids identical fingerprints but rewrites vault-derived graph rows when the fingerprint changes. [LocalDelegationCoordinator.memoryObservations](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/LocalDelegationCoordinator.kt) requires another enabled LiteRT profile and excludes this enrichment path when the source is also LiteRT. These are source observations, not measured latency numbers.

Keep immediate explicit-memory requests durable, but run optional enrichment through a persistent, deduplicated background queue with an inference resource budget. Update graph/vector entries incrementally. Show whether extraction is rule-based, waiting for a local model, or complete. Permit deferred extraction using the same local runtime after a local response finishes.

Acceptance: optional extraction does not delay main-response startup; deletion and disabling memory cancel pending capture; process death safely resumes work; a local-only setup can enrich memories without requiring two simultaneously loaded models.

**07 — Explain memory and preserve correction history**

Evidence: [FactVaultScreen](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/FactVaultScreen.kt) already supports editing, pins, enable/disable, review and deletion. `VaultFact` stores sources, occurrences, confidence and supersession, but cards mostly show a source type and numeric chat/message IDs.

Make sources tappable, show supporting evidence, distinguish auto-disabled conflicts from pending review, and provide a compact correction timeline. Add “why this was recalled,” “wrong topic,” bulk review, and explicit merge/split actions. Do not display a model's private reasoning; show stored evidence and deterministic retrieval factors.

Acceptance: users can reach the originating message, understand a replacement, correct a mistaken association, and see the correction reflected in graph and vector recall after restart.

**08 — Memory quality as a benchmark category**

Evidence: [benchmark code](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/BenchmarkModels.kt) covers response speed, reliability, task fixtures, JSON and tool success. That does not establish memory extraction or retrieval quality.

Add a versioned local evaluation corpus covering paraphrases, repeated interests, conflicting facts, dates, multilingual input, project isolation, deletion and quoted-source contamination. Measure fact precision, missed durable facts, retrieval recall at K, invalid merges, deleted-fact recurrence, indexing time, p95 recall latency and peak memory. Run 4,096- and 16,384-fact device scenarios. Compare encoder changes against the current baseline rather than assuming a larger model is better.

Acceptance: every memory-model or consolidation change produces comparable quality and resource results; isolation/deletion cases have zero accepted regressions in the fixture suite.

**09 — MCP compatibility, then durable tasks**

Evidence: [version catalog](../../gradle/libs.versions.toml) pins Kotlin MCP SDK 0.15.0, which the upstream release page lists as latest. The resolved SDK's protocol constants extend through `2025-11-25`; [McpClientManager](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt) uses initialization/session APIs. Upstream identifies `2026-07-28` as the current protocol, and its Kotlin implementation tracking issue remains open. Therefore “latest SDK” does not mean full current-protocol support. This is not evidence that existing backward-compatible servers fail.

Expose negotiated protocol/capability information and test against explicit old/new server fixtures. Introduce a transport boundary that can adopt current per-request negotiation and input-required exchanges while preserving supported older servers. Track upstream rather than maintaining an unnecessary permanent SDK fork. Add optional task progress/result recovery only when both sides advertise compatible support. Do not apply old `Last-Event-ID` resumability rules indiscriminately: the current Streamable HTTP specification no longer supports that mechanism.

Acceptance: supported servers pass a published compatibility matrix; unsupported capabilities have actionable errors; disconnect recovery never replays an ambiguous write. See sources U1–U4 below.

**10 — More precise permissions and useful action previews**

Evidence: [ToolTrustStore](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/permissions/ToolTrustStore.kt) persists grants by connection identity and tool, or whole provider. It does not encode repository/path/action restrictions, expiry or a tool-schema fingerprint. [ToolApprovalDialog](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ToolPermissionDialogs.kt) shows an argument preview. The existing approval manager already tracks ambiguous/repeated actions.

Add “this conversation,” “this repository,” and time-limited grants; a searchable grants manager; and schema-change review. For GitHub edits, show actual file diffs, destination branch and expected head, with a final revalidation before execution. Continue treating server-provided read-only annotations as untrusted hints.

Acceptance: a grant for repository A does not authorize B; expired grants stop applying; changes to destination or tool contract invalidate relevant grants; the approved diff matches what is committed.

**11 — Preserve alternative conversation paths**

Evidence: [ChatViewModel.saveUserMessageEdit](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt) trims messages after an edited question before continuing. [MessageV2](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/MessageV2.kt) preserves assistant revisions, and whole-chat duplication exists, but neither provides a branch tree for user edits.

Add “branch from here,” preserve the original continuation, label alternatives and allow comparisons. Capture the selected model, settings and context on each branch. Only the active path should enter the next prompt or influence automatic memory learning.

Acceptance: editing an old question preserves the original branch; switching branches restores the correct conversation path; exports and backups preserve branch relationships; inactive alternatives are excluded from active context.

**12 — Prove performance on devices**

Evidence: [settings.gradle.kts](../../settings.gradle.kts) includes only `:app`; no app-specific Macrobenchmark/Baseline Profile module or JankStats usage was found. Existing model benchmarks are useful but measure different things. Seven instrumented test files cannot substantiate the README's blanket 60/120-fps claims.

Add release-like startup, long-chat opening, unread-response positioning, fast streaming, settings navigation and background/foreground benchmarks. Generate app-specific Baseline Profiles, and measure their benefit. Add physical Qualcomm and non-Qualcomm device lanes for QNN/LiteRT fallback, memory encoder/index operation, sustained thermal load and cancellation. Keep emulator tests for functional coverage and use physical hardware for representative timing.

Acceptance: publish baseline and changed-run TTID/TTFD, frame timing, peak memory and native throughput/thermal results for named devices and workloads. Set regression thresholds from these baselines, not an assumed universal frame rate. Sources U5–U7.

**13 — Make the growing settings surface discoverable**

Evidence: [SettingScreen](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt), [AdvancedSettingsScreen](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/AdvancedSettingsScreen.kt) and [PlatformSettingDialogs](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/PlatformSettingDialogs.kt) provide many controls across global, profile and conversation layers. There is no central settings search in the inspected navigation/settings flow.

Create one typed settings registry with searchable names/synonyms and deep links to the correct subtab. Show effective values and their origin: global default, profile or conversation override. Offer per-section reset, an “only changed settings” filter, and coherent presets such as Private local, Balanced and Research. Extend theme previews with contrast, large-font and reduced-motion previews rather than more decorative switches.

Acceptance: searching for crawler, reasoning or memory lands on the actual setting; users can identify and reset an override; presets disclose changes before applying and do not silently enable cloud data sharing.

**14 — Localization and accessibility**

Evidence: a heuristic scan found 628 `Text("...")` occurrences in main Kotlin/Java source, including interpolated text. This is not an exact count of translatable strings. Of 744 base string keys, German defines 56 matching keys and Arabic, Japanese and Spanish each define 81; missing translations fall back to the default language. No automated accessibility-check integration was found.

Move user-facing strings to resources, prioritize the chat/settings/onboarding paths and add pseudo-localization. Exercise TalkBack, switch access, RTL, 200% font scale, sliders, selected tabs, charts and modal focus. Use Compose accessibility checks plus manual device testing. Respect reduced motion throughout streaming and theme transitions.

Acceptance: priority journeys work at large font sizes and in RTL; controls expose meaningful roles/state; charts have text summaries; dialogs return focus; visible labels and announced values remain consistent. Source U8.

**15 — Consistent privacy, deletion and storage controls**

Evidence: [SecretVault](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/security/SecretVault.kt) protects authoritative memory in the no-backup area, while [MemoryGraphRepository](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryGraphRepository.kt) stores derived names/observations in Room and [LocalSemanticMemory](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/LocalSemanticMemory.kt) stores vectors in an app-private no-backup directory. These are different protection layers. `deleteChatsV2` deletes chat rows without an explicit corresponding vault-memory purge. Android backup exclusions cover the chat database and token preferences; [diagnostic logs](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/AppLogRecorder.kt) use `filesDir` when enabled. This warrants a documented data inventory, not a claim of observed leakage.

Add temporary conversations that disable saving, capture, indexing and content diagnostics; optional app lock and screen-preview privacy; a storage dashboard; and “delete chat plus learned memories” with shared-evidence handling. Make OS backup policy explicit for logs and other content. Evaluate stronger derived-store protection against the supported threat model before claiming every memory representation is encrypted.

Acceptance: temporary content is absent after process restart; deletion clears applicable vault/graph/vector entries and queued extraction; shared memories follow an explicit policy; backup/restore tests verify every selected data class. App lock alone must not be presented as database encryption.

**16 — CI, dependencies and reproducibility**

Evidence: [PR Validation](../../.github/workflows/pr-validation.yml) and [Remote diagnostics](../../.github/workflows/remote-diagnostics.yml) both run unit tests and lint; APK/debug workflows also build debug artifacts. The current [Dependabot config](../../.github/dependabot.yml) covers Gradle and Actions, not gateway pip. Gateway constraints are lower bounds; no Gradle dependency-verification metadata or locking configuration was found. Native/model checksum work already exists and should remain.

Use reusable jobs and a single artifact-producing build, leaving diagnostics dispatch available on demand. Add gateway lock/update coverage, Gradle dependency verification, and release dependency/native inventories. Expand migration fixtures across supported upgrade starting versions; the concrete [AuditMigrationTest](../../app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/database/AuditMigrationTest.kt) covers v27 to current, not every historic user database. Add a focused device lane before release.

Acceptance: unchanged checks run once per intended PR event; clean-environment builds resolve approved artifacts; each supported migration starting point preserves representative messages, attachments, credentials references, queue and memory state. Source U9.

**17 — OCR and page-aware sources**

Evidence: [DocumentTextExtractor](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/DocumentTextExtractor.kt) reads PDF text/Office content and bounds extraction to 80,000 characters or 100 PDF pages. Empty scanned PDFs ask users to attach images or use a capable provider. Attachment indexing excludes images. Existing source links point to text chunks and character offsets, not PDF page regions.

Add local OCR for scanned PDFs/images, selective page import, document thumbnails and tappable page-region citations. Store page/section provenance during extraction. Offer cancellation, progress and a low-memory page-at-a-time mode. ML Kit's bundled text-recognition models are an Android option; choose bundled versus downloaded language packs deliberately so the offline promise is accurate.

Acceptance: scanned documents become locally searchable; citations open the right page; mixed text/scanned PDFs avoid duplicate extraction; unsupported languages are disclosed. Source U10.

**18 — Build a real voice path**

Evidence: README advertises `VoiceSessionCoordinator`, but no current class/caller or SpeechRecognizer/TextToSpeech implementation was found in the Android source. The manifest declares speech-service queries but no microphone permission. This does not exclude keyboard-provided dictation; it means an app-owned voice conversation feature is not established by the current source.

Start with explicit push-to-talk transcription and selected-response read-aloud. Use Android on-device recognition only when availability checks pass; expose any network fallback as a separate choice. Add language handling, audio focus and lifecycle cleanup. Treat interruption-aware/full-duplex voice as a later milestone requiring a suitable continuous ASR/audio pipeline, echo handling and physical-device evaluation; do not assume SpeechRecognizer alone supplies it.

Acceptance: voice input works on a supported offline device; unsupported devices receive an accurate state; stopping speech releases audio resources; speaking over playback behaves predictably. Source U11.

**19 — Share content into the app**

Evidence: [AndroidManifest](../../app/src/main/AndroidManifest.xml) declares launcher and pairing routes but no `ACTION_SEND`, `ACTION_SEND_MULTIPLE` or selected-text receive filters. Outgoing sharing already exists.

Add Android share targets for text, URLs, images and supported documents, with a project/conversation destination chooser and “summarize,” “ask about,” or “save to knowledge” actions. Use the existing attachment pipeline. Import into a draft first; receiving a share should not automatically upload content or start a provider request.

Acceptance: browser/gallery/file-manager shares arrive intact; URI grants are handled safely; cancellation removes temporary files; a cold start preserves the draft. Source U12.

**20 — Tablet and foldable layouts**

Evidence: chat/settings use primarily full-screen navigation and width-based bubbles/dialogs. Existing responsive sizing is useful, but no canonical adaptive list-detail scaffold was found in the inspected main flows.

Use a conversation list/detail layout on wide screens, an optional source/memory pane, resizable model comparisons and keyboard navigation. Preserve single-pane phone behavior. Compose's adaptive list-detail scaffold is a suitable implementation option.

Acceptance: resizing or folding preserves the selected conversation, draft and scroll anchor; pane navigation works with predictive back and keyboard focus; dialogs do not assume phone widths. Source U13.

**21 — Explainable model and accelerator recommendations**

Evidence: [LocalRuntimeRouter](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/LocalRuntimeRouter.kt), [DeviceHardwareGovernor](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/DeviceHardwareGovernor.kt) and existing profile benchmarks already provide fallback, hardware state and measurements.

Use those observations to recommend a model/backend/context preset for the current device and task. Explain the tradeoff among capability, memory, heat, speed and privacy. Offer a repeatable calibration wizard and retain per-model/backend results. Make cloud fallback opt-in and visible; separate measured suitability from static capability metadata.

Acceptance: recommendations identify their evidence and confidence; unsupported NPU packages are never selected as generic fallbacks; privacy restrictions and required tool/vision capabilities win over speed scores.

**22 — Money-aware budgets**

Evidence: [InvocationLedger](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/accounting/InvocationLedger.kt) stores provider/model/kind, input/output counts, estimates, timing and status. It enforces token allowances, but has no currency/pricing snapshot fields.

Add estimated spend by conversation, project, profile and delegate role, including cache/reasoning categories where providers supply them. Snapshot price provenance and time; label unknown prices as unknown. Reserve estimated cost before starting parallel delegates and provide soft alerts and explicit caps. Reconcile actual usage when reported, while explaining that an app estimate cannot guarantee a provider invoice ceiling.

Acceptance: concurrent delegates share one reservation budget; estimated and reported quantities are distinguishable; unknown pricing does not appear as zero; provider price changes do not rewrite historical estimates silently.

**23 — Show what context actually went into a response**

Evidence: [ContextBudgetService](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/context/ContextBudgetService.kt) estimates tokens from UTF-8 bytes, assigns an attachment estimate and can omit old turns or tool schemas. LangChain4j uses these estimates; it does not make them tokenizer-exact. Chat diagnostics already expose some context notices.

Add a per-response inspector showing included history range, recalled fact references, document excerpts, exposed tools, omissions and output reservation. Show the applied conversation/profile/global settings and whether token values are estimated or reported. Allow a user to exclude an incorrect memory or attachment on the next retry.

Acceptance: the inspector corresponds to the actual assembled request and selected branch; secrets remain redacted; changing an exclusion affects the retry without rewriting the original audit record.

**24 — Research evidence workspace**

Evidence: [WebSearchResults](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/WebSearchResults.kt) normalizes URLs, titles, snippets and optional dates; [SearchCrawlStage](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/SearchCrawlStage.kt) bounds URL reading and reviewer ownership. These are good foundations for more visible research.

Add a persistent sources panel with searched/read/failed status, retrieval time, publisher, duplicate groups, pinned excerpts and claim-to-source links. Let users rerun a failed page read or refresh selected stale evidence without repeating an entire search. Preserve the distinction between a search snippet, a fetched page and a reviewer inference.

Acceptance: every displayed citation resolves to retained evidence or a clear unavailable state; failed reads are visible; refreshing sources does not silently replace evidence used by an earlier answer.

**25 — Task recovery and follow-up visibility**

Evidence: [DurablePromptQueue](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/queue/DurablePromptQueue.kt) persists work and pauses failed submissions; [AgentRunCoordinator](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/AgentRunCoordinator.kt) can recover interrupted gateway jobs. Existing delegation follow-ups are consumed at supported request boundaries.

Expose a unified task center with running, waiting-for-input, queued, paused and outcome-unknown states. Show whether a queued message will join the active delegation turn or become a separate question. Add “continue from saved evidence” for interrupted read-only research, and negotiated remote task handles when supported.

Acceptance: after process death, users can distinguish recoverable work from a restart; completed writes are not automatically replayed; queued text is consumed exactly once; the UI never promises mid-stream steering to a provider that lacks it.

**26 — Versioned plugin configuration**

Evidence: [ToolConnectionsScreen](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/ToolConnectionsScreen.kt) already has plugin-specific controls and generic execution limits. MCP resources, prompts and form elicitation are also present.

Add versioned setting schemas, validation before saving, effective-setting summaries, isolated connection tests, and explicit migration of older configs. Provide secret-free configuration export/import with required fields marked for re-entry. Show schema/permission changes when refreshing a server's tool catalog. Avoid making every plugin show irrelevant generic fields.

Acceptance: an invalid option is caught before a paid request; exported configs contain no credentials; imported settings validate against the installed plugin version; catalog refresh does not silently widen remembered permission.

**27 — GitHub review workspace**

Evidence: [GitHubWorkspaceClient](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/github/GitHubWorkspaceClient.kt) already supports repository context, rate-limit status and expected-head-checked multi-file commits. Another generic caching layer is not the highest-value addition.

Build a coherent branch/PR workspace around those APIs: changed-file tree, editable diff preview, test/check status, review evidence and commit destination. Attach evidence to the inspected commit SHA; invalidate or mark it stale when the branch moves. Offer selective refresh and a compact rate-limit/cache summary.

Acceptance: every proposed edit is reviewable before submission; a moved branch requires refreshing/reconciling the proposal; stale CI results cannot be mistaken for the proposed commit's results.

**28 — Consolidate architecture without a rewrite**

Evidence: `ChatViewModel` has 2,378 lines, `PlatformSettingDialogs` 2,321 and `ChatRepositoryImpl` 2,215 in this snapshot. The single `:app` module couples orchestration, providers, memory, runtime and large screens. `gateway_v12.py` and `gateway_v12.1.py` are byte-identical 21,135-line copies. The version-copy equality test and installer references must be accounted for before consolidation. The `presentation/unified` picker/queue screens have no caller found outside their own declarations; the associated data layer is DI-bound, so reachability still needs proof. The old Node MCP companion is explicitly retired, not an active integration to repair.

Extract stable boundaries around memory, provider contracts, tool execution and native runtime; split large composables into state-owning features; keep one canonical gateway implementation with compatibility entry points as needed. Verify reachability before removing alternate queue/catalog/backup code. Inventory APK contributions before choosing optional feature packages. Preserve ABI/native model compatibility and existing packaged-model integrity checks.

Acceptance: no new parallel queue/memory/settings authority is introduced; extracted contracts retain existing tests; installer and CI use the same gateway implementation; any removal has demonstrated unused reachability and no lost migration/backup path.

**29 — Match documentation to reality**

Evidence: [README](../../README.md) still references Room schemas 19 and 23 while [ChatDatabaseV2](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2.kt) is 32; advertises an absent voice coordinator; presents hybrid document embeddings although the production document caller uses keyword search; and makes absolute rate-limit, context-overflow and frame-rate claims. Its Apache-2.0 badge conflicts with the GPL-3.0 [LICENSE](../../LICENSE) and README footer. This is a metadata inconsistency, not a recommendation to change the project's license.

Publish a concise implemented/experimental/planned capability matrix and supported device/protocol table. Generate versions from the build configuration where practical. Describe estimates, fallback, offline behavior and device validation honestly. Correct the license badge to match the repository's chosen license after checking project provenance.

Acceptance: every advertised capability has a reachable flow and test or a clearly marked experimental status; release documentation identifies what was actually device-tested.

**30 — Reusable task recipes and optional scheduling**

Proposal: turn successful multi-step tasks into user-reviewed recipes: summarize selected documents, compare research sources, prepare a GitHub change proposal, or generate a project brief. Store allowed inputs, tools, destinations, budget and output format alongside the recipe. Start with manually launched recipes; add optional scheduled read-only checks only after recovery, notification and budget controls are solid.

Use the existing durable queue and approval system rather than inventing a second scheduler. Android persistent-work APIs are appropriate for deferrable work, but must not be described as guaranteeing exact run times. Treat write actions as separately reviewable; do not quietly reuse a broad old approval for a new scheduled action.

Acceptance: recipes are editable and cancellable, respect local-only settings, persist progress, and explain missed/deferred scheduled runs. Source U14.

**Recommended all-in-one local memory architecture**

Keep the existing ObjectBox + local encoder + encrypted fact authority + Room provenance/graph + LangChain4j context selection. Add a resolved scope as an explicit input throughout the pipeline. Capture exact evidence first; perform optional extraction and topic consolidation asynchronously; persist authoritative changes before updating derived indexes; retrieve facts and documents with separate but composable hybrid rankers; assemble only the bounded, allowed context; expose the result to users through source and context inspectors.

Use one versioned index identity containing encoder ID, dimensions and normalization policy. Rebuild derived indexes when that identity changes, with progress and a safe lexical fallback. Do not equate a higher vector dimension or a higher fact cap with better memory. Measure recall, erroneous merges, latency, RAM and disk on representative phones.

| Candidate | Recommended role in this repository |
| --- | --- |
| ObjectBox | Keep and extend the existing HNSW integration to document vectors and incremental updates. It already supports the core local vector requirement. |
| LangChain4j | Keep the existing complete-turn window integration; improve cost estimation and inspectability. Its presence does not supply exact token counts for every provider. |
| Mem0 | Continue borrowing the extract/search/update pattern. A remote Mem0 integration remains an explicit optional connected service, not part of the zero-server guarantee. Do not add a Python runtime to the Android memory path. |
| sqlite-vec | Consider only if measured requirements justify consolidating vector storage into SQLite and the native integration is validated. Adding a second vector engine now would increase migration and maintenance work. |
| Another embedding model | Evaluate a quantized multilingual candidate against the current bundled encoder and a fixed test corpus before changing the default. No specific replacement was device-tested during this investigation. |

**Suggested delivery order**

1. Fix gateway access/setup (01–02), correct documentation (29), consolidate duplicated CI checks and establish device baselines (12, 16).
2. Complete project scopes and incremental memory processing (03, 06), then document retrieval and topic quality (04–08). Ship this as one connected knowledge feature with measurable acceptance results.
3. Add branching, context inspection, searchable settings and the first accessibility/localization pass (11, 13–14, 23). Add explicit privacy/deletion controls alongside project memory (15).
4. Address MCP compatibility, scoped permissions and task visibility (09–10, 25–26), then build evidence/GitHub workspaces (24, 27).
5. Add OCR, share-in, adaptive layouts and basic voice based on user demand (17–20). Use measured model recommendations and budgets before adding larger autonomous recipes (21–22, 30).

Architecture consolidation (28) should accompany these boundaries incrementally. A whole-app rewrite or more unmeasured dependencies would increase risk without proving user value.

**Primary upstream sources checked**

These sources inform feasibility and current compatibility, not claims that their capabilities are already integrated. Accessed 3 October 2026. Recommendations above are engineering judgments based on the source inspection and these documents.

- **U1:** [MCP versioning: current revision 2026-07-28](https://modelcontextprotocol.io/docs/2026-07-28/learn/versioning).
- **U2:** [MCP current specification and optional extensions](https://modelcontextprotocol.io/specification/2026-07-28).
- **U3:** [Kotlin SDK releases](https://github.com/modelcontextprotocol/kotlin-sdk/releases) and [current-protocol implementation tracking issue #842](https://github.com/modelcontextprotocol/kotlin-sdk/issues/842).
- **U4:** [Current MCP Streamable HTTP transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).
- **U5:** [Android benchmarking overview](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview).
- **U6:** [App-specific Compose Baseline Profiles](https://developer.android.com/develop/ui/compose/performance/baseline-profiles).
- **U7:** [Measure Baseline Profiles with Macrobenchmark](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile).
- **U8:** [Compose accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing).
- **U9:** [Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html).
- **U10:** [ML Kit Android text recognition, bundled/unbundled options](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).
- **U11:** [Android SpeechRecognizer availability and lifecycle](https://developer.android.com/reference/android/speech/SpeechRecognizer).
- **U12:** [Receive shared content on Android](https://developer.android.com/develop/ui/compose/sharing/receive).
- **U13:** [Adaptive list-detail layouts](https://developer.android.com/develop/adaptive-apps/guides/list-detail).
- **U14:** [Android persistent background work](https://developer.android.com/develop/background-work/background-tasks/persistent).
- **U15:** [ObjectBox on-device vector search](https://docs.objectbox.io/on-device-vector-search).
- **U16:** [FastAPI authentication dependencies](https://fastapi.tiangolo.com/tutorial/security/first-steps/).
