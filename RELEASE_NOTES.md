# GPT Mobile 0.9.66.7

Android build 141. Scoped reliability release covering the changes since [0.9.66.6](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/tag/v0.9.66.6). That previous release was published from `223b7c9f1dbf1ecaa77b65936ca112817cfb6a9b`.

This release brings together persistent gateway conversations and jobs, more reliable GitHub task recovery and acceptance evidence, application audits, safer diagnostics, and the November 7–9 integration repairs. The latest repair also addresses the Gateway conversations crash and the Android method-size and regression failures found while reviewing PR #815.

## November 14 chat, voice, and benchmark follow-up

Late reports #1129 and #1130 are included in the same repair. Generic chat errors now retain safe exception evidence at the actual Gemini, Groq, Ollama and LiteRT catch sites. Repository-emitted error values retain their observed boundary and owning run/provider even when no Throwable exists. Known typed failures and cancellation keep their existing semantics; message-only errors do not gain invented dispatch, HTTP or causal evidence. The two new capture sites require `CAPTURE_BOUNDARY_EVIDENCE_V1` from hosted reporters, while approved bytes stay saved and native authenticated GitHub delivery remains independent. The old incidents are not claimed reproduced. Capability self-tests retain legitimate fixture-approval, owned-resource and live-evidence prerequisites and do not equate full catalog scope with exhaustive pagination.

Ollama retains bounded recovery for typed transport errors only when the producer explicitly permits retry and request replay before any output. Unknown remote acceptance does not erase that explicit authority; absent or contradictory permission, reconciliation, unknown outcomes, provider limits and typed nontransport rejections remain terminal with their original details. A typed failure also owns recovery classification and route selection if the stream subsequently throws. The existing selected-route constraint, attempt timeout, two-minute recovery deadline and backoff are unchanged.

For #1114, stable captions and observed-range following are repaired, while exact spoken-word synchronization still requires matching engine timing. Kokoro supplies no range callback, so that route cannot provide exact-word highlighting. Bounded engine, phase and caption-alignment observations support diagnosis of recurrence; no physical audio alignment is claimed.

Gateway 17 adds native Chat and Saved destinations, searchable message bookmarks, favorites and archive controls in the app's Cyan theme. The benchmark workspace keeps durable history, graphical breakdowns, paired serial/concurrent request measurements and bounded isolated coding trials. Advanced controls remain behind Settings.

Gateway 17 coding scores cover disposable local repository fixtures and declared grading criteria; seeded repeats are not distinct coding capabilities, HTTP concurrency does not verify native inference batching, and Windows layout, sandbox execution, physical-device behavior and reporter rollout require their own observed qualification.

Benchmark restart recovery now clears provisional scores and resolution flags on unfinished cases while preserving completed attempts. An interrupted parallel comparison signals admitted observations and stops queued trials before further dispatch; unknown cancellation outcomes remain unqualified with their original receipts.

Coding benchmarks retain exact sandbox ownership before launch and across restart. Unconfirmed cleanup blocks further execution, installer maintenance and removal of the owning result until bounded cleanup confirms settlement; candidate execution is never replayed during recovery. Malformed model-supplied evidence loses only its evidence points and cannot discard independently measured results.

Voice integration review fixes stale speech during regeneration, retains intentional historical playback, and prevents late admission or screen recreation from re-offering an accepted voice prompt. Transcript controls now inherit the theme content color and have required dark, light and 200% text render coverage.

Repository maps now use revision-bound result pages and backend symbol search, retain larger source files and configuration metadata, improve supported cross-file relationships, and reuse authorized unchanged fragments. Saved indexing continues when its screen closes, with explicit generation-bound cancellation, failure retries, and truthful partial-coverage counts. Stage-specific graph context has its own bounded allocation and records actual model admission. See the [repository-map repair](docs/repairs/repository-map-reliability-2026-11-14.md).

The repository-map follow-up requires matching app and gateway deployment. Structural parsing still has partial and unresolved relationships; model-answer accuracy, token savings, and affected-device behavior remain unmeasured. Hosted submissions of new graph and policy diagnostic fields require a reporter advertising GITHUB_GRAPH_POLICY_EVIDENCE_V1; older services leave the approved report saved until the selected service is compatible. Native authenticated GitHub delivery uses local validation and does not require that hosted deployment.

The independent integration review preserves the distinct never-indexed map state even when its status includes revision metadata. Hosted reports containing the new budget, validation, source-revision or repository-access failure codes require ISSUE_RECOVERY_CODES_V1; protocol failures require COMPLETION_EVIDENCE_V1 at every supported nested location. An older hosted service leaves the approved bytes available for retry after support is updated. Native authenticated GitHub report delivery remains independent of hosted readiness.

Voice pronunciation and latency changes require physical-device qualification. Unknown ordinary words retain captions with an explicit synthesis failure while no offline grapheme-to-phoneme front end is qualified. CPU remains the verified provider; GPU acceleration and higher thread defaults are not established. Hosted submissions of new GitHub prerequisite fields require a reporter advertising GITHUB_PREREQUISITE_EVIDENCE_V1. Native authenticated GitHub reporting validates and renders the approved payload locally without that hosted dependency.

