# Repository review implementation — November 22, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base branch: `main`; inspected base: `c023735c02368fef4c748cf7be97b9fcfe2cd513`.
Shared PR: **#2254**, `fix/diagnostic-execution-completion-2026-11-22`.
Inherited head: `f225c208e80393f0232ba7849340461fd23901b9`.
Integration branch: `work/review-implementation-2026-11-22`.

## Authorized outcome

Implement the actionable improvements from the repository/ChatGPT review, preserve
the existing diagnostic repairs and concurrent changes, publish the changes on the
same draft PR, require successful current-head verification before merging, then
qualify and publish the coordinated Android and Gateway release. Never force-push,
discard another worker's commits, weaken required checks, or replace published
release assets. Recheck both shared head and main before every remote mutation.

## Acceptance map

| Area | Required result | Owner / evidence |
| --- | --- | --- |
| Completion and persistence | Preserve typed atomic checkpoints, comparison coverage, provider causes, bounded continuation, and saved waits | Existing PR diagnostic repair ledger; integration regressions |
| Caption motion | Keep line identity through continuous following, multi-line moves, manual browsing, and reduced-motion settings | Voice implementation and behavior/instrumented tests |
| Spoken word | Bold theme colour fades in over 250 ms; preserve speech-start fade and generation hiding | Voice animation policy and caption tests |
| Voice readiness | Make installed language and actual speech provider clear; preserve qualified accelerator fallback | Voice readiness and runtime evidence |
| First successful chat | Distinguish configured, available, and actually observed readiness with actionable existing controls | Readiness and setup presentation |
| Data route | Explain phone, PC, and cloud routes using available evidence; preserve profile-selection freedom | Runtime/status presentation |
| Optional orchestration | Avoid unnecessary adaptive work while preserving explicit participants, required review, and hard budgets | Task-aware delegation regressions |
| Architecture | Extract a stable provider boundary with behavior preservation | Provider dispatch regressions |
| Outcome efficiency | Expose successful-task outcomes, elapsed effort, retry overhead and known cost/usage provenance | Benchmark metrics, UI, and export regressions |
| Research and documents | Preserve implemented-versus-pending engines, partial extraction and scan handling without false full-read claims | Capability/readiness guidance and existing extraction contracts |
| Portability | Make deliberate backup/restore and recovery paths discoverable; preserve encrypted export boundaries | Existing backup flow and setup guidance |
| Gateway setup | Explain shared network access and authenticated owner capabilities separately | Existing identity boundary and readiness presentation |
| Hardware measurement | Provide repeatable observations and retain unknown results until compatible hardware executes | Voice/runtime benchmark evidence; no invented device measurements |
| Publication | Exact PR/main checks, artifact identity and immutable public release readback | CI and publication receipts |

The comparison's observations about different product architectures are retained
as context. Native full-duplex speech, new multilingual model distributions,
additional operating-system clients, external model quality and Windows signing
certificates require their own compatible runtime or credentials. Existing support
must remain accurately described; a UI option, passing unit test, or static source
inspection cannot qualify those external capabilities.

## Validation journal

- Intake: main and the active draft head matched the revisions above. Main's
  published versions are Android 0.9.66.12/build 146 and Gateway 18.1.0.
- Current candidate versions inherited from PR #2254 are Android 0.9.66.13/build
  147 and Gateway 18.1.1; publication is not yet complete.
- Local environment has JDK 21. Full Android/device qualification is owned by the
  existing hosted CI until an Android SDK/device is available locally.


## Outcome efficiency implementation

`benchmark-task-efficiency-v1` reports the total recorded non-warmup elapsed effort divided by fixtures graded PASSED. Incorrect answers, interrupted work and dispatched cases subsequently skipped retain their elapsed effort; a completed request alone does not count as a successful task. Retry duration is disclosed without adding it twice. Missing durations and overflow keep totals unknown. Setup, downloads and demonstrably unattempted skipped cases remain outside this fixture measurement.

Configured token prices are captured immutably at run creation. API cost estimates require matching profile/model identity, valid unexpired rates bound to the selected endpoint, transport, model and routing context and final reported input/output usage. An incomplete participant or retry usage population stays unknown, rather than reporting a misleading partial total. Local API charge is distinct from device energy/hardware cost. Cost is displayed with six decimal places and exported with its scope, evidence gaps and policy.

