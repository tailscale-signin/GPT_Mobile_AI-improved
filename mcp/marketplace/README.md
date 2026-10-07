# Optional location marketplace plugins

These are first-party GPT Mobile adapters, not provider-endorsed plugins.

## Android installation and use

For the twelve **Runs in app** entries, choose **Download & install**. The app
verifies a commit-pinned package and installs its registration. The REST
adapters are compiled into the APK; no downloaded Python/Node code is executed.
Installed plugins appear in Marketplace and Settings > Tool connections > Plugins.

Fill any required red-outlined field, save settings, then choose **Enable**.
REFUGE and Toronto Open Data need no API key. Nominatim and Overpass need a
permitted managed/self-hosted HTTPS search/interpreter endpoint but no API key;
shared public endpoints are not defaults. Ticketmaster, TomTom, Yelp, Eventbrite,
ArcGIS, OpenRouteService, Google Places and Foursquare require their own provider
key/token. Keys are stored in the encrypted Android Keystore vault.

The defaults are ten results (five where the provider requires it) and fifty
request attempts per provider per UTC day. Each tool call makes one bounded
request without automatic retries, with a one-second cooldown and sixty seconds
after HTTP 429. Failed requests count. Provider limits, account access and charges
still apply; the app allowance is not a spending cap. Use provider billing caps.
No provider requests occur just by installing or opening the marketplace.

Enabled adapters are available to the app's agent tool resolver and conversation
tool controls. Turning a plugin off blocks subsequent calls, including tools
already held by a chat. **Uninstall** disables it and removes the downloaded
package, settings, saved key and permissions. Reinstall starts disabled with defaults.

Hosted MCP entries require a saved connection and provider sign-in/key, followed
by discovery and tool assignment. Their enable/disable and uninstall controls
manage that connection and remove its saved credentials and assignments. Setup
plans remain **View guide** entries, not installable apps. Download does not
provide a subscription, authorize billing, deploy a server or install a runtime.

## Optional computer-hosted companion (Python 3.11 or newer)

The standalone `location_mcp.py` remains available in this directory for users
who prefer to host the same providers on their own computer. It is separate from
the Android installation above.

Get `location_mcp.py` from this directory at the commit shown in the app and
transfer it to your own computer. Review
it before execution. No pip packages, Docker, telemetry, shell execution, or
provider credentials are included.

PowerShell example, run from the directory containing the file:

```powershell
$env:MARKETPLACE_MCP_TOKEN = (python -c "import secrets; print(secrets.token_urlsafe(32))")
$env:MARKETPLACE_ENABLED = "refuge,toronto"
$env:MARKETPLACE_DAILY_REQUESTS = "50"
python location_mcp.py
```

The default bind is loopback, port 8110. For your phone, deliberately deploy a
TLS reverse proxy to loopback, or bind to your private VPN address with
`--host <private-address> --allow-network`. Restrict the firewall to trusted
clients. Never publish the plaintext port to the Internet. There is no TLS in
this development companion. Use a production server/proxy with request and
connection limits for broader deployment; this is a single-process companion
with at most eight concurrent clients, not a public multi-tenant service. A slow
provider or idle connection does not block other clients. Excess connections
are closed; retry later. Provider cooldowns and daily counts are shared across
the workers, so concurrency does not multiply the allowance.

Add `https://<your-host>/mcp/refuge` (or `/mcp/toronto`) in the app and choose
Bearer authentication with MARKETPLACE_MCP_TOKEN. A LAN/VPN HTTP endpoint
requires explicit cleartext approval in the app. A phone's `localhost` is not
your computer. Provider API keys stay on the companion; they are NOT the MCP
Bearer token. Set only the credentials for providers you explicitly enable:

