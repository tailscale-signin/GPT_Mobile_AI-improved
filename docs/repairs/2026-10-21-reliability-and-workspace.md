# Research, Combined, local inference and GitHub reliability repair

This implementation follows the two October 20 repair documents, the diagnostic review of app 0.9.55.0 / build 124, and the GitHub integration audit. Its starting revision is `5ca4cead455f61742fb223501063d6690f0a9c9a`.

The implementation was reconstructed after an earlier temporary checkout was lost. Validation from that checkout does not certify this source. The validation section below records only checks rerun on the reconstructed implementation.

## Completion and recovery contracts

### Examine and source retrieval

`ResearchDocumentResult` separates extraction from presentation. The real URL reader can return a structured document whether or not link collection is requested. Research and Examine use the same result: requested and final URL, retrieval status, format, content identity, saved body references, completeness and typed failure information. HTML, plain text and PDF are exercised through the actual reader and router, including a real local HTTP fixture.

Publisher access failures, redirect exhaustion, offline transport, rate limits, memory admission and exhausted work admission are separate recoverable conditions. An unavailable source does not become a negative scientific assessment. The coordinator retains its checkpoint; an unfunded fallback does not immediately repeat the same impossible acquisition. Examiner configuration is explicit, and pending work is reconsidered using the relevant conversation/profile configuration revision.

The reader carries bounded head-only DOI, PMID, PMCID and publication-type metadata separately from visible text. Metadata enrichment follows the same body, access and final-domain admission checks. A conflicting DOI is reported without replacing the existing capture's identity, version or authority.

### Structured stages and report delivery

Transport completion, retained partial output, schema acceptance and stage acceptance are separate outcomes. A provider disconnect cannot turn an incomplete structured response into a completed extraction. Up to 128 KiB of partial output can be saved privately through the captured journal generation; the result reports a retained checkpoint only after that write succeeds. Transport failure enters recovery rather than semantic repair. The reasoning-only repair allowance respects the verified provider route ceiling and explicit user limits.

One report validator surrounds the complete output-limit recovery session and any admitted queued follow-ups. Physical continuation suffixes are assembled before report validation. Each physical request still receives its own authorization, context admission and usage receipt. Frozen Combined finalization keeps its framed delivery contract and leaves new queued prompts for the next turn. Corrections identify rejected paragraphs by exact ID and prior content hash; accepted paragraph bytes cannot be replaced by the correction response. Unavailable correction preserves the valid paragraphs and yields a labelled partial evidence report, with unresolved delivery requirements retained.

Delivery records contain report validity, evidence availability, packet and revision digests, source/claim/block identities, output allowance and retry eligibility. A stale generation or deleted task cannot persist an accepted receipt. Current authority is rechecked after buffering, before publication and when storing receipts. Both the UI and Combined contributor selection require the receipt to match the exact answer revision they display or consume. Scientific coverage is not claimed merely because transport, JSON parsing or structural citation checks succeeded.

References headings do not terminate validation. Only exact registered bibliography lines are treated as navigation; subsequent findings still need support. Factual and numerical headings are checked independently, and a heading's citation cannot supply evidence for an uncited paragraph beneath it.

New quantitative extraction binds declared values, units, populations, outcomes, comparators and qualifiers to the exact source version and supporting passage. Report validation rejects changed declared relationships and cannot bypass the binding requirement by omitting the fact records. This is a literal grounding check for supported quantity/unit forms, not independent scientific review or an inference engine for undefined units and every bare numeral. Restored legacy records remain distinguishable from newly bound evidence.

### Combined task identity and funded delivery

Accepted task identity remains bound to the original chat, user message, request digest, participants and authority. Stage instructions are separate from that original identity. Synthesis, repair, restart and legacy recovery validate the stored parent instead of treating an application stage envelope as a new user request.

Required contributor analysis, contribution delivery and bounded correction/continuation receive separate work allocations. Optional expansion cannot consume the final writer's protected allocation. Failed contributors with usable retained evidence remain eligible inputs; failed transport is not silently promoted to successful completion. Retry merge, retained-reply selection and successor snapshots retain publication fences, original identity and immutable audit provenance.

Resume Writing loads the accepted turn's evidence manifest and saved context. Source version, body and quote identities and current authority are checked before reuse. A newer empty snapshot cannot shadow richer authorized evidence. Continue Research resumes acquisition; Choose Examiner resolves configuration; Cancel Writing targets the active writer. These are separate actions in the conversation UI.

The accepted task also retains one immutable question/query plan for authorized contributors. Reuse checks the original chat, root, task, participant and generation; sharing the plan does not grant access to another contributor's evidence. Simultaneous first contributors can propose plans, but only the first accepted plan becomes the shared plan.

