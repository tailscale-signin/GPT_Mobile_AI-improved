# Amazon product cards and details

Both Amazon plugins use the same clickable product carousel. Cards no longer have separate History & manual watch, Details & price history, or Open on Amazon rows. Manual-watch entry points and the native watch-list tool are removed; existing database records and backups retain compatibility.

The popup has a scrollable product-information area and a fixed Open on Amazon button. It shows an available Amazon CDN photo with a themed outline, title, selected-offer price, rating, supplied specifications, unique description/highlights, and the public history chart. Empty sections and repeated facts are omitted. Canonical and validated affiliate links still point to the selected marketplace and ASIN.

## Loading

- The result-level view model preloads every displayed product's chart, including off-screen cards, as results arrive. Public chart reads allow up to three concurrent requests; calls for the same product share an active read and the cache.
- The cache holds up to 60 product histories within 16 MiB, sufficient for normal 30-card carousels. Successful charts expire after 90 minutes; failed lookups retry after one minute. Marketplace and ASIN identify each entry.
- History, the initial photo, and extra product information load independently. A slow detail provider does not delay a preloaded graph. Existing full native product-page results do not spend another detail request.
- Detail enrichment keeps the selected card's offer price, currency, seller, condition, variant, and affiliate link. A different marketplace, ASIN, or explicit variant is rejected.
- Photos use a separate bounded, credential-free client, validated Amazon CDN URLs, no redirects, PNG/JPEG/WebP validation, a 3 MiB download limit, and a 12 MiB cache. Bitmap decoding runs off the UI thread and samples large images down to the display size. Invalid pictures and charts stop loading and fall back gracefully.
- Current owner/profile and Amazon-plugin grants apply before and after media reads. Revoking access while the popup is open cancels work and removes product media. Closing or replacing the popup prevents old tasks from updating its new state.

Public providers may not have history for a product, and Amazon may block additional details. Missing data is reported without inventing historical prices or numeric series. Public chart availability remains independent of local observations.

## Validation

- 25 focused JVM tests passed for normalization, offer preservation, native HTML extraction, and duplicate-free display information.
- Changed Kotlin files passed ktlint 1.3.1; Android regex, resource preflight, and `git diff --check` passed.
- Additional regression tests cover preload request sharing, carousel cache capacity, bounded concurrency, media validation, popup loading, grant revocation, and cancellation.
- Full Android Gradle tests/build were blocked locally because the Android SDK is absent. The network also blocks downloading the missing Ktor/coroutine-test dependencies for a separate transport-test run. Run the checked-in tests through the repository's Android CI environment; device visual validation is still required.
