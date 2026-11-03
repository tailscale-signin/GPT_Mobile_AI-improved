# GitHub chat audit — export_Test_1793730947653.zip

Examined all five export members and screenshot 54049.jpg. Export build: **0.9.66.4 / 138**, app commit **3de77a00946f5c621443dc7c8668dc8879ef0d1c**. Source changes start from PR #794 at **c9f4ff03cc0ef2b275c82a1fb3dc6ce5538922ad**, preserving its scrolling and recovery changes. Before publication, the branch was aligned with merged main **3a05c621dd0d5dd1777a3decdd92abb8feb20273** (identical source tree). The screenshot is visual evidence of repetition; it does not establish the timing or cause of each log event.

The main defects are incomplete integration testing, poorly routed continuation/conversational requests, avoidable invalid tool calls, and repetitive progress presentation. Successful API responses are not proof of task completion.

## Capture and measured results

| Evidence | Observed result |
| --- | --- |
| File integrity | All four manifest-listed file byte lengths and SHA-256 digests match |
| Runs | 13: 2 COMPLETED, 6 PARTIAL, 4 FAILED, 1 CANCELED |
| Completed requests | `test` acknowledgement and repository `status` |
| Full integration task | Initial run and five resumptions remained PARTIAL |
| Tools | 54 retained calls: 49 completed, 5 failed; these are calls, not 54 distinct capabilities |
| Initial full-test run | 49 calls in 86 seconds, within its 256-call / 60-minute cap |
| Five resumptions | One additional discovery call across the resumptions; remaining four contain no new retained tool calls |
| Model invocations | 6: 5 COMPLETED, 1 CANCELED; all token figures estimated, no final provider token observations |
| Journal | 819 events; zero reported dropped events, missing batches or missing sequences |
| Additional diagnostics | 59 selected-conversation diagnostic events and 2,000 recent app-wide log records; overlapping sources |
| App-wide levels | 7 error records and 10 warning records; repeated symptoms are not independent root causes |
| Final capability table | 68 rows: 37 passed, 2 failed, 2 preflight rejected, 27 blocked, 0 untested, 0 recovered |
| Retained frame windows | 42 windows: 24,678 frames, 201 reported jank frames, 15 stalls over 100 ms; maximum 290.416 ms |
| Timing samples | Main-thread text measurement 28.464 ms; background database publication 590.291 ms |

The manifest explicitly says capture is incomplete and is not a database transaction or full provider-wire replay. No active runs remained at export. No journal loss does **not** imply complete source capture. App-wide provider/voice events belong to other chats and are identified separately below.

## Every distinct issue and diagnostic gap

**Implemented** means source changed on this branch; it does not mean an APK was installed or device behavior verified. **Recommended** identifies remaining work. Repeated observations are grouped by problem family.

