# Research plugins and diagnostics improvements

## Enable the new plugins

Use Plugins to enable **News**, **Airbnb** or **Google Places**, then enable each service for the AI profile that should use it. New services are opt-in. Remote-tools controls and individual MCP connection toggles still apply. In Combined mode, cards use the profile that produced the result.

- **News:** built-in Google News/Trends and Hacker News work without a key. Country and language are configurable. For all three requested MCP implementations under one endpoint, follow [the companion instructions](../mcp/research/README.md#news).
- **Airbnb:** connect hosted OpenBnB with OAuth, or run the pinned open-source companion. Assign both search and listing details to the profile. Listings appear as outlined themed cards with photos, prices, ratings and stay context. The detail popup provides a photo gallery, provider fee lines, amenities, rules, review signals and browser/map buttons.
- **Google Places API:** install the Google Places integrated adapter in Marketplace, configure a Places API key, and enable it. Text search, nearby search within 1,500 metres, and Place-ID details use the Places API (New). The key is stored in the existing secret vault. Results retain attribution and feed the embedded map.
- **Google MCP:** choose Google Places · Maps Grounding MCP and supply a key for [Maps Grounding Lite](https://developers.google.com/maps/ai/grounding-lite/reference/mcp). The official endpoint is `https://mapstools.googleapis.com/mcp`; the app sends `X-Goog-Api-Key` rather than a Bearer header. Enable the corresponding Google Cloud API and billing. This is a separate surface from direct Places API calls.

## Fixes motivated by the diagnostics

The supplied log shows repeated search/read calls after tool suppression, missing-key news errors, unavailable MCP hosts, and incomplete multi-finger gesture streams. The app now removes credential-failing tools immediately and requests a final answer when the model asks only for unavailable tools. Existing per-response budgets, connection backoff and cancellation remain in place. Provider outages, HTTP rate limits and invalid remote credentials still need to be resolved at the provider or host.

Amazon image parsing accepts common string, object and gallery formats. Cards preload images; popups reuse the same bounded download and conversation cache. Failed image reads retry once, then try alternate provider URLs. Fresh metadata preserves a working photo until its replacement is ready. A popup offers a photo retry when no image can be loaded. Supplied offers, currencies and variants remain intact.

Embedded maps keep the pointer sequence inside MapLibre, use texture rendering in Compose, and avoid resetting the camera during theme or route changes. A dark OpenFreeMap basemap uses theme colours for markers and routes, with travel icons and a recenter control. Phone pan/pinch verification remains part of device validation.

Combined synthesis waits for each active profile's latest run to finish, reads its latest response rather than a historical revision selected for inspection, and retains all distinct contributions. Retrying a helper can regenerate the combined result. Combined sources and the completed text timeline stay available for profile tabs and conversation history.

## Regression checks

Tests cover Amazon gallery fallback and cache replacement, current Combined inputs and helper retries, News source interleaving and partial feed failures, Airbnb normalization/deduplication and review evidence, Google Places request headers/coordinates, and tool finalization after a missing credential. Bridge contracts run in Marketplace CI alongside builds of pinned Airbnb and Hacker News sources. Hosted OAuth, live searches, photo availability and map gestures also need a configured account/network and device checks.