## Bounded memory and truthful runtime progress

Research session metadata is indexed by chat, turn and authority before source bodies are decoded. Session and transient evidence caches have byte limits. Full source bodies remain in app-private storage with stable references. A Java heap admission gate bounds simultaneous parsing and export work independently of native-model memory and system RAM.

Debug export writes redacted records directly to files and ZIP entries. Integrity verification is also streaming and checks the manifest, digests and required conversation schema. The legacy reader remains compatible with the five-entry export format. Production no longer builds a complete research JSON tree, a redacted duplicate and a final full-size string together.

The bundle has a 64 MiB total uncompressed limit. An oversized export fails explicitly rather than silently dropping records.

GGUF progress now carries operation and physical-attempt identities, processed/total prompt tokens, sampled tokens, actual batch size, measured prefill rate, model name and actual runtime through IPC, provider events, the watchdog and UI. Only advancing counters extend the no-progress allowance; repeated heartbeats do not. The overall execution deadline remains separate. Cancellation, timeout, acknowledgment and worker disconnection share one terminal-receipt claim so races cannot produce contradictory terminal records.

The CPU prefill governor starts with a useful bounded batch and adapts within configured capacity. It backs off on slow batches. This changes the tested algorithm; it does not establish a throughput gain on any particular phone or model. The actual runtime and effective batch are observable so device benchmarks can make that decision.

## GitHub task and evidence service

### Compact operations and consistent routing

The model-facing surface is split into `github_context`, `github_search`, `github_read`, `github_pr`, `github_ci`, `github_stage_patch`, `github_commit` and `github_release`. The legacy implementation remains a compatibility backend. Progressive selection keeps task-relevant operations available and retains repository intent across follow-up instructions. Initial discovery, later discovery and execution use the same per-operation native/MCP preference. Additional authorized MCP capabilities remain available.

Gateway execution schemas accept supported annotations and retain the full supported contract for validation. Compact model schemas are separate. Rejected schemas produce diagnostics, and validated catalogs are revision cached. Read classification is separate from mutation identity: mutable observations can refresh, immutable content may be reused in its authorized scope, and an uncertain mutation still permits read-only reconciliation. Keys preserve the case of paths, refs, queries and contents.

### Android native snapshots, index and patches

A repository task retains the credential/account scope, connection, conversation, task, repository, branch, commit and tree. Source reads and searches use the pinned snapshot. CI and merge-readiness observations identify the actual SHA they checked. Switching to another explicit snapshot does not silently change an existing evidence handle's identity.

The persistent lexical index stores paths, definitions, references, imports and line information by blob identity. Unchanged blobs are reusable across revisions. Cold indexing is bounded and exposes continuation and partial coverage. Warm searches use retained local data. This implementation explicitly reports lexical coverage; it does not claim compiler-grade symbol resolution. Oversized or truncated trees and symbol records remain visibly incomplete.

The file ingestion allowance is separate from the model excerpt allowance. Multi-range reads return exact commit/path/line evidence, stable handles and expandable continuations. Long lines and larger source files are bounded independently. Exact patch staging checks head and original blob hashes, rejects ambiguous anchors, reconstructs full files locally and supplies a bounded preview before the expected-head commit pipeline runs.

CI log capture reads a bounded local body and extracts error windows, including failures after the old 32 KiB prefix. Results state log coverage and the checked head. PR files and checks use coherent page sizes and nonduplicating continuation contracts. Changes since review compare exact saved and current revisions.

These index, patch and snapshot implementations belong to the Android native repository service. Gateway operations expose their configured, authorized upstream capabilities; the gateway does not independently implement the Android local index.

### Scheduling, cache and output budgets

Credential-scoped scheduling limits concurrency, prioritizes visible work, joins identical reads and shares rate-limit state. Mutable metadata and immutable blobs have separate invalidation behavior. Network reuse and model-context reuse are reported separately. Combined profiles share explicitly classified immutable source reads; each model still receives the excerpts it needs.

Tool results remain structured until bounded presentation. Retained result cursors preserve snapshot, coverage and continuation information. Gateway output limits are applied to the final assembled result, including PR preload, rather than capping one prefix and appending unbounded material afterward.

MCP discovery separates an authorized known schema from current execution health. A cached catalog is available immediately while refreshes share bounded work. Current defaults use a two-second interactive wait, an eight-second refresh budget and a five-minute catalog lifetime. If an upstream operation ignores cancellation, it retains its physical in-flight ownership until completion so another request cannot start a duplicate producer. A gateway output budget too small to fit the required identity and continuation receipt returns an explicit `TOOL_VIEW_CAPACITY` result.

