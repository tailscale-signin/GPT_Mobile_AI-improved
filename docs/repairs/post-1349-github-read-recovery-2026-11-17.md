# Post-1349 GitHub read recovery, November 17, 2026

Base: `6f0d7ac37b03e63fec04a9c8dc1990768b8549e6`. Diagnostic evidence: build 141, commit `3319c37a2cb0cafdf9d1fbe8bd935d07ef4591a2` and the attached `diagnostics-29e5fc3f-c135-436e-bb64-b879a6d7af04-part-1-25795b72-a99b-4c4a-8d6e-b4f0b440c2ea.zip`.

## Confirmed defect

Three live dispatched calls of `github_pr:context` and `github_context:branch_dependencies` were rejected as `GITHUB_PAGINATION_INCOMPLETE` with zero record collections despite being single-resource responses with nested detail. The guard incorrectly treated `has_more` on single-resource results as a numbered collection and could invent page 2. This patch preserves those results without weakening real collection pagination checks.

Build-141 issue reports #1529 and #1530 also demonstrate top-level `path` arguments in `github_read` where the contract requires `ranges[]`. The new proposal maps only the actual supplied safe relative path into one range and never dispatches a rejected call automatically. The existing `github_search` unknown `per_page` correction remains intact.

## Remaining open reports / disposition

All 29 open issue reports are historical observations from the installed build, not proof of failure on this candidate. Other pagination requests, rate limits, invalid argument constraints, protocol frames, unknown chat failures, acceptance fixtures, exhausted budgets, and saved-view recovery still require exact-head CI and current-build evidence. A fixture authority condition does not constitute a source bug and is never bypassed. Do not close unresolved issues as repaired without qualifying results.

## Required verification

Run targeted Kotlin tests, inventory checks, Android compilation, CI, CodeQL and real device/Gateway checks on this exact head; do not count predecessor or cancelled workflows. Preserve incomplete evidence and authorization boundaries.