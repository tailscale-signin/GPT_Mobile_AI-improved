# Amazon Research Free: native preview with local history

The custom Amazon add-on supplies native search/details, local observations and automatically preloaded public price-history charts. It registers a separate integrated service, `amazon_free`, in the existing Toolkit and profile controls. It does not need an API key, desktop, Node/Python process, or MCP bridge. Public-page availability is unproven in a live Android environment, so the public-page integrations remain previews; the card preview label appears only in debug mode.

## Current behavior

| Capability | Implementation |
| --- | --- |
| Search | `amazon_search__free_native`: one public results page, up to 10 products; price filters and sorting apply to that page only |
| Details | `amazon_get_products__free_native`: 1–5 distinct ASINs; one page per ASIN, independent failures retained |
| Markets | `amazon.ca` / CAD, `amazon.com` / USD, `amazon.co.uk` / GBP and `amazon.fr` / EUR; selected marketplace is enforced even when model arguments request another country |
| Controls | Global and per-profile opt-in, off when no stored choice exists; live grant checks and cancellation when the global/profile grant or network permission is revoked |
| Product cards | Click anywhere to open organized details, available specifications and an Amazon CDN image in a themed outline; the Open on Amazon button is inside the popup. Full sanitized product facts are retained when the model-facing output is shortened |
| Public price history | All displayed results, including off-screen carousel cards, preload Keepa/camelcamelcamel charts with up to three concurrent network reads. Popup reads share the cache and active preload; history appears independently of slower product details |
| Search feedback | Chat shows distinct empty, blocked, rate-limited and failed lookup states; opt-in diagnostics include counts of listings, displayed prices and typed error codes |
| Price-filter discovery | Confirmed matches remain in `products`; priced listings with an unconfirmed currency remain separately in `unverifiedProducts`; the model contract retains verification facts while normal cards omit confirmation labels. Unpriced listings are excluded |
| Bounds | 200-character query, exact marketplace/ASIN validation, 8 MiB decoded HTML, three redirects, 5–120 second operation timeout, bounded JSON output |
| Request budget | Atomic Room installation ledger; the previous `noBackupFilesDir` ledger is imported once without changing its usage, pacing or cooldowns; 1–100 physical requests per UTC day, shared across profiles and marketplaces, including redirects and failed attempts; serialized requests at least five seconds apart |
| Blocking | No automatic retry; 24-hour challenge cooldown; HTTP 429 honors a bounded Retry-After or uses six hours; cooldowns survive restart |
| History | Profile-scoped confirmed-currency observations in Room schema 34; search/detail pages remain separate incomplete-offer series; no persistence from ordinary temporary-chat lookups |
| Manual watches | Removed from chat cards and plugin settings; the native watch-list tool is no longer registered. Existing saved records remain compatible with backups |
| Local tools | `amazon_get_price_history__free_native` (up to 100 returned points); no model-selected owner; respects Disable local tools, and public chart reads separately require the remote grant |
| Backup | Amazon data is included in the Conversations backup group; legacy targets restore paused or orphaned, free-plugin grants are cleared, installation usage/cooldowns are preserved |
| Data handling | No cookies, credentials, affiliate tags, raw HTML storage, raw response/error logging, or result sharing between profiles |

The existing SerpApi Amazon service and JanNafta desktop MCP bridge remain independently controlled. Switching off remote MCP connections does not switch off this native HTTP service; a profile's **Disable remote tools** and **Disable all tools** controls do.

Search and detail tools use the existing canonical tool classifications so the chat product cards recognize their results. Native results deliberately opt out of the shared read-only turn cache until cached consumers can recheck their current grants. The provider uses a dedicated Ktor/OkHttp client rather than the general credential-bearing network client.

## Enable and exercise the preview

1. Open Settings → Toolkit, expand **Amazon Research Free**, and enable it globally.
2. Open its configuration, choose Canada, US, UK or France, and set results, output, timeout, sponsored-result preference, and daily allowance.
3. Click **Test search · 1 request**. This is an explicit request, counts against the allowance, and never runs during discovery. An individual successful test does not certify marketplace reliability.
4. In the intended AI profile's Tools panel, opt in to **Amazon Research Free**. Other profiles remain off.
5. Ask for a product search, then details for a returned ASIN. Check the market, canonical link, timestamp, price currency, and partial error output.
6. Tap anywhere on a product card. Inspect the product information, image and preloaded public chart, then use **Open on Amazon** in the popup. Missing provider information is omitted. Product media loads only with the owning profile's current Amazon and remote-tool grants.
7. Disable the profile or global grant during a request; it must cancel and withhold product facts. An open details popup removes media when access is revoked.

