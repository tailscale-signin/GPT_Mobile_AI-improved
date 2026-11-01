# Combined feedback repair — 2026-10-31

Repository: `tailscale-signin/GPT_Mobile_AI-improved`
Base: `main` at `be5d55930df8be6caad2380eea71b2f9a6bb0419`
Branch: `fix/combined-quality-budget-rendering-2026-10-31`

Intent: address the supplied partial-export feedback without treating its retained events as a complete capture or claiming measured device improvements.

## Behavior

- Straightforward requests receive a 350-word maximum independent of contributor volume. Explicit word requirements and broader tasks retain their own contracts. Conservative paragraph deduplication and sentence-boundary shortening run before final assessment, including structured answers; changed text ignores stale decoder offsets during reassessment while retaining parser failures and exclusion decisions.
- New conversations default to adaptive contributors: at most three selected profiles, including the primary, for straightforward questions. Research, broad tasks, explicit every-contributor requests, the Every contributor diversity preference and the off switch preserve the full selection. Saved legacy preferences retain their previous membership and serialization, including accepted-policy digests.
- Short single-pass requests use the focused editorial claim inventory while full immutable contributor bodies remain retained. The writer receives the same visible length contract, explicit regional-variation guidance and per-contributor source-availability states.
- A failed required check, unestablished semantic coverage, material internal conflict or incomplete conflict scan prevents a checked final publication. Existing committed prefixes and original contributions remain available. The provider's reported output is checked against the frozen writer allowance; this does not undo already billed output or certify token counts when the provider supplies none.
- Completed answer bundles wait for `ApiState.Done`; a subsequent error or missing terminal event cannot publish them. The coordinator commits a checked answer and terminal run status in the existing fenced terminal transaction before showing it. It avoids a duplicate terminal write during normal finalization.
- HTTP 410/retired endpoints and insufficient-credit/payment failures are permanent failures for the current turn. They are excluded from retry and transient stream recovery, with actionable profile status text. Updating account/configuration and starting a new turn re-evaluates eligibility.
- A generic `web_search` alias can route only to exactly one currently exposed search tool with a compatible schema. Ambiguity is rejected without execution; no connection is discovered or authorized by alias recovery.
- Combined work-allowance exhaustion has a separate result flag. Runner, research readers and crawler consumers stop optional work and use retained evidence without claiming that Maximum Tool Calls was exhausted. Optional-tranche denials include remaining resources and requested resources.
- Markdown sampling is reduced from 120 ms to 500 ms, live corpus-wide wording matching is deferred, and Combined streaming fades are suppressed. Long streaming documents use the existing virtualized page viewport, with a conflated non-cancelling preparation loop. Text, source-link presentation and page ranges are captured together to prevent a shorter replacement from being indexed with older ranges.
- Both export writers surface active run IDs and an explicit completion-state interpretation beside the existing dropped-event/gap metadata. URLs alone do not establish retrieval; a global source-unavailability assertion conflicts with retained readable evidence.

## Validation

- `bash scripts/validate-local.sh fast`: 276 Python tests plus CI/dependency/resource/regex/provenance checks passed on the final fast-check run.
- Kotlin host harness: 398 tests passed, including new scope, provider-failure, settings round-trip, search-alias and work-budget finalization regressions. It compiles the actual tool runner/budget code with Android/persistence boundaries stubbed; it is not an Android app build.
- Expanded host harness: 90 tests passed after the publication review, compiling actual delivery/final-check/source-mapping/synthesis-prompt/policy/turn-budget code. Android/logging/research-ingestion boundaries are stubbed. It includes output-overrun, protocol-error retention, duplicate-cleanup, global-versus-contributor source status, and updated publication-gate expectations. The Android `ApiState` reducer, Room terminal transaction and Compose integration still require CI/device validation.
- Requested Android Gradle tests could not start: the wrapper download for Gradle 9.8.0 failed with `Network is unreachable`. This host has JDK 17; the full repository check additionally requires JDK 21 and Android SDK 37.
- No on-device performance measurement or claim of semantic/factual certification is made. Conflict and claim alignment remain conservative local checks; originals and evidence receipts remain authoritative for review.

## Pre-publication review

- Re-fetched `main`; base remains `be5d55930df8be6caad2380eea71b2f9a6bb0419`. The user explicitly authorized pushing this branch and opening a draft PR.
- Preserved structured-parser errors through local shortening, preventing cleanup from making malformed output eligible for checked publication.
- Scoped source-unavailability detection to global retrieval statements, allowing one contributor to report failure while another has readable evidence.
- Bound the virtualized inline document's text, ranges and source presentation to one snapshot so replacement cannot recolor an older page using newer answer links.
- Updated prior delivery tests to assert the new withholding behavior for interrupted or unrepaired answers.

- Re-ran canonical fast validation after the audit: 276 Python tests and all fast preflights passed. Formatting and whitespace checks passed on the changed Kotlin files.
