# Amazon Research Free: native preview with local history

This is the second usable stage of the custom Amazon add-on: native search/details plus local observations and explicitly saved manual watches. It registers a separate integrated service, `amazon_free`, in the existing Toolkit and profile controls. It does not need an API key, desktop, Node/Python process, or MCP bridge. Public-page availability is unproven in a live Android environment, so Canada and US are explicitly marked as previews.

## Current behavior

| Capability | Implementation |
| --- | --- |
| Search | `amazon_search__free_native`: one public results page, up to 10 products; price filters and sorting apply to that page only |
| Details | `amazon_get_products__free_native`: 1–5 distinct ASINs; one page per ASIN, independent failures retained |
| Markets | `amazon.ca` / CAD and `amazon.com` / USD; English HTML only |
| Controls | Global and per-profile opt-in, off when no stored choice exists; live grant checks and cancellation when the global/profile grant or network permission is revoked |
| Product cards | Existing `amazon_products_v1` rendering, canonical untagged Amazon link, actual retrieval timestamp, preview/currency/context labels |
| Bounds | 200-character query, exact marketplace/ASIN validation, 2 MiB decoded HTML, three redirects, 5–120 second operation timeout, bounded JSON output |
| Request budget | Atomic Room installation ledger; the previous `noBackupFilesDir` ledger is imported once without changing its usage, pacing or cooldowns; 1–100 physical requests per UTC day, shared across profiles and marketplaces, including redirects and failed attempts; serialized requests at least five seconds apart |
| Blocking | No automatic retry; 24-hour challenge cooldown; HTTP 429 honors a bounded Retry-After or uses six hours; cooldowns survive restart |
| History | Profile-scoped confirmed-currency observations in Room schema 34; search/detail pages remain separate incomplete-offer series; no persistence from ordinary temporary-chat lookups |
| Manual watches | Explicit native save/edit/pause/delete; 20 per owning profile; Check now uses the current remote grant and shared allowance; no background checks or notifications |
| Local tools | `amazon_get_price_history__free_native` (up to 100 returned points) and `amazon_list_price_watches__free_native` (up to 20); no model-selected owner or hidden network refresh; respect Disable local tools |
| Backup | Optional Amazon history/manual-watches section; restored watches pause or become orphaned, free-plugin grants are cleared, installation usage/cooldowns are preserved |
| Data handling | No cookies, credentials, affiliate tags, raw HTML storage, raw response/error logging, or result sharing between profiles |

The existing SerpApi Amazon service and JanNafta desktop MCP bridge remain independently controlled. Switching off remote MCP connections does not switch off this native HTTP service; a profile's **Disable remote tools** and **Disable all tools** controls do.

Search and detail tools use the existing canonical tool classifications so the chat product cards recognize their results. Native results deliberately opt out of the shared read-only turn cache until cached consumers can recheck their current grants. The provider uses a dedicated Ktor/OkHttp client rather than the general credential-bearing network client.

## Enable and exercise the preview

1. Open Settings → Toolkit, expand **Amazon Research Free**, and enable it globally.
2. Open its configuration, choose Canada or US, and set results, output, timeout, sponsored-result preference, and daily allowance.
3. Click **Test search · 1 request**. This is an explicit request, counts against the allowance, and never runs during discovery. An individual successful test does not certify marketplace reliability.
4. In the intended AI profile's Tools panel, opt in to **Amazon Research Free**. Other profiles remain off.
5. Ask for a product search, then details for a returned ASIN. Check the market, canonical link, timestamp, price currency, and partial error output.
6. Open a native product card’s **History & manual watch** button, or Toolkit → Amazon Research Free → **History & manual watches**. Inspect the owning profile, ASIN and original currency. Reading this screen sends no lookup. Save a manual target explicitly, then choose Check now to spend the request allowance. A listing price below the target still remains **Awaiting matching price** while offer identity is unknown.
7. Disable the profile or global grant during a request; it must cancel and withhold product facts. Re-enable and confirm no request was queued automatically.

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

## Persistence and manual-watch contract

`amazon_observations` stores decimal text, acquisition time, source type and an explicitly incomplete listing-series key. Missing prices are `amazon_check_events`, not zero-price points. UUID acquisition IDs differ from model call IDs; repeated processing of the same acquisition is idempotent. Temporary or missing chat rows fail closed for automatic capture. Explicit manual-watch checks are clearly identified persistent actions. Capture remains inside the live permission observer and a revoked grant rolls back an in-progress transaction.

History retention is 365 days with a 50,000-row soft cap; checks expire after 30 days. Preserve the latest point of each unpaused watched series. The UI uses separate unconnected dots, labels search/detail series, reports the retained and displayed counts, and shows a one-point state without a trend. Toolkit data management stays readable while disabled; AI local reads still require the current global/profile and local-tool grants. Clearing a profile’s history leaves targets, request usage and cooldowns intact.

Watch ownership comes from the selected native profile, never model arguments. Generation checks prevent stale edit/delete/check completion from replacing newer state. The current parser cannot confirm seller, condition, variant or destination, so even a below-target listing observation never becomes Target met or creates an alert. Search/history/list tools contain no watch mutations; UI confirmation is the write authorization for this milestone. Deleted owners remain locally manageable without ownership transfer.

Complete backups explicitly map observations and watches to the optional Amazon section, which starts unchecked. Check events and request counters are excluded. Restoring Amazon data or settings turns the free capability off globally and for stored profiles; restored targets pause, last-check metadata resets, and absent owner UIDs are orphaned. Full and partial restore preserve this installation’s existing request budget; the old import file also stays excluded. Migrations from prior schemas create empty Amazon tables. Known challenge/rate-limit cooldown writes survive caller cancellation without allocating another request.

## Next implementation stages

1. Validate precise comparable offer identity, then add explicitly approved AI watch mutations, bounded WorkManager scheduling and notifications with the plan’s crossing/rearm/outbox rules. Current manual watches grant no background activity; failed/ambiguous prices must never trigger a price-drop alert.
2. Expand live Android checks for storage, restore, visual layout and CA/US provider reliability. Daily allowance resets assume the device’s forward UTC clock; backwards resets are guarded, but the allowance is not server-enforced.
3. Add comparisons, deal evidence, authorized optional historical data, and bounded product images. First observation must never imply historical coverage.
4. Add optional hosted/seller/B2B adapters only behind separate credentials, consent, and spend budgets. Do not advertise account actions or purchases as consumer search.

Background alerts, AI watch mutations, seller/review text, checkout, affiliate attribution, additional markets, hosted-provider fallback, and cross-market currency conversion are not exposed by the native preview tools.