| Provider ID | Host configuration | Read operations |
| --- | --- | --- |
| refuge | No provider key | Nearby restrooms |
| toronto | No provider key | CKAN dataset search and a bounded records page |
| nominatim | NOMINATIM_ENDPOINT | Managed/self-hosted geocoding |
| overpass | OVERPASS_ENDPOINT | Managed/self-hosted restroom search within 1.5 km |
| ticketmaster | TICKETMASTER_API_KEY | Events by keyword and city |
| tomtom | TOMTOM_API_KEY | Places search |
| yelp | YELP_API_KEY | Businesses by term and location |
| eventbrite | EVENTBRITE_TOKEN | Your authorized organization's events only |
| arcgis | ARCGIS_API_KEY | Non-stored address candidates |
| openrouteservice | ORS_API_KEY | One walking route |
| google-places | GOOGLE_PLACES_API_KEY | Text Search with explicit fields |
| foursquare | FOURSQUARE_API_KEY | Places search |

Enabled IDs are a comma-separated allowlist. New providers are never enabled
automatically. Nominatim/Overpass have no public shared defaults; configure a
permitted HTTPS endpoint including its search/interpreter path. The app does
not geocode or collect your GPS location merely by opening the marketplace.

The host enforces one request per tool call, no automatic retries/pagination,
a one-second provider cooldown, a longer cooldown after HTTP 429, bounded
inputs/results, and a persistent per-provider daily request allowance (UTC).
The default is 50 attempts per provider per day across all clients of this
companion. Failed attempts count. The usage database stores day/provider/count,
not prompts or coordinates. Deleting it resets the allowance. This is NOT an
invoice/spending cap: set billing restrictions at each provider as well.
Discovery and initialization do not call paid APIs. Provider credentials,
queries, URLs and upstream exception text are not written to server logs.

## Hosted MCP packages

Mapbox uses its hosted geospatial MCP connection and OAuth flow; confirm client
registration and account access in connection settings. Geoapify uses its
documented `apiKey` endpoint query parameter, which the app must vault/redact.
The TomTom companion uses its REST API; do not send a Bearer token to TomTom's
official MCP endpoint, which documents a different authentication header.
Live account and Android OAuth interoperability tests are still required.

## Downloadable guides, not working tools or offline databases

Meetup, Google Maps Grounding, Overture Places, Foursquare Open Source Places,
Toronto/OSM regional packs, and Toronto Public Library programs have setup/data
planning entries only in this first pass. Their downloads do not install data,
render maps, import DuckDB/Parquet, or invent a usable MCP endpoint. They cannot
be added as active connections until an adapter/data pipeline is implemented.

## Data, coverage and terms

Free package code is not free or unlimited API usage. Yelp is trial/plan-based;
Eventbrite does not provide the former public citywide search endpoint. Results
are bounded and are not comprehensive or live operational confirmation. Missing
access, fee, accessibility or hours fields remain unknown. Toronto DineSafe
statuses are inspection outcomes, not customer ratings; resource schema-specific
normalization and offline washroom packs remain future work.

Keep provider attribution. Do not merge restricted commercial results into a
redistributed OSM database. Google Places, ArcGIS non-stored geocoding and other
commercial content have storage/display restrictions; do not use persistent
chat/history storage for such results without a compliant retention design.
This first pass does not implement provider-specific chat retention or map
attribution UI. Use temporary chats for initial account testing and review the
provider terms before any public rollout. OSM ODbL and municipal attribution
obligations apply to future data packs. OSM public tiles must not be bulk fetched.

Primary documentation checked during implementation:

- https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
- https://developer.ticketmaster.com/products-and-docs/apis/discovery-api/v2/
- https://docs.tomtom.com/tomtom-maps-mcp/documentation/remote-vs-local
- https://docs.developer.yelp.com/reference/v3_business_search
- https://www.eventbrite.com/platform/new/api
- https://developers.arcgis.com/rest/geocode/find-address-candidates/
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/
- https://developers.google.com/maps/documentation/places/web-service/text-search
- https://docs.foursquare.com/fsq-developers-places/reference/authentication
- https://www.refugerestrooms.org/api/docs/
- https://open.toronto.ca/dataset/washroom-facilities/
- https://operations.osmfoundation.org/policies/nominatim/
- https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
- https://apidocs.geoapify.com/docs/mcp/
- https://github.com/mapbox/mcp-server

Run mocked contract/security tests from the repository root:

```sh
python -m unittest discover -s scripts/tests -p 'test_marketplace_companion.py' -v
```

These tests do not establish provider availability, complete MCP conformance,
live Toronto coverage, Android builds or device visual correctness.
