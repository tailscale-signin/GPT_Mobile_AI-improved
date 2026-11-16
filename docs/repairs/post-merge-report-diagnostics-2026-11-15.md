# Post-merge report diagnosis and corrections

## Scope

This work continues the requested repository, issue and attached-log review after PR #1203 was merged. Its base is main commit `e9610259b7238043de7aaad937a865c65976ff5b` (tree `60c5e1ced286fd4cfd9898e234cb3469f009c11a`). All follow-up changes belong on the already existing branch `fix/post-1203-diagnostics-2026-11-15` and [PR #1299](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/1299). The adopted branch advanced through `d2682f6c75539b04e10e4b02c5972641df1ea797`, `64f2aee10a05ae843d003c9bd446c4492490b8c0`, `210d378cba98c2cd9f2ba2a596204df2dfae94bb`, `56d7c91c27a12dcab107df09ed9eff178becebbc`, `7ec748f811f68e38ed521b648659d166576cc702`, `396ee7d3b9c67301b78ec9eba1e2d901a63c4a62`, and the release-metadata follow-up `a7cb9d2572893c7927d076d7a12b7682f8caaf86`. These changes are composed with the reviewed components and the concrete corrections identified during integration. Fresh expected-head checks preserve the concurrent updates before publication.

The original 173 issues have individual dispositions in the [verified resolution index](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/1203#issuecomment-6895129739). The [original final verification](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/1203#issuecomment-6894762390) qualifies that earlier source. It does not qualify changes described here. The [voice design and implementation](github-chat-motion-voice-2026-11-14.md#latest-voice-implementation) remains part of the inherited source.

The retained intake at 2026-11-15T14:35:03.751Z contains 36 reports: #1276–#1312, excluding PR #1299. Every report receives an individual disposition; later intake is reconciled before final completion. A reported build commit is useful provenance but does not independently attest the APK installed on a reporting device. Shape hashes describe argument names and types; they cannot identify a URL or establish equal argument values across reports.

## Findings and corrections

| Reports or retained log finding | Established evidence | Follow-up |
| --- | --- | --- |
| #1276, #1282, #1286, #1288 | Four distinct dispatched `read_url` receipts retain HTTP 404, `SOURCE_UNAVAILABLE` and nonretryable classification. The resource identities and historical causes are not supplied. | Correct a separate source defect: an exact URL already in the short missing-source cache must retain `SOURCE_UNAVAILABLE`. The cache refusal is a new undispatched call and must not fabricate a fresh HTTP status. |
| #1277–#1281, #1283–#1285, #1287, #1289–#1292 | Each refusal retains `REPEAT_LIMIT` and 3 prior + 0 provisional admissions = 3 used / 3 allowed. Admissions are not counts of HTTP requests or successful results. | Add bounded typed links to the earlier charged calls and their observed states. Keep provisional batch references distinct, preserve coverage omissions, and leave limits, counters and recovery behavior unchanged. |
| #1293 | One of seven acceptance criteria remains blocked: a protection endpoint returned 404 while policy observation is incomplete. Six of 32 calls were used. | Preserve a closed response-message category and recognized permission-header names from the actual policy response. Keep policy completeness and recovery semantics conservative. |
| #1294 | The supplied `conclusion` field is not declared in the matching `github_ci` runs schema. The call was rejected before dispatch. | Keep schema validation and bounded correction. Do not silently map unknown values to another argument. |
| #1295, #1296 | A range supplied both `end_line` and `max_lines`; the semantic validator rejected the conflict. The second report records an exhausted repeated-constraint correction allowance. | Express at most one limit in the full and selected caller contract, retaining the guidance lost by action projection. Preserve default reads, EOF and bounded range behavior. |
| #1297 | The specific argument rejection coexists with a 32/32 budget snapshot. Reporting promotes the snapshot to the headline and hides the original cause. | Preserve the specific call failure as primary evidence while retaining the concurrent budget state and hard stop. Add only finite, qualified CI semantic-rule evidence needed to identify the actual failed constraint. |
| #1298 | Seven capability tests remain blocked by retained fixture-approval prerequisites; no executable ready reads or admissible fixture creation are recorded. | Document the existing approval prerequisite. Unused calls do not authorize fixtures. Existing diagnostics retain the relevant state; no additional behavior change is established. |
| #1300 | A dispatched workflows read retains HTTP 200 and a successful remote receipt, then reaches the 24,576-byte result delivery allowance while calls remain 6/24. | Preserve the successful read and bounded withheld-result explanation. The existing diagnostic fields explain this boundary; neither a failed HTTP request nor a need for more calls is established. |
| #1301, #1302 | A stale catalog pagination refusal is explicitly named as the failed dependency of a later read. Current reporting loses the catalog reason and existing correction decision. | Add closed observations from the owned pagination guard. Keep the restart allowance, partial coverage and dependent-call refusal unchanged. |
| #1303 | A distinct tool request is rejected for one unknown schema field; supplied public field names are `limit` and `query`. The historical tool, complete schema and offending name remain unproved. | Preserve unknown-field and invalid-field counts separately. Include a rejected name only when it is already in the existing public vocabulary; private names remain omitted. |
| #1304, #1305 | Two distinct OpenRouter admission reports use a conservative 4,096-token capacity while prompt plus safety already exceeds 13,000 tokens. | Carry the admission-owned context decision, configured output and hashed profile/model observations into existing report fields. Keep conservative fallback provenance and the original allocation guard. |
| #1306, #1307 | The same reported run has two undispatched `github_search` schema rejections, with one out-of-range field and correction attempts one then two. The complete historical shape does not identify the field or value. | Add an optional closed list of actual rejected registered search field names, separate from the global public vocabulary. Preserve the validator, limits and repair allowance. |
| #1308, #1309 | Distinct provider invocations retain OpenRouter HTTP 429 with approximately 30-second retry delays and no remote acceptance. | Review existing cooldown and replay authority against each exact receipt. A reported rate limit alone does not establish a retry implementation defect or later recovery. |
| #1310 | A distinct acceptance report retains six of seven verified criteria, protection 404, rules observed and seven of 32 calls used. | Apply the same future bounded policy-response diagnostics while preserving this report’s independent identity and unknown historical cause. |
| #1311 | The retained original-code digest matches the source-defined bounded output-continuation stop at final synthesis. | Preserve the local stop, draft and receipts; no provider outage, exact continuation count, or whole-run nondispatch is established. |
| #1312 | A distinct acceptance checkpoint retains fixture-approval blockers and incomplete capability coverage despite unused calls. | Preserve its individual prerequisite disposition. Neither the #1298 attachment nor #1311’s cause is attributed to this run. |
| Optional News and Airbnb companion audit notices | Retained logs contain actionable dependency findings in pinned companion builds. | Qualify reproducible dependency overlays against publisher metadata, actual builds and tool contracts. Retain upstream source pins and avoid dependency-checksum workarounds. |

These findings distinguish demonstrated source defects from incomplete external observations. A future diagnostic improvement cannot establish the cause of an earlier report that lacks the new fields.

## Diagnostic contracts

### Missing-source cache

The known 404/405/410 cache path should use the existing `SOURCE_UNAVAILABLE` code. Its new receipt remains a precheck refusal with `dispatched=false`, `requestSent=false`, `retryable=false`, and no current `httpStatus`. A different URL must remain readable. The separate turn-local suppression set also emits `SOURCE_UNAVAILABLE`, with `TURN_SUPPRESSION` distinct from `NEGATIVE_CACHE`. That set can contain domain, redirect, media or HTTP refusals; it must never imply a historical 404. The finite availability marker intentionally reaches the model and the capability-gated report. Cache lifetime, capacity and network behavior remain unchanged. Three regressions cover actual HTTP then cache refusal, domain denial without a request, and unsupported binary content without a second request.

### Repeat history

An optional group links the original refusal to bounded earlier call-ID hashes in the existing hash domain. Prior outcomes describe observed execution state, including unknown or undispatched state; they do not assert HTTP success. Provisional batch admissions are separate and describe the state at the original guard.

The private history is bounded and owned by the actual originating run. It is recorded at charged admissions after dependency closure, rather than reconstructed from restored or compacted exchanges. Reused IDs, wrong-run data, missing history and evicted entries must reduce or suppress diagnostic coverage without changing execution. A fresh loop and empty recovery checkpoint do not establish that the persisted run is new. Enable the optional history only after the existing database observation succeeds and finds no prior tool event, the initial durable call count is explicitly zero, and recovery is empty. A prior precheck can have spent zero calls and still make the history incomplete. Missing or failed freshness observations suppress the optional fields; cancellation still propagates. Keep the original five repeat operands and all counters. No URL, host, argument value, repeat key, raw call ID or new target fingerprint is published.

The group uses a new reporting capability. Hash/outcome arrays and coverage are validated together against the existing guard operands; compaction drops the whole optional group when necessary. Raw receipt fields cannot recreate the trusted typed carrier. The five new history fields are report-only and excluded from model envelopes; the separate URL availability marker described above remains intentionally model-visible.

### GitHub policy responses

A new policy-response capability carries only closed 404-message categories and the names of at most four recognized metadata headers. Classification comes from a bounded actual HTTP response at the designated endpoint. It must not parse an exception message or copy the legacy failure object, which can contain private response or credential information.

An exact branch-not-protected message is an observation category, not permission to mark required-check policy complete. Header presence cannot establish granted permissions, denied access or an SSO failure. An observed empty header list is distinct from unavailable or legacy evidence. Raw header values, response prose, URLs and credentials remain excluded. Register the new values only in schema 3: the legacy recovery-field registry must stay unchanged. The composed HTTP-to-policy-to-approved-report regression covers an incomplete six-of-seven ledger and measured empty header arrays; it does not reconstruct the original rules observation.

### Argument failures and budgets

The failure that prevented the specific call must remain identifiable when the allowance is simultaneously exhausted. Reporting retains both facts without changing budgets, counter increments, finalization, retry or dispatch. Genuine budget-denied calls retain their budget classification. Range and CI rule diagnostics come from the actual validator and contain finite rule identifiers and known field names, never rejected values.

Unknown additional properties remain counted as unknown, not invalid. Their names enter `validation_field_names` only if they are already permitted public names. This corrects the earlier projection without expanding the global field vocabulary or changing admission.

### Search validation

The native search contract declares four bounded parameters that the global public-name list intentionally does not include: `fuzzy_threshold`, `context_lines`, `max_new_blobs` and `batch_size`. The reporting addition uses a separate optional `search_validation_field_names` list with one to four distinct names from exactly this set, preserving observed order. Only an actual validator-owned rejection for the native search tool can supply it. A forged receipt, foreign call or raw metadata field cannot recreate the observation.

`GITHUB_SEARCH_VALIDATION_EVIDENCE_V1` gates the values and omitted-field names. Unobserved evidence stays absent; there is no empty-list default, unavailable form, new constraint enum or inferred historical offender. Existing error class, counts and schema digest stay intact. No parameter values, search query, target or full argument reconstruction are added. This component remains subject to the same final source and execution qualification as the other additions.

### Catalog pagination

The actual pagination guard owns two optional report fields: `inventory_pagination_reason` (`MISSING_VERSION` or `VERSION_MISMATCH`) and Boolean `inventory_restart_offered`. The carrier is bound to the originating call and complete private arguments; normal persistence and report projection must retain that ownership. Raw receipt reconstruction, a different call or contradictory dispatch state cannot supply these fields. The new capability gates each value and omitted name independently.

The adopted catalog correction checks a missing or stale inventory version before rejecting a cursor that exceeds the current inventory, while keeping negative-cursor rejection first. A valid stale-version restart can therefore remain available after the inventory shrinks. Existing public `TOOL_UNAVAILABLE` classification and the model-visible reason code are preserved. The reporting fields remain excluded from model envelopes and do not change restart allowance or dependent-call admission.

### Context admission

An immutable observation now travels from the existing primary admission decision through the typed tool-evidence budget exception. The report can retain configured output, observed capacity, planning context, hashed profile and hashed model using existing public fields. Configured output is distinct from the zero allocation of a rejected request. Observation is accepted only when it matches the actual planning decision; unbound or mismatched data stays unavailable.

The conservative fallback remains `CONSERVATIVE_POLICY`. The historical reports do not prove a model-cache refresh failure, a provider context limit or which missing/stale route observation caused fallback. The change adds no model lookup, network refresh, retry, dispatch or capacity increase.

## Existing PR behavior retained

The adopted branch already provides bounded, validated `correction_call` proposals for the exact supported failed-runs filter, overwide CI log window, and redundant bounded-EOF range. The original invalid request stays rejected; no proposal is silently dispatched and unknown values are not guessed. The full and selected schemas now agree about mutually exclusive range limits and preserve positive, ordered CI line bounds.

The Diagnose CI preset honors its existing selected 48-call ceiling while still respecting the smaller profile or explicit request allowance. Claim extraction prefers 8,192 output tokens within the actual model context quarter, explicit output budget, prompt and safety allowance. The attachment retains four distinct length-limited 4,096-token extraction attempts; it does not prove that every extraction will finish at 8,192 tokens. Stage-specific failure reporting preserves the actual failing extraction stage and recovery identity.

The later adopted update fits GitHub continuation replay into the provider planning allowance, compacts retained tool results when needed, keeps the original current request, and prefers a bounded 6,144-token continuation output within the selected cap and context quarter. Final request admission remains authoritative. The reopen path preserves typed failures and authority exceptions. A rejected input or output ceiling must not be recorded as the full context: only a context-specific rejection with trusted capacity provenance can supply that fallback, while an admitted context receipt keeps priority.

The default workflows result projection retains workflow ID, name, path and state while keeping remote pagination and the explicit full-result view. First-page search correction may propose a schema-valid bounded `max_new_blobs` value; pinned continuations are not rewritten, and the original invalid call remains rejected. Neither change reconstructs the historical values or proves that the corresponding old task recovered. A single observed commit parent may be suggested as a review baseline, but selecting it still requires the user's explicit baseline choice.

## Voice interaction and pacing

The inherited voice room keeps a full transcript, themed per-response Read/Stop controls, automatic playback of each new assistant response and a typed composer. Speech remains owned by one session, with streaming/final deduplication and no automatic rereading of history. The pronunciation front end uses the pinned word lexicons for ordinary words such as here/Here/HERE; it preserves known initialisms and reports unsupported ordinary words instead of spelling them as letters.

The concurrently adopted voice changes add persistent speech-rate, pause and performance preferences and an explicit speak-while-generating switch. Existing saved rates remain intact; the new default is 1.25×. Quality/Balanced/Fast select bounded phrase and audio-prime policies; Natural/Short/Minimal control only near-digital quiet samples. A stable first phrase can start while synthesis continues, and terminal logs retain buffer restarts, longest refill, rate and selected mode. These are implemented controls, not measured latency or audio-quality improvements.

The partial-branch APK compiled, but its generated voice-session method exceeded the existing packaged-method size guard. Settings, transcript, conversation and audio-route presentation now use separate composable slots under a private router. All session state, effects, audio ownership and callbacks remain unchanged. The existing DEX threshold and device acceptance tests qualify the final build; the extraction is not itself a measured size or runtime result.

The later concurrent presentation adds a compact activity display and a direct captions toggle. The playback indicator uses a bounded measurement from written PCM, while microphone activity uses the existing capture level. The shaped bars are a level visualization, not a frequency-spectrum measurement or proof of physical audibility. Lifecycle collection, reduced-motion behavior, per-response playback controls and the smaller presentation slots remain. The captions control and callback wait for preference restoration before saving, and checked semantics expose its on/off state without overwriting other saved preferences during startup.

Kokoro remains on the qualified CPU path, with the existing bounded two-to-four-thread control and finite PCM queue. No unsupported GPU execution, automatic thread escalation, physical audibility or thermal result is claimed. Device measurements must compare time to first audio, underruns, cancellation, steady synthesis throughput and sustained power/temperature before changing that backend policy.

## Concurrent chat and report presentation

Manual reporting now separates editing from review: users inspect the prepared report before submission or saving it for later. Narrow portrait layouts use a scrollable sheet; wide or landscape layouts retain a dialog. The existing exact approved-payload and destination contracts still govern delivery. A new capture invalidates the previous review, and this presentation does not grant permission to attach additional private data.

Chat changes retain gesture-driven follow behavior, a bounded keyboard recovery after an observed window interruption, and selectable header-fade profiles. The recovery must respect an explicit release, Back dismissal and a hardware keyboard. The Gateway dashboard can display a validated, bounded tool-inventory observation separately from unavailable or denied observations; an inventory listing does not establish that the listed tools executed successfully.

## Concurrent engineering workflow integration

The later branch update also adds pinned repository exploration, admitted adaptive checks, typed execution-budget receipts and revision-fenced checkpoint inspection and restoration across the Gateway and Android/desktop clients. [The separate engineering design](autonomous-engineering-2026-11-15.md) records the exact boundaries. Search and graph observations do not grant edit authority; checkpoint restoration is limited to eligible unpublished local work and clears prior approval/validation. Existing-PR adoption retains explicit head/base identity and the single write owner.

Integration review found two additional concrete issues. Checkpoint, candidate and evidence reads now retain the epoch captured before dispatch, and bind/reset/publication share the same short ownership lock; a delayed result from one chat cannot replace another chat's UI. The same ownership fence covers restoration's local panel clear, while domain request/job authority and the existing IO dispatcher remain intact. The production evaluator now requires complete retained measurements and at least one observed dispatched model attempt before reporting a verified scenario; absent or truncated first-attempt evidence remains unmeasured. The desktop existing-PR form also handles unsupported or overlong decimal input through its existing validation message instead of raising an exception. These fixes do not execute the live scenarios.

Generated agent build facts now come from the actual Gradle and Room schema sources, with drift checks in both the existing PR validation step and fast local validation. The production-scenario tooling is retained, but its availability does not establish that the live scenarios or installed clients have been exercised. The added Gateway paths also activate the existing Windows installer qualification.

## Partial diagnostic export

The received archive is a valid but partial first part: 20 stage members, 709 JSONL records and 16 reconstructed chunk groups verify internally. Its reported 45% describes nine of twenty stages without omissions, not 45% of chat history. Workspace capture stopped 2,800 bytes below the existing 16 MiB headroom guard; eight later stages also deferred. This demonstrates measured heap pressure, not an out-of-memory crash or a leak cause.

Capture now records the already measured retry-admission headroom and threshold when the cooperative retry is skipped, with finite reason `HEADROOM_BELOW_REQUIRED`. It does not resample memory or invoke the source again. Configuration runs before ordinary history after the existing critical and low-memory-safe priorities. The caller stage list, durable stage-plan hash, identity, cursor resume, memory threshold and archive/row/record limits remain unchanged. The new regression verifies a real two-part resume against the old plan hash.

The archive already labels its app-wide report history as `APP_REPORT_HISTORY_NOT_CONVERSATION_ATTRIBUTION`. Those correlations cannot be treated as selected-chat execution records. The seventeen reader capsules match that history, but their run and call hashes do not match the retained selected-chat raw execution. Four later same-run acceptance observations do not identify the tool behind excluded receipts. Separately, the four CI/range capsules and retained claim-extraction attempts have exact selected-run joins. These scopes remain distinct in issue dispositions. A later bounded argument review matches the complete structural digest of each of the four CI/range event arguments to its approved capsule: it identifies the supported failed-runs filter combination, redundant EOF limits, and the overwide but ordered CI log range (`LOG_SPAN`). No private targets, raw job/run IDs or scalar line bounds are published.

## Companion dependencies and resource checks

Pinned Airbnb and Hacker News source commits remain unchanged. Checked-in manifest/lock overlays update the four reviewed dependency scopes to supported SDK and Zod releases, with publisher integrity and signature checks, clean installs with lifecycle scripts disabled, explicit builds and deterministic compiled-server fixtures. The preparation helper verifies source and overlay hashes before writes and propagates failed install/build results. Existing installations require preparation and restart to acquire the new builds.

Airbnb resource checks now pass the absolute URL expected by the existing robots parser. Allowed and blocked rules are verified through the actual compiled server. Existing unavailable-robots fallback and the explicit ignore flag are unchanged, so this is not a claim of universal fail-closed enforcement. Audits cover the four reviewed lock scopes, not every separately optional provider. Upstream deprecation notices remain distinct from actionable vulnerability findings.

## Qualification

Each component receives source review and focused behavior regressions before composition. Required controls include actual HTTP then cached refusal without another request; trusted repeat-history ownership, omissions and unchanged model output; policy HTTP producer through sanitized report; old-host exact approved-payload preservation; range defaults and conflicts; and specific semantic rejection at the last admitted budget slot versus a genuinely budget-denied call.

Run the canonical host validation and formatting checks on the combined source. Canonical Android compilation and unit execution, applicable workflow checks, emulator acceptance and required-check identity are qualified on the final published head and its current base. Component host results and earlier PR/main results remain scoped to their own source revisions. This checkout has no configured Android SDK; source-only review is never labeled Android execution.

Partial-branch CI exposed three distinct boundaries: a Kotlin compiler heap exhaustion under CodeQL on `210d`, an oversized packaged voice-session method on that same revision, and missing JSON type qualification in the newly added `56d` context-observation block. The later compile error stopped its jobs before they could revisit the earlier boundaries. Each remains tied to its actual job and source; only successful final execution can establish that it is resolved. The partial a7 CodeQL job also exhausted its 4 GiB compiler heap before analysis. Its named progress-deduplication source location is not proof of a logic defect; the later adopted equivalent single-pass selection removes the comparator pipeline. Final compiler execution must establish whether that and the retained voice extraction resolve the observed build boundaries. No memory limit, method threshold or required check is relaxed to obtain a pass.

The partial `a7` Gateway matrix also exposed an anchored-regex fixture failure on both supported Windows Python versions. Its setup wrote platform-dependent line endings into committed Git blobs. Explicit LF fixture bytes preserve the intended input, query and ten-match assertion. The exact old setup reproduces the failure with CRLF locally, while the corrected existing nine-method module passes locally. The subsequently adopted branch also adds a bounded alternative for an unescaped terminal regex anchor against CRLF source blobs, with a separate explicit CRLF fixture inside the existing regression. This limited expansion is not a claim of general regex normalization. Final Windows execution qualifies the combined native matrix.

If companion overlays are included, verify exact pinned source, publisher integrity, clean install with lifecycle scripts disabled, supported Node builds, actual stdio initialization/tool discovery, deterministic allowed/blocked resource fixtures and bridge compatibility. Live upstream availability is separate from fixture success.

Merge only after current-head required checks and repository rules pass. Reconcile each issue's current identity and body, publish its complete disposition with final evidence, then close it. Recheck actual main and the live issue frontier after merge. Final execution receipts belong in the PR so recording a completed run does not change the source it qualified.

---

# Post-1299 diagnostic reconciliation (November 15, 2026)

## Provenance and safety

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`. PR #1299 was merged into `main` at `4a1d790939ae1ab50bb582bbf8ce7c34000e1d1c` before this intake. PR #1349 is its single follow-up branch.
- Incoming archive: `diagnostics-7b550448-…-part-1-….zip`, captured on Android **0.9.66.7 build 141**, app commit `482eb81645e462f0690979d152cd02070ee4eda3`. This build is older than the merged main. The capture was completed but not fully comprehensive. The 20 retained issue-correlation records are not an exhaustive replay of all 25 GitHub issues.
- The archive contains configuration and profile records. Do not attach or paste these raw records into public GitHub issues. Preserve only allowlisted evidence, privacy-safe hashes, enum categories, measured counts, and revision-bound receipts. A missing field is unknown, not false.
- Audit window: 25 open issues, #1293–#1348 as retrieved on November 15 Toronto time. Not every issue in that numeric range is open. All listed observations are from reports, not reproductions on a newly installed/current app build.

## All 25 open issues: evidence, disposition and corrective action

| Issue(s) | Count | Observed failure | Resolution and required next evidence |
| --- | ---: | --- | --- |
| #1348, #1340 | 2 | OpenRouter HTTP **429**, with measured retry-after delays and no response content | Respect server delay and persist one resumable wait when a *proven* checkpoint exists. Keep `remoteAcceptance=NO` distinct from a successful retry. Capture a later scheduler outcome bound to the originating run; never invent recovery. These are provider responses, not proof of an app source regression. |
| #1347, #1344, #1310, #1293 | 4 | GitHub status required-check policy unavailable; branch protection endpoint returned **404** | Retain endpoint name, 404 classification and permission-header **names only**, ruleset coverage, connection capability and exact repo/head. Use active rulesets if authorized, but do not interpret an inaccessible endpoint as no required checks. Requires privileged policy observation or a separately verified substitute before `completion_verified`. |
| #1342, #1341, #1312, #1298 | 4 | GitHub full-catalog tasks ended without verified capability prerequisites or authorized test fixtures; some reports counted **7 approval** and **47 input** blockers | Keep read-only progress, request exact missing input/approval per operation family, resume under the same task identity after permission/fixture prerequisites are met. Do not repeatedly invoke forbidden operations or claim full-catalog coverage. |
| #1346, #1343, #1335, #1327, #1326, #1311 | 6 | Generic `CHAT_FAILED`: raw producer code unrecognized or absent, some lacking a reliable stage or exception | **PR #1349:** debounce now differentiates independently observed failure stage or privacy-safe exception fingerprint; regression tests cover both. For remaining code-less failures, the producer must attach a trusted failure stage, provenance, invocation and typed transport error before it can be classified. Do not infer cause from a nearby prior run. |
| #1345 | 1 | `GATEWAY_CAPABILITY_UNAVAILABLE` in Llama connection preflight | Re-evaluate model loaded state, gateway identity, negotiated capabilities and reachability on the installed Windows gateway; retain capability version, route state and source-build match as typed diagnostics when available. No evidence of restored gateway service in this archive. |
| #1336, #1323 | 2 | `REPOSITORY_GRAPH_UNAVAILABLE` during `status` read; one observed HTTP **500**, the other lacks an HTTP status | Merged #1299 already added a revision/authority-fenced single native read retry. Verify its exact-head behavior with permission-preserving tests and a new build. Retain physical dispatch state, observed HTTP status, allowed retry and native-tool receipt per attempt. |
| #1328 | 1 | Kokoro `PRONUNCIATION_UNAVAILABLE` during voice playback; recognized speech and measured first-audio latency are present | Merged #1299 already added safe pre-audio unknown-word fallback. Validate on a real device, including spoken words, captions, interruptions and fallback latency; a host test is not proof of audibility. |
| #1324 | 1 | `RECONCILIATION_REQUIRED` after tool/fixture blockers | Inspect saved operation receipts before any replay. Preserve attempted/accepted/unknown mutation counts, reconciliation identity, pending tool IDs and authority revision. Unapproved fixture creation is not a source patch. |
| #1321 | 1 | `GITHUB_TOOL_CONTEXT_BUDGET`; observed route 5,632 tokens, tool schema needed 2,280, available 1,673 | Allocate schema after verified physical route capacity and reserve; compact or narrow the admitted catalog without silently omitting required tools. Re-test on exact selected local model/context settings. |
| #1304, #1305 | 2 | `CONTEXT_CAPACITY_EXCEEDED`; observed conservative 4,096 capacity versus more than 10,400 prompt tokens | Use verified selected-route window and a bounded safe context reserve; summarize/prune histories rather than repeatedly submitting oversized requests. Merged #1299 changed the proportional local admission reserve, but this old build cannot verify it. |
| #1300 | 1 | `BUDGET_RESTRICTED` after a successfully dispatched GitHub CI workflow read (HTTP 200); result-bytes ceiling reached | Retain read receipt and page cursor, return a bounded projection and explicit resume path. Do not relabel a successful remote read as a network failure or silently raise accepted budgets. |

**Total: 25 open issue reports in 10 categories.** This document is a triage ledger; an old-build report, a merged patch, or a passing synthetic test alone is not grounds to close a confirmed runtime regression.

## Additional evidence visible in the uploaded archive

The 20 most recent retained issue-correlations also included `INVALID_ARGUMENTS` (2), `GITHUB_TRANSIENT_NETWORK` (2), `DNS_RESOLUTION_FAILED` (1), and `GITHUB_CIRCUIT_OPEN` (1). These observations are not automatically equivalent to additional currently open GitHub issues. Other retained correlations covered `PROVIDER_RATE_LIMITED` (5), `GITHUB_TASK_INCOMPLETE` (4), `CHAT_FAILED` (3), `REPOSITORY_GRAPH_UNAVAILABLE` (1), and `GATEWAY_CAPABILITY_UNAVAILABLE` (1). Investigate unknown arguments against the exact pinned tool schema; for DNS and the circuit breaker, record observed network/route health, cooldown source and replay safety instead of blindly retrying.

## Closure / verification contract

1. **Fixed:** tag the issue with the exact fixing PR/commit, test(s), and a subsequent matching installed-build or live-operation observation. No stale predecessor build may qualify the current head.
2. **Diagnostic-limited:** retain a privacy-safe source-owned missing-evidence list plus a tested capture improvement, explain what remains unobserved, provide repro instructions, and close using a transparent `not_planned`/follow-up disposition only if the tracked request is explicitly triaged rather than falsely described as repaired.
3. **External blocker:** record missing permissions, quota, installed runtime, approval or network route, plus next responsible action. Keep the issue open while the required action is pending, or close explicitly as external/duplicate if an accountable tracking issue retains it.
4. **Merge:** exact PR head must pass the canonical Required verification aggregate plus all applicable Android, emulator, gateway/Windows, Kotlin format, CodeQL and diagnostic checks. A draft, pending, skipped-required or failed check is not merge-ready.

## New regression scope in #1349

The in-memory issue-report deduplication previously keyed generic reports by public category and available run/invocation/call IDs but omitted the observed failed stage and already privacy-safe exception fingerprint. Two distinct failures during the three-second debounce could be collapsed. The patch adds these observed dimensions without changing the public report contract, leaving same-incident deduplication and source-owned causal identity rules intact. Tests assert distinct stage and type retention, same-key duplicate suppression and no private exception text in approved payloads.


---

## New intake reconciliation: November 16, 2026 UTC (PR #1349)

### Source boundaries

- New uploaded capture: `diagnostics-dca45322-…-part-1-….zip`; 21 archive members containing 12 agent-run records, 80 tool-receipt records, 20 retained incident-correlation records, and bounded app/timeline snapshots. The staged manifest says `captureCompleted=true`, `capturedFully=false`, `captureCompletionPercent=100`, and `externalPartsRequired=false`. Completion of the bounded capture is **not** full historical coverage.
- The app revision in the capture is **0.9.66.7 (build 141)**, `482eb81645e462f0690979d152cd02070ee4eda3`. All captured failures predate this branch and do not prove a regression in the new PR head.
- A subsequent issue query found **19 currently open issue reports**, numbered #1350, #1348, #1347, #1346, #1345, #1343, #1342, #1336, #1335, #1328, #1327, #1326, #1324, #1323, #1321, #1312, #1311, #1305 and #1300. The older 25-report ledger above is an earlier snapshot, **not** the current open-issue count. Do not reopen other issues based on an old report.
- Exclude personal profile fields, chat text, raw identifiers, request URLs, authorization headers, complete exception messages and opaque service responses from issue comments. The GitHub issue body and source-provided sanitized capsule are the public evidence boundary.

### Newly observed pagination report #1350

Issue [#1350](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1350) contains an observed `GITHUB_PAGINATION_INCOMPLETE` for `github_ci`, action `runs`, with `per_page=30`, `dispatched=true` and HTTP **200**. The old report contains argument names/types and call hashes but does **not** contain the traversal's page count, deduplicated record counts, prior page coverage, changing-inventory flag, or terminal reason. Thus no one can safely distinguish changed history, overlapping pages, omitted earlier pages or a cross-epoch refresh from that report alone. Treat HTTP success as a successful *remote read*, not verified *complete inventory*.

**Current-branch source fix:** `GitHubPaginationBoundary.completedPage` now invalidates the entire previous traversal (including stale first-page observations) on an explicit `verify_live=true` request even when the requested page is later than one. Previously that flag bypassed the page-end guard without resetting earlier page membership. A regression test checks that a fresh page 2 cannot inherit page 1 from an older epoch or be counted as a complete traversal.

**Current-branch reporting improvement:** when an actual GitHub CI pagination tool result has an `INCOMPLETE` or `STALLED` traversal receipt, `ToolReportDiagnostics` copies only validated local counts and booleans from `github.pagination-progress.v1`: requested page, number of observed pages, upstream reported total, unique seen records, duplicates on the page, first-page contiguity, and observed upstream inventory changes. The incident capsule accepts only these bounded fields and the closed traversal-state enum. It never copies raw identifiers, response text or live URLs. Tests cover the safely projected diagnostic fields and non-GitHub/mismatched-call suppression.

**Remaining verification:** These tests and the exact PR head must compile and pass CI. The Android app with the new build must reproduce a controlled changing-inventory or refresh case; prior issue #1350 must not be described as repaired in a deployed installation before that observation. The reporter enhancement provides the missing evidence for subsequent occurrences.

### Required disposition of the remaining open reports

The existing ledger above documents the independent causes and missing evidence. None of the old-build issues should be blanket-closed as `fixed` simply because a synthetic code test passes. Provider 429 and DNS retry windows require actual provider/network observations; GitHub policy 404 requires a permission-qualified policy read; full-catalog integration requires authorized fixtures and approvals; Llama model loading requires gateway reachability; Kokoro pronunciation requires physical-device playback; and acceptance/checkpoint failures require a current-head receipt. Publish final issue closure evidence only after a matching verified run, or close transparently as a duplicate/diagnostic-limited issue only when an accountable open tracking issue retains the work.

No other branch was rewritten in this intake. All patches remain on the single existing PR #1349 branch, and merge stays blocked until the exact head's required checks and policy visibility are satisfied.


## November 16, 2026 UTC — newer diagnostic archive and concurrent PR updates

This intake inspects the uploaded archive `diagnostics-e0007ab2-...-part-1-...zip`. The ZIP has 21 entries and contains the older Android build 0.9.66.7 (141), source `482eb81645e462f0690979d152cd02070ee4eda3`; it does not establish a failure or a deployed fix at this pull request's head. The captured GitHub task stopped as `PARTIAL`, with `GITHUB_TASK_INCOMPLETE`, `RECORDED_OPERATION_BLOCKERS`, and no verified completion criterion. Its selected run includes 33 tool receipts, 31 completed and two failed.

- **Workflow pagination / visibility.** A GitHub CI run-history request for page 2 received a successful HTTP response with a reported upstream total of 53. Its resulting model-visible observation was truncated; successful remote retrieval is **not** verified complete historical coverage. The existing pagination epoch/continuation protections remain in place. The native `list_workflow_runs` projection now keeps stable workflow fields but reduces nested actor and head-commit objects to a minimal typed identity, avoiding unnecessary duplication of profile and commit text in each row. The new regression preserves important run identifiers, status, continuation-sensitive output and artifact links without leaking nested profile information.
- **PR revision raced while reading files.** A read-only `github_pr context` operation observed a concurrent PR head change after three successful remote reads; no mutation was sent. A guarded one-time reread now applies **only** to an unpinned `verify_live=true` context request when its before/after file-list revision check fails. Reads with explicit expected head/base and all writes continue to reject revision drift. The successful refreshed result records `pr_context_refresh_reason=HEAD_CHANGED_DURING_FILES`. Unit regressions cover the bounded recovery and the strictly pinned refusal.
- **Next-report diagnostics.** `GITHUB_PR_HEAD_CHANGED` is a recognized public code. A persisted matching tool result may project only the fact of a detected revision race and the dispatch-owned request/response counts; it cannot project raw PR SHAs, actor profiles, provider text or inferred causality. Optional capability `GITHUB_PR_CONTEXT_RACE_EVIDENCE_V1` marks the additional reporter fields. The existing outbox must retain the approved report if the receiving service lacks the capability.

The live issue query found **26 open issue reports** at this intake; several new reports (including #1356 and #1357) are still tied to the older build. Review each as a distinct incident but correlate common root causes. External provider 429, GitHub policy permissions, approval/fixture limits, gateway availability and voice hardware still need fresh runtime verification. Nothing here authorizes a blind issue closure, a branch history rewrite or a merge of a draft PR. Closure as fixed requires the current source's successful checks and a matching deployed/live observation, while diagnostic-limited disposition must name the missing evidence and a route to recapture.


---

## November 16, 2026 — Gateway coding benchmark and refreshed issue frontier

### Evidence from the uploaded Gateway benchmark

- Benchmark ID: `bench_79ddfba0995845b1928d23e623c9b35d`, schema `gateway.benchmark.v1`.
- The **source commit in the benchmark equals the reviewed initial PR #1349 head**: `e85c2dce759a403172b3cc01995b38d751fc22b2`.
- Gateway 17.0.0, selected model `qwen3.5-9b`, coding/standard preset, seed 17, concurrency 2.
- The snapshot was still `RUNNING`: **12 recorded failures, 24 pending, 0 executed, 0 passes**, from 36 planned trials. The global score was `null`. All 12 completed grades showed `PATCH_PARSE_OR_ANCHOR_INVALID`, but the first three budget-family cases reached the exact **4,096-token output ceiling** with `finishReason=length`. This is not proof of 36 failures or a final zero model score.
- The reported families were pagination, idempotency, dependencies, and budget (three trials each). Reused gateway job IDs and token counts within families may correspond to identical seeded prompts; this cannot establish independent inference without per-submission digests.
- The benchmark uses isolated Python repository fixtures; it does **not** exercise the Android GitHub AI tool audit, real GitHub permissions, or protected-branch publication.
- The score remains unqualified when independent caller-input-preservation evidence is absent. Never relabel model-output correctness as full safety qualification.

### Source repair on the existing PR branch

1. `gateway/benchmarks/coding.py` now classifies safe JSON, blob, anchor, edit-representation and scope rejection codes instead of conflating distinct causes. Fixed allowlisted repair hints never contain raw generated patches or source exception text.
2. `gateway/benchmarks/runner.py` recognizes `finishReason=length` as `PATCH_OUTPUT_TRUNCATED` **before** applying a partial patch. It records output digest and byte count, provider finish reason and model job ID per attempt.
3. Retried local-model prompts use the original immutable fixture plus a short, typed validator result instead of replaying the truncated model answer, preserving more of the selected model's context.
4. The fixture prompt prioritizes compact exact anchors and concise regression vectors. New `gateway/tests/test_gateway_benchmark_patch_recovery.py` verifies failure classification, SHA/anchor distinction, scope enforcement, valid anchored edits and absence of sensitive exception text in exported feedback.
5. `gateway/manifest-v14.json` was refreshed to include current file SHA-256 values and the new discoverable gateway test.

These are **source fixes**. A successful current-head GitHub Actions run, a newly packaged Gateway and an actual rerun of the 36-case coding suite are still required before claiming that local `qwen3.5-9b` performance improved.

### Live GitHub issue frontier

The subsequent connected GitHub query found **31 currently open reports**. Five reports were newly present compared with the preceding 26-report snapshot:

| Report | Source-owned observation | Disposition / next verification |
| --- | --- | --- |
| #1358 | `INVALID_ARGUMENTS`, precheck `github_search`, field `max_new_blobs`, `OUT_OF_RANGE`; not dispatched | This is a correctly rejected model-provided argument, not remote GitHub failure. Inspect the model-visible tool schema and retry feedback on the current Android head; retain field name/class and bounded attempt count only. |
| #1359 | `GITHUB_TASK_INCOMPLETE`, one unmet criterion with `MATCHING_EVIDENCE_MISSING`, only 6 of 512 calls consumed | Investigate acceptance claim mapping and evidence attribution; no authorization to declare completed or to generate missing acceptance evidence. |
| #1360 | `CHAT_FAILED`, unrecognized producer code and no trustworthy failed stage | Diagnostic-limited: origin remains unknown. Require typed boundary, invocation identity, safe original-code fingerprint and sanitized exception type on a future reproduction. |
| #1361, #1362 | OpenRouter HTTP 429, explicit retry-after ~60s / ~120s, `remoteAcceptance=NO` | Provider-side throttling observed. Respect cooldown; restore only proven resumable work, never claim the provider has recovered without new receipts. |

The other 26 issues remain classified in the earlier ledger and require refreshed installed-build evidence or an explicit documented diagnostic-limited/duplicate disposition. Different reports from a single run can represent distinct layers of the same underlying problem; do not equate a fixed validator with a fixed network/provider failure.

### Privacy-safe recapture contract

For coding benchmark reports, retain only the case ID/family, selected-model route/identity scope, exact source revision, fixture digest, terminal finish reason, attempted output token/byte counts, output digest, closed patch-validator code, closed failure stage, repair count, and independent sandbox status. No raw code snippets, prompts, credentials, repository access tokens or paths should appear in public issue bodies.

For Android issues, source-owned call/dispatch identity and typed failure stages must be preserved when available. Missing evidence stays explicitly unknown. Device-only voice, branch-protection permissions, provider quotas and real gateway availability cannot be replaced by synthetic fixtures.

**Closure and merge gate:** Verify the exact final PR head, required Actions checks, and follow-up behavior on the installed build before closing a report as fixed. Diagnostic-limited reports may close only with a documented missing-evidence list, a tested reporter improvement and an accountable follow-up tracker. Do not merge a failing or draft head merely because the initial benchmark was incomplete.
