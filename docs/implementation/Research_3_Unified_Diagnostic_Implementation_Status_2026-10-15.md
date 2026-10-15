# Research 3.0 and unified diagnostic reliability implementation

Date: 2026-10-15. Branch: `feat/unified-diagnostic-reliability-2026-10-14`.
Base: `91b497f58824c7cdf4d624e46ce10532342d5cd3`.

This implements the subsequent user request against the [October 14 design](Unified_Diagnostic_Reliability_Design_Implementation_2026-10-14.md). The design itself remains a historical specification. This record distinguishes changed code, protections already present on the base, and validation still required. No version bump, database migration, merge or release is included. Report detail is excluded from the legacy collection-policy identity, so changing presentation cannot reset spent budgets or strand retained evidence.

## Research delivery

Collection remains owned by `ResearchSessionStore`, its journal and the shared ledger. Presentation now uses `ResearchDeliveryPacket`, `ResearchDeliverySession` and `ResearchDeliveryValidator`; there is no second research database or scheduler.

The packet selects whole source/claim/quotation units, ranks task relevance, study type and publication metadata, and reserves representation for recent, contradictory and unread findings. Retracted and unrelated candidates are excluded by a conservative lexical filter; this filter is not semantic verification. The journal retains the complete corpus. Omission counters describe the selection budget, and metadata-only records cannot support findings. Claim quotes must occur in the retained source passage. Appraisal fields require matching source version, body chunks, passage references and quote hashes.

Brief, Detailed (default) and Comprehensive settings target 300–600, 1,200–1,800 and 2,000–3,000 words respectively, only when evidence supports that detail. Eight sections cover executive findings, background, leading studies, latest papers, comparisons, safety/limitations, gaps and references. Study instructions require authors, dates, journal, design, enrolled versus analyzed participants, comparator, numerical outcomes, uncertainty and limitations; unavailable details stay unknown. Issue dates and online dates must remain distinct. Different interventions and populations must not be conflated.

Final research text is buffered while tool progress remains observable. Citation IDs, reference URLs, report structure, leading-study inclusion, numerical presence in the cited evidence, partial coverage and review disclosure are checked before display. One tool-free repair is allowed, with a 45-second deadline and bounded buffers. A failed repair produces captured passages and explicit gaps. Cancellation and authorization failures are not silently retried. Explicit native research also validates evidence acquired inside the native tool round. Ordinary chat retains streaming.

These deterministic checks **do not prove entailment or that a numerical value belongs to the claimed outcome**. `semanticCoverageVerified=false` remains explicit. Independent scientific review is reported only when recorded. A second model agreeing with the first is not source verification. Device/provider end-to-end checks remain necessary.

The existing citation card now expands stored study fields and design. Citation binding runs away from the main dispatcher. Source-version identity and original-paper navigation remain in the existing binding/archive owners.

### Hydrogen acceptance case

The supplied export was inspected locally; it is not committed. It contains 334 sources, 34 claims, `complete=false`, and `Partial coverage`. The captured sleep trial is readable. The perioperative surgery DOI ending `61287-y` has failed retrieval, with `pdf_unsupported_without_gateway`. The new packet retains the sleep trial among leading findings and the surgery paper as an unread lead. No clinical numbers from the pasted recommendation are hardcoded into production or fixtures.

Synthetic tests cover readable/unread separation, recent failed-study retention, retractions, unrelated results, fabricated quotations, short unsupported numerical claims, references-only omissions, atomic budget selection, one repair, repair timeout, and native evidence acquired during the round.

## Diagnostic workstreams

