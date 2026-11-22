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

The entire tree preparation and cached-shard projection runs off the UI dispatcher.
Typed local Privacy Mode rejections retain their policy code and recovery action.
Each native map tool invocation records actual GitHub requests: a locally blocked
first send is not dispatched, while a later blocked request preserves earlier
observed reads. The rejection stops queued tool authority without enabling a
different map route. The interface retains the matching map and asks the user to
review Privacy Mode before refreshing.

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

## Concurrent integration follow-up

The combined branch preserves the privacy and UI work through
`b8ac69dcac38f7d71d6fbe00135dae1c34302ad2`. Current follow-up validation passed
98 focused tests: 30 engine, 47 ViewModel/presentation and 21 provider/tool/Gateway
fallback tests. The production privacy store, HTTP privacy gate and request
accounting are included; Android platform and unrelated service boundaries remain
isolated. Actual Compose compilation and the canonical 615-test host checks passed.

The long-path regression failed on the concurrent baseline with
`PHONE_GRAPH_RESULT_TOO_LARGE` before returning its first page. The fix pages
ordered path IDs together with their nodes and reports whole-path endpoints and
page offsets. A 250-hop path now returns every one of its 251 unique nodes, in
order, within the model tool's 16,000-byte limit on each page.

Privacy UI coverage retains the selected source and last matching map. If an
older Gateway index remains unconfirmed, the interface asks for Privacy Mode
review followed by a saved-status refresh and retains the existing indexing fence.
The new rendered UI cases still require the hosted Android suite.

## Hosted integration repairs

The hosted Android run on `b8ac69dc` passed production and test compilation, then
ran 12,684 tests with three failures and five skips. The map source-link fixture
incorrectly constructed a context with an unresolved branch name. It now checks
constructor rejection directly and tests path traversal with a valid immutable
context. The real-context host suite reproduced the old failure and passes all
15 corrected cases.

The diagnostic projection assertion incorrectly expected manual recovery for an
earlier unknown outcome. The corrected 11-case suite keeps reconciliation first
while retaining the typed privacy reason and original correlation identities.
Production projection behavior did not change.

MCP discovery previously inferred earlier interrupted reads from the outer type of
suppressed exceptions. Wrapped failures and legacy reconnects could lose that
evidence. A per-invocation observation now retains interrupted reads separately
from response timestamps. Local privacy rejection, cancellation and a local
not-connected state do not create interruption evidence; retry bounds, read
allowlists and the prohibition on replaying tool calls are unchanged.

Concurrent commit `6e788707` introduced immutable per-conversation privacy and
Room schema 54. Its four MCP privacy tests are preserved. New transport cases inject a
local gate rejection after an actual interrupted read without changing a
conversation's privacy through global settings. Sixteen current host cases pass,
including a real MCP SDK/CIO connection to a local fixture server. The whole
manager, Robolectric, database migration and full Android suite remain hosted CI
requirements. Together these repairs pass 42 focused cases and 616 canonical host
tests; generated inventory and formatting checks also pass.
