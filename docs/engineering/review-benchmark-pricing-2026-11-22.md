# Repository review: benchmark price provenance

## Intent and revision

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Parent pull request: `#2254`
- Base branch: `work/review-implementation-2026-11-22`
- Base SHA: `b6417173b071d1fbecd8e831d80c0fb4cd6c2e4c`
- Working branch: `work/review-benchmark-pricing-2026-11-22`
- Intent: capture immutable account/provider prices for the exact benchmark route using the existing pricing settings; retain unknown cost when attribution is incomplete; prevent private price-source notes and URLs from entering standard benchmark exports.

## Checkpoint

Read `AGENTS.md`, `.github/instructions/ai-engineering.md`, and the benchmark subsystem in `docs/architecture/repository-index.json`. This isolated follow-up preserves the parent changes for skipped in-flight work, micro-currency precision and unsuccessful-effort accounting. The parent owns global inventories, integration, Android CI, merge and release publication.

No provider price was fetched or invented for this change; run capture uses the existing stored pricing evidence.

## Implementation

1. Shared pure price resolution now serves both the existing account-rate UI and benchmark capture. Account overrides require the exact connection/profile, endpoint, transport, model, batch mode and routing context, with consistent provider/model identity. Provider catalog rates require a recognized billing origin; an API-compatible custom endpoint does not inherit an official provider rate.
2. Each run captures its immutable configuration key, a digest of the applicable price context, effective request model, billing origin, currency and exact decimal-rate quote. Benchmark requests use interactive pricing; a batch override cannot price that route. Deterministic OpenRouter benchmark model aliases follow the existing `:batch` normalization. Stored catalog hydration performs no network refresh.
3. Quotes preserve their original review/validation and expiry evidence. Invalid, expired, route-reference or conditional rates do not yield a complete API cost estimate. Decimal arithmetic rounds once per fixture after both token lanes, and unrepresentable totals remain unknown. Later settings changes cannot rewrite previous snapshots. The old allowance-price field remains decodable but cannot supply route evidence it never recorded.
4. New snapshots retain only source kind and digest; the copied quote's source URL is blank. Standard and diagnostic exports also redact private notes/URLs from older or imported snapshots while preserving rates, dates and source provenance. Re-export is stable. Legacy private-detail export remains explicitly opt-in and passes through the existing credential redactor.
5. Positive final-reported token usage counts as execution evidence even when a contradictory retained record says no dispatch. Such records cannot be priced as a free pre-dispatch rejection or dropped as an unattempted skip. The parent behavior for retries, missing participant usage, incorrect answers, skipped in-flight work and six-decimal cost display is preserved.

## Validation

- PASS: 41 focused Kotlin/JVM behavior tests exercise the production resolver, price validity/freshness model, benchmark efficiency/coverage, privacy transformation, accounting math and presentation formatter. The host harness supplies narrow unrelated platform/benchmark data boundaries and uses the real serialization JSON library for privacy tests.
- PASS: ktlint 1.3.1, `git diff --check` and Android regex validation (1,885 literal patterns; 84 dynamic patterns skipped; zero errors).
- STOPPED: `bash scripts/validate-local.sh fast` passed CI policy then detected a stale generated architecture index. The parent worker owns regeneration of shared inventories after integration; this isolated branch does not modify them.
- ADDED FOR FULL CI: `BenchmarkExportTest` serializable-run round-trip and standard/diagnostic legacy-source redaction regressions.
- NOT RUN LOCALLY: full Android compilation, Hilt/Compose generation, serialized whole-export integration tests and device tests because the Android SDK is unavailable. Host checks do not replace the parent worker's required full Android CI.

The reproducible host harness and logs are retained in `/workspace/scratch/dfab796cea9c/benchmark-pricing-host-tests` for integration review.
