# GPT Mobile AI 0.9.36.0

## Amazon product browsing

- Preload public price-history graphs for products returned by either Amazon plugin, including results below the visible area. Reuse cached graphs when opening product details and limit concurrent downloads.
- Open product details by tapping anywhere on a product bubble. Remove the duplicate "Details & price history" label and the "History & manual watch" option.
- Move "Open on Amazon" into the product-details popup as a prominent button, preserving the selected product, marketplace and supported affiliate links.
- Simplify the popup with clear pricing, ratings, product facts, descriptions and features. Show an available Amazon product image inside a themed outline, alongside the price-history graph.
- Load product details, images and graphs independently so a slow or unavailable source does not block the remaining information. Respect the owning AI profile's plugin permissions and discard stale results after permissions are revoked.
- Remove manual-watch controls and the saved-watch listing tool. Preserve existing Amazon records and backup compatibility.
- Improve Amazon Research Free product bubbles and handle empty search results without an error.

Amazon images and public graphs appear when their providers supply usable data. Public Amazon pages can still be unavailable or blocked.

## Release integrity

- Correct the multiple-header fixtures in the product-image tests so Android CI can compile and validate them.
- The signed release workflow validates the release commit with unit tests, Android lint and packaged runtime checks before building APKs and the Android App Bundle.
- Verify application identity and signing-certificate continuity, and publish checksums and provenance with the signed artifacts.

## Version and installation

- Version: 0.9.36.0
- Version code: 105 (previous release: 104)
- Package: dev.melo.gptmobile.improved
- Install the signed arm64-v8a APK for an ARM64 Android phone. Keep the existing app installed when updating with the same signing certificate.
- Amazon product details and preloading: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/621
- Amazon Research Free product bubbles and empty-result handling: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/620