## UI and diagnostics

Workspace has a theme-aware repository session card with account, branch, revision, freshness, capability and partial-index status. Search this revision, Explain failed CI, changes since review, exact patch preview and request-efficiency details use the same native service. Optional PR check enrichment is explicit and bounded. Visible sections load on demand; a failed request does not become an automatic reload loop. Draft persistence observes actual edit state, debounces it and fences a delayed write against the latest draft before closing.

Dispatch rechecks the live connection, policy and credential identity. A removed or changed connection requires the workspace to reopen; rejecting that dispatch preserves the user's draft.

Research shows readable sources, claims checked against current passages, independently reviewed claims and report acceptance separately. Retained replies and merge recovery remain visible in Combined. Local chat shows actual prompt processing and CPU/Vulkan runtime rather than an inherited preference or manifest filename.

Diagnostic parsing separates metadata from event text, keeps `processId` numeric, normalizes the full prefix and identifies heap exhaustion. Frame records include screen, active operation and Java heap context; normal jank samples are not all promoted to incidents. Invocation usage distinguishes interim/final measurements, estimates, lower bounds and reservations. Only a physical attempt proven unsent can be refunded after DNS failure. Missing usage components remain unknown.

The primary request coroutine is split into typed, non-inline admission, context, acquisition, dispatch and streaming stages. The APK check reads actual DEX instructions and requires the guarded methods to stay below the configured 16,383-code-unit ART guard threshold, including generated nested methods. PR validation also runs the explicit 256 MiB heap fixtures and the native prefill-governor regression; UI render images are retained with the test results.

## Continuation review: publication, recovery and repository races

The resumed implementation keeps the unpublished checkpoint fixes and adds regression coverage for the remaining publication and recovery paths:

- Explicit **Use Reply** publishes the exact selected contributor revision, survives a Room reload, and cannot be replaced by the older active answer. Once a validated retry passage selects the latest revision, later callbacks carrying the old selection cannot silently restore the archived contributor. Fallback evidence receipts bind to the rendered text hash. Missing, stale or invalid Combined delivery reports cannot create a final publication receipt; startup reconciliation leaves a closed delivery outcome intact.
- Restoring a captured source preserves its bounded passage, including claims quoting text after the former 6,000-character cutoff. General evidence merges and richer-revision recovery retain acquisition high-water counters and extraction receipts. Question identity excludes the ID field without modifying the question text. Rejected grounding remains an unaccepted stage even when transport and schema parsing succeeded.
- Groq retains trailing usage through its explicit transport marker. Compatible in-stream usage remains interim until completion proof, and malformed tool arguments produce one failed terminal result. Output-limit recovery discards incomplete tool calls while preserving the limit diagnosis and any useful text; the classifier also recognizes the existing Groq limit wording. Provider error codes remain available for diagnostics. The extra final usage frame is documented in the [Chat Completions streaming contract](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events).
- PR file/check continuations are independent and preserve each other’s UI position. File reads and continuations reject changed PR head/base revisions. Repository metadata updates preserve a newer selection and concurrent evidence handles. Exact patches reject overlapping ambiguous anchors, and a stage cannot publish against a revision changed while its source files were loading.
- Suppressing repeated arguments for a source read no longer forces an immediate tool-free answer while other authorized reads remain available. One suppression-only round allows another source to be chosen; a second consecutive round finalizes from retained results. Executable work resets that counter. The per-argument limit and overall run limits still apply.
- Streaming export preserves numeric and boolean journal metadata. Its small nested-value writer permits scalar JSON values while the input reader and bundle verification retain their strict checks. Combined lifecycle fixtures now assert the intended provider failure and retained contribution text, so a mock-memory setup failure cannot satisfy a recovery scenario accidentally.
- The constrained-heap gate sets report paths after AGP has configured the unit-test tasks. XML, HTML and binary results are isolated from the normal suite instead of being redirected by AGP's later defaults.

## Validation of this source

Application validation was rerun on October 21, 2026 against implementation commit `85f8bedf81911cc5dde44a2400fc5a89d9ddbfa1`. The subsequent validation commit changes test-report configuration and documentation only. No previous checkout's pass count is reused. The packaged `BuildConfig.APP_COMMIT` field independently identifies that implementation commit in all three APKs.

