# Gateway, task verification and retention follow-up — November 6

Repository: `tailscale-signin/GPT_Mobile_AI-improved`  
Branch: `fix/diagnostic-workflow-evidence-2026-11-06`  
Parent repair: [Diagnostic workflow and evidence repair](diagnostic-workflow-evidence-2026-11-06.md)

## Evidence boundary

The second review identifies build 139, version 0.9.66.5, at `69309b332c0d25b6721a2d7befbc04f49b804a3b`. Git data identifies that SHA as the merge build of this branch at `2ea2e8b9ff14b0ea74fe72bc26858fbeb2bcbf55`, with source tree `4492387151820ba023d2e28cf8112e1166e9fbd7`. It is feedback on the first implementation, not evidence that the first patch already resolved these symptoms. The subsequent `2b6d6592` revision contains the schema/provenance regression follow-up.

The supplied analysis, rather than the underlying JSONL or Windows server log, is the diagnostic input available here. Its seven run outcomes, 21 transport failures, three capacity failures, seven disk-read warnings and frame measurements are retained-evidence observations. This source audit does not convert them into independent defect counts or attribute every stall to blur.

The existing acceptance/evidence stores, durable SQLite gateway jobs, readiness endpoints, bounded diagnostic exchange, replay budgeting and rendering guards remain the foundations. The changes below repair their actual integration. No authorization policy, selected execution profile, result-storage ceiling, accepted tool-call budget or merge gate is relaxed.

## Confirmed source defects and changes

### Run completion and acceptance

`ChatRepositoryImpl` previously emitted `taskVerification=NOT_MEASURED` even when `GitHubVerifiedSession` had produced a verifier verdict. A generic verification failure with no dispatch flag also became `UNKNOWN_OUTCOME`, without evidence of an uncertain mutation.

The delivered acceptance verdict now has a compact receipt bound to its run ID, ownership fence, chat, message and response hash. Persistent runs retain that receipt through the protected workspace checkpoint store. The terminal transition validates the exact receipt before accepting COMPLETED. After restart, durable receipt existence still establishes the verification requirement even when its encrypted payload is unavailable. Terminal diagnostics then retain required/verified criteria and unresolved remote outcomes. General chat remains unmeasured when no task verifier ran. Missing evidence, a changed owner or response, incomplete retention and unresolved effects cannot become verified success.

The general acceptance path previously required an implementation commit and CI even for non-code operations. Operation-specific live readback can now establish simple branch create/delete and issue/PR create/update/close/reopen requests. This path must consume the entire supported request; unrecognized trailing requirements cannot disappear from acceptance. Compound code changes, explicit build/test requirements and merges retain their stronger requirements. An HTTP response, model answer or accepted mutation still does not establish end-to-end completion.

### GitHub requests, circuit state and graph fallback

Action-specific validation now rejects missing or contradictory fields before dispatch, including PR mutation requirements and release selectors. Repository/ref input is normalized before validation. Bounded structured field guidance from GitHub validation responses remains separate from credentials and arbitrary response text.

HTTP 408 after a write remains an uncertain remote outcome requiring reconciliation. It is not classified as a definite rejection merely because it is a 4xx response. Deterministic validation, authorization and missing-resource responses do not receive automatic transport retries.

Native mutation fences use the operation's actual payload fields. Changing a selected workspace ref or a presentation option cannot authorize another identical comment or PR write after a lost response. Disposable branch fixtures can preserve the selected workspace while still verifying their exact remote ref and head.

The concurrent repair at `3ef12e8798f7ff11fe7e18e1761b254d63e70c27` adds bounded, credential-local negative ref observations and explicit refresh recovery. A rejected exact ref blocks dependent requests during its cooldown without substituting the default branch. Branch dependency inspection resolves the branch before fanning out into PR and workflow reads. Tag inspection bounds commit-file projection and supplies an exact continuation instead of embedding every patch.

The circuit tracks consecutive transient failures. An observed HTTP rejection establishes reachability and clears an earlier transient streak; a local validation failure or failed TLS handshake does not establish a successful half-open probe. Failed probes release their slot without pretending the service recovered.

When a graph read fails because its transport is unavailable, the existing authorized status probe supplies a bounded native fallback. Other core branches, trees, commits, PR and check reads are offered as explicit bounded alternatives; the fallback does not claim those operations already ran. Graph-specific semantic or dependency coverage remains unavailable rather than being fabricated. Exact identities, paging bounds and authorization still apply. Branch-only arguments normalize refs/heads/ and reject tag refs; generic ref and commit selectors retain branch/tag/SHA semantics. An explicitly missing ref propagates its failure instead of silently falling back to the default branch.

### Result retention and upgrade recovery

