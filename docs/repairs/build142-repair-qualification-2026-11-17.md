# Build 142 repair and qualification

Repository: tailscale-signin/GPT_Mobile_AI-improved. Base: main at
6f0d7ac37b03e63fec04a9c8dc1990768b8549e6. Existing PR: #1534.
Working branch: fix/post-1349-context-pagination-recovery-2026-11-17.
Intake head: aca3a6b6011a77571ba87f29abd80cb051b0a84a.
Intent: implement the November 17 build-142 audit on this branch only.

## Changes

- Normalize safe source reads before native budget/schema admission as well as operation dispatch. Preserve call ID and the original provider invocation in restored journals. Authorization sees the normalized request and still runs before dispatch.
- Accept the declared response_detail option when converting a top-level source path to ranges; normalize the bounded nested EOF form. First-page searches remove only the known unsupported per_page/response_detail options, followed by complete schema, operation and replay-safety validation. Cursor-bearing calls, unknown fields, unsafe paths and mutations are not repaired.
- Apply the existing verification/cleanup reserve to ordinary provider acquisition, retaining a partial task and its ledger before the hard call ceiling. Specialized fixture reconciliation and approval paths retain their earlier ownership.
- Retain bounded receipt-linked exact source SHAs and evidence epochs with acceptance checkpoints. Unknown revisions remain NOT_RECORDED; observed source reads do not claim test success.
- Preserve observed model invocation identity at the generic error boundary without overwriting tool causality. Public diagnostics continue using hashes and explicit unknowns.
- Enrich a native rejected issue-list page with the missing exact-cursor contract and structured validation evidence. Successful REST pagination is unaffected; no cursor or page is silently invented.
- Honor a confirmed upstream shared quota only across models pinned to that provider. Unknown 429s remain route-specific, and unrelated provider routes remain available. Regression covers the observed 60,117 ms wait and restoration.
- Format changed Kotlin files and regenerate both source inventories.

## Issue disposition

The 55 open reports through #1560 remain evidence, not proof that this candidate passed on a device. Families covered by these source repairs: INVALID_ARGUMENTS (#1516, #1519, #1529–1531, #1540–1543, #1549, #1553–1558), budget/acceptance (#1511, #1514, #1560), provider limits (#1509, #1535, #1537, #1544, #1552), generic failure diagnostics (#1507, #1548, #1551, #1559). Existing singleton-pagination, saved-view storage, repeated-admission diagnostics and malformed-frame repairs require their own qualification. Do not close a reproducible incident because a unit test was added.

## Validation and remaining gates

- Local Kotlin formatting and whitespace checks: run on changed files.
- Provider scope/retry host regressions: run actual production policy classes with lightweight request-config/routing ports; not Android integration.
- Canonical fast host validation: run after regenerating inventories.
- Android Gradle tests: local wrapper distribution download is blocked by the environment network. GitHub CI must compile/run the full Android suites at the resulting commit.
- Device/Gateway acceptance: NOT_RUN here. No attached Android runtime, Windows Gateway endpoint, selected model credential or live engineering job is available in this environment. Do not infer Android-to-Windows execution from connector GitHub calls.
- Required before merge/issue qualification: final-head CI, singleton and real collection traversal, exact build-142 arguments through normal/native/restored paths, saved-view reopening after restart, provider wait/resume, exact-SHA acceptance and fixture cleanup, installed summary/export UI, Windows installation and release checks.

Keep PR #1534 draft and unmerged until those gates pass. This document does not authorize relaxed acceptance or automatic issue closure.