Voice rooms now keep their full transcript and typed composer inside the same voice session. Each assistant response has a themed Read/Stop control; new typed and spoken replies read automatically in sequence. Historical messages, duplicate terminal updates and stopped replies do not restart narration. Auto read off keeps captions and turn completion working, and explicit playback does not require microphone permission.

Kokoro pronunciation normalizes dictionary/text forms and word-internal formatting. Known ordinary words remain words; only explicit initialisms spell their letters. Unsupported vocabulary reports a clear synthesis failure with retained captions instead of silently spelling or omitting it. Bounded PCM batches overlap generation and playback on the supported CPU provider. Device sound, latency, heat and GPU execution still require measurement. GitHub prerequisite reports add negotiated graph/workflow observations through `GITHUB_PREREQUISITE_EVIDENCE_V1`.

The supplemental diagnostic repair gives reasoning-only GitHub review preflights one retry only when a larger output allowance fits the selected model's actual context. It publishes terminal review failures once, retains the producing invocation's observed request identity and transport evidence, and follows exact-revision workflow continuations without repeatedly probing skipped runs. Repository-graph preparation uses the existing bounded index/checkpoint path and reports the precise inputs still needed for unsupported probes.

Diagnostic exports retain memory and numeric source-row evidence at the actual deferral, require the original stage's headroom before retrying, and resume with smaller source pages without reducing record limits. Protected rows share one bounded projection. Opted-in frame reports record layout constraints and repeated measurement context. The desktop gateway can select, validate and retain its approved repository validation policy across both launcher stages and restarts.

The supplemental November 14 archive repair requires an updated app and desktop gateway where applicable. Physical-device heap/frame improvements, microphone ownership, private-provider behavior and host policy/runtime readiness still require their own live evidence; diagnostic follow-ups do not establish the historical cause of an unreproduced report.

GitHub motion and voice design follow-up: retain the 3%-of-window header fade and its expanded/collapsed geometry; let deliberate downward input at the bottom reattach chat following even when no more pixels can scroll. A one-line arrival takes 1.5 seconds, while a multiline block takes one second per rendered line. Voice chat gains a themed orbital stage, stable controls and captions, and reliable following of observed speech ranges without font reflow. Gateway conversations include eligible chats before their first successful submission and show their chat mode inside Gateway-tab bubbles. Generic terminal chat reports retain bounded application stack frames and exception type through the existing approved report fields. See the [current repair notes](docs/repairs/github-chat-motion-voice-2026-11-14.md).

GitHub context follow-up: retain the full authorized GitHub inventory for on-demand discovery but admit only the task-sized tool schemas needed for each request, including small local contexts. Prevent initial planning and later tool rounds from reintroducing the full catalog. Regression tests cover the observed 5,632-token route and optional-schema deferral. Exact provider tokenization and physical-device behavior remain to be verified.

Keep the composer focused during background run changes and report bounded keyboard transitions, invalid tool frames, result-budget exhaustion and reasoning-only completion attempts.

The new keyboard and completion diagnostics require a hosted reporter advertising COMPOSER_IME_EVIDENCE_V1 and COMPLETION_EVIDENCE_V1. Confirm keyboard behavior on the affected Android device and empty model responses on the original route.

Preserve typed GitHub schema admission and its token budget, retry transient voice audio handoffs once, and add per-pack, capture, playback and caption evidence to approved reports.

Voice capture and playback report fields require a hosted reporter advertising VOICE_CAPTURE_PLAYBACK_EVIDENCE_V1. Exact caption highlighting requires speech-engine timing for the displayed utterance. The affected phone and private gateway validation policy require live verification.

Increase the GitHub header fade, retain preview activation order, reattach chat follow at the user-reached bottom, and pace new rendered GitHub text using the single-line/multiline timing above. Refresh voice presentation with theme-aware captions and observed playback-start feedback. Preserve schema constraints in benchmark requests, select reasoning modes from frozen cases, and distinguish request dispatch, admission and model execution. The [earlier repair audit](docs/repairs/chat-voice-and-report-audit-2026-11-14.md) accounts for the complete supplied diagnostic and benchmark exports and records the preceding timing policy.

Move turn-presentation scans out of the large chat composable and enforce the Android compiler's method-size boundary against actual packaged chat and voice methods. Retain a small APK-bound measurement report in CI. Restore redacted benchmark history without recovering credentials or substituting an endpoint, and keep malformed-backup errors free of private payloads.

For reports #1090 and #1091, retain capacity provenance and numeric input components before admission rejects, preserve the resolved output allowance, and show guidance based on remaining context headroom. Preserve explicit conversation rejection evidence and add bounded pending-tool state, identity hash and handoff sequence without granting ownership or replay authority.

For report #1092, preserve the retained capability scope and pagination mode so an incomplete task report distinguishes full catalog requirements from observed capabilities and complete inventory requests from bounded sampling. Keep those two closed values through bounded criterion-sample trimming, without changing approvals or completion requirements.

The November 14 chat and voice follow-up requires physical-device gesture, layout, audio and frame-time acceptance. Preparing-voice reports require a hosted reporter advertising VOICE_PLAYBACK_PHASE_V1. Private gateway/model availability and historical incidents with missing receipts remain dependent on live evidence; host and CI checks do not establish those outcomes.

The November 14 capacity/conflict follow-up requires matching gateway and hosted reporter capabilities CAPACITY_ADMISSION_EVIDENCE_V1 and CONVERSATION_CONFLICT_EVIDENCE_V1. The original private requests and affected-device performance are not qualified by diagnostic or host regressions.