| Gate | Result |
| --- | --- |
| Android JVM suite | 3,899 tests discovered: 3,897 passed, zero failures/errors, two opt-in heap cases skipped in the normal suite |
| 256 MiB session/export stress fixtures | Six tests passed with zero failures/errors/skips; separate XML, HTML and binary outputs verified, with all 1,299 existing normal-suite report files unchanged |
| Android lint | Passed with zero errors, 2,923 warnings and 24 notes |
| Android instrumentation sources | `compileDebugAndroidTestKotlin` passed; device tests were not executed |
| Debug APK assembly | ARM64, x86_64 and universal APKs built successfully |
| Packaged runtime and telemetry checks | All three APKs passed runtime inventory/hash, JNI/dependency, memory-model, ELF alignment and forbidden telemetry-marker checks |
| Packaged DEX dispatch-size guard | All three APKs passed; 634 methods checked per APK, maximum 16,156 code units against the exclusive 16,383 limit |
| Robolectric Compose renders using native graphics, including narrow/large text | Four tests passed; three production-surface renders visually inspected |
| Native ARM64 and x86_64 CPU/Vulkan libraries | Both freshly compiled and packaged; current JNI exports, source dependencies and at least 16 KiB ELF LOAD alignment verified |
| Changed Kotlin formatting | All 223 changed `.kt`/`.kts` files passed ktlint 1.3.1; whitespace check passed |
| Active gateway Python and browser suites | 190 Python and 7 browser tests passed |
| Native CPU prefill governor host regression | One production-header regression passed |
| Repository validation scripts | 147 tests passed |
| Regex, Room and tool-statistics checks | Passed: 713 literal regex patterns checked, 43 dynamic patterns skipped; exported Room schemas checked; all 33 tool-statistics producers explicitly covered |

The constrained cases include more than 40 MiB of retained evidence across 90 sessions and 900 sources, and a separate export exceeding 40 MiB across 2,000 messages. Both large cases assert a JVM maximum heap of at most 256 MiB. Full-suite totals come from the completed normal-run console log; the constrained results are a separate invocation and are not additive. The initial heap run exposed AGP overriding the requested report paths. After that configuration was corrected, the focused rerun passed all six cases and preserved all 1,299 existing normal-suite report files byte for byte.

Lint's remaining findings are predominantly missing translations (2,025) and unused resources (442). Review of findings on changed production files found no new runtime or security blocker. This is not a warning-free lint result.

The largest guarded method is `delegateToProfile` at 16,156 DEX code units, 227 below the failing threshold. The actual `invokeSuspend$executePreparedRequest` implementation is 6,288 code units. The passing guard must remain in place because the largest method has limited headroom; it does not establish physical-device compilation or execution timing.

Reports are retained under `app/build/test-results/testDebugUnitTest`, `app/build/test-results/heapRecovery`, `app/build/reports/lint-results-debug.html` and `app/build/reports/reliability-ui`. The inspected renders are `research-recovery-dark.png`, `workspace-light.png` and `workspace-dark-large.png`. APKs are under `app/build/outputs/apk/debug`.

| APK | SHA-256 |
| --- | --- |
| `app-arm64-v8a-debug.apk` | `de80781cd3e66091f1d2ac11e0233a79673114114327bd8e1878c470168e86f4` |
| `app-x86_64-debug.apk` | `bd7b9f62330d87b420ede0694954e78a2acea6cab3e73f76b58a359bce5d4385` |
| `app-universal-debug.apk` | `6fcae547d25ceaa097b953bdd302639a37131105037dfad4c4a9604f76c26c32` |

The new behavioral fixtures exercise reader/router integration; 125 PR files and 45 checks; warm immutable content; late CI failures; exact patch restart; concurrent discovery deadlines; mutable read freshness; read timeout recovery; structured partial responses; current-version claim grounding; accepted task and publication fences; actual ledger/budget admission; export integrity and redaction; and native progress forwarding.

Useful commands (JDK 21 and the repository's Android SDK/NDK requirements apply):

```bash
./gradlew -Pksp.incremental=false :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug
./gradlew -Pksp.incremental=false -I scripts/heap-stress.init.gradle :app:testDebugUnitTest --tests '*ResearchSessionStoreHeapTest' --tests '*StreamingDebugBundleTest'
python gateway/check_gateway.py
python -m unittest discover -s scripts/tests
python scripts/test_gguf_prefill_governor.py
python scripts/check_tool_statistics_coverage.py
python scripts/check_local_runtime_apk.py app/build/outputs/apk/debug/*.apk
python scripts/check_no_telemetry.py app/build/outputs/apk/debug/*.apk
python scripts/check_research_dispatch_size.py app/build/outputs/apk/debug/app-universal-debug.apk
```

Physical-device throughput, Android background/process-death timing, live provider reconnect behavior and authenticated remote mutations require their actual device/service environment. Automated fixtures and native builds are evidence for the paths they execute, not measurements of those external systems. No production release or merge is part of this repair branch.