Validation: 17 focused Kotlin/JVM behavior tests passed using the production efficiency, coverage, price arithmetic and presentation formatter source with narrow unrelated Android/serialization/model boundaries. Added a serialization/export regression for full Android CI. These checks do not replace Android compilation, Compose/Robolectric tests or device qualification.

Concurrent voice work arrived on the shared PR while this implementation was in progress; remote head `2dfb90432b2981fdb3183b71883df2e3217f77d2` is being preserved before integration of our review deltas.

## Integrated validation checkpoint

Preserved the complete concurrent voice candidate through `2dfb9043`, including the QNN host qualification limitations. Integrated setup/runtime route guidance, provider dispatch and routine automatic preparation, outcome-efficiency reporting, route-bound price snapshots and private-source redaction, plus the voice measurement export and fractional-caption spacing regression. Pricing capture uses the existing account/provider catalogue without a network refresh. Positive costs below one micro-currency unit per successful fixture render as a bound rather than zero.

The active main ruleset is `27005122` (Verified main changes): a pull request and strict successful `Required verification` and `Analyze (java-kotlin)` checks are required; the repository lists no bypass actors. Publication additionally waits for all exact-main release workflow gates. These policies are preserved.

The final combined local host pass (including concurrent recovery commit `3d672c04`) completed 605 script tests successfully, with generated architecture/audit/agent/diagnostic metadata, dependency catalogue, secret scan, Android regex, resource/icon/native-provenance and diff checks passing. All 51 changed Kotlin files passed ktlint, and the scoped release validator passed. The integrated pricing/efficiency/export-helper/formatter harness passed 43 tests; the separate readiness, dispatch/routing and voice suites passed 12, 12 and 41 tests respectively (108 focused tests across these suites). Full Android/Hilt/Compose/serialized-export and device regressions remain mandatory in current-head CI. No physical performance result or publication is claimed at this checkpoint.

## Full-suite repair and canonical Room 53 handoff

The earlier f225c208 full Android run executed 12,256 tests and exposed 29 failures. Candidate failures were reproduced and inspected against the newer source before changes. Gateway fixture isolation and explicit connection rechecks now pass 37 focused tests, preserving all HTTP/cancellation/TLS and retry safeguards. Bounded cause traversal, safe upstream provider identity and compact GitHub descriptions pass 11 focused tests; original schema budgets remain 1600/2000 and every input constraint is preserved. The diagnostic recovery fence fix and scoped incident/grouping helpers pass 14 focused tests, with the production fence regression confirmed red before the fix. Export timing mocks and Markdown foreground observations retain their existing privacy and rendering assertions.

Concurrent privacy highlights and conversation badges at eeb2a205 advance Room to 53; their source and tests are preserved. The genuine KSP export was recovered from Diagnostic audit run 45058161842, job 137318034810, artifact 14768098633. Its source head is 2fa6f59f; GitHub's tested merge 7676c6df has an identical source tree. The artifact SHA256 is c601674748694d9511ab3b85673ee84ece8ef476cbdb6fb02926b90d5b23b3e6. The 164660-byte schema has SHA-256 4d9294e56972dd23bb99841b263e026d9f4d97faa0acf71d4d40da4f9b28afdb and Room identity 489fe6ad9e5f86a5af203af446e77dbf. Downloaded bytes match the lossless KSP job-log copy. Database entities, converters and migrations are unchanged from that artifact source; subsequent DAO transaction changes preserve authoritative privacy evidence across stale snapshots. Current-head KSP verification remains required. The missing-schema handoff gate was retained and no schema was fabricated. Generated metadata now derives from that export. Full current-head Android CI remains mandatory before merge.

## Concurrent full-suite repair reconciliation

The shared branch advanced through 7c7634df while the repair upload was prepared. The expected-head check stopped the branch mutation, and the changes were merged normally. Equivalent schema, traversal, fixture and renderer fixes retain the stronger incoming assertions. The combined source also preserves selected-service-only Gateway rechecks, transactional privacy receipts, conversation ownership checks, and required Room/caption instrumentation on both emulator variants. The subsequent 6e03f1e0 Research settings callback fix is included.

