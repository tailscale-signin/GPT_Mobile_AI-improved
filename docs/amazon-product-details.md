# Amazon product cards and details

Both Amazon plugins use the same clickable product carousel. Cards no longer have separate History & manual watch, Details & price history, or Open on Amazon rows. Manual-watch entry points and the native watch-list tool are removed; existing database records and backups retain compatibility.

Only listings with an observed price become cards. The normal card label shows the marketplace; Public-page preview is visible only in debug mode. Confirmation labels, observation timestamps and the carousel checkout footer are omitted.

The popup stays open when swiping left/right between products. Previous/next buttons and an index provide an accessible alternative; swiping stops at the ends and each product starts at the top. It has a scrollable product-information area and a fixed Open on Amazon button. It shows an available Amazon CDN photo with a themed outline, title, selected-offer price, rating, supplied specifications, unique description/highlights, and the public history chart. Empty sections and repeated facts are omitted. Canonical and validated affiliate links still point to the selected marketplace and ASIN.

## Loading

- The result-level view model preloads every displayed product's chart, including off-screen cards, as results arrive. Public chart reads allow up to three concurrent requests; calls for the same product share an active read and the cache.
- The cache holds up to 60 product histories within 16 MiB, sufficient for normal 30-card carousels. Successful charts expire after 90 minutes; failed lookups retry after one minute. Marketplace and ASIN identify each entry.
- History and photos preload all displayed results, including off-screen cards. Popup history, photos and extra information load independently. A missing or unusable listing photo gets one shared, bounded public product-page lookup under the owning Amazon media grant and Research request budget; other detail enrichment uses the enabled native/SerpApi provider. Complete native product-page facts are reused when the photo is usable.
- Detail enrichment keeps the selected card's offer price, currency, seller, condition, variant, and affiliate link. A different marketplace, ASIN, or explicit variant is rejected.
- Original photos and sanitized product metadata persist in app-private conversation storage until the conversation is archived or deleted. Closing the popup or restarting the app preserves them; temporary chats never persist them, and late downloads cannot repopulate an archived conversation. This disposable media cache is excluded from portable backups.
- Photos use a separate bounded, credential-free client, validated Amazon CDN URLs, no redirects, PNG/JPEG/WebP validation, a 3 MiB download limit, and a 12 MiB cache. Bitmap decoding runs off the UI thread and samples large images down to the display size. Invalid pictures and charts stop loading and fall back gracefully.
- Charts are recolored off the UI thread using the current theme background, text and series colors; geometry, labels and distinct series are preserved. Product photos keep their original colors.
- Current owner/profile and Amazon-plugin grants apply before and after media reads. Revoking access while the popup is open cancels work and removes product media. Closing or replacing the popup prevents old tasks from updating its new state.

Public providers may not have history for a product, and Amazon may block additional details. Missing data is reported without inventing historical prices or numeric series. Public chart availability remains independent of local observations.

## Validation

Regression coverage includes selected-market enforcement, foreign-market rejection, unpriced search filtering, UK/French price parsing, lazy/provider image formats, durable photo reuse, archive cleanup, temporary-chat isolation, independent popup loading, navigation bounds, themed charts, grant revocation and cancellation. Hosted Android CI supplies full compilation, Compose/Room tests, lint and APK checks; local JVM checks cover the dependency-light contracts and parser. Synthetic fixtures do not establish live Amazon availability.
