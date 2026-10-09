# Airbnb maps and diagnostics fixes

Airbnb results reuse the embedded MapLibre/OpenFreeMap map. Every returned listing with a valid latitude/longitude pair receives a numbered pin. With Android location permission, Device location enabled and local tools allowed for the owning profile, a fresh shared Android fix supplies the user marker, straight-line distances and walking/driving routes. Without a fix, the map shows listing locations without inventing a user position or distances. The current-position button requests Android permission when needed. Dates, final fees and availability still require confirmation on Airbnb; public listing coordinates are approximate.

Pins open the existing listing details gallery. Coordinates loaded by the details gallery remain available to the map while its ViewModel lives. Listings without coordinates remain in the carousel and are counted in the map notice. No neighborhood geocoding is used as a substitute for a listing coordinate. There is no Airbnb/OpenBnB sign-in, API key, hosted MCP requirement or Mapbox dependency for this map. Basemap tiles and route requests require network access.

The shared map now has numbered markers, a horizontal place selector, balanced lifecycle callbacks, explicit load failure/timeout notices and a retry action. Camera fitting responds to coordinate changes, preserving panning during theme/route updates. Existing touch interception remains intact.

## Log findings addressed

- Airbnb calls failed within milliseconds, before a public fetch could plausibly complete. The log did not retain arguments, so the exact cause is unproven. Validation and profile-disabled errors now report their own cause instead of a generic connectivity error. Empty/null unused optional fields no longer fail date parsing. Empty public results can use the indexed fallback, without claiming verified availability.
- Airbnb HTML parsing and response/card provenance decoding run on a background dispatcher, reducing avoidable UI-thread work.
- Amazon's 2 MiB decoded HTML ceiling rejected a normal search response. The ceiling is now 8 MiB; oversized decoded bodies, challenges, redirects, deadlines and request accounting remain bounded. Extraction still requires verified identities/prices.
- Large raw tool results could debit all 256 KiB even when only a small excerpt was replayed. Each ordinary result now admits at most 16 KiB and debits that allowance, retaining the original for cards/recovery. The existing delegation handoff reserve remains available.
- Primary responses that reach their output cap receive one text-only continuation from a bounded answer tail and compact completed evidence. No pending tool call is executed or replayed. The second truncation remains a failure with partial work intact. Internally managed native/gateway tool sessions are excluded. Token accounting includes both requests; profile caps and total-run token/spend guards remain enforced. Delegates receive an explicit concise brief target rather than a long essay request.
- Legacy MCP discovery decisions now last as long as positive decisions (one hour), avoiding repeated rejected `server/discover` probes each minute. Explicit reconnect/refresh and changed endpoint/credentials re-probe.
- OAuth refresh errors such as `invalid_grant` now require reconnect and suppress repeated refreshes of the same rejected credential during the app session. Transient/server failures remain retryable. Server error descriptions and credentials are not exposed.

## Validation and remaining limits

Added regression coverage for Airbnb argument handling/fallback, coordinate pairing, all 30 listing pins, missing GPS, result-budget fairness, large Amazon HTML, output-limit continuation/accounting, legacy discovery caching and rejected OAuth refresh classification.

Local ktlint, Android resource/regex checks, exported Room schema validation and 51 existing Python script tests pass. Android Gradle tests/build could not run locally: the workspace has Java 17, no Android SDK and cannot download the required Gradle distribution. The repository's JDK 21/Android CI checks still need to run before merge.

No app crash is recorded in this log. Chromium startup/codec/Bluetooth warnings and Adreno shader/pipeline messages do not identify an app-owned defect on their own. This change does not claim to repair the phone's graphics driver, make blocked websites accessible, repair a revoked Mapbox authorization, or guarantee every indexed Airbnb result has coordinates. Device validation is still needed for rendering and gesture behavior.