After reconciliation, the actual-client Gateway harness passes 39 tests, the provider/policy/schema harness passes 11, and the diagnostic helper harness passes 14. Unique supplemental tests retain shared cooldown enforcement across clients, cache-only refresh behavior, non-completion of blocked GitHub work, process-scoped incident isolation and malformed recovery-fence rejection. Provider diagnostics preserve safe numeric 401/429 codes and bounded hash-only correlation for unrecognized upstream codes. All 12 Kotlin files differing from 6e03f1e0 pass ktlint; release-scope and committed Room-schema checks pass. These focused results do not replace the full current-head Android suite.

Canonical validation after reconciliation: `bash scripts/validate-local.sh fast` passes all 608 host tests and the generated metadata, dependency, secret, regex, resource, icon, provenance and diff checks. Full exact-head Android CI and publication remain pending.

## Emulator binding contract repair

Both API 34 and API 36 acceptance jobs from source 6e03f1e0 (run 45059368354; jobs 137322973552 and 137322973853) exposed the same persistence test failure. The fixture expected SQLite's nullable composite index to reject two built-in rows with different IDs; that is not the database's actual contract. Normal UI enables use deterministic IDs. The supported mutation gaps concern interrupted delete/insert operations, configuration or legacy imports with arbitrary saved IDs, and duplicate rows restored from an existing snapshot. The relevant code was unchanged through 7afcd240.

The repair centralizes ownership validation and logical replacement in a Room transaction and routes the repository setter through an atomic built-in boundary. Concurrent commit 85389f14 adds database-open INSERT/UPDATE guards and atomic normalization of equivalent legacy NULL-connection bindings, retaining the newest timestamp and then binding ID in each exact profile/tool scope. The normal app and temporary backup databases install those guards. A backup's writable staged source is normalized before copying into the guarded destination; the original archive is unchanged. Generic legacy tool names remain representable. Distinct profiles, tools and connection-backed bindings retain their scope. Schema 53, entity declarations, migrations, columns and indices are unchanged.

The strengthened raw-SQL/reopen test now exercises a real database guard. Five additional actual-Room behavior tests cover exact-scope replacement, ownership rejection, rollback through the production repository setter under an injected insert failure, concurrent UI/import writes, and full/TOOLS/excluded-TOOLS restore behavior. All 25 persistence methods are mandatory in the combined emulator receipt gate; no failed or missing result is accepted. Before the guard reconciliation, five focused JVM contracts, 16 receipt checks, 51 runner checks and changed Kotlin formatting passed locally. JVM annotation stubs do not establish Room atomicity; the complete current-head Android and emulator suites remain required.

## Full JVM suite follow-up

The uninterrupted 50131133 PR validation run 45059996467 completed its full app suite in job 137323925528: 12,497 tests, 12,490 passed, two failed and five skipped. Core-domain added four passing tests. The early statistics artifact preserves all 1,719 XML reports before later constrained-heap reruns. Its SHA-256 is 03129117559d3f0755b618fa92b0e0e3a5c554039c276d95f781949481b3294d. The remaining failures concern the Gateway ViewModel fixture's old refresh signature and the explicit issued-cursor guidance contract; both were reproduced against the current source and repaired. The selected-config ViewModel fixture passes all three focused tests with explicit refresh ownership, cancellation and measurement assertions retained. The unchanged cursor admission test and thirteen related cases pass, with every schema constraint unchanged and budgets at1514/1600 and1940/2000. These older results do not qualify the current revision for merge.

Concurrent feature-wiring revision f3b9b408 is preserved. Its exact voice/caption/export source passes 50 focused JVM tests; changed callback call sites were reviewed. A presentation-only follow-up resumes Gateway row animation when a filtered details dialog is no longer visible. Full Compose, Room, Android, emulator and exact-main publication gates remain required.

Concurrent correction 3f047bce is preserved. The final cursor description uses its shorter exact-issued/ONLY-field wording; the unchanged fourteen focused cases pass at measured schema estimates1486/1600 and1913/2000. The resolved Gateway ViewModel test retains both strict exact-configuration fixtures and the concurrent target identity assertions, with all three actual ViewModel tests passing.

### Final concurrent package and Research follow-up

