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
| Correct malformed reads | Argument validation gives the model a schema-checked correction for the exact observed path | Validator regression cases in the PR | Android 0.9.66.8 build 142 | Pending. An automatically dispatched retry is still open |
| Preserve malformed provider frame diagnostics | OpenAI-compatible and Groq adapter failure receipts retain a bounded subtype for reporting | Provider adapter regression cases in the PR | Android 0.9.66.8 build 142 | Pending real provider reproduction. Protocol repair is still open |
| Reduce repetitive summaries | GitHub chat ticker and narrated updates allow first findings at 45 seconds, then space follow-ups by 135 seconds | Clock-driven tests cover a second distinct source finding and narrator admission | Android 0.9.66.8 build 142 | Pending |
| Windows update publication | Gateway Windows installer workflow builds and smoke-tests the installer and upgrade ZIP. The explicit publish job on main checks exact-source CI and verifies released asset digests | Gateway host tests and Windows workflow run are separate evidence | Gateway 17.0.1. Signed Android release targets 0.9.66.8 build 142 | Pending publication, installation and update acceptance |
| Rich summaries, tables, charts and XLSX | GitHub chat renders recorded check tables and charts from recognized receipts and can copy CSV | Existing presentation tests cover the recognized check inventory | Existing feature is partial | Wider evidence types and native XLSX are pending |
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
