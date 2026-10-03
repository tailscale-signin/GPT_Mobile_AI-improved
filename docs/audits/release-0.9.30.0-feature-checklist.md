# 0.9.30.0 implementation checklist

Branch: `v0.9.30.0`. Version code: 98. Review date: 2026-10-03.

This records the initial 20-request implementation at commit `281c38f`. The subsequent [approved roadmap implementation](approved-roadmap-implementation.md) extends it, advances Room to schema 33 and records current validation results. Counts below describe that earlier checkpoint.

| Request | Implementation |
| --- | --- |
| 1. Debug, Statistics, Benchmark | Three primary workspaces with their own subtabs, shared visual panels, live diagnostics, model comparisons, and existing benchmark execution/history. |
| 2. Intelligent memory | Unified local memory tool, ObjectBox HNSW, bundled sentence encoder, LangChain4j context window, repeated-topic evidence, reinforcement, and guarded consolidation. See [memory design](../ON_DEVICE_MEMORY_V0930.md). |
| 3. Plugins and remote MCP | Searchable catalog, workspace tabs, connection cards, health/permissions, and per-connection execution controls. |
| 4. Themes | Gallery, palette creation, and display controls; compact swatch cards and saved-theme management. |
| 5. Advanced toggles | Ten new controls: smooth streaming, unread centering, response animation, edge fades, timestamps, queued follow-ups, parallel search, search deduplication, GitHub conditional requests, and GitHub blob cache. Local model cache and native metrics are also exposed. |
| 6. Model profiles | Profile, Tools, and Advanced tabs; advanced generation/system/tool limits grouped together. |
| 7. Search discovery | Plugin/MCP names and descriptions identifying web search are admitted, including tools outside fixed provider aliases. Search execution validates supported query schemas. |
| 8. Crawlers | Removed from engine classification; explicit profile switch, multi-selection, and page limit. Post-search reads are restricted to returned URLs. With delegation plus review enabled, only the reviewer receives the stage's crawler tools; unavailable reviewers do not fall back to another model. |
| 9. Profile delegation | Per-profile opt-in, off by default, applied by tool resolution and chat execution. |
| 10. Conversation settings | Models opens first, current active profile is preselected, obsolete tabs/help removed, persisted reasoning override, delegation amount/depth controls shown when enabled. |
| 11. GitHub API | Credential-scoped bounded shared caches, conditional GET, immutable blob reuse, short list freshness, mutation/error invalidation, corrected rate-limit expiry, and configuration panels. Existing account, repository, branch, trust, and permission controls remain accessible. |
| 12. Plugin settings | Timeout/output controls plus plugin-specific search, calculator, date/time, location, file, URL, memory, delegation, and GitHub settings. Remote connections retain their server/authentication/tool-approval controls. |
| 13. Home conversations | Conversation cards use zero shadow elevation. |
| 14. Queued follow-ups | Eligible text-only, same-profile/model/config prompts transfer atomically into a running delegated conversation at a provider request/tool-round boundary. Paused, oversized, attachment-bearing, incompatible, concurrent-profile, Free, and LiteRT requests remain queued. Active HTTP streams are not mutated. If no subsequent boundary exists, the prompt remains queued. |
| 15. Dependencies | Official metadata audit retained in `dependencies-2026-10-03.json`. Gradle 9.8.0, Android target/build tools 37, ObjectBox 5.4.2, LangChain4j 1.21.0, MediaPipe Text 1.0.0; GitHub checkout action updated to pinned v7.0.1. Already-current stable dependencies retained. Preview releases are not substituted for stable production SDKs. |
| 16. LiteRT/QNN | Verified current stable LiteRT-LM 0.17.1 and QNN 2.50.0. Existing dispatcher/fallback/ABI integrity architecture retained; semantic memory participates in idle and memory-pressure handling. Native metrics and model-cache controls exposed. |
| 17. Optimization/cleanup | Removed unused legacy diagnostics package/database/service and drawable. Bounded caches, bulk graph synchronization, chunked SQL, batch index rebuilding, bounded crawl concurrency, and corrected build JVM memory defaults. |
| 18. Conversation entry | Existing chats align to the final anchor; unread assistant targets open at their beginning with optional viewport centering. |
| 19. Streaming scroll | Conflated smooth tail-following observes growth inside the last message, stops on manual upward scrolling, resumes at bottom. |
| 20. Restart | Failed/interrupted/cancelled assistant runs expose retry in the overflow menu when their profile is available; duplicate retries are blocked during active generation. |

## Upgrade compatibility

New settings have serialization defaults. Saved delegation configuration is gated by a new per-profile opt-in. The initial implementation retained Room schema 32; the approved roadmap follow-up adds an explicit migration to schema 33; ObjectBox uses a separately versioned generated model file committed under `app/objectbox-models`. ObjectBox's build preparation task currently cannot be serialized by Gradle's configuration cache, so configuration caching is disabled until the plugin supports Gradle 9. Incremental compilation and build caching remain enabled. Obsolete instrumented tests for unsupported pre-v10 Room migrations were removed; current migration history remains checked against every committed schema export.

The native model asset adds approximately 6 MB before APK compression; ObjectBox and MediaPipe also add native libraries. This is the cost of offline semantic inference. Runtime memory and quality claims require device measurements.

## Validation

Validated on 2026-10-03 with Java 21, Android platform/build tools 37, and Gradle 9.8.0:

- 1,424 JVM/Robolectric tests passed with no failures or skips.
- Android instrumented-test sources compiled, including real MediaPipe encoder/ObjectBox JNI persistence coverage and Compose bottom-follow behavior. Device execution still requires an Android device or emulator.
- Debug and minified/resource-shrunk release APKs assembled for arm64-v8a, x86_64, and universal targets.
- Release APK integrity checks verified the bundled encoder checksum, required memory libraries, all pinned LiteRT/QNN payloads, ABI policy, duplicate ZIP entries, and 16 KiB LOAD alignment for 24 universal host libraries.
- Android resources, regex compatibility, Room schema history, ktlint 1.3.1, and whitespace checks passed.

Hardware-dependent inference quality, thermal behavior, NPU compatibility, and instrumented gesture execution require representative devices and are not inferred from compilation alone.
