# Multi-web search

This implements the user-supplied **Multi_Web_Search_Implementation_Design.docx** in the existing `web_search` aggregate. No additional model-facing search tool, embedding service or analytics endpoint is introduced.

## Requests and limits

- `maxResults` remains the per-selected-engine contribution cap, 1–10 (default 10).
- `totalResults` adds a final source limit, 1–50. App-generated research requests use 20. Omitting it retains the legacy ceiling of engine count × `maxResults`.
- Each first page requests 10 candidates independently of the contribution cap. Discovered provider schemas and plugin settings clamp that count; unsupported parameters are omitted. Parsed results are capped locally.
- Eligible engines launch concurrently in a frozen selection order. Shared child wrappers retain consent, call reservations, byte limits, provider concurrency limits and cancellation. Consent preflight is serial and outside provider timeouts.
- Search attempts use an 8-second deadline and share a 12-second monotonic search-phase allowance. Queueing within the shared provider limit counts toward that allowance. Optional crawling has its existing separate 30-second ceiling.
- A shortfall can trigger one concurrent refill wave, only for successful full pages with supported pagination and remaining time/budget. A filled target never refills.

## Merge and source presentation

Candidates first interleave by engine and provider rank. Conservative URL identities group exact duplicates. Representative queues then interleave by their original contribution owner, with independent per-engine/final limits. The full bounded pool is processed before trimming, so a later duplicate can still add attribution to a retained source.

Navigation links remain unchanged. Identity removes reviewed tracking parameters and default ports while retaining schemes, `www`/other subdomains, raw path case, encoded separators, non-root trailing slashes, meaningful query values/order, duplicate query keys and unknown fragments. Invalid and credential-bearing links are excluded.

The local source checkpoint retains alternate URLs, snippets, provider attribution, duplicate reasons and match scores. The model receives one compact representative per emitted group. The source popup shows **Found by** labels and expandable **Similar coverage** without fetching pages or favicons when opened. Restored conversations retain these groups.

## Content rollout and rollback

Content matching defaults to **shadow mode**: it computes guarded duplicate proposals without suppressing sources. **Group Similar Coverage (Trial)** enables suppression; turning it off returns to shadow mode. URL grouping and request reuse have separate controls.

Matching uses Unicode-normalized three-token shingles, a 0.70 snippet threshold and corroborating title agreement (or title Jaccard ≥ 0.60). Each snippet needs at least 12 tokens and eight distinct shingles. Missing/generic titles, conflicting numbers/versions/product identifiers, explicit negation differences, differing publication dates, and unsupported word-boundary scripts remain separate. Matching compares the original representative, preventing transitive approximate chains. Candidate/comparison guards bound local work and disclose degraded content checking.

These rules group similar visible excerpts, not proven full-article identity. The default stays in shadow mode until labelled real-world review covers news, technical, Canadian and multilingual searches. The automated fixtures include copy matches and conflict/false-merge protection; they do not establish universal precision.

## Compatibility, reuse and diagnostics

Schema version 2 preserves `query`, `engines`, `results`, optional `pages`, legacy `completed`/`unavailable` status values, and per-source `engine`/`engines`. Additive fields expose attempt outcomes, requested/effective counts, contributions, refill flags, merge counts and separate search/crawl timings. Empty successful searches and no selected engines have distinct outcomes. Initial and refill budget snapshots use high-water marks, never sums.

Successful evidence has a bounded 30-second request cache. Its key includes the ordered selection, arguments, policy, owner route and configuration revision. Production cache hits require live child permission/configuration checks. Empty, canceled, failed and exhausted results are not cached. Gateway-owned engines retain their gateway route instead of joining a client-owned batch.

PR validation disables ObjectBox build analytics with `OBX_DISABLE_ANALYTICS=true`. All search counters are local. Device p95 merge latency, incremental allocation and live-search precision remain measurement work; no device-performance claim is made by this change.
