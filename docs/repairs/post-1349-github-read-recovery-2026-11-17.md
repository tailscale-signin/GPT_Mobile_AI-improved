# Post-1349 GitHub read recovery, November 17, 2026

Base: `6f0d7ac37b03e63fec04a9c8dc1990768b8549e6`. Diagnostic evidence: build 141, commit `3319c37a2cb0cafdf9d1fbe8bd935d07ef4591a2` and the attached `diagnostics-29e5fc3f-c135-436e-bb64-b879a6d7af04-part-1-25795b72-a99b-4c4a-8d6e-b4f0b440c2ea.zip`.

## Confirmed defect

Three live dispatched calls of `github_pr:context` and `github_context:branch_dependencies` were rejected as `GITHUB_PAGINATION_INCOMPLETE` with zero record collections despite being single-resource responses with nested detail. The guard incorrectly treated `has_more` on single-resource results as a numbered collection and could invent page 2. This patch preserves those results without weakening real collection pagination checks.

Build-141 issue reports #1529 and #1530 also demonstrate top-level `path` arguments in `github_read` where the contract requires `ranges[]`. The new proposal maps only the actual supplied safe relative path into one range and never dispatches a rejected call automatically. The existing `github_search` unknown `per_page` correction remains intact.

## Remaining open reports / disposition

All 29 open issue reports are historical observations from the installed build, not proof of failure on this candidate. Other pagination requests, rate limits, invalid argument constraints, protocol frames, unknown chat failures, acceptance fixtures, exhausted budgets, and saved-view recovery still require exact-head CI and current-build evidence. A fixture authority condition does not constitute a source bug and is never bypassed. Do not close unresolved issues as repaired without qualifying results.

## Required verification

Run targeted Kotlin tests, inventory checks, Android compilation, CI, CodeQL and real device/Gateway checks on this exact head; do not count predecessor or cancelled workflows. Preserve incomplete evidence and authorization boundaries.
## Feature delivery matrix

Implementation branch `fix/post-1349-context-pagination-recovery-2026-11-17` in `tailscale-signin/GPT_Mobile_AI-improved`, based on `main` at `6f0d7ac37b03e63fec04a9c8dc1990768b8549e6`.

| Requested behavior | Entry point and runtime path | Test evidence | Target package | Installed and live verification |
| --- | --- | --- | --- | --- |
| Singleton GitHub context | GitHub Chat tool requests pass through the action-specific pagination guard for PR context, commit and branch dependencies | Singleton and collection regression cases in the PR. See the candidate head's checks for execution | Android 0.9.66.8 build 142 | Pending selected model, GitHub account and installed build |
| Correct malformed reads | The live agent routes the exact top-level path into a range before ordinary schema, permission and budget admission, preserving the provider call identity | Validator and routing regressions cover identity and reject unsafe paths and mixed selectors | Android 0.9.66.8 build 142 | Pending installed GitHub Chat verification |
| Preserve malformed provider frame diagnostics | OpenAI-compatible and Groq adapter failure receipts retain a bounded subtype for reporting | Provider adapter regression cases in the PR | Android 0.9.66.8 build 142 | Pending real provider reproduction. Protocol repair is still open |
| Reduce repetitive summaries | GitHub chat ticker and narrated updates allow first findings at 45 seconds, then space follow-ups by 135 seconds | Clock-driven tests cover a second distinct source finding and narrator admission | Android 0.9.66.8 build 142 | Pending |
| Windows update publication | Gateway Windows installer workflow builds and smoke-tests the installer and upgrade ZIP. The explicit publish job on main checks exact-source CI and verifies released asset digests | Gateway host tests and Windows workflow run are separate evidence | Gateway 17.0.1. Signed Android release targets 0.9.66.8 build 142 | Pending publication, installation and update acceptance |
| Rich summaries, tables, charts and XLSX | GitHub chat uses budgeted selected-profile narration for qualitative evidence. Structured check receipts retain the table, chart, CSV and **Save XLSX** | Presentation and workbook tests check recognized inventory, numeric cell types and safe source labels | Android 0.9.66.8 build 142 | Pending installed UI verification. Wider evidence types remain open |
| Default acceptance configuration | Gateway Settings → GitHub → Repository Validation can discover candidates for approved behavior mappings | Existing validation setup and acceptance discovery tests | Existing feature is partial | Automatically applying authorized mappings is pending |
| Complete selected-profile GitHub benchmark | Benchmark → App Audit exercises the profile with fixture tools. Live read probes are separate | Existing fixture and read-probe checks | Existing feature is partial | Selected model → Android → Windows Gateway → GitHub → saved result → UI remains pending |

