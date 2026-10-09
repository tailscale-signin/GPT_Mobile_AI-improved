# Unified News and Airbnb MCP bridges

Android connects to Streamable HTTP MCP servers; it cannot launch `uvx` or Node subprocesses. This companion exposes approved tools from pinned upstreams through an authenticated `/mcp` endpoint. News uses one endpoint for all three sources. Airbnb uses its own endpoint and plugin permissions.

## News

The app's integrated **News** plugin already supports Google News RSS, Google Trends RSS and Hacker News without an API key. Enable News globally and for the desired AI profile. Configure country and language in the plugin settings. A combined search interleaves Google and Hacker News results before applying the result limit.

To add the requested MCP implementations, install Node 22 or later and [uv](https://docs.astral.sh/uv/getting-started/installation/) on the host, then run from this directory:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run prepare-upstreams -- hn-server
export RESEARCH_MCP_TOKEN="$(node -e "process.stdout.write(require('crypto').randomBytes(32).toString('hex'))")"
# Optional provider; do not put the actual key in source control:
# export SERP_API_KEY="your-serpapi-key"
npm start
```

Keep the generated token for the app's Bearer credential. The bridge starts on `127.0.0.1:8112/mcp`. Expose it through a HTTPS reverse proxy or a private network reachable from the phone. Set `RESEARCH_MCP_HOST` to the private interface address and `RESEARCH_MCP_ALLOWED_HOSTS` to the exact hostnames used by the phone/proxy. The phone's `localhost` refers to the phone itself.

In Marketplace, choose **News · Unified MCP**, enter the reachable `/mcp` URL and Bearer token, discover tools, and select them for the AI profile. Enable the News service and the connection. Missing `SERP_API_KEY` omits only the SerpApi source; the other two MCP sources remain available.

| Upstream | Pin | Tools | Credential |
| --- | --- | --- | --- |
| [jmanek/google-news-trends-mcp](https://github.com/jmanek/google-news-trends-mcp) | `google-news-trends-mcp@0.2.9` | Keyword, location, topic, top news, trending terms | None |
| [ChanMeng666/server-google-news](https://github.com/ChanMeng666/server-google-news) | `@chanmeng666/google-news-server@1.0.0` | `google_news_search` | Optional `SERP_API_KEY` |
| [pskill9/hn-server](https://github.com/pskill9/hn-server) | `ed1a4b951e9a7cdd7de0575a16d53bbe53330a9d` | Top, new, Ask HN, Show HN, jobs | None |

Provider prefixes prevent name collisions. Country/language settings govern the built-in News tool; MCP providers keep their own input schemas. Trend traffic measures search interest. Publication dates and publishers remain attached to news results.

## Airbnb

For hosted service, choose **Airbnb · OpenBnB** in Marketplace, connect `https://mcp.openbnb.ai/mcp`, and sign in using OAuth. Discover and assign search and listing-detail tools. Enable Airbnb globally, for the profile, and for the connection. Hosted availability and account limits belong to OpenBnB.

For a self-hosted service:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run prepare-airbnb
export RESEARCH_MCP_TOKEN="$(node -e "process.stdout.write(require('crypto').randomBytes(32).toString('hex'))")"
export RESEARCH_MCP_CONFIG=airbnb.config.json
export RESEARCH_MCP_PORT=8113
npm start
```

Choose **Airbnb · Self-hosted OpenBnB** in Marketplace and enter the reachable endpoint and token. The preparation script checks out [openbnb-org/mcp-server-airbnb](https://github.com/openbnb-org/mcp-server-airbnb) at `d5a8f18f5978b0a33dea4b3ec130c389bad845cc`. Its adapter preserves listing photos and review text from pages the upstream already fetched, while retaining robots checks, argument validation and error handling. Re-running preparation at the same pinned revision is supported. Unexpected source contracts stop the build.

Listings use canonical IDs and URLs. Deduplication distinguishes dates and party size. Popups request additional details only through the originating profile's assigned Airbnb tools, with the same dates and guests. Prices, fee lines, review text and photos are provider observations; unavailable fields remain unknown. English review keyword signals include supporting excerpts and simple negation handling. They are not a complete sentiment model or a safety assessment. Booking opens Airbnb in the browser.

## Transport and validation

The bridge requires a 24–512 character Bearer token, checks hosts/origins, limits requests and sessions, expires idle sessions, and forwards only configured read tools. Subprocesses receive only required environment variables. Use a different token for each exposed bridge. Do not add write tools to the allowlist.

```sh
npm test
npm run prepare-upstreams
```

Contract tests use real stdio MCP fixture servers and an HTTP client to check multi-provider routing, optional credential handling, authentication and media extraction. Preparation builds the pinned Airbnb and Hacker News sources. Live upstream searches require network access and may be blocked or rate limited by providers. Upstream license notices remain in their checkouts; this project does not vendor their source.
