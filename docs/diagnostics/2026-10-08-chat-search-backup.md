# Chat, search, Amazon and backup corrections

The supplied diagnostics exposed repeated Amazon calls, errors hidden behind large
JSON envelopes, repeated MCP compatibility probes, and missing MathJax dynamic
font modules. This change bounds repeated retail searches without dropping already
accepted calls, extracts actual provider failure reasons before truncation, caches
legacy transport detection, and bundles the matching MathJax SVG modules for
offline asynchronous rendering. DNS failures and denied Android location access in
the log are device/network conditions, not evidence of an application crash.

The chat jump control uses the active theme, jumps directly to the latest item,
and pulses once per second during generation. Answers omit source-list presentation
while original text and tool evidence remain available for provenance. A centered
circular Sources control opens total counts, known-site filters and Other.
Marketplace entries expose actionable setup and website links; documentation-only
View guide entries are removed from the marketplace.
The Stack Overflow documentation link now points to its current
[official MCP setup page](https://api.stackexchange.com/docs/mcp-server/).

See [shared search](../WEB_SEARCH_INTEGRATION.md) and
[Amazon product details/history](../amazon-search.md#shared-product-details-and-public-price-history)
for provider behavior and limits.

## Statistics and legacy backups

Debug Statistics has a direct Backup / restore entry. The Statistics section
includes model invocation tokens, costs, timings, profile attribution and supporting
conversation/tool records. Completed statistics survive process restarts; requests
interrupted at backup time restore as interrupted, never as running operations.

Importing the old GPTBKUP configuration, database, user or favorites containers
also writes a current-format encrypted snapshot of the restored sections. Older
complete-backup manifests and database schemas migrate before the same conversion.
Installation-bound GPTFULL2 containers convert to portable encryption. Current
password-encrypted GPTFULL1 and portable GPTFULL3 containers remain supported.
The original file is preserved. The restore dialog offers Save converted backup
and, when needed, Save recovery key. A suitable original password is reused;
otherwise a portable recovery key is generated. Converted backups/keys are excluded
from attachment collection. A conversion-storage failure is explicitly reported
without misreporting a successful data import as lost.

Legacy JSON backups contain local-model metadata, not weights. Missing or incomplete
downloads restore as retryable failed records; valid local files remain ready.
History-only archives do not advertise or overwrite absent profiles.

## Validation

Focused regression suites cover backup round trips and old schemas, statistics,
Amazon merge/permission/HTTP behavior, multi-engine concurrency and URL uniqueness,
tool-loop finalization, source presentation and marketplace contracts. MathJax
extended symbols were rendered in a headless browser with all external requests
blocked. The regression run passed 311 tests; final Amazon/search/budget coverage
passed with 323 total test cases represented in the reports. `assembleDebug`,
ktlint on all 58 changed Kotlin files, and `git diff --check` passed. The 74 explicit
marketplace website links were checked, with the broken Stack Overflow URL repaired;
optional-package documentation links were also checked.

Android device testing remains necessary for WebView/driver behavior,
dynamic-theme appearance and live Amazon public-page availability.