Preserved `459261b5` and `e9148ab3` through a normal merge. Both Kokoro shared libraries must satisfy each packaged ABI and its adapter dependency checks. The synchronized eight-way DAO replacement case remains alongside the mixed UI/import ownership and rollback cases, making 26 mandatory persistence methods. Research loading and save state guards remain intact throughout the editor. Combined exact-head Android, emulator, release and publication evidence is still required.

Combined validation after the package/Research merge passed 615 canonical host tests, all 12 changed Kotlin files, generated inventories, regex, resources, icons, native provenance and diff checks. The resolved strict Gateway fixture passed 3 actual ViewModel tests; binding contracts passed 5 host cases and all 26 instrumentation method names have mandatory acceptance receipts. Android compilation, real Room and emulator execution remain required on the uploaded head.


## Phone map, private-tool and acceptance reconciliation

Preserved every concurrent revision through `b8ac69dcac38f7d71d6fbe00135dae1c34302ad2` by normal merges. This includes the raw-SQL Kotlin argument fixture, Research frame synchronization, accessible GitHub choices, phone repository maps, responsive privacy controls, trusted private local tools and typed audit evidence. The follow-up at `9173e214` keeps phone-map preparation on the background dispatcher while preserving coroutine authorization and accounting. The map search reconciliation retains the newer complete bounded-path contract, truthful path/impact limits, source-switch fencing and directory-scoped relative imports. All 30 incoming engine/parser cases remain, with independent downstream Python call-target and genuine-negative path regressions: **32 actual-source tests passed**. No graph discovery, response-byte or schema budget was increased.

The phone tool route now retains the native GitHub cooldown and request accounting: a local rejection records zero dispatch only when no earlier request in the same operation was observed. Confirmed HTTP responses retain their status and bounded same-response wait evidence. Gateway source selection stays explicit and neither native rejection nor cooldown silently switches providers. The 24 focused tool/accounting/fallback cases pass; five new evidence cases fail against the original production tool. The actual immutable repository context rejects a branch name before source-link rendering, and the corrected test asserts that constructor invariant. After reconciliation with the new map guidance, all 14 presentation/context cases pass. Focused harnesses isolate unrelated platform boundaries and do not establish Android integration or physical-phone performance.

The latest completed full-suite artifact is still historical: source `f3b9b408c41d078d140062da38270bf0e75fdf84`, PR Validation run `45064285717`, unit job `137338173693`, artifact `14771843248`, SHA-256 `6097e5674f04467c6d58f45bba5ff1bd46035b335ad7bf1b32885291317da9a0`. Its 1,720 XML reports contain 12,519 cases across app and core: 12,511 passed, three failed and five skipped. The issued-cursor guidance and exact-config Gateway fixtures are already repaired; the remaining provider-report fixture now checks HTTP status in its authoritative typed context and outgoing field, preserving the closed diagnostic allowlist and hash assertions. These older failures do not count as current-head results.

The earlier binding candidate `87204b8667233f1d01334cdae2765d1f152d6545` completed real emulator acceptance successfully in run `45067079348`: API 34 job `137345787199` and API 36 job `137345786900`. Its mandatory 26 persistence methods include database guards, transactional replacement, ownership, rollback, concurrent writes and restore coverage. This confirms that earlier candidate only; the phone map and later privacy changes still require their own exact-head execution.

The combined acceptance script now also invokes `PrivacyProfileSelectionInstrumentedTest` and requires the individual success receipt for selection and deselection with Privacy Mode off and every verification state. The receipt validator rejects a missing or skipped method. All 16 receipt-validator tests and shell syntax validation pass. Together, the selected latest-feature families require 26 persistence, one migration, nine voice and one privacy-selection method, in addition to the other existing acceptance suites. No acceptance check was removed or relaxed.


### Final phone-map projection and captured privacy authority

Preserved concurrent `0885b3cc6aa681e4d84ca4d431f979d1e8dd7024`, including path IDs paged with their nodes, source/target and offset metadata, and explicit Privacy Mode repair guidance. The integrated graph engine still passes all 32 tests. The combined tool/accounting/fallback suite now passes **29 cases**. The same 29 cases fail five times against our pre-merge tool and six times against the incoming tool, demonstrating that both complements are retained. Cooldown state remains WAITING with zero or earlier dispatch; privacy rejection retains its actual precheck/execution stage; an observed HTTP response cannot acquire invented zero request counts. Phone-local success and indexing never borrow remote Gateway checkpoint or reconciliation claims.