Acceptance-scope reports require a hosted reporter advertising ACCEPTANCE_SCOPE_EVIDENCE_V1. Older approved reports remain unchanged and cannot reveal scope that was never captured; the historical task is not established as completed by the diagnostic enhancement.

For reports #1093–#1096, allow voice downloads over internet-capable VPNs, reconcile real scheduler state and bind progress/cancellation to each install attempt. Add a report action with bounded scheduler and transfer observations. Keep the expanded GitHub header inset live through retained navigation and share the anchored generation indicator across chat modes. Add eight GitHub audit categories with separate local/live scores, sixteen production contracts and explicitly enabled bounded read-only probes.

Voice-install evidence requires a hosted reporter advertising VOICE_INSTALL_EVIDENCE_V1. The GitHub audit separates local contracts from explicitly enabled bounded live reads; write/approval cleanup, protected merge/workflow execution, artifact installation/device checks and interruption recovery remain untested without their own execution evidence. Historical device download causality and physical gesture, audio and frame-time outcomes are not established by host or emulator tests.

## November 13 interface, voice, and audit updates

Improve GitHub chat scrolling and progress, Gateway conversation navigation, background voice plugin installation, and bounded app audit modes. Add safe observations for URL-reader DNS cooldowns, saved-view recovery, and fixture approval.

The November 13 interface and voice changes still require physical-device gesture, audio, and background installation checks. Measured voice latency improvements, the full stress workload suite, and Macrobenchmark evidence import remain pending. New diagnostic fields require a hosted reporter advertising RECOVERY_OBSERVATIONS_V1.

## November 13 gateway session repairs

Keep logical gateway operation identities stable and retain bounded model preparation, gateway capture retrieval, MCP rejection, tool provenance, GitHub verdict provenance, and frame timing evidence. Defer eligible pre-dispatch provider connectivity failures with bounded backoff.

The November 13 gateway session repairs still require paired phone and gateway validation. Automatic connectivity recovery covers eligible initial runs; broader GitHub fixtures, durable gateway supplements, and device performance qualification remain pending.

## November 13 issue follow-up

The [issue repair register](docs/repairs/issue-diagnostics-2026-11-13.md) covers #891–#927. Review presets no longer interpret `head/base revisions` as a Git ref. Tool continuation history preserves the provider's original call while executing normalized arguments, including after checkpoint reload. Issue queries accept the shared presentation option. Reports add bounded readable tool failure details, provider wait/checkpoint observations, chat correlation and separate acceptance counts.

Extended incident fields require a hosted reporter advertising INCIDENT_FAILURE_DETAILS_V1. Diagnostic-only issue closures do not establish that the original incident is fixed; Android, private gateway and hosted rollout validation remain outstanding.

## PR 881 open-issue follow-up

The [PR 881 repair register](docs/repairs/pr881-open-issues-2026-11-12.md) covers repository issues #884–#890. Exact sleeping gateway models use a non-generation wake request with durable uncertainty handling. Reports retain safe preparation causes and backend status. GitHub tool/issue reviews no longer trigger an unrelated branch-comparison requirement; explicit commit, tag and branch baselines resolve to immutable revisions. Integration checks continue through already-observed workflow runs when a successful jobs query contains no usable jobs, and exact-revision failure queries preserve truthful empty results.

New gateway preparation diagnostics require a hosted reporter advertising GATEWAY_MODEL_PREPARATION_V1; new review error codes require REVIEW_COMPARISON_V1. An older reporter leaves the approved report saved with an update-required status before submission. Native GitHub submission retains its separately selected account and approval. Private gateway configuration, installed-device behavior and full hosted Android validation remain separate qualification steps.

## Diagnostic capture 62a4cb28 follow-up

The [diagnostic repair register](docs/repairs/diagnostic-62a4cb28-pr881.md) records explicit engineering scope, preserved historical request identity, early setup receipts, correlated preparation operations, versioned report evidence, accepted status criteria, target-repository isolation, exact-revision pagination, and the complete integration prerequisite ledger. The durable queue collector is split into serial stages while retaining its ownership and permission checks. The new admission report context uses version 6 and gateway diagnostic snapshot version 2; the matching hosted service is required before new extended reports can be sent.

Diagnostic capture 62a4cb28 repairs require matching gateway and hosted reporting rollout, complete hosted Android validation and separate private-service/device qualification; host regressions and source inventory do not establish those outcomes or promote Unified Reliability v2 acceptance.

## November 12 candidate repairs

These unreleased repairs preserve the selected gateway request contract and the original pending tool batch through local result reads, images, compaction and app restart. Completed calls retain their receipts without new execution events. Conflict and selection failures carry bounded recovery details, and explicit continuation prohibitions remain effective after a new run starts.

GitHub reads can recover from a proven path or retained search recipe, and eligible reads wait through one bounded circuit cooldown. Reviews require a valid comparison baseline, preserve typed source links and immutable read coverage, and retain observed capability rows without duplicating the unused integration catalog. Diagnostic exports retain bounded failure summaries, critical-section counts and separate logging availability. Voice cooldown re-entry does not create an incident without a new recognition attempt.

