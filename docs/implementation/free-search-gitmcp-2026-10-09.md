# Free Search and GitMCP integration

## What is implemented

Free Search is an optional provider of the existing `web_search` tool. It is not a second automatic search pipeline. Select `free_search__search` in the AI profile alongside any native providers you want. Existing parallel fan-out, URL/content deduplication, engine interleaving, execution ownership and bound child permissions/budgets continue to apply.

The discovered search schema maps query, count and domain filters, requests `format=json`, keeps the upstream health-aware keyless pool, and limits cache reads to 15 minutes while retaining cache writes. Day/week/month/year freshness is approximate: undated results can survive upstream filtering. Requests beyond one year are reported as unsupported, rather than silently narrowed. Date provenance and retrieval/cache/provider-error metadata survive normalization. An upstream partial failure retains usable sources and marks the merged answer partial, so the app does not cache it as a complete search.

The marketplace offers Free Search as computer-hosted MCP, with a pinned `free-search-mcp==0.13.0` configuration. Python, Chromium, FTS5 caches and upstream plugin files stay on the host. Android does not install or launch the Python plugin. `fetch`, `read_doc`, and `cache_search` are optional profile tools; page readers can participate in the existing bounded crawl stage. This is keyless search, not unlimited or guaranteed access to every search engine. The host's browser fallback requires a separate Chromium installation.

GitMCP is a public repository documentation/code provider under the existing GitHub plugin controls. Its current published server uses legacy SSE; the research bridge converts `https://gitmcp.io/docs` to authenticated Streamable HTTP. Only fetch/search generic documentation and search generic code are exposed. The bridge accepts this exact public remote endpoint and never forwards its bearer token, GitHub tokens or provider environment variables. Native GitHub connections remain visible; only the existing redundant official GitHub MCP suppression applies.

The native GitHub tool gains `repo_docs`. It resolves the selected branch/ref to a commit, lists regular llms.txt/README/docs files with blob SHAs, and returns the commit SHA for subsequent line-ranged `read_code` calls. Pagination and GitHub's truncated-tree marker are explicit; symlinks and binary assets are excluded. This also works for authorized private repositories entirely through the native GitHub API. GitMCP does not replace issue/PR handling, writes or private repository access, and hosted GitMCP evidence is not branch-pinned.

New Free Search/GitMCP connections start disabled. Discover tools, confirm connectivity, then enable and select them for the intended profiles. Existing saved connections retain their state. Built-in phone search continues to work without either host integration.

## Host setup

Use Node 22+ and run `npm ci` in `mcp/research`. Install `uv` for Free Search; the pinned Python package requires Python 3.11+. Set a locally generated strong `RESEARCH_MCP_TOKEN` (at least 24 printable ASCII characters). Do not put a GitHub API token in this field.

| Provider | RESEARCH_MCP_CONFIG | Suggested RESEARCH_MCP_PORT | Selected profile tools |
| --- | --- | --- | --- |
| Free Search | free-search.config.example.json | 8115 | free_search__search; optional free_search__fetch, free_search__read_doc, free_search__cache_search |
| GitMCP | gitmcp.config.example.json | 8116 | gitmcp__fetch_generic_documentation, gitmcp__search_generic_documentation, gitmcp__search_generic_code |

Run `node bridge.mjs` once per configuration. By default it binds to loopback; use an authenticated private tunnel/HTTPS route and configure `RESEARCH_MCP_HOST` / `RESEARCH_MCP_ALLOWED_HOSTS` for the actual host route. The Android connection receives the reachable bridge `/mcp` URL and bridge bearer token, not the upstream GitMCP SSE URL. Phone localhost refers to the phone, not the PC.

For Free Search, optionally set `SEARCH_MCP_CACHE_DIR` and `SEARCH_MCP_PROXY`. Chromium can be installed using `uvx --from free-search-mcp==0.13.0 playwright install chromium`. Search API keys are not required for the default pool. No host service, Chromium download or device connection was performed in this workspace.

## Source verification and licensing

Upstream API signatures were checked against `sweetcornna/free-search-mcp` tag v0.13.0; its current main declares 0.13.1, so the runnable package remains pinned to the verified released 0.13.0. GitMCP's generic tool handlers and SSE implementation were inspected. This integration uses protocol adapters/configuration rather than vendoring their code. Free Search is MIT; GitMCP is Apache-2.0.

- https://github.com/sweetcornna/free-search-mcp/tree/v0.13.0
- https://github.com/sweetcornna/free-search-mcp/blob/v0.13.0/src/search_mcp/server.py
- https://github.com/idosal/git-mcp/blob/main/src/api/tools/repoHandlers/GenericRepoHandler.ts
- https://github.com/idosal/git-mcp/blob/main/dist/server.js

## Validation and remaining limits

The production catalog/search adapter, result extractor, repository documentation index and helper code compiled in an isolated Kotlin runner with dependency stubs. Thirteen provider fixtures passed, including bare and bridge-namespaced Free Search; additional checks covered freshness boundaries, generic private-search exclusion, documentation ranking, pagination and symlink exclusion. This is not an Android build.

Nine dependency-free Node tests passed (remote-source validation and transcript artifact regressions). Bridge syntax passed. The complete bridge suite is blocked because the MCP SDK dependency is absent and the offline npm cache lacks a required package; no live SSE-to-Streamable-HTTP test is claimed. New Android-dependent tests cover GitHub commit-pinned requests and service connection routing; these await CI because the Gradle distribution is unavailable here.

Remaining checks: full Android build/tests; the complete Node bridge suite after `npm ci`; live Free Search and GitMCP discovery/calls; cancellation and reconnect across the SSE bridge; actual profile enable/disable, backup/restore, and source-popup behavior on the phone. No claim that external services eliminate hallucinations or return unlimited searches is made.