A returned tool operation could previously be replaced with `RETAINED_RESULT_CAPACITY` when its display fragments exceeded the 4 MiB presentation cache or its source exceeded 1 MiB. The evidence book also retained large original bodies in its active 16 MiB projection, and completed execution receipts shared an evictable cache with ordinary reads.

The display tier now uses a bounded compressed source representation and reconstructable, hash-verified pages when expanded fragments would exceed capacity. A presentation-storage failure preserves the already observed operation outcome and retained source; it does not turn a completed remote operation into a transport failure. Real remote pagination remains explicit.

The evidence tier keeps exact structural identifiers, provenance and bounded source excerpts in active context. Large originals are stored as independently hashed chunks behind stable identifiers, with whole-record integrity and ordered retrieval. Missing or corrupted chunks invalidate completeness. Release projections keep the full original response and asset inventory, including assets beyond the previous display cutoff.

Execution receipts receive protected storage and a pre-dispatch reservation inside the existing disk allowance. Protected evidence and unresolved write fences are not LRU read-cache entries. Capacity is rejected before a new write claim is created. Receipts from the previous layout are identified by their existing publication fences and migrated before ordinary cache pruning, so an upgrade does not abandon interrupted jobs. Cancellation immediately after a response cannot escape the persistence boundary.

### Gateway connection and job ownership

The established `/gateway/live` and `/gateway/ready` contracts remain the health endpoints. Readiness distinguishes a responding gateway from model availability and durable storage readiness. The dashboard now distinguishes connected, degraded, reconnecting and offline states, retaining saved measurements with their freshness limits.

Dashboard polling follows the Android route state and a successful readiness observation. It pauses while offline, deduplicates refreshes and uses bounded backoff. Safe capability/readiness/result reads share a single transport-retry owner with jitter and request-specific deadlines. EOF/reset evidence invalidates idle pooled connections and requests a fresh connection. Mutation and inference requests do not acquire automatic replay through this policy.

A retained legacy disconnect handler could cancel an interactive worker after its 20-second orphan grace even when the v14 job was already committed to SQLite. Server-owned durable admission now controls that decision. A committed job continues under its original execution deadline and explicit cancellation controls when the Android subscriber disconnects. Failed or missing durable admission retains the legacy cancellation boundary.

### Selected-model capacity

A catalog context limit is not server confirmation. The client now uses the selected gateway capability handshake when local metadata is unavailable, binds observations to the requested model, and distinguishes confirmed server limits from catalog estimates and missing values. Readiness and context evidence do not substitute a different model or broaden the profile's authority. The requested output allowance remains separate from measured token usage.

### Correlated client/server diagnostics

Previously, client exchange records were primarily emitted after HTTP headers. Broken pipes, connect timeouts and EOF before headers could therefore leave request attempts without terminal evidence. The server middleware also emitted a synthetic HTTP 500 response in `finally` even when no response headers had been sent.

Each physical attempt now retains its actual trace/span and request identity, bounded route/method, monotonic start time and whether response headers arrived. Transport failures and cancellations receive correlated terminal records without raw exceptions, URLs, request bodies or credentials. A body EOF after HTTP 200 remains a transport failure. Typed HTTP rejections do not become socket failures, and TLS failures are not advertised as retryable.

Gateway response evidence now follows completed ASGI sends. A pre-header failure has no invented HTTP response. Headers sent and a completed body are separate fields; neither is presented as proof that a remote mutation or model task completed. Engineering requests use the existing opt-in bridge, and its unified client/server session export retains these fields. An undelivered request can have client-only evidence; no server event is invented to satisfy a traceability target.

Capture is still opt-in and paused for temporary conversations. Revoking capture strips prepared diagnostic headers and the request ID generated by that capture at physical dispatch. Allowlisted route/method enums, hashed identities, bounded retention, session expiry and privacy rechecks apply on both sides of the exchange.

### Android IO and rendering

GitHub workspace preference creation and reads now occur behind suspend IO boundaries. Session updates remain serialized. The credential vault and repository state store defer Android directory resolution until their IO operations, including deletion. Startup registration also defers diagnostic and quota directory lookup while preserving the quota's explicit not-ready admission gate; moving the lookup does not admit requests before saved limits load.

Startup diagnostic preference restoration checks the revision it read, so a delayed saved value cannot overwrite a newer tracking choice. Free-provider rejection still appears immediately, while both preflight diagnostic writers persist on IO. Their captured event time preserves the chat-deletion revocation boundary when a queued write resumes later.

The chat backdrop now suppresses blur while messages are generating or the list is scrolling, using the existing material fallback and frame-pressure guard. Stable message keys, off-main message grouping, bounded streaming publication, remembered Markdown/progress projections and scroll anchoring remain in place. These changes complement the first repair's off-main GitHub preview parsing and reduced historical animation.