Native tool progress now updates the observed steps, failure count and next action after gateway preflight telemetry. Repeated privacy catalog notices are grouped by their actual denial reason and reported tool-entry count; all privacy filtering and per-tool diagnostics remain in place.

The issue-report dialog now retains an approved report for direct native submission, visible delivery status and retry with the same report ID. Reports include bounded failure context and explicit missing observations, with hashed identities that can be matched to future diagnostic captures even when app logging is off. Review and debug APK workflows receive the reporting capability configuration. Hosted reports require the updated reporter; when native GitHub delivery is enabled, an explicitly approved verified account provides an alternative. Missing or incompatible destination configuration leaves the approved report available in the app with an explicit status. Send no longer opens a browser.

Combined Mode also rejects demonstrated participant reversals that previously passed lexical matching, including passive wording and disputed-claim declarations. The guard preserves valid comparisons within independent answer clauses and leaves ambiguous relationships unresolved. It does not qualify the unfinished general semantic-provenance pipeline.

These fixes require matching Android and gateway deployment. Passing host or emulator regressions does not identify the cause of older generic incident reports or qualify physical-device behavior. Unified Reliability v2 is not complete; the 99 outstanding acceptance cases and device/release evidence remain outstanding, and no acceptance results are promoted by these changes.

The [findings and remedies](docs/repairs/github-job-recovery-2026-11-12.md) describe the code changes, reporting rollout and remaining evidence requirements.

Hosted issue-report delivery requires a real project-owned HTTPS reporter advertising the supported context schema and ISSUE_REPORT_ORIGIN in the APK; enabled native GitHub delivery requires a verified account and explicit approval of the report and route. Host regressions do not qualify live delivery, and no live service, repository variable or credential was changed by the November 12 session repair.

## November 12 session reliability and reporting addendum

These candidate repairs cover the supplied reporting, app-audit, GitHub, voice and diagnostic findings. The version remains 0.9.66.7/build 141. The [session repair register](docs/repairs/session-reliability-reporting-2026-11-12.md) separates each implemented change, observed validation and remaining external acceptance.

- Reporting shows the destination and verified GitHub account before approval, offers connection setup or saving for later, and preserves the original report ID and exact approved contents. A durable marker precedes native issue creation; uncertain delivery triggers read-only exact reconciliation, and an absent search result never authorizes another creation. Distributable APKs require an enabled reporting capability, while local unconfigured development needs an explicit opt-out.
- GitHub review accounts for complete serialized inputs, bounded batches and repair limits. Saved candidates and explicit user-resume conditions survive private-review failure. Workflow pagination, repeated-read protection, scoped status schemas, current-task budgets and owned integration preparation retain their accepted authority and evidence limits.
- Voice capture records typed initialization/read failures and allows at most one transient startup recreation before any PCM is captured. Recorder ownership stays attempt-specific. Terminal whitespace trimming no longer interrupts valid queued speech, and model completion permits playback to drain; first-audio, cancellation and drain observations support later device diagnosis.
- MCP failures retain available phase, retryability, dispatch and configuration evidence with exact operation correlation. Selected-model readiness is bound to the observed configuration/runtime. Audit presentation separates measured results, execution coverage, disabled features, optional absence and remaining untested work.
- Invocation-owned usage, typed capacity failures and exact-task review checkpoints preserve causal diagnostics without borrowing successful primary-generation provenance. Observed head revisions remain distinct from explicitly tested SHAs. Transcript export preparation runs off the UI thread; no measured frame, memory or device-performance improvement is claimed.

Unified Reliability v2 is not complete; no acceptance results are promoted by this addendum. Current source and regression evidence does not qualify an installed APK, live account/service, selected model or physical device.

### Supplemental cooldown and diagnostic repairs

The [open issue verification register](docs/repairs/open-issues-verification-2026-11-12.md) records the supplied fifteen diagnostic findings and the reports that arrived during validation. These changes retain the existing release version and qualification limits.

- Provider cooldown recovery claims the current durable owner, restores original completed-result identity, reconciles bounded replay changes and continues the accepted research generation. Missing or contradictory evidence and unknown tool outcomes remain explicit blockers. Retry attaches to an existing wait before creating another action, and the first run start remains immutable.
- Gateway result readers share one response-anchored Retry-After deadline and bounded result/immutable-command caches. Internal reconnects, concurrent screens and refreshes respect the same deadline. Explicit Gateway Send prepares the exact selected model through the durable queue and rechecks readiness before generation; passive browsing remains observation only.
- Transcript receipt projection runs outside composition and reuses a bounded incremental cache. Unchanged message updates preserve existing row identities. Integration reports retain accepted budget provenance and counters, derive repository prerequisites from the action schema and available lifecycle receipts, and distinguish supported claims from unmeasured scope and unfinished tasks.
- Recovery, extraction and tool-precheck reports preserve closed typed causes instead of generic tool failures. Schema 4 separates the original provider interruption from the current recovery failure and retains bounded hashed checkpoint/ownership correlation. Previously approved payloads and uncertain-send reconciliation retain their original bytes and hashes.
- Claim extraction stops unknown or unfinished provider invocations without treating them as four schema-format repairs. Completed tool receipts and completed extraction batches remain available, and unknown remote outcomes never authorize write replay.
- Anthropic failures retain actual HTTP status and typed current-attempt evidence through stream assembly and delivery; unknown outcomes prohibit credential rotation and replay. Voice-mode reply failures retain run correlation and structured context without inventing a microphone or playback cause.
- Spoken replies retain readable link labels while omitting raw destinations, citation syntax and code payloads. The full original answer remains in captions. Both system and local output can begin at a stable first clause or four complete words; device latency still requires measurement.

