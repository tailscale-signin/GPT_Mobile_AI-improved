# Android Deep Research — implementation sign-off

Branch: `feat/android-deep-research-2026-10-09`

This upgrade builds on the latest fixes in research draft PR #631. It keeps orchestration, ordinary HTTP retrieval, bounded extraction, progress and the evidence ledger on Android. Inference uses the existing selected delegate; choosing an on-device model makes inference local too. Calling a public website or an enabled hosted search provider still requires network access.

## Delivery checklist

- [x] Quick, Research and Exhaustive controls within Delegation settings, with optional Papers and Studies discovery.
- [x] Reuse the existing `web_search` aggregator, enabled provider selection, permissions, quotas and execution ownership. Limit native aggregate engine execution to three simultaneous calls.
- [x] Native `read_url` first; connected page readers are an explicit, initially disabled fallback. No Python, Chromium, Playwright, Crawl4AI, Docling or embedding runtime is bundled in the APK.
- [x] Native domain filtering before each request/redirect, bounded page attempts, read concurrency, extraction bytes, rounds, queries and total research deadline.
- [x] Research questions with requested evidence, relevant link selection, targeted follow-up/counter-evidence queries and stopping on no additional readable evidence.
- [x] Separate discovered leads, read/partially read pages, blocked and failed sources. Search snippets cannot pass quote validation.
- [x] Exact supporting passages, source IDs, engine attribution, available publication dates, retrieval timestamps and read status.
- [x] Extract claims, validate that quotes actually occur in retained text, and assess each claim against that passage. Preserve rejected/insufficient claims as limitations.
- [x] Bounded content-overlap checks flag likely copies; engine counts and model agreement never establish independent corroboration.
- [x] Live job counters, evidence history sheet, and Stop and summarize. Stopping cancels in-flight research and retains completed evidence for the normal answer.
- [x] Atomic app-private history, conversation deletion, Conversations backup/restore selection, and temporary-chat memory-only handling.
- [x] Recover saved discoveries/reads for the same run/task/policy within 15 minutes. Completed queries are not repeated; refreshed credentials/permissions remain enforced by wrapped tools for new calls.
- [x] Optional native Crossref bibliographic discovery through the authorized page reader. Metadata is a lead, not full-text evidence.
- [x] Document optional llama.cpp and gateway improvements separately below.

## Phone defaults and ceilings

| Setting | Quick | Research | Exhaustive | Hard phone ceiling |
|---|---:|---:|---:|---:|
| Successfully read pages | 0 | 12 | 24 | 30 |
| Research rounds | 1 | 2 | 3 | 3 |
| Additional link levels after a search result | 0 | 1 | 2 | 2 |
| Simultaneous page reads | 2 | 2 | 2 | 3; one per host per batch |
| Page timeout | 15 s | 15 s | 15 s | 30 s |

The user's shared model/tool limits remain authoritative and can end a job sooner. A pass stops starting new work after 256 KiB of returned tool data; an already running batch can finish. Native HTTP bodies retain their existing 1 MiB download ceiling. Retained page excerpts are at most 6 KiB each, discoveries at most 120, accepted claim records at most 30, and page attempts at most twice the page allowance. Search queries are capped at nine per pass. Model calls retain existing delegation budgets and thermal/battery handling. These defaults need real-phone benchmarks; page counts are not quality guarantees.

Presets do not silently change which search engines a profile has enabled. Free Search and SearXNG remain optional remote services: their Python/server runtimes cannot be downloaded into Android as ordinary plugin files. Existing keyless native search is the phone-only discovery path. An authenticated bridge is still required for configured Free Search/GitMCP services; no third-party search API key is distinct from transport authentication.

## Evidence and history semantics

“Supported” means the review model found support in the exact quoted passage. It is not a truth probability or proof of independent verification. The final handoff includes exact passages and explicit limitations; rejected claims do not become accepted findings. A missing publication date stays unknown. Related-content flags are conservative overlap hints, not publisher identity guarantees. Retrieval failure never upgrades a snippet into evidence.

History is separate from personal memory. Temporary conversations do not write this ledger to disk. Persistent records live in app-private `research-history`, use atomic writes, and are included only with Conversations backups. Disk history retains the latest 48 jobs; live state retains 24. Conversation deletion removes its records and prevents a running job from rewriting them. Recovery applies to the same run, task, tool selection and settings; there is no automatic cross-conversation reuse of evidence. Restore does not automatically execute jobs. Existing Android run recovery controls whether an interrupted run is resumed.