Review of private local tools found a policy race in native LiteRT preparation: an already-private turn retained a mutable store reference and could see a broader tool set after Privacy Mode was disabled while preparation suspended. Each conversation now captures one exact device privacy state and revision before preparation, retains its owning job, and carries a closure-backed authorization guard through native callbacks. The private allowlist survives live-flag changes; profile and plugin revocation remain authoritative. The physical tool boundary validates the retained guard even when native IPC has no private marker. Configuration and document checks revalidate their captured parent after their own suspensions. A revoked parent aborts capture rather than becoming a new child snapshot; the existing bounded child-only retry remains intact. Cancellation still propagates without being converted into an ordinary tool failure.

Focused evidence: **nine actual-source private-turn/store/tool tests passed**; the saved pre-final transport gate fails the marker-free off/on/off physical-boundary case with the same suite. The earlier eight-case fixture against the original `1b603e4` transport implementation reproduced two policy/allowlist failures. The exact document-guard expression passes its one-case coroutine probe; removing its final parent check permits one external body call and fails that probe. The configured-authority probe compiles verbatim production capture/guard code with the committed four-case regression: baseline four cases/two failures, corrected four/four passed, retaining cancellation and the original child-only retry bound. Android/model/projection boundaries are isolated in these host probes; they do not constitute full repository or native-device execution.

Committed integration regressions cover three native-preflight toggle sequences, resolver revocation and no-discovery behavior, runner/budget non-dispatch receipts, document validation at physical dispatch, inherited configuration revocation, cancellation and the existing retry bound. Full current-head Android compilation, all JVM tests, Room/Compose instrumentation, native packaging, security, Gateway and release validation remain mandatory.


Final combined local validation passes **615 canonical host tests**, all **19 Kotlin files differing from the preserved remote source**, generated architecture/agent/audit inventories, Android regex, resources, icons, native dependency provenance, release scope and authentic Room-schema checks. The acceptance shell passes syntax validation. These results qualify the integrated source for upload, not merge or publication; every current-head CI and exact-main publication gate remains required.


## Comparison-generation and uncertain-read reconciliation

Preserved diagnostic review completion `fea1a6ac82756817aad485b6267e4294873bfb8b` through a normal merge. This retains delivered-payload source proofs, bounded cursor and comparison windows, truthful context compaction, stage-aware visible-output recovery, typed diagnostic contracts and output-allowance reservation. The combined source passed 616 canonical host tests, 206 authentication tests, formatting for 78 changed Kotlin files, and all 29 actual-source map/accounting/fallback tests. Exact integrated Android checks remain required.

An independent response-order probe found that a live first page arriving after page two could erase the second page's same-generation receipt. The boundary now carries a locally issued process namespace and monotonic ordinal into execution receipts. The coverage reader uses that issuance identity; ordinary pinned rereads retain their completed coverage, a deliberate refresh excludes older generations, and superseded/expired replies retain their original stamp. Upstream payloads cannot provide the reserved stamp. All 54 incoming helper cases remain, with seven additional regressions and 24 explicit-live response permutations: 61 focused tests passed. The original one-case probe fails against saved original source and passes against the correction. Eight changed Kotlin files pass formatting and independent review. The full operation-facade propagation regression is committed for Android CI; the helper harness does not qualify the complete Android integration.

The latest completed full-suite artifact is from historical source `b8ac69dcac38f7d71d6fbe00135dae1c34302ad2`: run `45070165494`, unit job `137355179755`, artifact `14773884757`, SHA-256 `0d12085fa95ac330bacb59034f3c52c489fa20209327c403a5eca0192a1463e6`. Its 1,734 XML reports contain 12,688 app/core cases: 12,680 passed, three failed and five skipped; declared and observed report counts match. The source-link constructor fixture was already repaired. The Gateway privacy diagnostic fixture now asserts that a prior unknown outcome requires reconciliation and unknown dispatch, while a purely local denial remains manual and not dispatched; the production priority was already correct.