| Design requirements | Code disposition and boundaries |
| --- | --- |
| R01, R03 | Reuse existing artifact inspection, installation epoch, admitted engine specification and Local Assist preparation. Unknown capacity still skips optional inference safely; a profile value never supplies fabricated artifact capacity. Existing backend/quarantine controls remain authoritative. |
| R02, R04 | `LocalOutputAllocation` subtracts required input, schemas and safety/result reserves using Long arithmetic. `LocalContextPlanner` reduces requested output before retaining optional history/tools. LiteRT now passes and reports the resulting effective cap. Native engine creation retains its existing context guard. Real tokenizer/backend boundary tests remain required. |
| R05–R06 | Direct greetings can bypass optional discovery even with history. Contextual vault loading, cached tool resolution and optional recall share the remaining preparation deadline. On expiry, an authorized discovery facade defers cold tool lookup. This behavior does not require idle warming to be enabled. Mandatory attachment/research/recovery work retains required preparation. |
| R07 | Initial ordinary catalog: at most 8 tools / about 2,000 schema tokens. Explicit advanced tasks: 16 / 6,000. Discovery keeps the executable authorization set separate from advertised schemas. |
| R08–R09 | Preserve existing readiness snapshots, single-flight metadata caching, epoch invalidation and nonblocking provider metadata. Request context uses conservative accepted limits. No invisible model switching or additional idle network permissions. |
| R10–R12 | Existing provider stream guards now admit at most 8 call identities; runner defaults are 8 per round and 6 concurrent. Standard sends have a 24-call ceiling; explicit research may use 64. Existing oversized-batch escape/finalization and action deduplication remain. Cancellation speed depends on provider transport. |
| R13 | Existing immutable dispatch authority is retained. A per-collection fence permits one preparation rebuild on `DISPATCH_POLICY_CHANGED`, only before any physical inference or tool attempt. Physical and tool boundaries permanently close that fence; completed actions cannot replay. |
| R14 | Retain existing typed tool/admission/search outcomes. Built-in search adds explicit DNS/timeout versus parse-or-transport messages. Not every third-party provider exposes enough detail for a more specific classification. |
| R15–R18 | DuckDuckGo challenge/HTTP/parser failure is no longer a successful empty search. Explicit no-results markup is distinguished from malformed content. Aggregate cache keys normalize query whitespace/case while retaining owner, filters, settings and authorization checks. Valid empty results can be reused briefly; errors and partial failures cannot. Three distinct valid empty attempts pause that engine for 30 seconds, without asserting an outage; selected siblings can still return evidence. |
| R19–R21 | Existing question planning and DOI/version identity are retained. Title/abstract relevance is checked before acquisition. Extraction requests separate methods, numerical results, null/adverse findings and limitations. Structured packet selection replaces the small passage-only handoff. Reviewed, independent, readable and discovered counts remain separate. |
| R22 | Existing generation fences, saved stages, completed query identities, work leases and durable evidence remain. Cancellation records distinguish deleted chat, superseded generation, explicit stop and parent cancellation. The parent fallback is not relabeled as user intent. |
| R23 | Shared physical work has safety ceilings of 2,400 requests, 512 MiB and one active hour, even when user limits are absent. Discovery yields at 75% of request/byte/time allowance to preserve review work. Extract/review uses verification allowance. Resume carries spent reservations; gateway allowances are bounded by remaining parent counters. |
| R24 | Combined evidence retains all validated claims in the frozen corpus and uses the same ranked packet for presentation. Existing URL-based contribution attribution and source-version binding remain. A reference-list entry alone cannot satisfy leading-study body coverage. |
| R25–R27 | Existing Europe PMC XML, arXiv HTML and configured PDF gateway remain. Known Nature/MDPI PDF routes can use same-publisher HTML through the existing permission, domain and request-budget checks. Unsupported routes stay unread. Disabled engines and pending credentials do not become authorized. |
| R28–R31 | Existing MCP negotiation, health/cooldown, reused sessions and execution ownership remain. Authentication-required and unreachable connections are excluded from cached advertisement. An unchanged auth-required configuration cannot immediately reappear via cached discovery. Reauthorization stays user initiated. Real OAuth/DNS/protocol fixtures are still integration gates. |
| R32–R33, R35 | Replay derives from accepted context minus input/output/margin, with a conservative 32,768 context ceiling and 24,000 replay ceiling. Zero headroom grants zero replay. Whole tool results are retained when they fit, instead of always applying a small per-result excerpt cap. Default planning output reserve matches the 4,096 effective default. |
| R34 | A maximum 6,000-character continuation manifest carries run/generation identity, last committed answer segment, overlap hash, coverage counts and resource references. Bulk receipts stay in the existing authorized `read_recovery_context` reader at offset 0. Saved tool-effect reconciliation and no-repeat behavior are retained. Models without the reader receive a bounded view and an explicit omission warning. |
| R36–R38 | Existing unique per-message/hash/scope work is retained. Queue resumption observes foreground-work, open-conversation and benchmark admission transitions; it does not schedule while those owners are busy. Terminal inference failure waits for a changed runtime fingerprint or explicit retry, so completion events cannot recreate a 15-second loop. Memory generation uses the shared safe output allocator. Enrichment distinguishes processed-with-no-new-facts from writes/updates, and policy/source skips from successful extraction. |
| R39–R42 | Existing frame/Markdown/first-draw instrumentation remains. Presentation updates have a 33 ms minimum, with existing larger-message throttling. Packet building, checks and citation binding use background dispatchers. No claim is made that these changes resolve measured device jank or IME warnings; physical-device profiling and composer interaction tests remain open. |
| R43 | Signal-9 historical exits are explicitly labeled `UNKNOWN_CAUSE`; only OS low-memory reasons are labeled as such. Existing RSS/PSS/native lifecycle telemetry remains. The export does not prove an OOM or native crash. |
| R44–R48 | Existing run journals, correlation IDs, request/timing milestones, redaction and privacy controls remain. Added delivery diagnostics report structural results without semantic-verification claims. UI statuses explain report checking and policy rebuilding. Answer completion and research completion remain separate. |