Native reading currently handles text/HTML/JSON/XML. PDFs, scanned documents, XLSX and slides need a configured external document reader; this change does not claim native OCR or layout/table extraction. Optional connected readers may render pages on their server, but native phone research does not launch a headless browser. Blocked pages remain blocked. Do not configure authenticated personal browser sessions as unattended research tools.

## Recommended llama.cpp server additions

These are deployment recommendations, not changes made to your PC:

1. Keep the existing chat-completions endpoint for planning, extraction, review and synthesis. Add a compact dedicated research profile with a bounded context/output budget and one active local inference request initially. Serialize it with large-model work if GPU memory is tight. Verify JSON response-format and tool-template support on the selected model/build.
2. Optionally run a **separate reranker model instance**, using llama.cpp's reranking endpoint. The documented setup requires a compatible reranker model and `--embedding --pooling rank`; supported releases also expose `--reranking`. Check `llama-server --help` on your installed build. Rerank only a small candidate set (e.g. 20–40 passages), then send the best passages to synthesis. Do not enable embedding-only mode on the main chat process.
3. Optionally run a **dedicated embedding model** through `/v1/embeddings` for a larger server evidence cache. Android's bounded lexical ranking works without it. Use one shared cache owner and include model/version in cache keys.
4. Use `/health`, `/slots` and, when enabled, metrics for admission control and actual queue/latency reporting. Do not increase slots without measuring context-memory requirements on the 16 GB GPU.
5. Keep HTTP fetching, Crawl4AI/Playwright browser automation, and Docling parsing in separately managed gateway workers. llama.cpp is the inference service; these Python/browser dependencies do not belong in the Android APK or the inference binary. Expose bounded read-only operations, carry cancellation/deadlines, and protect non-loopback gateway access.
6. Reserve synthesis capacity before research starts; forward only selected passages, source IDs and limitations. Optional retrieval must never force a cloud inference fallback when a user chose local-only models.

Suggested optional capability order: local reranking; browser fallback only for unresolved JS pages; Docling only for document jobs. Heavy services remain off until explicitly configured. DeepWiki is already available in the marketplace for public repository interpretation; native commit-pinned GitHub reads remain the reference for implementation claims. Sequential Thinking is lower priority than tested retrieval and evidence tracking.

Primary references checked on 2026-10-09:

- https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
- https://github.com/sweetcornna/free-search-mcp
- https://github.com/unclecode/crawl4ai
- https://docling-project.github.io/docling/
- https://docs.devin.ai/work-with-devin/deepwiki-mcp
- https://www.crossref.org/documentation/retrieve-metadata/rest-api/

## Validation and remaining release gates

Focused production Kotlin compilation and 22 JVM regression tests cover exact-quote validation, unread-source rejection, combined domain preferences, settings bounds, checkpoint serialization, read budgets, cancellation, saved-run reuse, non-web delegation and budget exhaustion. This focused harness uses stubs at application boundaries; it does not compile the Android UI, dependency injection or storage implementation. Four Robolectric history tests cover persistence, temporary-chat isolation, deletion and storage ceilings. Native reader tests cover domain-restricted redirects and the expanded schema. Kotlin formatting, Android regex checks and resource preflight pass.

The draft audit fixed the delegation-settings declaration order and crawler ownership when research is unavailable, retained completed sibling reads on parent cancellation, prevented plain-text reads from being overwritten by a fallback, kept access-denied responses from escalating to another reader, counted fallback results toward the evidence budget, enforced two reviewed claims per source, and validated citations when restoring history. A valid empty research plan returns non-web tasks to the existing delegate; malformed plans do not claim that research was unnecessary. The branch also includes the latest OAuth health reset and output-limit retry fixes from PR #631.

The first full Android CI run compiled production/test Kotlin and Java and passed 2,060 of 2,064 tests, including history and native-reader tests. Its four failures identified the nested research timeout returning before the shared deadline, a concurrency test that still required five simultaneous requests, and a synthetic benchmark page below the reader's minimum content length. The follow-up fixes preserve the shared deadline, assert the three-request phone ceiling while querying all engines, and provide a complete synthetic benchmark page. Include/exclude domain preferences now use one provider-side filter and enforce both rules locally. These changes require the updated full CI run to pass before merging.

Local Gradle execution is blocked while downloading Gradle 9.8.0 (`Network is unreachable`); the installed Java runtime is 17 while the Android project requests 21. Compose interaction, process-death recovery, backup round-trip on a device and real provider/model performance remain device release gates. No claim of a successfully built APK or end-to-end phone validation is made here.
