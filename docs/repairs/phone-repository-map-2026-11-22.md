# Repository map without Gateway

## Scope and ownership

This change extends PR #2254 on `fix/diagnostic-execution-completion-2026-11-22`.
Implementation began at `e9148ab3dd6df0d1ffb67a630304c1a93c139aed`, with main at
`c023735c02368fef4c748cf7be97b9fcfe2cd513`. Concurrent branch changes must be retained.
The paired task checkpoint records reconciliation and validation status.

## Behavior

`RepositoryMapRepository` selects the phone by default. `map_source=gateway` is an
explicit choice and retains Gateway admission, source-sharing consent and durable
index observations. Neither route retries a rejected operation through the other.

The native provider verifies the selected GitHub repository and current account,
connection, profile and plugin permissions before releasing cached or downloaded
source. Immutable commit and tree identities bind every result. Phone model-tool
access uses native GitHub authorization; it does not require Gateway sharing
consent or persistent engineering memory. Explicit Gateway model requests still
check the requested inference destination against their sharing consent.

The phone immediately projects the GitHub tree into files, folders and observed
containment links. Source preparation adds reusable per-blob parser shards to the
existing encrypted repository store. Index batches retain the existing limits of
24 ingestion attempts, 100 scanned entries and 4 MiB. The interface advances at
most 16 batches or 90 seconds in one foreground session, reports progress, allows
Pause and resumes saved work at the same revision. Cancellation cannot create an
unknown remote job because this route never submits one.

Cached source contributes definitions, imports and call candidates. The existing
map exposes architecture browsing, text search, source navigation, node inspection,
possible callers/callees, paths, possible impact and metrics. Source confidence,
unsupported files, incomplete scans, missing files and response limits remain
visible. `compiler_resolution=false` applies throughout; these observations are
not compiler resolution, test execution or proof of complete program behavior.

Pagination binds account, connection, repository, immutable revision, parser,
query and available coverage. Switching map source or advancing the index drops
old page cursors. Generation checks prevent a late response from replacing the
newly selected map. Symbol identifiers use a separate namespace and bounded
digest, so a crafted repository filename cannot overwrite a symbol node.

## Validation boundaries

Behavioral regressions cover tree-only browsing, parser candidates, immutable
blob/revision identity, source/index pagination, account and permission changes,
progressive pause/resume, source-switch races, explicit provider routing, tool
recovery and retained Gateway checkpoint behavior. Focused host harnesses compile
production components with platform boundaries isolated; they do not replace
the complete Android build, Compose rendering or required CI.

Two local whole-project Android compile attempts ended when the Gradle daemon
disappeared, once during KSP and once during KAPT stub generation. These attempts
are not recorded as successful builds. Current-head Android, lint, test, security
and required aggregate checks remain mandatory before merge. Main checks and
signed release readback remain mandatory before publication is considered complete.

No physical-phone performance, thermal, battery or live-service qualification is
claimed. Current GitHub authorization and connectivity are required. Coverage
describes the supported source candidates actually inspected; generated,
unsupported, dynamic and unscanned relationships can remain unknown.

## Completed local checks

- 101 focused production-component tests passed: 24 graph/scanner, 10 source-session,
  6 authorization, 31 ViewModel, 12 presentation, 7 routing facade, 3 model-tool
  and 8 existing Gateway fallback cases. Platform boundaries are isolated.
- The actual map UI and ViewModel sources compiled with the Compose compiler and
  cached AndroidX libraries. This is compilation evidence, not rendered-device evidence.
- Canonical `bash scripts/validate-local.sh fast` passed with 615 Python tests,
  generated inventory, dependency, ICU regex, resources, icon and whitespace checks.
- Ktlint 1.3.1 passed for all 27 changed Kotlin files, and scoped release validation passed.
- The combined change preserves concurrent commits through
  `26560d62a29b9d9cc628d5d5bf2bc64554222e9d`.