The remaining MCP failure was reproduced using real Ktor and the affected production recovery/projection code: seven cases with exactly one failure. Coroutine exception recovery discarded suppression attached to an intermediate policy exception, erasing an earlier uncertain read. A per-acquisition typed context element now retains only that uncertainty fact. Modern read recovery and legacy reconnect update it before retry; a subsequent local rejection cannot claim the whole acquisition was never dispatched. DNS/connect/TLS/local denials and cancellation retain their existing semantics, and no endpoint, exception prose or prior connection history is retained in the carrier. The original seven cases pass after repair; the expanded suite passes 13 cases, including concurrent isolation and exact legacy-method probes. Four changed Kotlin files pass formatting. The full manager regression adds fresh same-connection and separate-connection checks and remains mandatory in Android CI.

A further concurrent feature commit, `6e788707cc216b6e92b2189a343dedd9ceaad4b8`, introduces durable per-conversation privacy and Room54. It is being reconciled with captured request authority and the above repairs. Its policy separates the availability of new private conversations from existing conversations' permanent protection. This incoming feature's full integrated build, migration and instrumentation evidence is still pending; historical checks do not qualify it for merge or publication.


### Permanent conversation privacy and callback integration

The normal merge preserves `6e788707cc216b6e92b2189a343dedd9ceaad4b8`. Private-turn capture now binds the originating conversation and the effective protection revision. Availability in Options neither promotes an ordinary turn nor unlocks a saved private turn; category and private-term changes still revoke an admitted mask, including a change back to earlier values. The owning job, inherited guard, document revalidation and private local-tool allowlist remain authoritative. Ten actual-source guard/store/tool cases pass; the same cases fail twice against the old helper. The per-chat Ktor/recovery/legacy-method probes pass all 13 cases. Source and fixture changes pass formatting and two independent composition reviews.

Native LiteRT callbacks now capture the current conversation policy alongside the current request guard inside the exclusive request, reinstate both at IPC callbacks, and clear both during teardown. This closes a context-loss gap introduced by moving privacy enforcement from a global flag to conversation-local policy. The six-case production-expression and physical-tool-boundary probe fails three cases against the saved original callback and passes all six after repair, including private/ordinary reuse, local-tool success, external rejection, teardown and cancellation. Two full adapter regressions are committed for integrated Android execution. This probe does not execute a physical native model or qualify an accelerator.

The Room54 schema is an authentic full-app KSP export: 164926 bytes, SHA-256 `6363d1cf75c4464b7bdb0d58872a7200088d93e8da744a7717f60b1f5067ee36`, identity `2f972404e2d13990fcf36076058a79a4`. Its bytes match the successful producer's export and log. A later producer retry failed, so that provenance is not a current integrated build result. The migration/DAO review retains all 26 earlier persistence tests and adds three, with two mandatory migration methods. The selected new-feature families therefore require 29 persistence, two migration, nine voice and one privacy-selection success receipt, in addition to the other acceptance suites. The dynamic host receipt checks reject every missing or skipped method; no gate was relaxed.

The subsequent concurrent repair `dda6f5ce35956cab9fabf728a2d3acbec790eabd` adds complementary MCP uncertainty handling, a real legacy reconnect fixture, and stronger diagnostic recovery/correlation assertions. Its source-link fixture separates invalid immutable-context construction from pinned-path rejection. Both stronger fixture files are retained verbatim during normal-merge reconciliation. The overlapping uncertainty implementation is being consolidated into one same-acquisition evidence record before final validation.


The MCP reconciliation is complete. Modern and legacy reads share the incoming `MetadataResponseObservation` record; the duplicate intermediate carrier was removed. Its bounded cycle-safe traversal retains the previous response and interrupted-read fact while distinguishing known DNS, connect, TLS, cancellation and local admission failures. All 16 actual-source probes pass: 12 repository recovery methods, one real Ktor scope/isolation case and three exact legacy-session-body probes. The seven full manager transport tests retain all immutable-conversation, wrapped EOF, real legacy reconnect and fresh same/different-acquisition cases. Five affected Kotlin files pass formatting. Current integrated Android execution remains required.


Combined validation after `dda6f5ce` passes 616 canonical host tests, all 30 Kotlin files differing from the incoming source, generated inventories, 1904 literal Android regex checks, resources, icons, native provenance and diff checks. The exact working release scope contains 624 bounded paths. These local checks permit a draft upload; they do not qualify merge or publication.