Bare `$` without confirmed currency remains display text, never a numeric amount. With price filters, those verified listing identities remain visible as discovery cards in `unverifiedProducts`; they are not budget matches. Confirmed out-of-budget listings are excluded. The parser recognizes both desktop and mobile core-price layouts, split whole/fraction prices and explicit currency metadata. Sponsored redirect targets must still match the selected marketplace and card ASIN. Unpriced search listings are omitted. Detail reads may retain non-price facts to enrich an existing priced offer, with `PRICE_UNAVAILABLE` in the model contract. Prime/sponsorship facts are included only when evidence is present. Shipping, tax, coupons, delivery region, seller, variant, and comparable offer identity are not inferred; the current price is a base item observation, not a checkout total or historical low.

## Source and dependency ledger

| Source | Pinned version/commit | Reuse | Notice |
| --- | --- | --- | --- |
| [JanNafta/amazon-mcp](https://github.com/JanNafta/amazon-mcp) | `46fc2d0caa26941ba8ae801ff6e240be73c9fc04` | Search card, title, star, review count, and image selector patterns from `src/lib/amazon-scraper.ts`, adapted into `AmazonHtmlParser.kt`; new currency, identity, price-scope, challenge, and bounds checks | Full upstream MIT text and copyright in `app/src/main/assets/licenses/amazon-mcp.txt`, available through Settings → Licenses |
| [jsoup](https://jsoup.org/) | `org.jsoup:jsoup:1.23.2` | DOM parsing only; no jsoup networking | Full MIT text and copyright from the verified jar in `app/src/main/assets/licenses/jsoup.txt`, available through Settings → Licenses |
| [Ktor](https://ktor.io/) | `io.ktor:ktor-client-encoding:3.6.0` | gzip/deflate decoding before the decoded byte limit | Apache 2.0; the existing Ktor dependency notices provide the license text |

Other candidate repositories are not copied in this stage. Hosted providers' free quotas do not become an unlimited free backend by combining repositories. Reuse of another source requires a verified license and pinned provenance entry first.

## Verification and release gate

The HTML test fixtures are small, synthetic examples with dummy ASINs and product facts. They are not scraped customer content and do not establish live Amazon reliability. Unit coverage targets parsing, currency and identity ambiguity, invalid model input, grant revocation, partial results, output bounds, redirects, decoded body size, quota/cooldown persistence, and resolver isolation. Regressions also cover discovery-card rendering, native and configured plugin output limits, retained checkpoints, combined-provider results, and visible empty/blocked search feedback.

Before describing a marketplace as supported, run the plan's live multi-device/search/detail reliability evaluation, including blocking and cancellation scenarios, on Android 12+. A debug build and mock transport tests do not satisfy that gate. No background scheduler is started by this stage.

## Persistence and legacy targets

`amazon_observations` stores decimal text, acquisition time, source type and an explicitly incomplete listing-series key. Missing prices are `amazon_check_events`, not zero-price points. UUID acquisition IDs differ from model call IDs; repeated processing of the same acquisition is idempotent. Temporary or missing chat rows fail closed for automatic capture. Explicit manual-watch checks are clearly identified persistent actions. Capture remains inside the live permission observer and a revoked grant rolls back an in-progress transaction.

History retention is 365 days with a 50,000-row soft cap; checks expire after 30 days. Preserve the latest point of each unpaused legacy watched series. AI local reads still require the current global/profile and local-tool grants. The details popup displays the external chart rather than a second local-observation graph. Request usage, cooldowns and existing legacy targets remain intact.

Watch ownership comes from the selected native profile, never model arguments. Generation checks prevent stale edit/delete/check completion from replacing newer state. The current parser cannot confirm seller, condition, variant or destination, so even a below-target listing observation never becomes Target met or creates an alert. Search/history/list tools contain no watch mutations; UI confirmation is the write authorization for this milestone. Deleted owners remain locally manageable without ownership transfer.

Complete backups preserve the original Amazon archive section for compatibility and present it within the Conversations group. Saved older partial selections remain partial until the user changes the group. Check events and request counters are excluded. Restoring Amazon data or settings turns the free capability off globally and for stored profiles; restored targets pause, last-check metadata resets, and absent owner UIDs are orphaned. Full and partial restore preserve this installation’s existing request budget; the old import file also stays excluded. Migrations from prior schemas create empty Amazon tables. Known challenge/rate-limit cooldown writes survive caller cancellation without allocating another request.

## Next implementation stages

1. Expand product specifications and validate precise comparable offer identity. Manual-watch controls are removed; existing targets grant no background activity.
2. Expand live Android checks for storage, restore, visual layout and CA/US provider reliability. Daily allowance resets assume the device’s forward UTC clock; backwards resets are guarded, but the allowance is not server-enforced.
3. Add comparisons and deal evidence. Bounded public charts and Amazon CDN images are available now; a first local observation must never imply historical coverage.
4. Add optional hosted/seller/B2B adapters only behind separate credentials, consent, and spend budgets. Do not advertise account actions or purchases as consumer search.

Background alerts, AI watch mutations, seller/review text, checkout, affiliate attribution, markets beyond Canada/US/UK/France, hosted-provider fallback, and cross-market currency conversion are not exposed by the native preview tools.