| # | Priority | Issue and evidence | Solution and status |
| --- | --- | --- | --- |
| 1 | Critical | Full-tool testing remains incomplete despite available call/time budget. Six task runs are PARTIAL. | Separate capability discovery, read sampling, mutation fixtures and device qualification into explicit acceptance layers. Continue eligible work; report the exact dependency that stops each layer. **Recommended.** |
| 2 | Critical | Source completion policy cannot call the API test complete when the catalog contains any mutation: `GitHubTestChecklist.apiComplete` returns false for such a catalog, even if an owned branch fixture succeeded. | Add a fixture-specific completion contract with ownership, accepted mutation and cleanup proofs. Preserve the distinction between API checks and physical-device tests. Do not simply remove the guard. **Recommended.** |
| 3 | High | Continuations repeat saved blockers with almost no new work: one discovery call across five resumptions. | Persist dependency state and admit bounded refreshes when a prerequisite can change. Replace repeated full reports with a concise change-since-checkpoint result. **Recommended; existing no-blind-replay safeguard retained.** |
| 4 | High | `test again` starts a generative path instead of retaining the original integration task; it records no new tool events. | Recognize the exact `test again` / `retest` commands as recovery controls while retaining the original task identity and receipts. **Implemented**, with recovery tests. Longer substantive requests remain new tasks. |
| 5 | High | That retry ends `WORD_LIMIT_EXCEEDED:382/350` after a rewrite, delivering no visible answer. | Keep test continuation on the deterministic checklist path (implemented for this command). Separately distinguish an explicit user word constraint from an internal presentation target; retain a useful bounded failure explanation and private draft. **General contract-policy change recommended.** |
| 6 | High | A casual “useless message” probe receives a stale repository-status draft and enters review. | Recognize unambiguous conversational probes before repository recall, tools and review. **Implemented** with positive/negative intent tests. This is deliberately conservative, not unrestricted semantic intent classification. |
| 7 | High | Two reviewer invocations fail to produce a valid verdict; no final answer is published. The export does not retain enough reviewer wire data to establish the exact formatting defect. | Keep strict verdict validation; qualify the selected model's structured-output support and record bounded parse/validation errors. Preserve drafts privately. **Recommended; existing single repair and fail-closed behavior retained.** |
| 8 | High | Two subsequent retries immediately fail with `Saved review requires new evidence or a changed draft`, yet retained failure metadata says retry is allowed. | Classify exhausted review recovery as requiring changed inputs, so the UI does not offer an identical retry as useful recovery. **Implemented.** A first format failure remains eligible for the existing bounded repair. |
| 9 | High | `github_context/repositories` is advertised/planned although a programmer conversation is restricted to its selected repository; it produces `REPOSITORY_SCOPE_MISMATCH` before dispatch. | Do not schedule account-wide enumeration from a pinned repository self-test. **Implemented.** Also make the advertised capability catalog scope-aware and show excluded operations as out of scope, not product failures (**recommended**). |
| 10 | High | `github_release/status` is called without required `tag_name`, producing `INVALID_ARGUMENTS`. | Wait for an observed tag/release and include its exact tag. **Implemented**, with a dependency regression. |
| 11 | High | CI log request targets skipped `notify` job 126093630965, with no steps, and gets HTTP 404. | Prefer executed workflow runs and exclude skipped/neutral/cancelled jobs from automatic log sampling. **Implemented.** A 404 on an executed job still needs existence/permission/retention checks; it is not proof of one particular cause. |
| 12 | High | Saved local cursor expires with `CAPACITY_EVICTED` / `SAVED_VIEW_UNAVAILABLE`; its error gives generic recovery advice but no concrete source-recovery call. | Retain immutable origin recipes beyond payload eviction, reserve active continuation handles, and reopen only replayable reads at the same revision. Never repeat a mutation to recover output. **Recommended.** |
| 13 | High | Tag read reports `RETAINED_RESULT_CAPACITY` although `upstream_completed=true`; a useful tag-ref receipt remains available. | Separate remote outcome from local retention outcome. Preserve the compact receipt, retry only bounded immutable reads, and release inactive pinned payloads. **Recommended.** |
| 14 | High | Final report has 14 unread continuations; branch/workflow inventories are partial (10 branches, 5 of 41 workflows). | Maintain separate local-fragment and remote-page queues; label sampling explicitly. Resume required exhaustive traversals within budget instead of describing endpoint success as inventory completeness. **Recommended.** |
| 15 | High | Fixture exercise covers branch creation and deletion, while writes, issues, PR creation, release actions and CI mutations remain blocked. | Extend owned disposable fixture plans per operation, including cleanup proofs, scoped authorization and reconciliation after uncertain outcomes. Never test destructive actions on unrelated work. **Recommended.** |
| 16 | Medium | Generic blocker text groups unlike prerequisites: absent issue IDs, unavailable failure logs, optional index traversal and mutation authorization. | Use typed prerequisites and concrete next actions. Distinguish no applicable resource, optional capability, waiting state and real failure. **Recommended.** |
| 17 | Medium | Index response is classified blocked because traversal is incomplete, even though the API responded. | Track endpoint response, index coverage and exhaustive indexing as distinct criteria; continue indexing only when requested. **Recommended.** |
| 18 | Medium | Progress mixes capability counts and operation failures: 5 failed calls versus 4 failing capability rows; the expired saved-page call is a different category. | Label each denominator explicitly and keep capability coverage, operation errors and test-execution results separate. **Recommended.** |
| 19 | Medium | Generic “Inspect the retained error receipt” recovery advice does not tell the user how to fix several recorded errors. | Map failure codes to concrete dependency/permission/range/tag/capacity remedies and link the relevant result. **Recommended.** |
| 20 | High | Screenshot repeats the same “Repository information is ready” and CI paragraphs; counts of operations change without useful new findings. | Generate concise summaries from current action and observed file/job/run/PR identifiers; suppress recurring summaries and remove generic operation-count padding. **Implemented.** No model reasoning is exposed and tool completion is not called a test pass. |
| 21 | Medium | Timer-driven summaries lag activity and create redundant paragraphs. | Replace the 20-second heartbeat with event-driven observable state; unchanged values do not emit. **Implemented**, including an idle/event timing regression. |
| 22 | Medium | Progress counters are appended as separate entries. | Update a single stable `github-check-counts` timeline entry; also coalesce old saved timelines when rendering. Keep complete underlying tool receipts. **Implemented**, with reducer and presentation regressions. |
| 23 | Medium | “Show earlier activity” appears below previews and after only 3 entries, only for integration tasks. | Apply the control to GitHub activity generally, above the preview, after more than 20 displayed text lines. Count wrapped summary lines using actual available width, font style and scale; keep a summary before recent previews. **Implemented.** |
| 24 | Medium | Workflow presets occupy the chat surface and insert a canned objective into the composer. | Remove the chat button/dialog. Add a saved Settings dropdown with **Heavy work** as default (up to 512 operations / 24 hours); retain engineering/provider caps and explicit task-preset limits. Selection sets limits without changing the user's prompt. **Implemented.** |
| 25 | Low | Repository-map text button consumes chat space. | Replace its visible text with an accessible outline map icon; preserve the explorer and screen-reader description. **Implemented.** |
| 26 | Medium | User/assistant text is too small. | Increase GitHub conversation typography by 2sp, including summaries, previews and Markdown base styles; preserve Android font scaling. **Implemented.** |
| 27 | Medium | User reports blank space above AI answers. | Remove extra top padding before the GitHub response, strip leading blank newline characters for display, and explicitly align naturally measured content to the top of its reserved viewport. Reserved space remains below. **Implemented; physical scroll/IME/rotation behavior remains unverified.** |
| 28 | Medium | Summary text is hard-coded white, reducing light-theme contrast. | Use the theme's `onSurface` color. **Implemented.** |
| 29 | Medium | Full report is 16,149 characters and repeats operation and capability tables plus raw observations. | Present a short outcome, changes since last run, major blockers and next action; move detailed coverage and IDs into expandable evidence. **Recommended.** |
| 30 | Medium | Some reported CI resource branches are missing or fall back to `main`; a PR head and workspace head can differ. | Persist repository, resource branch, exact SHA, run ID and attempt with each receipt. Display unavailable values instead of inheriting an unrelated workspace label. **Recommended.** |
| 31 | Medium | Skipped job timestamps place completion one second before start. | Treat this as upstream metadata inconsistency; use monotonic local timing where available, otherwise show duration unavailable rather than negative duration. **Recommended.** |
| 32 | High | App-wide `current_date` call is rejected with `TOOL_NOT_AVAILABLE`; only discovery was advertised. No dispatch occurred. | Resolve exact available schemas before calling; supply current-date context without an invented tool or explicitly discover a supported date tool. Preserve preflight rejection. **Recommended.** |
| 33 | High | Two app-wide `openai/gpt-audio-mini` attempts fail OpenRouter's zero-retention endpoint policy, HTTP 404 / `POLICY_RESTRICTED`. | Choose/qualify a privacy-compatible voice route; do not weaken privacy automatically. Mark unchanged privacy configuration failures nonretryable. **Classification implemented; endpoint selection requires live service validation.** |
| 34 | Medium | Local engineering memory triggers `PERSONAL_DATA_LOCAL_ONLY` notices. This is a policy boundary, not proof that every GitHub tool is blocked. | Isolate local memory from external-service arguments; keep authorized repository reads available where their actual destination is permitted. **Existing isolation retained; route-specific acceptance recommended.** |
| 35 | Medium | 698 catalog records include 623 omissions and 44 authorization-hidden records in a recent 2,000-record log window. This can crowd out useful history. | Coalesce by connection, catalog revision and reason, retain omitted counts, and prioritize root failures. PR #794 already adds bounded catalog diagnostics; measure the newer build before claiming resolution. **Parent fix retained; further measurement recommended.** |
| 36 | High | App-wide rendering records report 201 jank frames and 15 stalls over 100 ms; main-thread text measure takes 28.464 ms. | Cache Markdown layout by content/width/font scale, bound live activity and avoid reparsing long tables on each update. Measure a before/after frame trace. **Less duplicate activity implemented; broader rendering work and device measurement recommended.** |
| 37 | Medium | Database-publication timing is 590.291 ms on background dispatchers. | Measure transaction duration/queue wait separately; batch/coalesce updates while forcing terminal and approval records to persist. Do not describe this sample as a 590-ms main-thread freeze. **Recommended.** |
| 38 | Low | Two IME callback errors and seven inactive input-connection warnings appear app-wide. | Review focus/keyboard teardown during navigation and cancellation; exercise rotation, back gestures and voice transitions on the device. The capture does not prove message loss. **Recommended.** |
| 39 | Low | Native feedback manager disables feedback tensors for a model signature mismatch. | Confirm whether that optional native feedback feature is used; choose a compatible model if required, otherwise keep a bounded diagnostic. No inference failure is established by this warning alone. **Recommended.** |
| 40 | Medium | Six model invocations have estimated usage and unverified runtime/template metadata; estimates can differ between context assembly and wire admission. | Keep estimated, reserved, interim and final provider usage separate; label the accounting stage and serialize/tokenize the actual request where supported. Never report estimated totals as measured throughput/cost. **Recommended.** |
| 41 | Medium | Export provenance says `semanticCoverageVerified=false`; source wire capture is incomplete, and recent app-log retention omits earlier context. | Retain bounded parse errors, invocation stage receipts and explicit omission counts; provide a diagnostic time-window capture option without leaking private payloads. **Recommended.** |