A new `gatewayReconnectRendering` Macrobenchmark exercises the production gateway status content through offline, reconnecting, degraded and connected presentations. It is explicitly a rendering fixture with no socket or account access. Existing long-chat scrolling, streamed response and keyboard benchmarks cover the related conversation scenarios. Compilation of a benchmark is not a measured frame result.

## Disposition of the second review

| Review area | Disposition and verification boundary |
|---|---|
| Four incomplete and two failed runs | Exact acceptance receipts, failure-stage diagnostics and resumable evidence are repaired. Historical outcomes are not rewritten. |
| Completed run with `NOT_MEASURED` | Actual delivered verifier evidence now reaches run-terminal diagnostics. Response completion remains distinct from task acceptance. |
| `UNKNOWN_OUTCOME` | Requires an actual unresolved effect or classified unknown outcome; verified read-only partial work is not mislabeled. Uncertain writes reconcile before replay. |
| `CIRCUIT_BREAKER_OPEN` | Consecutive transient streaks, observed-response recovery and unsuccessful half-open probes have explicit behavior regressions. |
| 21 gateway transport failures | Bounded safe-read recovery, stale-connection handling, readiness-aware polling, request correlation and durable job ownership are repaired. The original Windows process/network root cause remains unobserved. |
| Four validation failures | Local action schemas and bounded field guidance are strengthened. Deterministic errors remain non-retryable. |
| Two not-found/inaccessible errors | Repository/ref normalization and actionable status handling preserve distinct authentication, authorization, absence and validation evidence. Source changes cannot grant access to an unavailable repository. |
| Two graph outages | Core supported inventory reads have bounded native alternatives; graph-only semantics remain unavailable. |
| Three retained-result failures | Compressed display pages, bounded evidence projections, durable originals and protected write receipts address confirmed capacity mechanisms without increasing ceilings. |
| Skipped frames, blur and seven disk-read warnings | Concrete directory/preference IO and active blur paths are repaired; repeatable rendering scenarios are available. Attribution of every recorded frame requires device traces. |
| Two catalog cancellations | Existing lifecycle cancellation ownership and cached catalogs are preserved. Cancellation is not automatically a connection defect or retry instruction. |
| 112 intentional tool omissions | Policy restrictions remain enforced; the acceptance/capability matrix does not count omitted capabilities as executed. No broad tool exposure is added. |
| 214 dispatched execution records | Operation receipts, terminal acceptance and causal IDs remain distinct. A dispatch observation alone does not establish an unfinished job. |
| 27 client exchanges and disabled gateway debug | The opt-in unified exchange now includes failed physical attempts and honest delivery state. Debug capture is not silently enabled. |
| Unconfirmed context size | Selected-model server observations are distinguished from advertised/catalog capacity and requested output budgets. |
| 100–256 tool calls and 16 MiB results | Existing accepted ceilings remain; verification reserves, durable projections and pagination reduce pressure. |
| Native engine restart and finalizer warning | No reproducible leak is established by the supplied evidence. Existing lifecycle/memory diagnostics remain available; no speculative ownership rewrite is claimed. |

## Validation and release qualification

Focused suites cover transport delivery before/after headers, capture revocation, cancellation ownership, HTTP/TLS classification, durable disconnect behavior, strict argument validation, graph degradation, protected result capacity and migration, oversized evidence, response persistence during cancellation, and run/message ownership. Thread-boundary tests reject cold Android directory/preference access on the caller thread and verify that deferred quota restoration remains closed to dispatch.

The new source must pass current-head Android compilation, the complete unit and constrained-heap suites, lint, repository/gateway contracts and the canonical `Required verification` aggregate before merge. Results for `2b6d6592` qualify the first patch only; they are not reused as validation of this expanded patch. The PR and engineering checkpoint retain the exact source SHA and final CI receipts.

Physical targets remain explicit release qualifications: below 1% jank in the named benchmark scenarios, zero application-caused StrictMode disk violations under those scenarios, restored connectivity to the affected port-8090 deployment, and a fully verified authorized repository change through the running app. Run the Macrobenchmark module on the affected device, retain its Perfetto/frame artifacts and compare the same scenarios before and after. Use controlled disconnected-gateway, process-restart, exhausted-storage, inaccessible-repository and CI/merge fixtures for end-to-end release qualification. Source and emulator checks do not substitute for those measurements.

The existing `scripts/qualify_gateway_github_performance.py` evaluates physical evidence bound to the tested source SHA, APK/device identity and workload conditions. It requires actual deadline-relative frame samples and rejects incomplete evidence; no synthetic benchmark result is supplied by this repair.
