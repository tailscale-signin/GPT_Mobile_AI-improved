# GPT Mobile AI 0.9.38.0

## Chat stability
- Fix the repeatable crash when Amazon cards render alongside tool events without completion timestamps.
- Keep all result-owner, product and notice timestamp comparisons as Long values. Preserve the latest product price, sequence tie-breaking and the originating profile's permissions.

## On-device memory
- Detect FTS5 through registered SQLite modules instead of an optional compile-option function that is unavailable on some Android builds.
- Retain cached portable search fallback when FTS5 or module inspection is unavailable.

## Validation
- PR #626 passed Android unit tests/build/lint, Kotlin formatting, CodeQL and the minified native-memory smoke workflow before merge.
- Add regression tests for mixed completion timestamps, latest price/notices, profile ownership and memory module/fallback detection.
- Release publication runs validation again, builds APKs and AAB, and verifies signing-certificate continuity and packaged native libraries.
- This release does not resolve external VPN/DNS outages or ASUS vendor-framework finalizer errors. Physical-device confirmation of the reported chat crash remains pending.

## Release identity
- Package: dev.melo.gptmobile.improved
- Version: 0.9.38.0
- Version code: 107