## Observations that should not be misreported as new failures

- One run is canceled; cancellation alone is not a crash or an empty-output success.
- The visible MCP connection succeeds in 276 ms. This capture does not establish the older local-port timeout problem.
- Voice records include seven recognition starts, seven ready events and six final events. A missing final in a bounded snapshot is not proof of a recognizer failure.
- Ten short service lifecycles align with app activity; they do not by themselves prove service churn caused a failure.
- Two clock-reordered records are reported, with zero clock discontinuities; use the provided clock reconciliation rather than infer a clock failure.
- Glass blur is active in some frame samples, but the log explicitly does not establish a graphics-driver cause.
- The two completed simple requests and retained fixture cleanup are real progress; neither proves broad integration health.

## Progress-writing contract and further enhancements

Use current action + specific observed target + its purpose. Emit a new summary when the work changes or a meaningful result arrives. Do not rotate synonyms to simulate progress, repeat the previous paragraph with a new count, or imply that a request to run tests means they passed.

Illustrative UI wording (not additional execution evidence):

- `Job 42. I’m reading the job output to locate the failing step and its error.`
- `Pull request #794. I’m reading review decisions to identify unresolved requirements.`
- `Checks: 37/68 complete; 4 failed; 27 blocked` — one updating line.

Further recommended improvements: compact structured “done / current / next / blocked” state, links to the exact failed job or file, a meaningful no-progress explanation, and a collapsed technical evidence drawer. Keep failed and blocked work visible; never manufacture a percent-complete estimate when the scope is unknown.

## Validation and remaining acceptance

- Canonical host validation: **301 tests passed**, plus policy, dependency, secret, regex, resource/icon and provenance checks.
- Focused Kotlin/JVM production-logic regressions: **32 passed** using temporary host adapters for Android/provider DTOs and unrelated dependencies. These validate the changed pure logic; they are not Android, provider, redaction or complete-run integration execution.
- Changed Kotlin checked/formatted with ktlint 1.3.1.
- Full Android unit-test command was attempted but could not start: the Gradle 9.8.0 distribution download failed with `Network is unreachable`. Local JDK is 17 and Android SDK 37 is not installed; CI must compile and run the Android tests.
- Added Android-suite reducer regression verifies one persisted counter entry. UI/device acceptance must verify 20-line folding at different font scales, accessibility, dark/light themes, ongoing count updates, prompt anchoring, keyboard resizing and process restoration.
- This branch is a draft implementation. No APK was installed, no physical-device performance improvement was measured, and no release or main merge is claimed.