The supplemental repairs require exact-head Android regression results and an updated hosted reporter advertising context schema 5. Physical frame/allocation measurements, ASUS microphone behavior and the user's private gateway/model remain unqualified by host fixtures. Reports without a proven historical cause may be closed under the requested diagnostic-follow-up policy after specific capture is implemented and tested; this does not establish that the original failure was reproduced or fixed.

## PR #816 candidate addendum: Gateway 16 and engineering completion

These are unreleased branch changes on top of the versioned notes below. This addendum does not publish, install or qualify a release; it keeps the bounded release scope consistent with the combined PR.

- Upgrade Gateway runtime and installer contracts to 16.0.0; align Kotlin/Python diagnostics, preserve typed readiness and terminal causes, correlate requests and retries, retain frame measurements and export linked support evidence.
- Complete PR #816 admission and task recovery: require producible acceptance, validation policy and exact source/runtime preflight before saving implementation work; retain unmet requirements, bounded continuation and honest task ownership.
- Expose guarded native PR draft/ready transitions and collaboration actions with exact approval, expected-head or update-time checks and remote readback; identify same-model review accurately.
- Unify GitHub questions, choices and approvals at the conversation bottom; add 1.5-second relative bottom following and gateway conversation activity icons with lifecycle and reduced-motion handling.
- Include PR #816 audit, repository graph and selected-profile recovery repairs and the local Moonshine voice plugin with microphone-permission rechecks and retained model/runtime provenance.

Validation: [Gateway 16 engineering contract](docs/repairs/gateway16-engineering-contract.md), [chat and recovery](docs/repairs/gateway16-chat-2026-11-09.md), [session diagnostics](docs/repairs/gateway-session-2026-11-09.md), and [local voice](docs/implementation/Local_Voice_Plugin_2026-11-09.md). Host checks passed at their recorded source hashes; prior CI or a cancelled run does not qualify the final combined head.

- Gateway 16 requires matching Android and gateway deployment. Its acceptance preflight requires approved host policy, exact source mirror, digest-pinned local Docker image, allowed CI checks and mapped fresh behavior tests; arbitrary objectives without suitable explicit requirements or a full-objective template remain blocked before execution.
- PR #816 host fixtures validate contract and recovery behavior, not live GitHub collaboration mutations, private model inference, isolated Docker execution, physical-device scrolling or installed Moonshine recognition. Final Android, emulator, Windows installer and exact-revision release checks remain required.
- Gateway 16 support exports preserve newly recorded evidence and cannot reconstruct omitted causes or repair an unidentified service dependency from an older diagnostic archive.

## Gateway conversations, jobs and model readiness

- Add model-specific online-first gateway profiles, a gateway-wide Jobs tab, and **Gateway** between Chats and Favorites to browse conversations stored on the configured server. Normal GitHub Send can use durable gateway ownership; saved commands retain their original profile, model, repository and conversation bindings.
- Preserve accepted queued work, resume observation of the same job after reconnection, and distinguish observation timeouts or Android lifecycle interruption from explicit Stop. Reconcile uncertain commands, finalization and cancellation without replaying completed work. Large transcripts use verified bounded parts, and Room schema 51 preserves identities from both concurrent schema-50 layouts.
- Keep Gateway home and saved chats accessible offline, with an eligible profile/model picker and durable, globally serialized dispatch. Bind cached history and Stop to the original installation/account; changed credentials invalidate cached authority, and portable backups cannot transfer it. Shared availability monitoring distinguishes stale observations from permission to send.
- Accept legacy mixed-type gateway capability responses through explicit Boolean negotiation, retain safe schema failures, and separate gateway connectivity, selected-model availability, reasoning support and engineering prerequisites. Strict reasoning-OFF policy remains enforced at the final dispatch boundary.
- Preserve typed saved-work admission failures and exact source/identity checks. Passive readiness does not load a model; explicit model preparation remains a separate bounded host command. Successful history reads do not establish generation or engineering readiness.
- Retain actionable backend failures, exact-model observations, physical HTTP identities and arrival/completion timing. Bound metadata recovery, unavailable-state polling, diagnostic spooling and export; preserve selected execution identity through client-tool handoffs and restart recovery.
- Require response-bound gateway evidence for answer colour and badges, clear stale attribution between responses, and confirm recognized gateway capabilities even when diagnostic controls are disabled.

Implementation and qualification: [persistent jobs](docs/architecture/gateway-persistent-jobs.md), [gateway continuity](docs/repairs/gateway-continuity-2026-11-07.md), [gateway readiness and diagnostics](docs/repairs/gateway-readiness-diagnostics-2026-11-08.md), [account authority and offline history](docs/repairs/gateway-recovery-authority-2026-11-08.md), [saved-work admission](docs/repairs/gateway-admission-diagnostics-2026-11-08.md), and [diagnostic integration follow-up](docs/repairs/diagnostic-integration-followup-2026-11-08.md).

## GitHub execution, recovery and verification

