# Jan Nafta Amazon MCP integration

This bridge connects [JanNafta/amazon-mcp](https://github.com/JanNafta/amazon-mcp)
to GPT Mobile over authenticated Streamable HTTP. Upstream uses stdio; it runs
on your computer or server, not inside Android. The app bundles its nine tools
with the existing **Amazon Search** service and official, theme-tinted logo.

## Host setup

Use Node.js 22 or later. The integration was reviewed against upstream commit
`46fc2d0caa26941ba8ae801ff6e240be73c9fc04` (MIT licence).

```sh
git clone https://github.com/JanNafta/amazon-mcp.git amazon-upstream
cd amazon-upstream
git checkout 46fc2d0caa26941ba8ae801ff6e240be73c9fc04
npm ci
npm run build
```

From this repository's `mcp/amazon` directory:

```sh
npm ci
export AMAZON_MCP_ENTRY=/absolute/path/to/amazon-upstream/dist/index.js
export AMAZON_MCP_TOKEN="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')"
export AMAZON_DEFAULT_MARKETPLACE=CA
npm start
```

Keep the generated token for Android's **Bearer token** field. The bridge defaults
to `127.0.0.1:8111/mcp`; place a TLS reverse proxy or your private VPN in front of
it. Set `AMAZON_MCP_ALLOWED_HOSTS` to the hostnames/IPs forwarded in the Host
header. To bind directly to a trusted private interface, set `AMAZON_MCP_HOST`
and its allowed host. The phone's localhost is not your computer.

`.env.example` lists the configuration, but the bridge reads process environment
variables; it does not automatically load that file. The upstream server also
supports its own `.env` file and optional marketplace-specific Associates tags.
No affiliate tag or provider key is embedded in GPT Mobile.

## Android setup

1. Open **Settings → Plugins & Tools → Amazon Search → Connect Jan Nafta MCP**,
   or choose **Amazon Search · Jan Nafta MCP** in Marketplace.
2. Enter the reachable `/mcp` URL and bridge bearer token. Missing fields use
   the theme's error colour. A SerpApi key is not required for this provider.
3. Turn on **Amazon Search** globally. It stays off until you choose to enable it.
4. Open **AI → Profile → Tools**, opt that profile into Amazon Search, and select
   the MCP tools it may use. Each profile's choices are independent.

| Tools | Behaviour |
| --- | --- |
| `search_products`, `get_product`, `get_deals` | Product cards, bounded result counts, explicit currency and validated Amazon product links. |
| `get_price_history` | Preserves the upstream history, tracked-price source and buy/wait explanation. |
| `compare_marketplaces` | Preserves each marketplace's own currency; no currency conversion is invented. |
| `get_buy_link` | Returns the upstream links; no purchase is made by the app or bridge. |
| `add_price_watch`, `list_price_watches`, `remove_price_watch` | Store and manage watches in the upstream host's SQLite database. `checkNow` refreshes watches on demand. |

Tagged product-card links include a visible affiliate disclosure and support
the Associate configured by the host owner. Untagged links remain untagged.
The app validates marketplace, ASIN and allowed query fields before opening a
tagged link; cart links never become product-card navigation targets.

Price history starts with the host's tracked observations; upstream's optional
CamelCamelCamel enrichment is best effort. Amazon blocking, missing prices and
upstream failures remain visible. Results may come from the upstream cache.
Watches do not create scheduled phone notifications. Configure upstream cache
and history paths on the host; they are separate from Android backup/restore.

The bridge limits requests to 128 KiB, four concurrent calls and eight sessions,
with a 60-second tool timeout and idle session cleanup. It forwards only the
nine supported tools and does not log tokens, arguments or response bodies.
All sessions share the host owner's upstream process and watch database.

## Validation

```sh
npm test
```

Tests exercise real HTTP/stdio MCP transports with a local fixture, including
all nine tools, authentication, host/origin checks and session cleanup. Android
tests cover profile/global gating, response normalization and link validation.
No live product scraping or paid provider request is needed for these checks.

The pinned upstream was also built with Node.js 22 and checked through the bridge
for nine-tool discovery, untagged buy-link generation and local watch listing.
Those checks did not request live Amazon products.