For live requalification, record Android's version name, version code and source SHA,
and record the active Gateway version, source SHA and updater ZIP SHA-256. Use a
selected profile and an authorized GitHub repository with a known PR, commit and
branch. Run the transport probe, then exercise PR context, commit context and
branch dependencies through GitHub Chat. Keep the resulting request and saved
result IDs, verify that each result reopens in the UI, and confirm that no
synthetic page continuation was issued. Record the account and repository
references without exporting private source or credentials. The transport probe
and hosted emulator cover their respective paths. They cannot mark the live
selected-profile chain verified.

## Reconciled November 17 build-142 reports (43 open issues at intake)

The newer build-142 reports demonstrate that source repairs on this draft branch
must be qualified at the installed/native tool boundary, not inferred from
agent-only tests or a prior APK. Review all 43 open reports (#1505-#1548,
excluding this PR) as a changing historical frontier:

- **Native dispatch and arguments:** #1542-#1543 reproduced unsupported
  `github_search.per_page` after provider repair attempts; the native boundary
  now applies the existing *schema-validated, first-page-only, read-only*
  correction without consuming another model repair. #1540 and #1541 concern
  `github_issue` list/cursor validation; their exact invalid selector values
  are not present, so retain them pending a typed recapture. #1519, #1516,
  #1531 and #1529-#1530 remain independently classified.
- **Output pressure:** #1546 and #1547 show successful HTTP-200
  `github_pr:list` / `github_ci:workflows` reads, but the shared tool-result
  budget reached **24,576/24,576 bytes** after seven dispatched calls.
  This is neither a provider 429 nor proof of a pagination regression.
  Measure saved-page continuation and result-byte allocation in the live test.
  #1511 is a separate earlier call-budget report.
- **Causal gaps:** #1548 and #1507 report `CHAT_FAILED` without an established
  cause; #1545 and #1524 report claim-extraction failures. A report-boundary
  marker now identifies where generic and incomplete error values were observed
  when no prior capture site is present. This marker is **not** proof of the
  exception type, model invocation or remote dispatch.
- **Provider controls:** #1538 is a verified Llama `REASONING_OFF_UNSUPPORTED`
  admission; #1544, #1537, #1535 and #1509 recorded real OpenRouter HTTP 429
  responses. Preserve effective reasoning mode, requested mode, template
  control qualification, retry deadline source and provider/model route before
  claiming a fixed cause. #1532-#1533 require fresh malformed-frame subtype
  verification on an installed candidate.
- **Acceptance and policy evidence:** #1539, #1536, #1523, #1515, #1514 and
  #1508 lack complete verified acceptance/policy receipts. Candidate test
  discovery is not automatic host approval. A GitHub branch-protection 404 or
  integration permission denial must remain an *unavailable* observation.
- **Prior pagination and retained views:** #1528, #1527, #1526, #1525,
  #1522, #1521, #1518, #1513, #1512, #1510 and #1506 concern older
  single-resource and collection pagination observations. #1520/#1517 are
  dependent precheck tool failures and #1505 is saved-view recovery; each
  requires the exact current action, cursor owner and source revision before
  closure.

**Close criteria:** The issue's actual action must pass in a freshly installed
Android build with a matching source SHA, selected AI profile, Gateway source
and package revision, and authorized GitHub ref. Confirm that both the saved
receipt and UI reopening pass. Retain genuine provider 429 incidents until
the retry/resumption receipt verifies recovery. Do not close unsolved cases
only because a branch changed; missing cause is a recapture requirement,
not success. Never include raw prompts, credentials or private paths in
automatic report payloads.