- Retain task identity, structured operation receipts, spent-work counters and acceptance requirements across retries. The checklist uses complete saved evidence before consuming display fragments, keeps a bounded recovery allowance outside evictable replay, and reserves work for verification and cleanup.
- Present every authorized GitHub schema, including official MCP tools, on initial execution and through continuation or repair pauses; report a preparation blocker when the selected model cannot hold the complete catalog. Bind execution to those schemas, preserve permission revocation, captured pagination continuations, explicit branch targets, source-cache bypass for forced live reads, and exact owned-fixture identities and cleanup evidence.
- Add repository creation, fork, bounded snapshot import, gateway history import and clone with explicit one-time lifecycle approval and operation-specific proof. Keep upstream reads separate from the selected write destination; dependent engineering requires selecting the verified destination. Durable transfer identities prevent a lost acknowledgment from creating a second repository.
- Save completed tool boundaries and pending approvals before generation, including empty drafts, so interrupted work can resume with its original profile, model, repository and receipts. Permit one bounded logical continuation after usable connectivity returns; accepted wire requests, unknown mutations and approval waits retain their separate replay and authorization rules. Native Refresh access and Change repository preserve staged work and credential-scoped failure evidence.
- Add independent acceptance criteria and measured integration reports, durable stage-and-commit, bounded inline CI watching, flexible line reads, and an interactive repository graph based on the selected gateway's observed index. Incomplete, unavailable, stale and truncated evidence remains visible.
- Keep missing executable tools, exhausted allowances, provider failures and unfinished acceptance as distinct outcomes. Integration requests without an executable catalog stop before a model request; completing the final allowed operation can still satisfy the task. Retained evidence reports preserve provider-denial and cooldown metadata, while uncertain writes still require reconciliation.
- Export all acceptance criteria in bounded digest-checked pages with named blockers and next actions. Resume oversized diagnostic exports with durable cursors and complete logical records, prioritize current verification, and reduce repeated history snapshots.
- Harden server-cooldown/TLS retries, durable retry timing, cancellation-safe batch receipts, schema-bound MCP readiness and event replay. Stop deterministic protocol retries, preserve exact client-tool frames, and prevent diagnostic questions or optional progress narration from displacing the accepted repository task.
- Show retained public answers and explicitly public progress in the main conversation, keep the previous response until replacement text arrives, and label unfinished progress without treating it as newly completed work.

