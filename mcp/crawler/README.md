# Crawler companion — optional acquisition service

This optional Linux Python companion has an Android catalog entry and signed-grant bridge. The built-in Crawlers plugin works independently of this service. Browser egress and device end-to-end acceptance remain unqualified; installing Crawl4AI does not make `browserReady` true.

## Local development

Use Python 3.11 or later in a dedicated environment. Install `.[browser,alternate]` for the pinned Python engines; `.[documents]` is separate. Browser binaries and document models belong on the server, never in the APK. The code does not invoke an LLM or a paid extraction API.

Run `gptmobile-crawler --data-dir /private/crawler --grant-key-file /private/crawler-grant.key`. The grant file must contain at least 32 random bytes and have owner-only permissions. The trusted gateway supplies signed grant metadata; the model cannot supply grants in tool arguments. Do not place that key in model context, logs, or portable backups.

The stdio facade discovers exactly `fetch`, `start`, `status`, `results`, and `cancel`. The Android alias `crawler_companion` selects the signed-grant adapter and provides names such as `crawler_companion__fetch`. `crawler://health` reports installed versions and extraction readiness separately. Authenticated stateless Streamable HTTP is available at `/mcp`; Android sends authenticated grants in transport metadata after current profile and connection checks.

## HTTP deployment

Add `--http-host 127.0.0.1 --http-port 8120 --http-token-file /private/crawler-http.token` to the same command for a loopback listener behind a TLS reverse proxy. The token file must be owner-readable only and contain at least 32 non-whitespace ASCII characters. Keep the HTTP bearer token separate from the grant-signing key. For a LAN/Tailscale bind, supply `--tls-cert` and `--tls-key`; a non-loopback plaintext listener is rejected.

Send authenticated JSON-RPC POST requests to `/mcp`. Initialization, discovery and health use the same facade as stdio. Tool calls still require signed `gptmobile/crawlerGrant` metadata from the trusted host; bearer authentication alone does not authorize source acquisition. Cancellation notifications carry that same metadata and are scoped to the principal, root and grant. No browser Origin is accepted unless explicitly configured with `--allowed-origin`. GET/SSE is unsupported and returns 405; notifications return 202. The service does not log request headers, credentials, source URLs or source text.

## Implemented boundaries

- One coordinator owns the frontier. It has four background and two interactive worker slots, six HTTP permits, two permits per domain, and a one-second dispatch interval per domain.
- Every robots fetch, redirect and page request goes through DNS validation, a pinned connection and durable parent-grant accounting. Domains must be explicitly in scope. Cancellation closes sockets and fences publication.
- Grants are shared across jobs; creating another job cannot multiply their allowances. Unconfirmed attempts survive restart as uncertain. Startup never silently repeats them.
- Text HTML uses Trafilatura 2.3.1. Scrapling 0.5 is a captured-byte fallback. Text PDFs use the installed `pdftotext`. Add `--enable-ocr` to opt into local Tesseract English OCR for textless PDFs (at most three pages, always partial). Install `pdftoppm`, `tesseract` and licensed local `eng` traineddata separately; no language models are downloaded at runtime. Browser capability remains explicitly unavailable.
- SQLite schema 3 stores content and artifacts once per access scope. Resumed jobs reference those objects and have separate new-work accounting. Deleting a parent preserves objects still referenced by its child.
- Default content allowances are 512 MiB per service and 128 MiB per principal. These cover retained text and artifact payloads, not SQLite/index overhead. A full quota stops publication without erasing acquisition receipts.
- Unpinned terminal jobs expire after seven days; temporary jobs expire after one hour. Snapshot cursors expire after one day. Expiry runs when accepting new work. Explicit archive pins prevent routine expiry; temporary evidence requires an explicitly authorized copy instead of a pin. The Android managed crawler copies validated companion fetch chunks into its shared evidence store; users can then copy committed pages into Research. It does not pin remote source records or pretend a paginated excerpt is a full document.
- Upgrades check integrity and create a verified SQLite backup before migrating existing schema 1/2 databases. Rollback cannot open a newer schema. Cooldowns persist across process restart.
- Result pagination is snapshot-stable and counts the full JSON-RPC envelope, including compatibility text. Too-small output budgets produce an error with a retained job reference rather than empty success.

## Validation

`python -m unittest discover -s tests -v` exercises the actual SQLite store, coordinator, facade, extraction boundary and metered fake transport. Tests include real loopback HTTP initialization, authentication, origin/protocol rejection, bounded requests, signed fetches and cross-principal cancellation. Tests include an actual scanned-PDF OCR fixture when local tools/data are present. Browser rendering and real phone-to-server/device acceptance remain unqualified. Distribution still requires hash-locked dependencies, upstream notices, a qualified browser build and separately licensed model manifests.

## Android setup

Install **Crawler Companion** from the marketplace using the server's reachable
HTTPS `/mcp` address and separate HTTP bearer token. Keep its
`crawler_companion` alias. In **Plugins & Tools → Crawlers**, save the private
grant key as 64–128 hexadecimal characters. This is the byte content of the
server's grant-key file encoded as hex, not its pathname or HTTP token. Enable
the connection and only the required operations for the selected profile.
Select its approved read-only `fetch` operation as a multi-crawler reader.

Grants expire 24 hours after the accepted turn and share a bounded request and
64 MiB transfer allowance across jobs. A new permission revision cannot silently
resume old authority. Create new authorized work after expiry; do not reuse or
weaken the old grant. The Android native crawler can resume paused jobs from its
saved-jobs panel. Companion jobs expose their own start/status/results/cancel
operations and retain their existing reconciliation restrictions.
