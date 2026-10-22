# GitHub tool availability and branch validation repair

## Findings

The requested diagnosis describes a large GitHub schema being omitted by the 2,000-token ordinary catalog window. This branch already contained eight compact native GitHub operation tools, so the remaining repair concerns their runtime wiring and the discovery path, rather than adding another parallel GitHub backend.

Review started at `216858923ece62cac698ff9f701a6a69f9d3c2a5` on `fix/research-combined-delivery-2026-10-20`.

- Native GitHub operation expansion tested the concrete `GitHubTool` type after telemetry had wrapped it in `ObservedTool`. With the injected statistics repository present, that check failed: the hidden compatibility tool could survive without any compact executable schemas.
- Context planning created its own catalog and ignored an existing resolver catalog. Since an existing discovery tool prevents creating a second catalog, planning could charge the complete authorized set, including the hidden GitHub schema.
- Repository intent recognition differed between routing and schema selection. The catalog did not expand its fixed initial window after a GitHub discovery request, and generic GitHub MCP tools could receive zero relevance when compact native operations were prioritized.
- Cold tool admission independently imposed the ordinary 2,000-token cap even after the catalog selected a larger schema. Discovery receipts could then promise tools the runner had not admitted.
- The upstream system prompt listed authorized names before final schema selection. Wire diagnostics counted tools but did not identify their names; Gemini's grouped function declarations were counted as a single array entry.

The supplied diagnosis does not establish a GitHub authentication or API execution failure. No credential changes or permission expansion are part of this repair.

## Changes

Native GitHub operations are expanded before telemetry wrappers. The ordinary repository entry surface remains compact (`github_context`, `github_search`, `github_read`); detailed PR, CI, patch, commit and release contracts are selected by task or discovery. `github_context` also exposes a `status` action backed by the existing `repo_status` implementation. Compatibility actions remain available internally without advertising the large legacy schema.

Repository routing and operation selection share recognition of git, repo/repos, repositories, branches, commits and related terms. Explicit discovery gives requested tools priority over lower-priority retained schemas and can expand the schema window to the existing 6,000-token advanced ceiling. Cold MCP acquisition also prioritizes authorized GitHub connections for repository synonyms. Unknown or revoked tools remain unavailable.

Planning uses the same resolver catalog as execution and omits hidden schemas from context cost. Cold admission uses the advanced ceiling plus the actual remaining context/output allowance, subtracting the initially advertised schema cost rather than all authorized schemas. Every provider round also checks warm discoveries against remaining context, including the replay cost of discovery itself. Immediately before the next request, rejected discovery entries are removed from `available_next_round` and named in `context_budget_excluded`.

The primary prompt now directs the model to the schemas attached to each exact request instead of publishing a stale named inventory. Catalog diagnostics distinguish oversized schemas, schema-window/count limits, deferred tools and context/authorization filtering. Resolver diagnostics identify cached-catalog misses, remote tools not listed, connection failures, alias collisions, chat exclusions, consent requirements and equivalent-provider selection. Wire diagnostics list actual serialized tool names across OpenAI, Anthropic and Gemini shapes. They do not log arguments, credentials or source payloads.

## CI repairs

The prior head's native smoke test still expected the old one-token probe and 32-token maximum after the production governor had changed to an eight-token probe with growth bounded by configured capacity. The JNI test now checks the actual governor contract and complete input progress; the deterministic C++ governor test continues to cover growth, slowdown and bounds.

The Windows gateway failure was a test fixture's SQLite connection left open by a transaction-only context manager. It now closes explicitly, and the gateway package manifest carries its new checksum.

The full Android and GenieX suites accumulated enough Robolectric heap state to trigger production memory admission in later reader tests. Normal test workers now use a 1 GiB maximum and recycle after 64 classes. The separate constrained-heap gate explicitly reasserts 256 MiB and one class per process after AGP configuration, and restricts class scanning to its two intended suites so unrelated filtered classes do not each start an empty worker. No production heap guard or test assertion is disabled.

Search-state calculation and deferred-result construction are extracted from the runner's execution loop to keep the added admission checks within the existing Android DEX method-size limit.

## Validation

- Fresh Kotlin and test-source compilation passed with build cache and incremental Kotlin compilation disabled. A transient generated-Hilt-file filesystem race interrupted the first transform attempt; retrying the unchanged source passed that step.
- The DEX size guard passed on fresh project DEX outputs: 1,162 guarded methods, with `AgentRunner.executeLoop` at 15,858 code units, `AgentToolResolver.resolve` at 14,464, and maximum `ChatRepositoryImpl.delegateToProfile` at 16,156. The exclusive ceiling remains 16,383. The inspected ZIP contains project DEX files and is not an installable APK.
- All 16 changed Kotlin/KTS files passed ktlint 1.3.1; `git diff --check` passed.
- Gateway validation passed 190 Python tests and seven browser tests, including package manifest verification.
- The updated JNI smoke driver passed four runs: Debug and Release libraries with both ChatML and Jinja fixtures. Existing native binaries were reused after comparing their source checkout's C++/header/CMake files with this revision; no new native binaries were built for this follow-up. The deterministic native prefill-governor regression also passed.

The full Android JVM suite passed 3,917 tests across 643 suites, with zero failures/errors and two opt-in heap cases skipped. The previously failing academic evidence and real document-reader tests passed. The separate 256 MiB heap gate passed all six tests across its two suites with zero failures/errors/skips, including the retained-evidence and 2,000-message export cases. Both worker commands used `-Xmx256m`; all 646 existing normal-suite report files remained byte-for-byte unchanged. This follow-up does not claim a new packaged APK, device instrumentation, authenticated GitHub service validation, or a GenieX build.
