# Amazon Research Free: native preview

This is the first usable stage of the custom Amazon add-on. It registers a separate integrated service, `amazon_free`, in the existing Toolkit and profile controls. It does not need an API key, desktop, Node/Python process, or MCP bridge. Public-page availability is unproven in a live Android environment, so Canada and US are explicitly marked as previews.

## Current behavior

| Capability | Implementation |
| --- | --- |
| Search | `amazon_search__free_native`: one public results page, up to 10 products; price filters and sorting apply to that page only |
| Details | `amazon_get_products__free_native`: 1–5 distinct ASINs; one page per ASIN, independent failures retained |
| Markets | `amazon.ca` / CAD and `amazon.com` / USD; English HTML only |
| Controls | Global and per-profile opt-in, off when no stored choice exists; live grant checks and cancellation when the global/profile grant or network permission is revoked |
| Product cards | Existing `amazon_products_v1` rendering, canonical untagged Amazon link, actual retrieval timestamp, preview/currency/context labels |
| Bounds | 200-character query, exact marketplace/ASIN validation, 2 MiB decoded HTML, three redirects, 5–120 second operation timeout, bounded JSON output |
| Request budget | Atomic persisted ledger in `noBackupFilesDir`; 1–100 physical requests per UTC day, shared across profiles and marketplaces, including redirects and failed attempts; serialized requests at least five seconds apart |
| Blocking | No automatic retry; 24-hour challenge cooldown; HTTP 429 honors a bounded Retry-After or uses six hours; cooldowns survive restart |
| Data handling | No cookies, credentials, affiliate tags, raw HTML storage, raw response/error logging, or result sharing between profiles |

The existing SerpApi Amazon service and JanNafta desktop MCP bridge remain independently controlled. Switching off remote MCP connections does not switch off this native HTTP service; a profile's **Disable remote tools** and **Disable all tools** controls do.

Search and detail tools use the existing canonical tool classifications so the chat product cards recognize their results. Native results deliberately opt out of the shared read-only turn cache until cached consumers can recheck their current grants. The provider uses a dedicated Ktor/OkHttp client rather than the general credential-bearing network client.

## Enable and exercise the preview

1. Open Settings → Toolkit, expand **Amazon Research Free**, and enable it globally.
2. Open its configuration, choose Canada or US, and set results, output, timeout, sponsored-result preference, and daily allowance.
3. Click **Test search · 1 request**. This is an explicit request, counts against the allowance, and never runs during discovery. An individual successful test does not certify marketplace reliability.
4. In the intended AI profile's Tools panel, opt in to **Amazon Research Free**. Other profiles remain off.
5. Ask for a product search, then details for a returned ASIN. Check the market, canonical link, timestamp, price currency, and partial error output.
6. Disable the profile or global grant during a request; it must cancel and withhold product facts. Re-enable and confirm no request was queued automatically.

Bare `$` without confirmed currency remains display text, never a numeric amount. Missing/currently blocked prices preserve other verified product facts and include `PRICE_UNAVAILABLE`. Prime/sponsorship facts are included only when evidence is present. Shipping, tax, coupons, delivery region, seller, variant, and comparable offer identity are not inferred; the current price is a base item observation, not a checkout total or historical low.

## Source and dependency ledger

| Source | Pinned version/commit | Reuse | Notice |
| --- | --- | --- | --- |
| [JanNafta/amazon-mcp](https://github.com/JanNafta/amazon-mcp) | `46fc2d0caa26941ba8ae801ff6e240be73c9fc04` | Search card, title, star, review count, and image selector patterns from `src/lib/amazon-scraper.ts`, adapted into `AmazonHtmlParser.kt`; new currency, identity, price-scope, challenge, and bounds checks | Full upstream MIT text and copyright in `app/src/main/assets/licenses/amazon-mcp.txt`, available through Settings → Licenses |
| [jsoup](https://jsoup.org/) | `org.jsoup:jsoup:1.23.2` | DOM parsing only; no jsoup networking | Full MIT text and copyright from the verified jar in `app/src/main/assets/licenses/jsoup.txt`, available through Settings → Licenses |
| [Ktor](https://ktor.io/) | `io.ktor:ktor-client-encoding:3.6.0` | gzip/deflate decoding before the decoded byte limit | Apache 2.0; the existing Ktor dependency notices provide the license text |

Other candidate repositories are not copied in this stage. Hosted providers' free quotas do not become an unlimited free backend by combining repositories. Reuse of another source requires a verified license and pinned provenance entry first.

## Verification and release gate

The HTML test fixtures are small, synthetic examples with dummy ASINs and product facts. They are not scraped customer content and do not establish live Amazon reliability. Unit coverage targets parsing, currency and identity ambiguity, invalid model input, grant revocation, partial results, output bounds, redirects, decoded body size, quota/cooldown persistence, and resolver isolation.

Before describing a marketplace as supported, run the plan's live multi-device/search/detail reliability evaluation, including blocking and cancellation scenarios, on Android 12+. A debug build and mock transport tests do not satisfy that gate. No background scheduler is started by this stage.

## Next implementation stages

1. Add Room observation/history storage with precise offer keys, retention, and migrations; migrate the operational ledger atomically without resetting usage or cooldowns. The present ledger is intentionally excluded from backups. Its daily reset assumes the device's forward UTC clock; it protects against backwards date resets but is not a server-enforced allowance.
2. Add explicitly confirmed watches, WorkManager scheduling, persistent lifecycle states, notifications, and disable/restore behavior. Failed/ambiguous prices must never trigger a price-drop alert.
3. Add comparisons, deal evidence, authorized optional historical data, and bounded product images. First observation must never imply historical coverage.
4. Add optional hosted/seller/B2B adapters only behind separate credentials, consent, and spend budgets. Do not advertise account actions or purchases as consumer search.

History, watches, alerts, seller/review text, checkout, affiliate attribution, additional markets, hosted-provider fallback, and cross-market currency conversion are not exposed by the native preview tools.
