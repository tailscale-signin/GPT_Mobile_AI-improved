# Crawler companion — implementation in progress

This optional Linux Python companion is not yet enabled in the mobile catalog. The Android/gateway grant bridge, browser qualification, document models, and end-to-end acceptance gates must be completed before enabling it. Installing Crawl4AI does not make `browserReady` true.

## Local development

Use Python 3.11 or later in a dedicated environment. Install `.[browser,alternate]` for the pinned Python engines; `.[documents]` is separate. Browser binaries and document models belong on the server, never in the APK. The code does not invoke an LLM or a paid extraction API.

Run `gptmobile-crawler --data-dir /private/crawler --grant-key-file /private/crawler-grant.key`. The grant file must contain at least 32 random bytes and have owner-only permissions. The trusted gateway supplies signed grant metadata; the model cannot supply grants in tool arguments. Do not place that key in model context, logs, or portable backups.

The stdio facade discovers exactly `fetch`, `start`, `status`, `results`, and `cancel`. A gateway alias of `crawler` provides the corresponding `crawler_fetch` etc. names. `crawler://health` reports installed versions and extraction readiness separately. HTTP transport and companion installation metadata are still pending.

## Implemented boundaries

- One coordinator owns the frontier. It has four background and two interactive worker slots, six HTTP permits, two permits per domain, and a one-second dispatch interval per domain.
- Every robots fetch, redirect and page request goes through DNS validation, a pinned connection and durable parent-grant accounting. Domains must be explicitly in scope. Cancellation closes sockets and fences publication.
- Grants are shared across jobs; creating another job cannot multiply their allowances. Unconfirmed attempts survive restart as uncertain. Startup never silently repeats them.
- Text HTML uses Trafilatura 2.3.1. Scrapling 0.5 is a captured-byte fallback. Text PDFs use the installed `pdftotext`; absent OCR/browser capabilities are explicit, actionable outcomes.
- SQLite schema 3 stores content and artifacts once per access scope. Resumed jobs reference those objects and have separate new-work accounting. Deleting a parent preserves objects still referenced by its child.
- Default content allowances are 512 MiB per service and 128 MiB per principal. These cover retained text and artifact payloads, not SQLite/index overhead. A full quota stops publication without erasing acquisition receipts.
- Unpinned terminal jobs expire after seven days; temporary jobs expire after one hour. Snapshot cursors expire after one day. Expiry runs when accepting new work. Explicit archive pins prevent routine expiry; temporary evidence requires an explicitly authorized copy instead of a pin. The app import bridge is not implemented yet.
- Upgrades check integrity and create a verified SQLite backup before migrating existing schema 1/2 databases. Rollback cannot open a newer schema. Cooldowns persist across process restart.
- Result pagination is snapshot-stable and counts the full JSON-RPC envelope, including compatibility text. Too-small output budgets produce an error with a retained job reference rather than empty success.

## Validation

`python -m unittest discover -s tests -v` exercises the actual SQLite store, coordinator, facade, extraction boundary and metered fake transport. Browser rendering, OCR, authenticated HTTP, real gateway integration and device acceptance are not covered by those tests. Distribution still requires hash-locked dependencies, upstream notices, a qualified browser build and separately licensed model manifests.
