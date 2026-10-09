# News and Trends reliability

News keeps per-provider diagnostics: requested/effective endpoints, HTTP status when available, attempt number, result count, retrieval time and safe error codes. Bodies and credentials are not logged. Status distinguishes `ok`, `partial`, `no_results` and `unavailable`. Valid empty RSS is different from a blocked request or HTML challenge.

Google News follows at most three HTTPS redirects on the same host and standard port. Transient network failures, 429 and selected 5xx responses receive at most three attempts with backoff within a 20-second provider deadline. Short Retry-After values are respected; longer cooldowns go directly to fallback. Cancellation propagates. Each response is limited to 1 MiB and RSS parsing runs off the UI thread. The Google/fallback chain has a 45-second total deadline.

Failed or empty Google feeds trigger Bing News RSS with the same query and regional market. Alternate articles have `feedProvider: bing`; `fallbackAttempted` and `fallbackUsed` distinguish attempts from usable results. `source=google` selects the Google-first chain, including this labeled fallback. Hacker News stays independent and interleaved for `source=all`. Provider availability is not guaranteed. Topic/location actions continue to use keyword searches.

Country or its `geo` alias overrides the configured region; both must agree if supplied. Country/geo, language and `regionSource` are explicit in the response. Existing configuration/backup fields continue supplying defaults.

Trends have individual country-specific Explore links plus `sourceFeedUrl`. Traffic remains an approximate bucket. Up to five related RSS news items supply context and can populate an otherwise empty summary, labeled `summaryKind: related_headlines`. Related article URLs join the sources list. Headlines do not prove why a topic trends. `related_queries=false` omits related context. RSS does not provide related-query metrics: `relatedQueriesAvailable` stays false and `relatedQueries` is empty when included. Missing summaries remain explicitly unavailable.

The plugin does not cache results. Fresh network requests may receive provider-cached content: diagnostics include valid HTTP Age as `cacheAgeSeconds`, otherwise `cacheAgeKnown: false`, plus Date/Last-Modified when available. Fetch time is not publication time or a guarantee of current news. There is no silent stale fallback.

References: [Google Trending Now help](https://support.google.com/trends/answer/3076011?hl=en), [Bing News RSS announcement](https://blogs.bing.com/search/April-2008/News-Search-%E2%80%93-now-with-RSS). The supplied October 9 report motivates the changes; it does not establish the exact failure cause. Web lookup could not read live Google/Bing RSS, so live CA top/search and US Trends checks remain necessary on a device.

Regression coverage includes redirects, bounded retries/fallback, empty versus invalid feeds, regions, trend context, cache metadata, cancellation and disabled requests. Local ktlint, whitespace/Android regex checks and 51 Python script tests pass. Android tests/build require JDK 21/SDK CI and have not run locally.