## Conversational voice and benchmark integration

Preserved `ecd207312bbbcef04790762217c3d66a75ebb26f`, including durable voice-response policy across queued/retried/Combined turns, provider reasoning separation, shared answer/speech/replay/caption projection, pronunciation normalization and Quick/Full CPU/GPU/NPU benchmarks. An independent review matched all 44 files in the incoming 114-case session/prompt evidence manifest to that immutable source. Frozen writer prompts, accepted retry policy, original privacy ownership and request guards remain intact. The new silent-inference qualification limitation is retained alongside every earlier release limitation: first PCM is returned-buffer timing, not acoustic output, and physical acceleration, battery, utilization and playback measurements remain unqualified without real device evidence.

The benchmark audio lifecycle required a real integration repair. Closing Settings could declare idle before a cancelled native worker released its engine, and reopening Voice Chat could create a new owner that did not observe the old worker. The screen now observes a shared atomic owner-token lease until actual job completion. Session-level audio holds block automatic/manual capture and replay, fence late callbacks, and preserve the draft and mute state. Immediate handlers and suspended replay work recheck current ownership. Token-checked release prevents an old completion from clearing a newer worker, and cancellation remains local to its owner. All 84 actual-source session/caption/buffer/ownership cases pass. The original session with only a no-op compatibility hook fails exactly the two new audio-boundary cases among 82 tests; the two additional ownership cases exercise delayed teardown and stale-token release. Six affected files pass formatting and independent review. Full Compose/Hilt/ViewModel integration remains mandatory.

Native benchmark cancellation and worker interruption now prevent a COMPLETE result or backend recommendation while retaining completed samples. The 17-case suite reproduced two failures before correction. The final 18-case suite also reproduces one same-attempt evidence failure against the pre-fix source: a throwing later phrase borrowed the previous phrase's qnn-gpu receipt. Current-attempt evidence is now reset and captured only after that generation returns, preserving valid current-output failure evidence and previous completed samples separately. All 18 cases pass. Auto qualification keys now include the workload protocol revision, preventing historical two-shape receipts from mixing with current three-shape measurements; an actual Store/JSON/selection probe fails before this fix and passes after it. History, configuration identity and selected-backend retest behavior remain intact. Four affected files pass formatting and independent review. No physical-device benchmark result is claimed.

The two new voice-message control methods are mandatory emulator receipts. The dynamic receipt guard now checks all four control methods; before the runner update it correctly failed on the two missing names, and all 16 validator tests pass afterward. Existing class invocation and timeout are unchanged. The selected latest-feature families require 45 individual successes (29 persistence, two migration, nine voice-conversation, one privacy-selection and four voice-message-control methods), in addition to the other existing acceptance suites.


### Final concurrent backup preservation

Preserved `4c48b77621b603f80a480ea45e0d7eedcddde86e`, including the matching voice acceptance receipts and permanent-privacy restore repair. Existing locked IDs are captured before conversation replacement and reapplied only to matching restored IDs; deleted conversations are not recreated, historical masking is not invented, and settings-only restores leave conversation data intact. Existing transaction and staged tool-binding normalization boundaries remain unchanged. The incoming SQLite/Robolectric fixture evidence is independently source-matched: seven cases, three failures before repair and seven passes afterward, with unrelated run/Combined records isolated. Full archive/Room rollback and current-head emulator acceptance remain required.


Combined `4c48b776` validation passes 616 canonical host tests, all 40 Kotlin files differing from that preserved remote, generated inventories, 1923 literal Android regex checks, resources, icons, native provenance and diff validation. The exact release scope contains 667 paths and all ten limitations. Source hashes for all eight generation-repair files and all six audio-ownership files still match their reviewed test manifests. These results remain local qualification for draft integration; full current-head CI and exact-main publication are required.


## Bounded recovery and final voice supplement