## Verification record

Local verification uses Kotlin 2.1.20 with production pure-Kotlin components and minimal stubs for unavailable Android/runtime dependencies. This is an isolated JVM check, **not** an Android build or complete application test. The final isolated run passed **70 tests**, covering allocation, planner/catalog selection, shared ledger/resume budgets, packet delivery/repair, continuation bounds and stream presentation. Android CI status is tracked on the draft PR.

Completed preflights: resource validation (28 XML files), Room schema validation, Android regex compatibility, and 116 Python repository script tests. These script fixtures simulate device-validation outcomes; they are not device instrumentation results. Changed Kotlin files are formatted with ktlint 1.3.1.

The local Gradle invocation cannot download Gradle 9.8.0 (`Network is unreachable`); this environment has Java 17 and no Android SDK. Full JDK 21 Android unit/lint/build validation is requested through the repository PR workflow. No dependency-verification relaxation or SDK/version change is used to bypass that gate.

The first complete Android unit run on `1174813` compiled the app and executed 3,003 tests: 3,001 passed, with two failures in fixtures using the old collection-policy identity and unlimited-budget assumption. Those fixtures were updated to check presentation-independent continuation, citation-valid final delivery, long counter persistence and the non-refundable safety stop. This run is not a passing gate; the final commit must pass the PR checks.

### Acceptance mapping

| Tests | Verification status |
| --- | --- |
| T01–T03 | Isolated allocator/planner boundary tests; native/device confirmation pending. |
| T04–T06 | Existing artifact/backend/assist protections retained; real artifact tests pending. |
| T07, T09, T11–T12 | Isolated planner/catalog tests; end-to-end preparation timings pending. |
| T08, T10 | Deadline/epoch integration coded; lifecycle and network timing tests pending. |
| T13–T15 | Stream admission boundary fixture and existing runner storm fixtures; provider timing remains pending. |
| T16–T18 | Isolated one-rebuild/no-replay fence test; live revocation/uncertain-action tests pending. |
| T19–T21 | Existing health/auth controls plus cached-advertisement exclusion; service/device integration pending. |
| T22–T24 | Search fixtures include normalized reuse and 28-query empty cascade; full Android unit execution pending. |
| T25–T27 | Synthetic packet/relevance/quote fixtures plus private-export selection check; no semantic-verification claim. |
| T28–T31 | Existing resume/stop ownership and added cancellation provenance; multi-child interruption integration pending. |
| T32–T33 | Pure same-publisher route fixture; real allowed/disabled network routes pending. |
| T34, T36 | Pure replay-budget tests; complete provider serialization and repeated-compaction integration pending. |
| T35 | Pure bounded-manifest fixture; existing reader/reconciliation tests remain integration gates. |
| T37–T39 | Queue/result changes and existing source/scope checks; WorkManager/device execution pending. |
| T40–T42 | Open device gates: 1,000-message streaming, heavy report scrolling, focus/IME/send/back/rotate. |
| T43 | Classification code changed; historical Android exit fixture/device validation pending. |
| T44–T48 | Existing epochs, attribution, redaction and native cleanup retained; complete regression matrix pending. |
| T49 | Pure shared-ledger safety/resume tests; gateway/native mixed-owner integration pending. |
| T50–T51 | Bounded delivery repair and evidence fallback fixtures; complete mode matrix pending. |
| T52 | Open: clean-cache strict Android build, native size/dependency report and artifact validation. |

## Remaining work before merge

1. Complete Android compilation, unit tests and lint; resolve any failures on this branch.
2. Execute provider/MCP/OAuth failure fixtures and device/native cancellation, WorkManager, jank and IME tests above. Record measured before/after timings rather than treating fewer warnings as proof.
3. Review scientific claim entailment independently when available; deterministic delivery checks must continue to state their limitations.

The branch remains a draft until these gates are satisfied. This implementation record is not release approval.