Implementation and qualification: [PR #813 integration](docs/repairs/pr813-integration-2026-11-07.md), [diagnostic remediation](docs/repairs/diagnostic-remediation-8597579-2026-11-07.md), [diagnostic follow-through](docs/repairs/diagnostic-follow-through-c1466ca0-2026-11-07.md), [full tool preparation and repository transfer](docs/repairs/github-tool-preparation-gateway-diagnostics-2026-11-08.md), [interrupted work and approval recovery](docs/repairs/interrupted-work-readiness-2026-11-08.md), and [reliability recovery](docs/repairs/reliability-fault-recovery-2026-11-06.md).

## App audit and qualification evidence

- Add durable App audit checks in the Performance lab, with plugin/source inventory, selected debugging exports and measured pass scores displayed beside planned verification and actual outcome counts. Local checks can run without selecting an AI profile.
- Bind observations to their actual time and configuration revision, retain original failure age and cache provenance, and support bounded fresh checks for eligible repaired MCP discovery failures. Recovery controls open the exact saved connection; confirmed credential, protocol and configuration failures remain separate.
- Link execution worklists to their configuration, credential and discovery prerequisites. Add explicit selected-model generation, rendering/accessibility, persistence, source-area and device-measurement qualification entries; preparing a benchmark or worklist does not execute it or award measured coverage.
- Preserve disabled or absent optional integrations and untested external suites. Successful metadata or configuration checks do not establish live tool execution, private-service availability, full line coverage or physical-device performance.

Implementation and qualification: [App audit](docs/benchmark/app-audit.md), [MCP audit remediation](docs/repairs/app-audit-mcp-remediation-2026-11-07.md), and [November 9 audit and diagnostic ledger](docs/repairs/diagnostics-8fb7513c-2026-11-09.md).

## Crash protection, privacy, voice and response presentation

- Make optional frame-timing instrumentation nonfatal for invalid labels, protecting the Gateway conversations screen. Split oversized GitHub workspace composition into real components and preserve repository controls, dirty-editor navigation and partial-file safeguards.
- Remove raw chat-collection logging and redact complete multiline payloads before splitting or export. Correlate matching crash emitters, retain bounded safe crash details, correct pre-dispatch accounting, and avoid flushing frozen logs for every line.
- Carry explicit output origin and invocation identity from providers through runner, reducer and delivery. Complete model-origin attribution for the Groq/LLAMA/NVIDIA reasoning parser and LiteRT/GGUF output, including buffered/recovery text; application reports and synthetic progress closure remain separately identified.
- Preserve SpeechRecognizer error 10 as rate limiting, retain partial transcript review, bound TTS catalog initialization, and fence callbacks to the current engine and utterance. Repair actions preserve the user's selected speech services and preferences.
- Coalesce checkpoint writes and durable research wakeups, move socket cleanup off the main dispatcher, bound Debug/Logs rendering, page long responses, prevent clarification self-loops and preserve composer ownership of keyboard release.
- Extract streaming recovery setup to reduce the Android method-size failure without weakening the existing DEX guard. Strengthen frame-timing tests with native Robolectric drawing and captured-pixel assertions, and update regressions to enforce output lineage and explicit integration admission blockers.

These changes address the source defects and integration failures recorded in [PR #815](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/815). They do not claim a measured frame-rate improvement or qualification of the affected phone, speech service or private gateway.

## Validation and publication identity

Before the version update, source revision [`834f045d4ba8dcc01c691463663cc4d04ce76abb`](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/834f045d4ba8dcc01c691463663cc4d04ce76abb) passed all nine applicable PR workflows:

- [PR Validation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818055), [Android acceptance](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818200), and [UI Review APK](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818146).
- [CodeQL](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818271), [Kotlin lint](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818327), and [Diagnostic audit validation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818060).
- [Gateway stability](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818274), [Gateway Windows installer](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818308), and [GitHub build downloads](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42632818312).

The branch-filtered unsigned-release job was skipped and is not counted as a passing check. The results above are predecessor-source evidence: release approval requires fresh checks for the versioned candidate and its resulting main revision. The signed-release pipeline separately validates exact-commit unit tests and lint, required main workflows, packaged runtimes, privacy, signing-certificate continuity, APK/AAB identities, checksums, provenance and downloaded publication assets. Its receipts identify the actual release commit and uploaded files; prior CI does not substitute for those gates.

All scoped qualification limitations are retained below. Host and emulator results remain separate from physical-device and private-service measurements.

## Qualification and remaining work

- The November 12 session repair requires installed-APK reporting reconciliation, exact-model generation, affected MCP listener/protocol/credential repair, owned integration fixtures and physical-device voice/lifecycle/frame/memory acceptance. Source and host checks do not establish those external results.
- The later 141-check audit and diagnostic capture were supplied as analyses, not original files; their capture-specific rows and measurements were not independently revalidated. The audit's 67 untested checks remain untested until matching execution evidence is retained.

- The November 8 diagnostic integration retains required matching PC/phone deployment, original MCP endpoint checks, private-account workflow execution and physical-device speech/DNS/frame qualification. Closed transport stubs and a disposable CI fixture are explicitly separate from live-account or device acceptance.

- Gateway conversation and job features require matching Android and gateway updates. Host, mocked transport and Room regressions do not qualify private-server reachability, physical-device process death, OEM lifecycle behavior or end-to-end frame pacing.
- Legacy schema50 archive tables remain preserved. Restoring a backup containing those retained archives into a fresh schema51 database without the archive schema is explicitly blocked before data changes; canonical conversation and multipart backups remain portable.

- Gateway continuity requires matching Android and gateway versions. Physical-phone force-stop/reopen, production queue contention, private model availability and measured rendering performance remain deployment qualification; host and CI evidence are recorded separately.

- The historical three MCP discovery problem targets and 49 execution/suite gaps require fresh affected-installation or authorized harness evidence. Source fixes, generated qualification plans and unrelated CI passes do not convert them into successful runtime checks.

- The November 7 c1466ca0 repairs require matching Android/gateway deployment, live MCP endpoint and DNS checks, and physical-device handoff, reasoning, frame-time and IME qualification. Host tests do not establish those outcomes.
- App audit inventories supported production files and executes named local or selected metadata probes; it does not establish complete file, line or branch coverage, arbitrary plugin tool execution, physical-device qualification or private service availability.

- The November7 c285 emulator statistics workload retained API34 gaps of one unacknowledged start and one recorded-in-progress entry, and API36 one unacknowledged start. Functional acceptance passed but performance remains unqualified; later exact-head evidence must be inspected independently.

- Unified Reliability v2 is not complete; its remaining acceptance cases are not represented as passed.
- Physical-device performance and a production baseline profile remain unqualified until measured on a reference device.
- Android GPU qualification requires compatible-device receipts for actual offload, prefill, decode, repeated requests and cancel/recovery; host tests do not establish those results.
- Repeated physical NPU/GPU schema completion, reduced warm inspection latency, calibrated memory admission and Full-run performance remain device acceptance gates. The app does not infer runtime-effective settings from requested or SDK-applied values.
- Qwen demo backend availability and live authenticated LLM7 generation are not established by deterministic tests.
- Optional runtime extraction remains a staged packaging project. The measured payload inventory and APK code compression do not extract plugins or remove native runtimes.
- Exact recovered France/Portugal wording and report fields, and the earlier benchmark export round-trip, do not establish a complete conversation replay; the later 16-run benchmark export remains unavailable.
- Live GitHub sign-in, private-repository access and credential-refresh acceptance remain unverified in this diagnostic audit; public browser sign-in requires the registered broker deployment.
- The October 28 export still requires physical-device performance replay; fixing the identified filesDir calls does not establish that the 748 ms frame stalls are resolved.
- Provider HTTP 403s, Android-app GitHub token endpoint permissions, private local/MCP server reachability and the remote MCP discovery timeout require service, credential or device-route verification.
- No application access to asus_leak_detect_proj was found; the originating ASUS framework/vendor finalizer path still requires an affected-device stack. Host and emulator checks do not qualify the affected firmware.
- Live Android sign-in, authenticated App-token read/write and refresh acceptance remain unverified and are not claimed as qualified by this release.
- Browser PKCE requires its broker and verified HTTPS callback deployment.
- The October 30 GitHub chat diagnostic scenarios still require live provider and physical-device replay; automated tests do not establish private server availability or uninterrupted execution through permanent outages.
- The 34 pending operations in the original report cannot be verified from the supplied log; no device-side watch was registered from this environment.
- Checkpoint loading objectives and local recovery counters do not establish end-to-end recovery time or remote transaction reconciliation.
- Tool health reports version-bound observations from actual executions; it does not actively probe services or guarantee present availability.
- The diagnostics log rendering changes require physical-device frame measurements to quantify their effect. Forward rollback proposals require a reviewed new release; published tags are never rewritten.
- Compiler-resolved Kotlin/Java navigation, a dedicated conflict workspace and native GitHub Enterprise support remain unimplemented; the integration expansion is partial.
- Gateway publication requires an operator-provisioned GATEWAY_ENGINEERING_VALIDATION_POLICY with a local mirror, pinned Docker image and approved commands. Actual isolated Docker execution remains unqualified.
- Android webhook push, complete approval/activity history, unified model readiness, full mutation fixtures, attestation retrieval and remaining performance/accessibility work are partial.
- Voice speech services, GitHub prompt anchoring during physical gestures/IME/rotation, process death and reboot still require device acceptance. This release does not claim those cases passed.
- The Android signed release does not install or publish a new Windows gateway package; Windows installer checks are tracked separately.
- External Graphify server behavior and physical-device graph, speech and scrolling performance have not been qualified by these host and emulator checks.
- Automated mutation fixtures cover owned disposable branch and fixed synthetic content/stage/commit lifecycles with verified cleanup; arbitrary content writes, releases, merges and cancellations require separately authorized fixture plans.
- External Graphify execution, compiler-resolved navigation, and physical-device graph, speech and UI performance remain unqualified; host and emulator evidence does not establish those outcomes.
- The November 3 build-138 chat audit implements bounded UI and planner repairs; full mutation fixtures, retained-page capacity recovery and physical-device acceptance remain incomplete.
- The November 4 GitHub workspace repairs do not qualify physical-device process death, UI frame pacing or private Llama/server reachability; those outcomes require live device and service receipts. Saved evidence remains quota bounded, and an unavailable mutation result must be reconciled rather than replayed.
- Temporary conversations do not persist recovery evidence; interrupted work with missing receipts or counters remains blocked, and changing storage mode does not grant more tool calls.
- The November 5 diagnostic changes retain partial output and durable execution evidence; providers without a resume protocol do not gain token-for-token stream resumption.
- The affected ports 8101 and 8102, live DNS/providers, authenticated gateway inference, OEM recognizer/IME behavior, and the recorded frame stalls still require deployment or physical-device acceptance.
- PDF font selection uses available compatible system fonts and checked glyph coverage. Exact layout and all glyphs require the affected documents and device fonts; bounded retrieval can still report explicit partial coverage for oversized sources or pages.
- The November 5 gateway/GitHub changes implement source and test contracts; the coverage ledger separately tracks remaining integration and acceptance work, and automated success is not evidence that every specification requirement is qualified.
- Selected-profile guarantees, bidirectional debug capture, restart recovery and response frame pacing still require live Android/PC qualification with the configured local model and gateway. Performance targets are not measured improvements.
- The GitHub ticket broker, callback registration and enterprise/private-repository deployment remain operator qualification tasks; deterministic tests do not establish production sign-in or credential refresh.
- The November 6 diagnostic repairs require live acceptance on the original MCP endpoints, an authorized GitHub workflow against the running application build, and physical-device frame measurements. Retained exports cannot recover previously deleted, evicted or uncaptured history; cached or mocked observations do not establish live integration success.
- Legacy writes whose original invocation arguments and identifying context were already lost cannot be reconstructed from their hashes. Exact retained fences remain enforced; arbitrary resumption of that historical work requires external remote-state reconciliation.
- The 008174e2 archive was partial: omitted original payloads cannot be reconstructed. Live private endpoint availability, phone frame pacing and process-death acceptance remain deployment/device gates; host regression success does not establish those results.

- New release provenance requires a successful exact workflow with GitHub CLI >=2.102.0 and an unexpired digest-bound evidence artifact. Host tests do not qualify the current APK, microphone or deployed gateway. Unsupported mutation lifecycles remain explicitly outside the new bounded integration preset.

## Session repair validation follow-up

Backend completion and repair diagnostics retain bounded serialized allowances, finish categories, observed byte counts and terminal status even when token usage is unknown. Saved-job evidence remains separate from new result reads, and repeated repair errors do not erase the current HTTP/SSE failure cause or authorize a whole-request replay. Fresh portable-domain and Android JVM reports are collected together with measured application coverage; these results do not promote historical audit checks or establish physical-device and live-service acceptance.

Hosted report compatibility now negotiates context-v3 review extensions before dispatch. Original v1/v2 reports retain their bytes; already-approved extended-v2 reports require both v2 wire support and v3 capability, so an older service cannot accept readiness and then permanently reject those reports.

Foreground microphone capture no longer fails solely because output audio focus is unavailable. Permission, lifecycle, recorder-state and Android input-silencing checks remain enforced. Unknown model exceptions retain their provider boundary and completed GitHub receipts without message-derived retries; typed rejected-provider HTTP 429 reports keep their rate-limit category and cooldown. Physical-device and live-provider recovery remain separately qualified.