Preserved `c29cc56bc003f67ee7c6e3d746b9899b46e6f4b6` through a normal merge. Locally issued comparison-generation stamps and untrusted-payload sanitization remain intact alongside the incoming explicit pinned restart at the 512-page bound. The combined pagination suite passes 67 cases, retaining all 61 earlier generation cases and six incoming cap/restart cases. The combined session/caption/audio-ownership suite passes 91 cases; the persistent process lease, owner-local cancellation and physical audio hold remain alongside the newer reader/replay/caption identities. Both parents' independent test methods are retained and the resulting production composition is independently reviewed.

Auto comparisons now use the incoming shared protocol identity in both persisted capability keys and factory identity, with strict three-fixture eligibility. Historical, mixed-cohort and selected-backend retest regressions remain. All 20 benchmark/Auto cases and the actual Store/JSON/selection probe pass. MCP retains the invocation-owned uncertainty record and known pre-dispatch exclusions, with an additive early guard preventing wrapped local policy denials from being retried; the combined 14 repository methods and real Ktor/exact legacy probes pass 18 cases. Full manager and Android integration remain required.

The new reasoning scanner is independently source-matched to its 55-case passing producer evidence: 18 actual source/test files and six declared unrelated boundaries. The original source fails nine of those cases. It preserves quote, code-fence, escape and nested-tag state across streamed fragments and isolates choice-specific reasoning/tool cleanup. Sticky unshared private memory remains quarantined after source pruning, with full Room deletion/reopen behavior reserved for current Android CI. The additional caption device method is retained as a mandatory receipt.

The historical Development environments failure at source `0885b3cc` (run45070941651, job137357553875) is already addressed by the earlier preserved graph-accounting repair. Replaying the four unchanged rate observations against actual stale GraphTool fails the first dispatched assertion; current GraphTool passes dispatch, HTTP status, bounded delay, retry/reconciliation and safe-prose checks. No extra code or fixture relaxation was needed. The complete unchanged GatewayGraphTest remains part of current-head CI.

Combined `c29cc56b` validation passes 616 canonical host tests, all 39 Kotlin files differing from the preserved remote source, generated inventories (2921 production files), 1924 literal Android regex checks, 36 XML resource checks, icons, native provenance and diff validation. The exact release scope contains 670 paths and all ten limitations. The selected mandatory persistence, migration, conversation, privacy-selection and message-control families contain 46 individual methods; this is not the complete device suite. Full integrated Android validation, current-head CI, exact-main verification and publication are still pending.

### Loading-window and ordinary-memory follow-up

Preserved concurrent `dbc29c55` through a normal merge. Chat and voice retain secure-window protection while the saved room policy is unknown; an ordinary room releases only its own protection lease. Ordinary personal/project memory edits may move scope, while private and unshared records retain their restrictions. The shared voice benchmark ownership and captured private-turn authority repairs remain intact.

Historical `6e788707` full JVM execution produced 12,807 tests: 12,761 passed, 41 failed and five skipped. The retained artifact has no XML counter discrepancies. Later-fixed cases and remaining current-source failures are being triaged separately; this historical result does not approve the current branch. The local combined Android attempt stopped when its KSP daemon disappeared, so it supplies no passing Android claim. Existing CI compilation and memory gates remain unchanged. Two separately active acceptance-completion and coding-benchmark packages still require integration before final readiness.

The merged follow-up passes 616 canonical host tests and 44 changed Kotlin files in formatting, with 671 exact release paths. Independent review verified all 16 memory and six window source/test inputs against both the incoming commit and combined tree. The supplied actual-source evidence retains ten memory tests (two baseline failures reproduced) and three real Robolectric/Compose window tests (the old predicate fails at the first private caption frame); complete VoiceChatScreen process recreation remains outside that focused qualification.

Preserved concurrent `ba775246` voice follow-up: final answers survive arbitrary-width inline-code delimiters, numeric inch marks and initial fences, while hidden reasoning remains bounded. Accelerator qualification compares exact validated totals and the exact ten-percent threshold; display rounding does not authorize a backend. Existing three-fixture protocol, measured execution receipts and conservative non-qualification remain unchanged. Incoming actual-source evidence covers 63 parser and 12 Auto-policy cases; full integrated CI remains required.

Combined validation after `ba775246` passes 616 canonical host tests, all 49 changed Kotlin files, generated inventories, resources, native provenance and the exact 672-path release scope. This checkpoint is ready for draft integration only; the historical CI regressions and active external feature packages remain open, with no merge/publication approval inferred.
