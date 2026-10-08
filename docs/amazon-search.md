# Amazon product search

Amazon Search is an integrated retail plugin backed by **SerpApi**. It supplies product facts to AI models and displays themed product cards in chat. Amazon tools remain separate from general web-search aggregation so prices, ASINs and variants survive normalization.

Amazon Search is **off by default**, globally and for every AI profile. Saving a connection does not activate it. The service uses the official Amazon logo shape tinted by the current app theme.

## Setup

1. Open **Settings → Plugins & Tools → Connect → Search, Shopping & GitHub APIs**.
2. Choose **Amazon Search · SerpApi**, give the connection a name and alias, and enter your own SerpApi API key. Keys use the existing encrypted secret vault.
3. Expand **Amazon Search**, use **Configure** to choose a default marketplace (Canada initially), result limit, sponsored-product preference and freshness preference, then turn on the service. Connection execution settings override plugin defaults.
4. Expand the saved connection and optionally select **Test Amazon Search · 1 request**. Testing submits a sample query and spends one provider search request; discovery never performs paid searches.
5. Open **AI → Profile → Tools** and turn on **Amazon Search** for each profile that should use it. Ask that model to find Amazon products. The connection appears in conversation tool options. Existing remote-tool switches, approval policies, timeouts, shared read-only calls and output budgets apply.

There is no embedded provider key or Amazon account login. The first model invocation follows the existing tool approval flow unless the user already approved these tools. The plugin only searches and reads products; **Open on Amazon** launches a canonical marketplace product URL.

## Tools and data

| Tool | Behavior |
| --- | --- |
| `amazon_search__<alias>` | One SerpApi `engine=amazon` request with `k`, `amazon_domain`, `s` and `page`. Accepts a marketplace override, 1–10 results, pages 1–20, sort order and optional price bounds. |
| `amazon_get_products__<alias>` | Reads 1–5 distinct ASINs using `engine=amazon_product`. Each ASIN uses one provider request. Partial failures are disclosed. |

Results use `amazon_products_v1`: canonical HTTPS product links, ASIN, marketplace, provider, retrieval timestamp, and only the price, currency, range, rating, review count, availability, seller, condition, selected variant, coupon, Prime and sponsorship fields actually returned by the provider. A provider observation timestamp is included when available. Unknown prices and currencies are never replaced with zero or guessed values. Variant/seller/condition differences are retained during deduplication.

Price bounds filter the **retrieved page**, excluding unknown amounts. They do not promise exhaustive coverage of Amazon's catalog. Sponsored records are excluded by default when identified; missing sponsorship data remains unknown. Cached observations may be returned unless fresh was requested. Prices, shipping and availability may change at checkout. Result limits preserve valid structured JSON rather than cutting a product object in half.

Shopping tasks with an enabled Amazon tool go to the tool-capable delegate instead of the generic public-web preparation pass. Native retail tools are prioritized when a local model's context cannot hold the entire tool catalog. AWS and geographic Amazon questions retain ordinary research routing.

The native client accepts only the fixed HTTPS SerpApi endpoint, refuses redirects, bounds response bodies, and returns sanitized failures without exposing raw provider bodies or credential-bearing URLs. It does not cache raw provider responses on disk.

## Optional MCP providers

The MCP marketplace also includes:

- **Amazon Search · Jan Nafta MCP**: connects [JanNafta/amazon-mcp](https://github.com/JanNafta/amazon-mcp) through the authenticated remote HTTP bridge in [mcp/amazon](../mcp/amazon/README.md). Its nine tools add product search, details, deals, price history, marketplace comparisons, buy links and host-stored price watches. Product cards preserve validated, disclosed affiliate tags when the host configures them. No SerpApi key is needed. Watches are checked on demand, and cached or missing prices retain the upstream's limitations. Use **Amazon Search → Connect Jan Nafta MCP** for direct setup.

- **Amazon Search · SerpApi MCP**: `https://mcp.serpapi.com/mcp`, using the API key as a Bearer credential. Enable `search` for a model and pass `params.engine=amazon`, `params.k`, `params.amazon_domain`, and `mode=compact`. Product details use `params.engine=amazon_product` and `params.asin`.
- **Amazon Search · Bright Data MCP**: `https://mcp.brightdata.com/mcp?groups=ecommerce&token=<your-token>`. Enable `web_data_amazon_product_search` or `web_data_amazon_product`. Search is first-page only. Account ecommerce access and charges apply. Endpoint tokens are stored by the existing endpoint-secret vault.

Supported Amazon responses from these hosted tools use the same product-card contract. SerpApi Google searches and unrelated MCP providers are left unchanged. MCP provider tools still require profile selection and existing consent. The presets describe provider support; availability is not verified with a live account in this change.

## Provider references

- [SerpApi Amazon Search API](https://serpapi.com/amazon-search-api)
- [SerpApi Amazon Product API](https://serpapi.com/amazon-product-api)
- [SerpApi MCP server](https://serpapi.com/mcp)
- [Bright Data MCP tools](https://docs.brightdata.com/products/mcp-server/tools)

This feature uses third-party provider accounts. It does not implement Amazon's affiliate Creators API or imply Amazon affiliate approval. An official Amazon integration would require separate eligibility, mobile-app approval and compliance review.
