# Native GitHub precheck evidence — November 12, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Parent PR: #838. Implementation starts from `e1a754336d291ee1ad72878078d7f854d3d0735e` on `fix/session-reliability-reporting-2026-11-12` and is isolated in `codex/open-issue-tools` for integration.

## Verified source defects

- Native precheck failures such as a repository target mismatch, invalid saved-view operation or changed saved-view authorization were reduced to `APP_ERROR` when their internal code was outside the public reporting allowlist. The incident collector now maps a closed set of those codes to **existing** service-supported public categories. Projection requires `TOOL_RESULT`, `tool_precheck`, an explicit non-dispatch observation and no accepted or uncertain remote outcome. An existing recovery action retains precedence. Unknown codes remain unknown; message text is never used for classification.
- Argument validation returned useful rejection content but omitted its typed execution receipt. Rejections now retain non-dispatch/retry/reconciliation facts, a bounded schema/domain reason, top-level missing/invalid declared field names and an unknown-field count. The field scope explicitly excludes uninspected conditional constraints. No argument values or undeclared field names are included in the receipt.
- Missing or malformed issue selectors now have the same typed read-only discovery guidance as PR selectors. A numeric string does not satisfy the integer selector contract; it receives a discovery proposal without coercion or an invented identifier. Validation and authorization remain mandatory before any corrected request can run.

The approved report schema, allowed public-code set, service negotiation, serialized payload and delivery routes are unchanged. New local field names/reasons are not silently added to the public contract. Existing approved reports retain their exact bytes and hashes.

## Validation

| Check | Result |
| --- | --- |
| Current-source validator and public-category JVM tests | 8 passed |
| Original validator with the new receipt/discovery regressions | 5 failed, reproducing the missing behavior |
| Canonical host gate | 473 tests passed; Android ICU regex, resource, policy and whitespace checks passed |
| Authentication/reporting suite | 56 passed |
| Pinned Kotlin formatter | ktlint 1.3.1 passed |
| Independent source review | No blocking issue found; outcome uncertainty and service compatibility preserved |

The focused JVM harness compiles the current production validator, public-code projection and context class. Android persistence and unrelated tool-contract types are host boundaries. A new `IssueReportCollectorsTest` regression exercises the actual `ToolEventRecorder` to incident path; its Android/Robolectric execution is pending the integrated branch's hosted validation. Host results do not claim that execution.

## Original issue disposition

Issues #857 and #860–#864 identify the installed source `956d50d320c9d2f4794a21eee6886b39eaa14ded`. Their occurrences are later than the newest available diagnostic capture at **19:38:08.815 UTC**. The reports prove a search argument rejection and several non-dispatched native tool failures, but do not expose the original query, exact rejected field or internal cause.

These source repairs improve subsequent evidence and a confirmed selector correction path. They do **not** prove which underlying failure produced each original report or that its requested operation now completes. The original issues remain open until a matching retained tool receipt/checkpoint or reproducible branch-build failure establishes that connection.
