# Smart Readiness
## Predictive preparation and low-latency remote chat

Design and implementation specification | Version 1.0 | October 14, 2026

Repository: tailscale-signin/GPT_Mobile_AI-improved

Verified source baseline: main at 91312450f864da17572ec925e2af84683504b4c1

Baseline application: 0.9.52.0, versionCode 121; dev.melo.gptmobile.improved. The GenieX package is a separate build configuration. [R01](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/91312450f864da17572ec925e2af84683504b4c1) [R02](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/build.gradle.kts#L36-L180)

Prepared for Emanuel Melo.

**Status:** implementation-ready design, not implemented or device-validated. Source wiring was reviewed against the pinned revision. No branch, pull request, release, or live performance result was created by this document.

**Core rule:** prepare likely dependencies early, but never make optional preparation a prerequisite for sending a valid remote chat request.

---

# Document map

This specification separates verified code behavior from proposed changes. Existing classes retain their names; new types and methods are explicitly designated as proposed. Timing values are acceptance targets, not observations from the phone.

| Part | Contents |
|---|---|
| Foundations | 1. Goals and invariants; 2. Verified baseline; 3. Required runtime behavior |
| Architecture | 4. Ownership and dependencies; 5. Lifecycle; 6. Snapshot validity |
| Subsystems | 7. MCP cache; 8. Lazy discovery; 9. Memory; 10. Draft prediction; 11. Routing |
| Execution | 12. Send pipeline; 13. Provider networking; 14. Queue, combined mode and benchmarks |
| Product integration | 15. Scheduling; 16. Settings and UI; 17. Migration and rollback; 18. Diagnostics |
| Delivery | 19. Implementation work packages; 20. Acceptance tests; 21. Performance and CI; 22. Integration review |
| Evidence | 23. Source register and implementation handoff |

**Meaning of “verified.”** A source review can establish call ordering, existing interfaces and conditional waits. It cannot establish the precise cause of the reported approximately 20-second “Hi” delay without a correlated runtime trace. Nor can a design review certify perfect future wiring: release acceptance requires the tests and evidence defined here.

**Scope boundary.** This is an additive Kotlin orchestration and caching feature. It does not replace the agent runner, memory engine, local inference service, tool authorization system, transport protocols, or selected remote AI profile. It must not add a new large model, native runtime, always-on service, or analytics SDK.

# 1. Goals, constraints and non-negotiable invariants

Smart Readiness should prepare authorized MCP catalogues, local memory infrastructure, selected-profile configuration and useful networking state while the app is visible. Preparation starts after the interface becomes usable, intensifies when a chat/profile is selected, and yields when the user submits a message. A cold start must still have a correct, bounded send path.

The feature optimizes **tap-to-request-dispatch** and **first-provider-text-to-visible-text**. Provider queueing, generation and actual time to first token remain separate measurements. No local optimization can guarantee a remote server's response time.

| ID | Required invariant |
|---|---|
| INV-01 | Optional warmup never gates an otherwise admissible direct chat request. No “all dependencies ready” barrier. |
| INV-02 | Persist accepted input and run ownership before inference; preserve durable queue, cancellation and duplicate-dispatch protections. |
| INV-03 | Keep destination, source permissions, privacy, spending, capacity and required-work checks at their existing authoritative boundaries. |
| INV-04 | Pre-send draft processing is on-device and read-only. No draft text, embeddings, query-derived results or memory content goes to a provider or MCP server before Send. |
| INV-05 | A cached schema is not an execution grant. Revalidate tools and retained memory at use and dispatch. |
| INV-06 | Do not trim history, replace it with summaries, lower response limits, disable reasoning, change models or skip explicitly required research merely to improve speed. |
| INV-07 | Warmup performs no generative-model inference, benchmark, tool action, research run, purchase, write, memory capture, location read or health-data read. |
| INV-08 | Temporary chats, scope exclusions, cloud-recall restrictions and revoked facts remain effective through every cache, helper, retry and combined response. |
| INV-09 | Only one generation owner dispatches. Readiness produces reusable data, never a competing chat request. |
| INV-10 | No warmup in the :local_inference process, no new foreground service, and no promise of always-warm sockets after Android stops the app. |

**Success definition.** With all optional dependencies slow or unavailable, a new bare greeting in a standard single-profile remote chat still reaches provider dispatch without awaiting them. Required dependencies instead receive honest status and their normal policy-controlled behavior. [R03](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/GPTMobileApp.kt) [R04](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/AppForegroundTracker.kt) [R05](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/NetworkModule.kt) [R06](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/NetworkClient.kt#L30-L99) [R07](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/queue/DurablePromptQueue.kt) [R08](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/InferenceAdmission.kt)

# 2. Verified baseline and corrections to the initial concept

The baseline below is pinned to the verified main commit. Conditions matter: configured timeout ceilings are not measured elapsed times, and parallel operations do not necessarily add together. [R01](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/91312450f864da17572ec925e2af84683504b4c1)

| Finding | Existing wiring and significance | Design response |
|---|---|---|
| OpenRouter metadata can gate Send | ChatRepositoryImpl.completeChat awaits fetchModels(). The repository uses blocking HttpURLConnection, a mutex, and 4-second connect/read timeouts. The comment saying failed refresh never blocks chat does not make the call asynchronous. [R09](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L1428-L1446) [R10](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/OpenRouterModelRepository.kt#L24-L78) | Cache-only access on Send; shared, cancellable refresh elsewhere. |
| MCP resolution precedes primary streaming | AgentToolResolver resolves remote connection groups concurrently, then awaits all groups. The slowest admitted group can hold the turn. [R11](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AgentToolResolver.kt) | Separate catalogue acquisition from run-bound tool construction. |
| MCP caching is incomplete | Legacy sessions cache tool catalogues for five minutes. Modern transport caches protocol discovery, but listTools() calls paginated tools/list; its schemas map is not a full freshness-controlled catalogue cache. [R12](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt) [R13](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/ModernMcpTransport.kt) | One catalogue cache above both transports, preserving their protocol logic. |
| Connection ceilings are substantial | Legacy initialization is 15 seconds; modern discovery uses local/remote budgets and ordinary RPCs can run to 60 seconds. Transport failure must not be reinterpreted as evidence of legacy compatibility. [R12](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt) [R13](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/ModernMcpTransport.kt) | Stop discovery from gating ordinary Send; retain correct negotiation and request cancellation. |
| External memory can delay dispatch | ConnectedMemoryRecall runs selected external lookups within a 15-second outer timeout, before primary streaming. [R14](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/ConnectedMemoryRecall.kt#L72-L133) [R15](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L1576-L2099) | Query-specific remote recall only after Send and only when needed; no speculative external memory queries. |
| Local memory is not a generic cache | prepareTurn includes capture, persistence and recall under a mutex. LocalSemanticMemory runs native embedding and index access on a dedicated owner thread. [R16](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/FactVaultRepository.kt) [R17](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/LocalSemanticMemory.kt#L25-L146) | New read-only preview path; split capture from recall; preserve native thread affinity. |
| Readiness UI is not actual warmup | ChatViewModel.loadAvailableChatTools builds availability from stored connections/bindings; it does not itself establish all MCP sessions. [R18](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt) | Explicit producer/consumer contracts for warmed catalogues. |
| Benchmark admission is a hidden candidate | InferenceAdmission.shared can wait up to 60 seconds for benchmark exclusivity; completeChat uses sharedFlow. This is conditional, not proof of the reported incident. [R08](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/InferenceAdmission.kt) [R19](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L2081-L2507) | Emit admission-wait status and timing; keep isolation unless a separately tested policy changes it. |
| Startup already has recovery ownership | GPTMobileApp has StartupRecoveryGate, lazy providers, AppForegroundTracker, native-process exclusion and trim/idle work. [R03](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/GPTMobileApp.kt) [R04](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/AppForegroundTracker.kt) | Reuse these rather than introduce an independent lifecycle manager. |
| Network pooling already exists | NetworkModule supplies singleton NetworkClient(OkHttp). The client already configures a connection pool. Constructing it does not itself perform DNS, TLS or model warmup. [R05](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/NetworkModule.kt) [R06](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/NetworkClient.kt#L30-L99) | Reuse the actual provider engine; do not add duplicate pools per chat. |
| Alternative send paths exist | DurablePromptQueue dispatches without ChatViewModel; retries and combined synthesis have their own persisted inputs. [R07](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/queue/DurablePromptQueue.kt) [R18](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt) | Enforce runtime planning inside the common repository/agent path, not solely in UI callbacks. |

**Corrections that must survive implementation:** Dispatchers.IO is not a priority scheduler; supervisorScope still waits for its children; a timeout does not forcibly interrupt arbitrary blocking/native work; cached connections and query-specific memory are different assets. [E01](https://kotlinlang.org/docs/coroutines-cancellation.html) [E02](https://kotlinlang.org/docs/exception-handling.html) [E03](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html)

# 3. Required behavior from home screen to response

| Situation | Prepare in advance | Send behavior |
|---|---|---|
| App visible, no chat open | Restore valid schema metadata; initialize shared client; load enabled memory infrastructure; prioritize last-used enabled profiles. | No chat or inference is created. |
| Profile picker visible | Reprioritize the selected 1–2 profiles and their authorized connections. | Preserve the selected model and route. |
| New chat opened | Resolve settings/privacy scope, subscribe to memory revision, prepare reusable prompt/history metadata. | Unknown scope is not permission to use personal memories. |
| Existing chat opened | Load the correct history window/recovery state and scoped memory candidates. | Preserve complete fitting context and pending task state. |
| Meaningful draft typed | Optional debounced, local-only intent and memory preview. | Reuse only an exact revision match; otherwise discard. |
| Bare greeting submitted | Use ready settings/context; do not request optional network discovery or semantic cold-start. | Persist, authorize, dispatch one remote request and stream. |
| Explicit memory/research/tool request | Prioritize required capability; reuse schemas and safe prepared local data. | Do not answer from invented evidence or silently skip required work. |
| MCP unavailable | Retain valid metadata with a separate health label; back off refresh. | Unrelated chat proceeds; actual tool use reports its own failure. |
| App backgrounded | Stop speculative producers and clear draft previews; retain allowed inert caches. | Existing user-started generation follows its existing service policy. |
| Instant Send before warmup | Use the same common planner with a cache miss. | No dependence on having waited at the home screen. |

**No misleading shortcuts.** Do not display a locally fabricated “Hi” as though the selected remote model generated it. Do not start an early remote completion while the draft is still changing. Do not pretend memory arriving after HTTP dispatch was included in that already-sent request.

Preparation has two classes. **Query-independent preparation** covers schemas, identity snapshots and local index infrastructure; it can run before a prompt exists. **Query-dependent work** covers actual memory ranking and tool/research execution; only read-only local prediction may precede Send. Other work follows accepted input and its permissions.

# 4. Architecture, ownership and dependency injection

Introduce a proposed package `data/readiness/`. The coordinator manages speculation, while a stateless send planner makes per-turn decisions. Existing repositories remain the sources of truth.

| Proposed component | Responsibility | Must not depend on |
|---|---|---|
| ChatReadinessCoordinator | Accept lifecycle/selection hints; schedule bounded producers; expose StateFlow status. | ChatRepository, AgentRunCoordinator, a ViewModel or Activity. |
| ReadinessSnapshotStore | Atomic immutable snapshot publication; revision validation; bounded memory eviction. | Network calls or native inference. |
| ReadinessScheduler | Priorities, single-flight jobs, producer leases, resource admission, retry/backoff. | DurablePromptQueue ownership or generation dispatch. |
| AuthorizedMcpCatalogCache | Raw sanitized schema cache plus profile-specific authorized projections. | Cached run-bound tool objects or authorization grants. |
| MemoryPreviewCoordinator | Side-effect-free local preview, draft revisions, read leases and invalidation. | Remote provider/MCP clients. |
| SendPreparationPlanner | Compute DIRECT, CONTEXTUAL or REQUIRED_WORK plan from accepted input and immutable policy. | A callback that waits for all warm jobs. |
| ReadinessWorkloadSignals | Active foreground-send count, generation/native activity and benchmark exclusivity. | ChatRepository constructor dependencies. |
| ReadinessTrace | Correlate readiness, send, HTTP and display milestones with existing diagnostics. | Raw draft content or unredacted credentials. |

**Dependency direction**

```text
GPTMobileApp / HomeViewModel / ChatViewModel
                 -> ChatReadinessCoordinator
                 -> scheduler -> leaf warmup adapters
                 -> snapshot store

ChatRepositoryImpl -> SendPreparationPlanner -> snapshot store
                   -> existing authorization / context / tools
                   -> existing adapters -> existing NetworkClient

AgentRunCoordinator / InferenceAdmission -> workload signals
```

**Hilt contract.** Add `di/ReadinessModule.kt`; provide one qualified `@ReadinessScope` with a SupervisorJob, injected clock and dispatchers, scheduler, stores and leaf adapters. The application holds a Provider<ChatReadinessCoordinator>, obtained only after main-process/startup checks. ViewModels submit IDs/revisions, not Android components; UI collection remains lifecycle-aware. [E08](https://developer.android.com/topic/architecture/recommendations) Isolate failures inside each producer: SupervisorJob prevents sibling cancellation but does not consume uncaught exceptions automatically.

The coordinator must not inject ChatRepositoryImpl to “warm chat.” That repository already depends on leaf services; coupling it back through the coordinator risks a Hilt cycle and expensive eager initialization. Wire active-run signals outward from their owner instead. Verify this by constructing the production Hilt graph in both supported runtime configurations. [R02](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/build.gradle.kts#L36-L180) [R03](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/GPTMobileApp.kt) [R04](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/AppForegroundTracker.kt) [R05](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/NetworkModule.kt) [R06](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/NetworkClient.kt#L30-L99) [R07](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/queue/DurablePromptQueue.kt) [R08](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/InferenceAdmission.kt) [E02](https://kotlinlang.org/docs/exception-handling.html)

# 5. Lifecycle wiring and trigger rules

Reuse AppForegroundTracker instead of adding a competing process-lifecycle authority. Extend it with an immutable visibility StateFlow updated on lifecycle callbacks; preserve isBackgrounded for existing callers. Treat first usable main UI, not any auxiliary Activity, as permission to start home speculation. [R03](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/GPTMobileApp.kt) [R04](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/AppForegroundTracker.kt)

| Integration point | Proposed change | Failure/race requirement |
|---|---|---|
| GPTMobileApp.onCreate | Register the readiness observer once in the main process. Await recovery completion in the readiness observer, outside StartupRecoveryGate's own block. | Never make StartupRecoveryGate wait for warmup; no self-join. |
| MainActivity, usable content frame | Publish a one-time UI-ready hint after setup/privacy readiness and the first usable frame. | Do not hold the splash until MCP, memory or pricing completes. |
| HomeViewModel/profile picker | Send distinct selected/last-used enabled profile IDs and policy revision. | No repeated network work on recomposition or duplicate observers. |
| ChatViewModel chat-room/profile state | After room/settings resolve, publish chat-entry and membership changes. | A new chat's unresolved ID/scope cannot reuse another chat's memory. |
| ChatScreen/ChatViewModel draft observation | Publish committed text revisions only when local preview is enabled and the chat is visible. | No network requests; cancel/discard old draft results. |
| AgentRunCoordinator start/terminal | Increment/decrement independent active-work signals using finally cleanup. | Every terminal path releases its priority lease exactly once. |
| GPTMobileApp.onTrimMemory | Evict readiness-owned caches and request native-safe idle release. | Never unload a native engine or close a transport with active consumers. |
| Settings/credential/restore events | Advance typed epochs and invalidate affected snapshots immediately. | Old producers cannot republish across the new epoch. |

**Foreground session.** Observe visibility idempotently. Coalesce configuration-change stop/start into one session using a short cleanup grace period; defer resource-heavy work until scrolling/transition activity is quiet. App backgrounding stops new speculation immediately and cancels content-bearing preview producers. A later resume restores inert cache metadata and schedules only stale eligible work.

**Android background meaning.** Here, “background preparation” usually means asynchronous work while the app is visible, not an always-running Android service. Optional durable index maintenance may use existing WorkManager mechanisms under constraints. WorkManager is not a keep-alive mechanism for provider/MCP sockets and is not the direct Send scheduler. [E04](https://developer.android.com/develop/background-work/background-tasks) [E05](https://developer.android.com/develop/background-work/background-tasks/persistent)

# 6. Snapshot model, identity and invalidation

Use separate **metadata readiness**, **live connection health**, and **execution authorization** states. A single green READY flag cannot truthfully represent all three.

```text
Metadata: ABSENT -> LOADING -> FRESH -> STALE_USABLE
                          -> REJECTED / INVALIDATED
Health:   UNKNOWN -> CHECKING -> REACHABLE / BACKOFF / AUTH_REQUIRED
Permit:   UNRESOLVED -> ALLOWED / DENIED  (rechecked at use)
```

FRESH/STALE metadata may be useful when a transport is offline. Conversely, a reachable server may have revoked tools or changed schemas. Missing permission always overrides cache availability.

**Proposed immutable key fields:** connection UID; canonical endpoint with meaningful path/query preserved; opaque credential revision; selected profile/model/routing revision; tool binding and trust revision; memory policy and memory-generation revision; conversation scope/branch/recovery revision; serializer/schema version; process session; and network epoch where transport readiness depends on it. Raw secrets must not be keys, logs or disk filenames.

**Clock rules.** Use a monotonic clock for deadlines and elapsed time within a process. Persist wall-clock validation time and a boot/process marker, not a bare elapsedRealtime expiry. After process restart, treat disk entries as stale candidates requiring current permission validation; a clock rollback or unknown age cannot turn them fresh.

| Event | Required invalidation |
|---|---|
| Credential, endpoint or account changes | Cancel producers for old identity; remove corresponding health/session readiness; invalidate authorized projection. |
| Tool disabled/uninstalled or grant revoked | Immediately remove from projections and executable discovery. A network refresh cannot restore permission. |
| Memory deleted, edited, disabled, or cloud recall revoked | Advance memory/policy epoch; reject previews and retained facts built against the old revision. |
| Chat deleted, temporary flag or branch/scope changed | Drop chat previews/context snapshots; fence late callbacks. |
| Model or routing changes | Recompute capacity, serialization and profile plan; never reroute a queued accepted turn silently. |
| Network/VPN changed | Invalidate transport-health observations and route-bound sessions as required; schemas need not all be discarded. |
| Backup/restore, sign-out or reset | Quiesce speculation, rotate cache namespace, discard derived caches, restart only after recovery. |

Publication is compare-and-set against the producer's original epoch. Invalidation increments the epoch before asynchronous cleanup. Consumer validation and current source authorization remain necessary even when a cache key matches.

**Revocation linearization.** Define an atomic authorization/admission decision for each attempt and check its epoch immediately before handing bytes to the transport. Revocation before that decision rejects the attempt; revocation during queueing cancels or revalidates unsent work. Once bytes are transmitted, cancellation is best-effort and cannot retract information already received by a remote server. Do not claim otherwise or hold a policy lock across network I/O.

# 7. MCP catalogue cache: transport-independent design

Implement the cache above McpClientManager's modern/legacy selection. Both paths publish the same immutable catalogue format; neither creates a competing connection manager. Preserve existing protocol discovery, endpoint health and OAuth behavior. [R11](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AgentToolResolver.kt) [R12](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt) [R13](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/ModernMcpTransport.kt)

**Raw catalogue snapshot.** Store sanitized tool name, description, input/output schema, annotations, catalogue fingerprint and validation metadata. It is scoped to endpoint/account identity. Do not store live clients, coroutine jobs, callback closures, tool outputs, credentials or run-specific permissions.

**Authorized projection.** Derive a profile/chat-specific view by intersecting current global plugins, profile selection, bindings, per-chat disabled tools, read approvals, temporary-chat constraints, free-provider consent and tool-owner rules. Construct fresh McpAgentTool and ResolvedAgentTool instances for each run with live authorization callbacks. Run budgets, traces, broker scopes and call IDs cannot be shared through the cache.

**Proposed API contract**

```kotlin
// All types and methods here are proposed, not existing signatures.
interface ReadinessCatalog {
    fun peek(key: CatalogKey): CatalogSnapshot?
    fun requestRefresh(key: CatalogKey, reason: WarmReason): WarmTicket
    suspend fun acquireRequired(
        key: CatalogKey, deadline: Deadline, lease: ConsumerLease
    ): CatalogOutcome
    fun invalidate(identity: ConnectionIdentity, epoch: Long)
}
```

`peek` is an in-memory read with no disk, network or refresh-lock acquisition. `requestRefresh` schedules and returns immediately. `acquireRequired` is reserved for an actually needed capability, not called by every ordinary send. If a required waiter times out, it detaches without canceling another consumer's legitimate producer.

**Refresh policy, initial defaults.** Use a five-minute soft freshness interval and a one-hour hard metadata age as tunable starting values. Apply jitter to background refresh. Explicit refresh and supported catalogue-change notifications invalidate appropriately; no universal polling loop. Negative-cache transient failures for 30 seconds with capped exponential backoff. Authentication failures wait for a credential revision or explicit reconnect. Respect longer server Retry-After values. These are proposed values, not changes to protocol guarantees.

**Untrusted metadata.** Tool descriptions and schemas remain untrusted external data. Preserve schema/parser limits and existing tool-message boundaries; cached text is not a higher-priority instruction.

**Storage.** Memory-first bounded cache; optional encrypted reconstructible metadata under noBackupFilesDir using the existing secret-storage abstraction. Cap entries and bytes, reject malformed/oversized schemas, and publish only a complete validated snapshot. A failed refresh keeps the prior structurally valid snapshot but changes health; do not turn cached schema availability into a claim that a tool will succeed.

# 8. Cold MCP discovery, concurrency and safe execution

A cold schema cache must not silently remove the user's capabilities. The current ProgressiveToolCatalog searches an already-constructed definition set and only creates its facade under catalogue-size conditions. It cannot discover schemas that were never loaded. [R20](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/ProgressiveToolCatalog.kt)

**Extend, do not duplicate, discover_available_tools.** Add a proposed connection directory containing current authorized binding identities and minimal display metadata. When eligible connections have no schema snapshot, create the existing discovery facade even for a small catalogue. No fake input schema is advertised for an unknown tool. Required exact tool names and selected connectors must remain discoverable rather than be permanently dropped for latency.

The facade first searches known authorized definitions locally. If the task needs a cold connection, it requests that connection's catalogue using a required consumer lease, revalidates grants, and makes validated definitions available on the next provider round. Recompute context admission before adding schemas. Do not replace already-committed conversation context to fit an unexpectedly large catalogue. Oversized schemas produce a specific capability limitation.

The current synchronous/synchronized catalogue selection must not hold its monitor across suspendable discovery. Split it into: capture local selection under a short lock; perform required network work without the lock; validate epoch/grants; atomically publish the new selection. Preserve exact-name priority, catalogue-size caps, owner disambiguation, and per-run tool budgets. Discovery is not permission to install tools or execute arbitrary endpoints.

**Single-flight producer rules.** Maintain one producer per endpoint/account/configuration key. Home, picker and chat warm hints coalesce. Separate producer ownership from consumer ownership: stopping a draft or navigating away cannot cancel a catalogue fetch needed by an active tool call. Disconnect cleanup has bounded duration and cannot hold a global map lock. A cached read never waits on the mutex held by a refresh. Existing in-flight session logic is reused, not bypassed. [R12](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt)

**Cancellation classification.** User stop, app background, low battery and scheduler preemption are cancellations, not provider failures. They do not poison LocalServiceHealth, add authentication failure state, or trigger legacy fallback. Actual transport failures retain existing classification. Test the modern path's timeout exception behavior through its manager: a timeout subclassed as cancellation still needs an explicit timed-out observation at the readiness layer without being mistaken for user cancellation.

**No speculative side effects.** Only approved protocol discovery/catalogue operations may run pre-send. Never call tools/call, read a connected resource, refresh a remote task, issue a query-dependent memory lookup, or open a permission/OAuth dialog as warmup. Silent credential refresh may use only the existing authorized refresh mechanism and must be deduplicated.

# 9. Memory preparation without privacy or native regressions

Preserve FactVaultRepository as the authoritative fact/policy owner and LocalSemanticMemory as the native owner. Do not implement a second memory database or copy persistent private facts into an unrelated readiness store. [R16](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/FactVaultRepository.kt) [R17](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/LocalSemanticMemory.kt#L25-L146)

**Split the current turn operation.** Introduce proposed internal boundaries: `ensureLoadedReadOnly`, `recallReadOnly`, `captureAcceptedTurn` and `validateRecall`. Existing prepareTurn can delegate to them while compatibility is maintained. Preview must never call a path that extracts/persists facts, increments recurring-topic counts, enqueues model learning, or records an accepted conversation message.

**Prewarm stages.** First load enabled memory policy and scoped metadata. Then, when resources permit, prepare local lexical candidates and existing index handles. Cold embedding initialization is optional work, never a mandatory gateway to a greeting. Never use dummy user text to warm an index, because that can trigger capture or misleading usage. Optional native initialization needs a dedicated owner-thread method with no fact creation and explicit status.

**Native-owner scheduling.** LocalSemanticMemory explicitly requires its single owner thread; limitedParallelism is not a substitute. Current synchronize may embed a batch under a mutex. Split background maintenance into small checkpointed slices and prioritize submitted-turn queries between slices. A native embed cannot be assumed instantly preemptible. Foreground optional recall must be able to take a lexical-only snapshot without waiting for a long native index task. Never cancel and destroy the embedder while an operation still owns it. [R17](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/LocalSemanticMemory.kt#L25-L146)

**Snapshot consistency.** Publish immutable fact-ID/version candidates. On Send, resolve/validate selected facts against the current fact and privacy revisions before serialization and again through existing retained-source checks. On deletion or cloud-permission revocation, a preview result is immediately ineligible even if its background cleanup has not completed. Keep policy updates out of long-running semantic locks.

**Capture timing.** After the accepted turn is durable, schedule existing automatic capture once per committed message ID. Do not count drafts or multiple selected profiles as multiple occurrences. Explicit “remember/forget” requests retain their required durable action semantics; do not claim a memory was saved while it is only queued. The current user text remains in the request whether or not automatic capture has finished.

**External memory.** Warm only its authorized schema/transport metadata. Query-specific connected recall runs after Send. When directly required, preserve its existing evidence and budget rules with visible progress. When optional, permit the declared bounded fallback and record omission honestly. A local memory preview never grants cloud recall or allows local-only data into summaries, delegates, caches, debug exports or combined remote synthesis. Temporary or unresolved chat scope denies saved-memory recall. [R14](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/ConnectedMemoryRecall.kt#L72-L133) [R15](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L1576-L2099) [R16](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/FactVaultRepository.kt) [R21](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/context/RequestAuthorizationGuard.kt)

# 10. Draft prediction and adaptive preparation

Draft-based prediction is optional and local-only. Default it off for the first rollout while app-entry/schema preparation is enabled. Existing composer draft autosave is a separate feature; Smart Readiness must not add new persistent copies of drafts or change its behavior silently.

**Trigger contract.** Once the visible chat has a resolved scope and eligible memory settings, observe committed text changes. Ignore active IME composition, blank text, password-like inputs and drafts below a proposed 16-character minimum. After approximately 300 milliseconds without another committed edit, schedule a bounded local intent check and, when useful, a read-only memory preview. Large drafts use cheap conservative routing; do not embed a silently truncated prefix as though it represented the full request.

**Draft identity.** Use a monotonically increasing draft revision plus chat/profile/scope/policy/history/memory generations. Keep text comparison/digests only in ephemeral memory. Do not log plain hashes of short prompts: they can reveal a guessed “Hi” or other low-entropy text. Diagnostics use opaque correlation IDs and lengths/buckets only.

On each edit, profile change, attachment change, navigation, background event or policy update: increment revision first, detach obsolete consumers, then schedule replacement work. A late native completion may finish safely but its publication is rejected. At Send, reuse a preview only when the submitted text and every relevant revision match exactly. Never reuse the closest previous draft.

**No generative-model router.** Version 1 uses deterministic, bounded rules and existing local metadata. It does not call a remote model or load a local generative model to decide whether “Hi” needs research. An optional learned ranking algorithm can later prioritize warm connections using on-device aggregate hit/miss counts, not conversation content.

**Usefulness score, proposed:** estimate probability of near-term use multiplied by expected saved preparation time, minus measured network/CPU/battery cost. Give explicit current selection greater weight than past usage. Cap warm targets at two profiles and a small connection set; use fair aging so less-frequent selections are not starved once explicitly chosen.

Prediction may be wrong without affecting correctness: it changes only which allowed resource warms first. It cannot change tool permissions, select a different paid provider, or declare evidence sufficient.

# 11. Intent routing and dependency policy

Compute a deterministic PreparationPlan inside the common execution path from accepted input, conversation state, attachments, explicit tool selections, recovery/combined role, profile settings and current source boundaries. Readiness is an input, not the authority.

| Plan | Entry conditions | Permitted shortcuts |
|---|---|---|
| DIRECT | High-confidence standalone social greeting; no attachments, pending action, continuation, required memory, explicit research or mandatory delegation. | No optional memory retrieval, network schema refresh, helper load or research preparation before dispatch. Preserve profile prompt, valid history, permissions and limits. |
| CONTEXTUAL | Ordinary text task without an explicit pre-answer evidence dependency. | Ready local snapshots; optional recall within one shared budget; cached/lazy tool directory. Unknown intent stays here. |
| REQUIRED_WORK | Explicit connected action, recalled fact dependency, research mode, attachment requirement, recovery, or a configured mandatory workflow. | Reuse valid preparation, but never skip the required stage. Display its wait or capability error. |

**Greeting guard.** Normalize Unicode whitespace and surrounding greeting punctuation, then require an exact tested whole-message allowlist. “Hi, search for this,” “Hi, remember that…,” quoted text, an attachment-only turn, or a greeting in a pending task is not automatically DIRECT. “Yes,” “do it,” “continue,” and “retry” are never treated as independent greetings. Unsupported languages fall back conservatively instead of guessing.

**Research and delegation.** Globally available plugins do not imply every prompt needs research. However, an explicitly selected research workflow or mandatory 100% delegation/review policy must remain authoritative. The optimizer cannot silently flip those controls for a greeting. Improve the UI to make that requirement visible; only an explicit policy allowing trivial-message bypass may change it. Local Assist should remain available when an actual useful task exists, not become mandatory for all remote requests. [R15](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L1576-L2099) [R19](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L2081-L2507)

**Dependency classification.** Every preparation operation is REQUIRED, OPTIONAL, or MAINTENANCE. Authentication, current grants, accepted-input persistence, actual file preparation, destination checks and context/spending admission are never OPTIONAL. Model-price refresh, unused connector discovery, background indexing and unrelated memory enrichment are never REQUIRED. A missing cache is not itself a reason to classify a resource as REQUIRED.

One absolute optional deadline covers the entire preparation interval. Do not allocate a fresh 250 milliseconds to each serial subsystem. When it expires, record a typed skip and proceed only if that dependency is optional. Essential information may require waiting, a specific error, or explicit user-directed fallback—not an invented answer.

# 12. Common Send pipeline and ownership

Keep ChatViewModel.persistBeforeProvider and existing atomic run persistence. The final plan belongs in ChatRepositoryImpl so queue, retry and service-driven requests receive the same rules. [R07](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/queue/DurablePromptQueue.kt) [R18](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt) [R19](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L2081-L2507)

**Required ordering**

```text
SEND_TAPPED / QUEUED_INPUT_ACCEPTED
  -> snapshot submitted input and explicit policy
  -> persist turn + run ownership
  -> report any startup / queue / benchmark admission wait
  -> capture authoritative destination and source boundaries
  -> classify required vs optional preparation
  -> use valid snapshots; acquire only required missing data
  -> create fresh run-bound tools, context and request
  -> current permission + capacity + spend checks
  -> physical HTTP attempt (existing owner)
  -> parse stream -> reducer -> visible text
  -> terminal persistence and priority-lease release
```

**Illustrative integration contract; not drop-in code**

```kotlin
suspend fun prepareAcceptedTurn(input: AcceptedTurn): PreparedTurn {
    val authority = existingAuthority.capture(input)
    val policy = planner.classify(input, authority)
    val lease = workloadSignals.enterForeground(input.runId)
    try {
        val ready = snapshots.peek(policy.identity)
        scheduler.requestUsefulRefreshes(policy) // never await here
        val optionalDeadline = clock.deadlineAfter(policy.optionalBudget)
        val memory = memoryPreparation.forSubmittedTurn(
            input, policy, ready, optionalDeadline
        )
        val tools = toolResolver.bindForRun(
            input, policy, ready, optionalDeadline
        )
        val context = existingContextPlanner.plan(input, memory, tools)
        authority.validate()
        return PreparedTurn(context, authority, lease)
        // Ownership of lease transfers to the existing execution owner.
    } catch (failure: Throwable) {
        lease.close()
        throw failure
    }
}
```

The generation owner closes the transferred lease in finally, including failed construction, user cancellation, denied admission and process-recovery cases. If ownership-transfer code is awkward, acquire/release the lease around execute(request) instead; avoid two owners. The pseudocode illustrates contracts, not new parallel dispatch.

**Cancellation trap.** Do not implement optional preparation with `coroutineScope { async { slowWork() }; withTimeout { await() } }` and assume scope exit is fast. The scope still waits for its children. Optional producers must be lifecycle-owned by readiness; a submitted turn only takes a result or a bounded consumer lease. Producer cancellation/cleanup must not be joined on the Send critical path. Blocking I/O requires a cancellable transport or explicit close behavior; timeout alone is not sufficient. [E01](https://kotlinlang.org/docs/coroutines-cancellation.html) [E02](https://kotlinlang.org/docs/exception-handling.html) [E03](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html)

**History optimization.** Reuse a persisted in-memory context snapshot only when message IDs, content revisions, profile slots, history window and recovery state match. Otherwise read the authoritative database. Do not remove persistence or reduce context. Keep final RequestAuthorizationGuard, ModelRequestPreparation and PhysicalAttemptBoundary behavior intact. A readiness entry cannot reuse an inference attempt ID. [R18](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt) [R21](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/context/RequestAuthorizationGuard.kt) [R22](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ModelRequestPreparation.kt)

# 13. Provider catalogue, transport and first-token delivery

**OpenRouter.** Split proposed `peekModels/peekLimits` from refresh acquisition in OpenRouterModelRepository. Remove the awaited fetchModels from completeChat. Migrate public-catalogue loading to the existing cancellable NetworkClient, or explicitly adapt/close blocking connections until migration lands. Cache reads must not acquire the refresh mutex. Integrate with existing pricing/model refresh scheduling so Home, picker and worker do not create duplicate refreshes. [R03](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/GPTMobileApp.kt) [R09](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L1428-L1446) [R10](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/OpenRouterModelRepository.kt#L24-L78)

A missing/stale catalogue does not justify inventing capacity. Keep current declared limits and RequestAdmission checks; provider-reported narrower ceilings update the relevant cache. If an operation requires verified capacity, retain that requirement. Catalogue warming for a public endpoint does not imply warming a selected gateway/custom endpoint.

**Transport readiness.** Initialize the existing singleton client; this only avoids construction cost. Optionally issue a bounded, documented non-generating request to the actual selected endpoint when current consent/provider policy permits it. No generic /health assumptions, no synthetic chat completion, no inference request, and no new paid validation call. Do not attach a credential to an untrusted redirect or unrelated metadata origin. Verify reuse at the engine layer, rather than infer it from a “Ready” label. [R05](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/NetworkModule.kt) [R06](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/NetworkClient.kt#L30-L99)

Version 1 need not implement custom socket preconnect. Reusing existing legitimate metadata/auth refresh traffic is safer. Maintain existing TLS validation, proxy/VPN handling and connection closure rules. No per-chat client, connectionPool inflation, permanent ping loop or global `cancelAll` when only speculative work should stop.

**Retries and cooldown.** Preserve genuine Retry-After and authentication errors. Add visible states and timing around ProviderCooldown.await and existing retry sleeps. Deduplicate retry scheduling within the current transport/adapter owner; readiness cannot reset cooldowns. Never automatically race two paid completions or retry a request after tool-side effects/visible payload have begun. [R23](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ProviderCooldown.kt) [R24](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ResilientStreamingClient.kt)

**Streaming.** Preserve effective profile streaming/reasoning settings and expose them in diagnostics. If stream=false, explicitly identify non-streaming mode; do not silently override it. First visible text must not wait for usage receipts, suggestions, titles, slow telemetry or response completion. Retain parser integrity and terminal drain protections; do not flush partial protocol/control markup as answer text. [R25](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/provider/ProviderAdapters.kt#L195-L459) [R26](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/OpenAIAPIImpl.kt#L155-L358)

**Gateway/local routes.** Phone-side network readiness cannot guarantee a gateway's own helper/model readiness. Report gateway wait separately. Local LLAMA routes retain capacity probes where required; on-device LiteRT/QNN/GGUF generation is outside this remote fast-path optimization.

# 14. Queue, retries, combined mode and benchmark integration

| Path | Required integration |
|---|---|
| Standard chat | Hints come from the UI; runtime classification and validation happen again in the repository. |
| DurablePromptQueue | Queue entries remain accepted durable input. Prepare from pinned requested profile/model/tool details, not from current picker selection. Revalidate when dispatched. |
| Follow-up merged into an active response | Draft preview is never an inference continuation. Attach submitted instructions only through the existing follow-up session rules; recompute relevant revisions. |
| Explicit retry/restart | Retain recovery snapshots, task action, completed tool receipts and ownership fence. No greeting bypass for continuation/retry text. |
| Combined contributor profiles | Share only raw metadata or authorized equivalent local preview work. Construct each profile's memory/tool/context projection independently. |
| Combined synthesis | Preserve exact saved contributor corpus, provenance, full-context/multipass planning and source authorization. Do not re-run research or wait for unused warm connections. |
| User-started generation in background | Existing foreground service owns it. Readiness shutdown must not cancel it or its required catalogue consumer. |
| Benchmarks | Readiness pauses speculative native/network work. Keep benchmark exclusivity; label and time any chat admission wait. |

DurablePromptQueue already persists a queued turn and calls AgentRunCoordinator directly; a UI-only warm implementation would miss this path. It also waits for startup recovery. Add trace correlation and readiness invalidation without creating a second queue consumer. [R07](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/queue/DurablePromptQueue.kt)

**Benchmark wait correction.** InferenceAdmission currently gates all shared chat work behind an exclusive benchmark. Version 1 should surface QUEUED_FOR_BENCHMARK before the wait and offer existing cancellation/stop controls. Do not quietly bypass the gate for remote profiles: benchmarks can involve remote providers, credentials and accounting as well. Splitting native-resource and provider-resource admission is a possible later change only with a full exclusivity regression suite. Prewarming must never call benchmark() to “verify readiness.” [R08](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/InferenceAdmission.kt)

**No late context mutation.** Once a request is serialized/dispatched, newly completed memory previews cannot alter its body. They may benefit a later accepted turn, or an authorized next tool round through existing machinery. A restarted completion requires the same explicit retry and duplicate-action rules as today.

**Shared-work boundaries.** Deduplicate raw schemas by account/endpoint identity, not just tool name. Deduplicate memory ranking only where scope, text revision and effective privacy match. Never share a final tool wrapper, current response, consent decision, receipt, helper budget or role-specific prompt between profiles. [R11](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AgentToolResolver.kt) [R20](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/ProgressiveToolCatalog.kt) [R21](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/context/RequestAuthorizationGuard.kt) [R22](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ModelRequestPreparation.kt)

# 15. Scheduling, resource budgets and adaptive behavior

The scheduler is a logical resource-admission system, not a promise that Dispatchers.IO gives work a low operating-system priority. It must isolate pools/permits, bound queues, and prevent speculative jobs from owning locks needed by Send. Native operations yield only at safe owner-thread boundaries. A warmup timeout is a soft completion budget until the underlying transport or native operation acknowledges cancellation; foreground progress must not depend on that acknowledgment. [E07](https://ktor.io/docs/client-timeout.html)

| Class | Priority | Initial proposed limit |
|---|---|---|
| Accepted chat dispatch and stream delivery | P0 | Never behind speculative scheduler permits; existing generation limits still apply. |
| Required tool/memory acquisition | P1 | Existing run budget and permissions; shares needed producer via a lease. |
| Selected chat/profile warmup | P2 | Two network warm jobs maximum; one semantic maintenance slice. |
| Draft preview | P3 | One current preview per visible chat; stale revisions discarded. |
| Home/metadata maintenance | P4 | Two likely profiles; at most four selected connections per warm window. |

**Budget defaults.** Initial optional per-turn preparation budget: 250 ms total; DIRECT: zero optional network/semantic work. Draft debounce: 300 ms. Idle warm-window debounce: approximately 500 ms after a usable UI frame. Schema cache: 4 MiB decoded memory, 16 MiB encrypted disk, 16 connection identities; large entries may stay unavailable for speculation rather than be truncated. Readiness retains candidate IDs rather than duplicate fact bodies. These are testable starting limits, not platform constants.

Use existing hardware pressure, local runtime and memory-trim signals. Under battery saver, severe thermal pressure, low free memory, active benchmarking or sustained generation, suppress native speculation and nonessential refresh. Metered-network behavior must respect a visible setting. No automatic model download, QNN verification, GGUF weight load or benchmark on app opening.

**Foreground priority.** A Send signal stops dispatch of new P2–P4 jobs and cancels only producers with no required consumers. Do not await cancellation acknowledgments on the critical path. Resume maintenance gradually after interactive work settles to avoid a post-response burst. Leave capacity for foreground networking; if transport scheduler isolation is added, verify safe engine/pool sharing rather than create unbounded clients.

**Adaptive stage, optional.** Track aggregate local cache hits, warm misses, wasted warm bytes and saved-time estimates. Use these to reduce warm targets for rarely used plugins. Do not log user queries or upload behavior. A deterministic setting must disable prediction while preserving correct cache-only Send behavior. Clearing readiness history resets this ranking without deleting memories or tools.

# 16. Settings, status UI and accessibility

Add **Smart Readiness** under existing Advanced/Performance settings. Use one master control with an expandable detail panel, not a new chat-screen dashboard. Store proposed ReadinessSettings inside AppFeatureSettings through the existing atomic settings-mutation path. New nested fields require serialization/default/migration coverage; merely adding a Boolean is not complete wiring. [R27](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/AppFeatureSettings.kt#L1-L140)

| Proposed control | Default after acceptance | Behavior |
|---|---|---|
| Smart Readiness | On | Enables foreground prewarming and cached preparation; does not grant data access. |
| Predict memory while typing | Off | On-device, read-only prediction subject to existing memory policy. |
| Warm on metered networks | Off | Permit small selected-endpoint metadata refreshes when explicitly enabled. |
| Preparation strategy | Balanced | Balanced / Minimal resources; both preserve mandatory checks. |
| Clear readiness cache | Action | Removes derived readiness metadata only; cancels old producers and rotates namespace. |

Respect existing providerModelDiscovery, remoteMcpConnections, plugin selections, memory toggles, cloud sharing and local-assist policy. A Smart Readiness toggle never reenables a disabled subsystem. Do not add unsupported UI methods without updating AppFeature/withFeature mappings or the settings screen's existing mutation routes as applicable.

**Chat UI.** Keep Send active unless a genuine existing blocker applies, such as attachment preparation or unresolved mandatory ownership. Warmup has no blocking modal. An optional small themed status icon may expose “Tools cached,” “Memory index warming,” “Connection needs sign-in,” or “Preparing after startup” on tap. READY means the specifically named asset is ready, not that remote inference is guaranteed.

**Required waits.** Display succinct reasons: “Waiting for benchmark,” “Provider rate-limited: retry in …,” “Loading required attachment,” or “Connecting to required tool.” Use actual remaining cooldown where known, not an invented percentage. Keep existing Stop behavior and accessibility labels. Background failure notifications are quiet unless the user needs that capability.

**Debug panel.** Add a Preparation/Readiness section to existing response diagnostics: plan type, cache hits/misses, total pre-dispatch duration, optional tasks skipped, required wait reason, HTTP/retry/cooldown timings, first visible text and permission revision. Reuse the current theme and text scaling. No raw draft, memory body, token, account secret or cross-chat title appears in readiness telemetry.

# 17. Storage, migration, reset and rollback

Use the existing settings storage and secret-storage APIs. A derived MCP metadata cache can live outside Room to avoid an unnecessary conversation database migration. This is a proposal: schema/format validation and backup exclusion still require tests. Memory facts stay in their current system. [R03](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/GPTMobileApp.kt) [R16](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/FactVaultRepository.kt) [R27](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/AppFeatureSettings.kt#L1-L140)

**Settings migration.** Add a versioned ReadinessSettings structure with conservative defaults. Existing profiles retain their behavior settings, model, tools, limits and privacy. Pilot builds can keep warmup default-off while collecting local test evidence; switch the release default only after acceptance. Turning readiness off cancels speculative work but must not restore unconditional network discovery before ordinary Send.

**Derived disk cache.** Store only validated reconstructible metadata, encrypted through the existing vault facility under noBackupFilesDir. Use atomic writes, version and size checks, strict decode limits and a cache namespace epoch. Neither raw secrets nor credential hashes usable for account correlation belong in exported filenames. Corrupt cache entries are discarded without blocking chat or damaging authoritative data.

**Backup and restore.** Portable settings may include the user's readiness preferences; exclude runtime health, warmed schemas, session IDs, leases, producer jobs, draft previews and ranking history by default. Add an explicit readiness-cache exclusion to manual backup enumeration: Android's noBackupFilesDir alone does not prove an app's custom archive excludes it. Restore must invalidate derived caches and wait for existing recovery before rearming warmup. Retain the established backup grouping; do not add a new category solely for this feature.

**Deletion and revocation.** Memory deletion, conversation deletion, disable/uninstall, logout and factory reset advance epochs synchronously and trigger bounded cleanup. Late producer results are rejected even if their I/O cannot immediately stop. Query previews are ephemeral and never restored after process death. A revoked memory cannot be retained because a TTL has not expired.

**Independent rollback switches.** Provide build/runtime controls for speculative warmup, draft prediction, lazy remote catalogue acquisition and metadata persistence. Never use rollback to disable authorization, physical attempt accounting, parser hard limits, source checks or durable run ownership. If lazy discovery is rolled back, use the known safe capability path with explicit waits; do not mislabel performance as passing.

No application version bump or release publication is implied by writing this plan. A future release follows the repository's normal signed-build evidence gates. [R01](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/91312450f864da17572ec925e2af84683504b4c1) [R02](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/build.gradle.kts#L36-L180)

# 18. End-to-end timing and evidence contracts

Extend PreparationTiming/RunDiagnosticJournal rather than add a parallel analytics pipeline. Emit content-free in-process events, with durable export subject to current debug/privacy settings. Diagnostics disabled must not add blocking I/O. [R28](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/PreparationTiming.kt) [R29](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/RunDiagnosticJournal.kt)

| Milestone | Owner and exact meaning |
|---|---|
| SEND_TAPPED | UI input accepted by the send handler; before persistence. |
| INPUT_PERSISTED | Durable turn/run transaction completed. |
| DISPATCH_ELIGIBLE | Startup/queue/benchmark ownership permits processing. |
| PREPARATION_STARTED / FINISHED | Common planner interval, including mandatory and optional substages. |
| REQUEST_SERIALIZED | Existing admitted payload produced; not proof bytes reached the server. |
| HTTP_CALL_START | Transport begins the attempt; may still include scheduler/DNS/TLS delay. |
| REQUEST_BODY_END | Engine reports body write completion; distinguish from remote acknowledgement. |
| RESPONSE_HEADERS | Response headers received; not first model text. |
| FIRST_REASONING / FIRST_TOOL / FIRST_TEXT | First parsed provider event of each type. A heartbeat is none of these. |
| FIRST_VISIBLE_TEXT | UI presents actual answer text for this run/ownership fence. |
| RUN_TERMINAL | Existing persisted terminal result, linked to all physical attempts. |

Use monotonic timestamps with processSession, runId, accepted-message ID, attempt ID, role, cache event, elapsed duration and typed reason. Queue dwell, benchmark wait, cooldown, DNS/connect/TLS, provider wait and rendering latency must remain distinguishable. For process restarts, preserve semantic ordering through durable IDs, but do not subtract unrelated monotonic clocks.

**Derived metrics.** Report tap-to-dispatch, accepted-to-eligible, eligible-to-body-end, headers-to-first-text, body-end-to-first-text, first-text-to-visible-text and total tap-to-visible-text. For meaningful interpretation, separate content-producing text from reasoning, status labels and tool activity. A provider may not expose its internal queue time; label that interval “network/provider wait,” not verified server execution time.

**Additional events.** CACHE_HIT, STALE_USED, REFRESH_DEDUPED, CACHE_INVALIDATED, PREVIEW_DISCARDED, OPTIONAL_DEADLINE, REQUIRED_WAIT, READINESS_PREEMPTED and WARM_RESULT_REJECTED. Add health-failure vs scheduler-cancellation discrimination.

**Do not game timing.** A “Connecting…” label is not a first token. REQUEST_SERIALIZED is not physical network transmission. A failed/canceled sample must not disappear from the denominator. No provider profile switch, suppressed required workflow or context reduction can count as a successful latency optimization.

# 19. Implementation work packages and wiring map

Each package includes production call-site changes, tests and evidence. A helper class that is never called does not satisfy a work package. Source paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/` unless otherwise stated.

| ID | Implement in order | Existing owners / proposed additions | Completion evidence |
|---|---|---|---|
| W01 | Baseline trace and reproducible slow fixtures | diagnostics/PreparationTiming.kt; RunDiagnosticJournal.kt; agent/AgentRunCoordinator.kt; context/PhysicalAttemptBoundary.kt; UI display boundary | Correlated trace for cold/warm Hi, slow MCP, memory and benchmark wait. |
| W02 | Immutable keys, states, scheduler and leases | NEW data/readiness/{ReadinessModels,ReadinessSnapshotStore,ReadinessScheduler,ReadinessWorkloadSignals}.kt | Fake-clock race tests; no network/native work under snapshot lock. |
| W03 | Hilt/lifecycle wiring | NEW di/ReadinessModule.kt and ChatReadinessCoordinator; presentation/GPTMobileApp.kt; AppForegroundTracker.kt; ui/main/MainActivity.kt | Main-process-only init; first frame unaffected; no Hilt cycle. |
| W04 | Home/picker/chat hints | ui/home/HomeViewModel.kt, HomeScreen.kt; ui/chat/ChatViewModel.kt | Distinct profile/scope revisions; fast navigation cannot publish stale hints. |
| W05 | Unified MCP catalogue | agent/tool/McpClientManager.kt, ModernMcpTransport.kt, AgentToolResolver.kt; NEW AuthorizedMcpCatalogCache | Modern and legacy fixtures show cache reuse, grant intersection and deduplication. |
| W06 | Lazy capability discovery | agent/ProgressiveToolCatalog.kt and AgentToolResolver.kt | Cold authorized tool discoverable next round; no fake schema or duplicate facade. |
| W07 | OpenRouter cache-only send | repository/OpenRouterModelRepository.kt, ChatRepositoryImpl.kt; existing pricing refresh owners | A stalled catalogue refresh cannot hold a send or its snapshot lock. |
| W08 | Read-only local memory preparation | rag/FactVaultRepository.kt; memory/LocalSemanticMemory.kt; NEW MemoryPreviewCoordinator | Draft preview causes zero writes, model calls and network activity; native affinity intact. |
| W09 | Common plan and safe fast path | NEW SendPreparationPlanner.kt; repository/ChatRepositoryImpl.kt; agent/AgentRunCoordinator.kt | Required-work matrix enforced; optional deadline shared; every entry path covered. |
| W10 | Draft prediction | ChatViewModel.kt; existing draft observer; readiness package | IME/revision/private-chat tests; opt-in setting wired. |
| W11 | Provider/status/render diagnostics | NetworkClient.kt; provider adapters; ProviderCooldown.kt; streaming reducer/UI | First visible token trace; cooldown visible; no duplicate completion or buffering regression. |
| W12 | Queue, combined, recovery and benchmark review | queue/DurablePromptQueue.kt; FollowUpAgentSession; ChatViewModel combined/retry paths; InferenceAdmission.kt | No UI-only dependency; recovery/corpus ownership retained; benchmark wait categorized. |
| W13 | Settings, reset, backup exclusion and trim | model/AppFeatureSettings.kt; existing setting mutation/UI; GPTMobileApp; existing deletion/restore owners | Settings round-trip; cache cleanup; restore revocation tests. |
| W14 | Regression, release-like measurement and documentation | Existing JVM/instrumentation tests and benchmark target; docs/implementation/ | Green applicable CI plus measured device evidence; signed release gates remain separate. |

**Dependency ordering.** W01 precedes behavior changes. W02 enables W03–W08. W05 precedes W06; W08 precedes W10. W09 integrates W05–W08 and may not be declared complete before W12. W13 and W14 are mandatory, not follow-up polish.

**Suggested branch:** `feat/smart-readiness-fast-dispatch-2026-10-14`, created from the then-current main after comparing it with this baseline. Suggested repo document location: `docs/implementation/Smart_Readiness_Design_Implementation_2026-10-14.md`. These are proposed names; this planning task does not create them.

**Implementation review discipline.** Search all call sites of completeChat, openSession, resolve, listTools, prepareTurn, fetchModels, StartupRecoveryGate.await and InferenceAdmission.shared. Record which now use snapshots, which still perform required work, and why. Preserve functional tests before adding static guard tests that prohibit the known blocking call sites.

# 20. Acceptance test matrix

Every test has a stable ID linked to the work packages. Use controlled fake transports, deterministic clocks and blocked producers for correctness tests. Do not use arbitrary sleeps as evidence of wiring.

| Test | Setup and required assertion | Work |
|---|---|---|
| T01 | New remote Hi, cold cache, all optional producers blocked forever: exactly one provider request; no optional wait. | W01/W09 |
| T02 | Open chat and send in the same frame: cache miss works without a prewarm grace period. | W03/W09 |
| T03 | Hold OpenRouter refresh mutex/network: ordinary send reads snapshot without joining it. | W07 |
| T04 | Three MCP connections, one never responds: unused connection cannot delay direct chat. | W05/W09 |
| T05 | Modern and legacy metadata cache hits: no additional tools/list request on repeated sends within freshness. | W05 |
| T06 | Home, picker, chat and active required lookup request the same identity: one producer. | W02/W05 |
| T07 | Cancel home/draft waiter while a tool needs its producer: required consumer completes normally. | W02/W08 |
| T08 | All consumers detach: producer cancels/cleans up without blocking a new Send. | W02 |
| T09 | Old producer completes after endpoint/credential change: publication rejected. | W02/W05 |
| T10 | Raw catalogue shared across profiles: each projection exposes only that profile's current grants. | W05 |
| T11 | Warm server removes or changes a schema: stale call is not replayed blindly; refresh/next-round validation required. | W05/W06 |
| T12 | Unknown tool schema in a small catalogue: discovery facade exists and required tool becomes available next round. | W06 |
| T13 | Duplicate aliases, oversized schema, invalid JSON or repeated cursor: reject safely within bounds. | W05/W06 |
| T14 | Cancel modern discovery intentionally: no unhealthy-provider penalty or legacy fallback. | W05 |
| T15 | Genuine timeout/auth failure: classified correctly; no infinite retry or false permission reset. | W05 |
| T16 | Prewarm configured MCP: capture HTTP traffic and assert no tools/call, resources/read or task actions. | W03/W05 |
| T17 | Memory draft preview: facts, topics, history, captured-message IDs and model usage unchanged. | W08/W10 |
| T18 | Cloud recall off: all pre-send work local; no memory text appears in any remote request. | W08/W09 |
| T19 | Temporary or unresolved chat: no saved-memory preview/recall; no persistent readiness content. | W08/W10 |
| T20 | Delete/disable a recalled fact immediately before serialization: fact never leaves in the request. | W08/W09 |
| T21 | Edit draft while native preview completes: only exact current revision can publish. | W10 |
| T22 | Change profile, scope, branch or attachments with same text: old preview is invalid. | W10 |
| T23 | IME composition/debounce, paste, large draft and unsupported locale: conservative behavior without remote classification. | W10 |
| T24 | Native index batch slow: foreground optional recall uses nonblocking lexical fallback; no second native owner. | W02/W08 |
| T25 | Native initialization fails: regular remote chat works; status says degraded, not verified-ready. | W08 |
| T26 | Accepted prompt with multiple profiles: automatic memory capture occurs once per durable message, never once per profile. | W08/W12 |
| T27 | Explicit remember/forget: durable action semantics retained and correctly reported. | W08/W09 |
| T28 | Bare Hi versus “Hi, research…”: only eligible whole-message greeting takes DIRECT. | W09 |
| T29 | “Yes”, “do it”, “continue”, retry and pending confirmation: preserve task/recovery semantics. | W09/W12 |
| T30 | Research selected or mandatory delegation/review: no implicit fast-path bypass. | W09/W12 |
| T31 | Required attachment not ready: preserve draft/required wait; no attachment omission. | W09 |
| T32 | Large history and combined corpus: request content matches admitted baseline, excluding only optional extra enrichment. | W09/W12 |
| T33 | Optional stages all slow: combined wait is bounded by one deadline, not N independent timeouts. | W02/W09 |
| T34 | Provider cooldown: status/countdown and trace correct; Retry-After respected. | W11 |
| T35 | Stream=false or reasoning-on profile: settings preserved and diagnostic distinction correct. | W11 |
| T36 | Slow usage logging, titles or suggestions: first visible text not delayed until finalization. | W11 |
| T37 | Interrupted stream after text/tool side effect: no duplicate provider/tool action caused by readiness. | W11/W12 |
| T38 | Durable queue dispatch after leaving UI: same planner/authorization; no warm ViewModel dependency. | W12 |
| T39 | Edit/pause/delete queued prompt: correct revision and ordering; no early speculative dispatch. | W12 |
| T40 | Combined contributor/synthesis/failed retry: ownership, original sources and permissions retained. | W12 |
| T41 | Active benchmark: explicit queued state and measured admission wait; no exclusivity violation. | W01/W12 |
| T42 | Cancellation/exception at each acquisition/publication/dispatch boundary: leases released once; no leaked jobs. | W02/W09/W12 |
| T43 | Start :local_inference process alone: zero readiness, Room warmup, network or new workers. | W03 |
| T44 | Rotation/multi-window/foreground-resume: one coordinator; stale callbacks cannot reopen closed chat scope. | W03/W04 |
| T45 | Trim memory/battery saver/thermal pressure while native busy: no unsafe destroy or priority inversion. | W08/W13 |
| T46 | VPN/Wi-Fi/network switch: revalidate health without gratuitously deleting all schema metadata. | W05/W11 |
| T47 | Startup/restore in progress: no recursive gate wait or speculation before privacy recovery. | W03/W13 |
| T48 | Custom backup then restore: settings survive; warmed sessions/schemas/previews/health do not. | W13 |
| T49 | Process restart/clock rollback/corrupt cache: conservative stale handling; no chat startup failure. | W02/W13 |
| T50 | Disable Smart Readiness: no producers; direct-send correctness and authorization remain intact. | W13 |
| T51 | Debug export/private chat: no draft text, memory content or credentials added by readiness. | W01/W13 |
| T52 | Build both supported runtime configurations; compile Hilt and serialization defaults. | W03/W13/W14 |
| T53 | Release-like cold/warm runs with controlled SSE: latency, success and context parity meet Section 21. | W14 |
| T54 | Profile switched or credential revoked after plan but before HTTP: final existing guard denies stale dispatch. | W09/W11 |
| T55 | Chat deletion/restore races with cache publication: epoch changes before cleanup; stale entries remain unusable. | W02/W13 |
| T56 | Idle foreground for ten minutes: no periodic generating calls, warmup storm or uncapped cache growth. | W03/W14 |

**Existing regression suites to retain:** ChatRepositoryImplTest, ChatViewModelRetryTest, McpClientManagerTest, ModernMcpTransportTest, AgentToolResolverTest, FactVaultRepositoryTest, PreparationTimingTest, PhysicalDispatchReceiptTest, provider transport tests, queue/follow-up tests, LocalSemanticMemoryInstrumentedTest, and native process/runtime instrumentation. Confirm their current paths when implementing; this specification does not claim they were executed.

# 21. Performance targets, test protocol and CI gates

Targets below apply to a controlled standard single-profile remote greeting with no required attachment/research workflow, no existing queue, and no active benchmark/cooldown. These exclusions are measured and reported, not silently dropped. Compare identical model, endpoint, reasoning, context, prompt and network conditions.

| Metric | Proposed gate |
|---|---|
| Warm tap-to-HTTP body completion | p50 <= 250 ms; p95 <= 750 ms on reference device with controlled provider. |
| Cold-cache greeting, app already usable | p95 <= 1,500 ms; zero synchronous optional external discovery/lookups. |
| DIRECT optional dependency wait | 0 ms; zero helper inference and query-specific MCP calls. |
| CONTEXTUAL optional preparation | <= 250 ms total budget; native cleanup excluded only if it does not block Send. |
| First parsed answer text to first visible answer | p95 <= 100 ms in controlled streaming fixture. |
| Duplicate generation / stale permission leakage | Zero in all deterministic/race fixtures. |
| Foreground UI startup/navigation regression | No more than 5% p95 regression against matched baseline; inspect traces. |
| Speculation idle behavior | Zero generating calls; bounded cache/queue; no new ongoing service. |
| Memory footprint | Initial target <= 16 MiB incremental non-native readiness memory; measure native embedding separately. |

Do not compare the user's reported 20-second total response wait against a local preparation target as if they were the same metric. A live provider test measures the full breakdown and provider variability; deterministic gates use a local controlled streaming server. Actual provider tests are opt-in and disclose requests/costs.

**Measurement protocol.** Establish baseline and candidate APK hashes. Use a release-like profileable build and a physical Android device; include the target ROG Phone when available and one constrained-memory device. Use at least 50 cold-cache and 100 warm repetitions for each primary scenario; record sample count, p50, p95, maximum, failures and confidence/variability. Randomize baseline/candidate order. Distinguish process-cold, metadata-cold, embedding-cold and warm states. Keep power/thermal/network conditions recorded. Macrobenchmark/Perfetto provides evidence of startup, UI and trace behavior. [E06](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)

**CI layers.** Run deterministic JVM tests for routing, clocks, single-flight, settings and permission epochs. Run instrumented native-affinity, Hilt, Room/queue, lifecycle and Compose display tests. Then run release-like performance suites separately from debug correctness. Unit tests with a fake clock prove ordering/deadline logic, not physical-device milliseconds.

**Build discovery and baseline commands**

```sh
# Commands for the future implementation environment; not executed here.
git rev-parse HEAD
./gradlew :app:tasks --all
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  --no-daemon --stacktrace
./gradlew :app:connectedDebugAndroidTest --no-daemon
# Alternate existing runtime property, in a separate build/job:
./gradlew :app:assembleDebug -PgeniexRuntime=true --no-daemon
```

Confirm task names against the current Gradle task listing and existing CI before use. The inspected debug workflow uses JDK 21, Android SDK 37/build tools 37 and assembleDebug; the alternate runtime is property-based, not an invented Gradle flavor. Retain the current native-library verification script and signed-release evidence gates. No skipped/failed test may be reported as green. [R01](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/91312450f864da17572ec925e2af84683504b4c1) [R02](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/build.gradle.kts#L36-L180) [R30](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/.github/workflows/debug-build.yml)

**Evidence artifact.** Store commit, APK SHA-256, app/version/runtime configuration, device/OS, scenario/policy, enabled connectors, memory size, raw content-free traces, sample counts, latency distribution, failures, test IDs and CI run references. Signed-release validation is a later release gate, not inferred from a debug or fixture result.

# 22. Integration double-check and release review

The source review identified the following implementation traps. Each requires an explicit code-review answer plus a passing corresponding test; a design checkbox alone is not runtime validation.

| Trap | Required resolution | Evidence |
|---|---|---|
| Warmup moved earlier but Send still calls old slow API | Replace mandatory fetch/list entry points with cache reads or required-only acquisitions; test blocked refresh. | T01–T05 |
| Cache read waits behind refresh lock | Immutable lock-free/short-lock read; network outside locks. | T03/T06 |
| Earlier Kotlin sketch uses unowned appScope or supervisorScope incorrectly | Qualified managed scope, injected dispatchers, per-producer error handling, no child-join barrier on Send. | T07/T08/T42 |
| UI-only readiness bypass | Common repository plan used by queue, retry, follow-up and combined paths. | T38–T40 |
| Singleton dependency cycle | Coordinator depends only on leaves/signals, never ChatRepository/AgentRunCoordinator constructors. | T52 |
| Native warmup slows foreground or crashes | Preserve dedicated owner; small maintenance slices; no forced native interruption/destruction. | T24/T25/T45 |
| Cache becomes permission shortcut | Live grant/source checks at advertisement, acquisition, execution and physical dispatch. | T09/T10/T20/T54 |
| Cold cache loses tools permanently | Extend existing discovery facade with authorized cold-connection directory and next-round replan. | T11–T13 |
| Prediction creates remote traffic/content capture | On-device read-only preview API; traffic and database-delta assertions. | T16–T19 |
| Exact settings silently changed for speed | Whole-message routing guards and required-work precedence; preserve model/context/limits. | T28–T35 |
| Benchmark is mistaken for network latency | Explicit admission wait before sharedFlow wait; preserve exclusive benchmark ownership. | T41 |
| Health backoff polluted by preemption | Typed outcome separation; no retry reset on lifecycle events. | T14/T15 |
| Memory/socket cache survives restore as valid | Epoch/namespace invalidation; explicit manual-backup exclusions. | T47–T49/T55 |
| Fast number hides wrong first-token metric | Separate serialization, actual HTTP, parsed text and rendered text; preserve failed samples. | T36/T51/T53 |

**Source review completed:** pinned main and app build configuration; startup/foreground/native-process ownership; common and queued send paths; conditional benchmark gate; MCP modern/legacy schema behavior; existing progressive discovery limitations; memory native-owner and capture boundaries; singleton networking; existing authorization and telemetry boundaries.

**Still to prove after implementation:** production Hilt graph, compilation, cancellation under real transports/native calls, no content leakage, no stale-grant dispatch, no duplicate inference, performance on-device, full regression suite and signed-release acceptance. No live “Hi” request or Android benchmark was run for this document.

**Release rule.** Every invariant must hold with cache disabled, empty, corrupt, stale and fully warm. Enable release-default Smart Readiness only after deterministic correctness, lifecycle/native tests, real-device latency and existing repository release gates pass. A required feature may be slower; it must not be silently omitted to meet a greeting benchmark.

# 23. Sources and implementation handoff

Repository references below use the pinned commit rather than moving main links. Source line ranges identify inspected integration points; method names are the primary anchors when later changes shift lines. Technical behavior taken from official documentation is distinguished from this document's proposed policy values.

## Repository evidence

[R01](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/91312450f864da17572ec925e2af84683504b4c1) Verified main revision and merge baseline — commit 91312450f864da17572ec925e2af84683504b4c1; merge of PR #684, October 14, 2026.

[R02](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/build.gradle.kts#L36-L180) app/build.gradle.kts — namespace, version/application ID, SDK, runtime property and native ABI configuration, lines 36–180.

[R03](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/GPTMobileApp.kt) presentation/GPTMobileApp.kt — main-process startup, StartupRecoveryGate, existing application scope, lazy providers, pricing refresh scheduling and trim behavior.

[R04](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/AppForegroundTracker.kt) presentation/AppForegroundTracker.kt — existing application activity visibility owner.

[R05](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/NetworkModule.kt) di/NetworkModule.kt — singleton NetworkClient using OkHttp.

[R06](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/NetworkClient.kt#L30-L99) data/network/NetworkClient.kt — shared engine/pool and default HTTP timeout/logging configuration, lines 30–99.

[R07](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/queue/DurablePromptQueue.kt) data/queue/DurablePromptQueue.kt — startup gate, pinned queue payload, persistence and coordinator dispatch.

[R08](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/InferenceAdmission.kt) data/localruntime/InferenceAdmission.kt — shared/exclusive admission and 60-second benchmark wait.

[R09](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L1428-L1446) data/repository/ChatRepositoryImpl.kt — awaited OpenRouter catalogue refresh and provider-limit use, lines 1428–1446.

[R10](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/OpenRouterModelRepository.kt#L24-L78) data/repository/OpenRouterModelRepository.kt — cache/mutex and HttpURLConnection catalogue path, lines 24–78.

[R11](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AgentToolResolver.kt) data/agent/tool/AgentToolResolver.kt — authorized resolution, concurrent MCP group barrier and fresh wrapper binding, lines 102–145, 298–356 and 896–963.

[R12](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt) data/agent/tool/McpClientManager.kt — modern/legacy entry point, legacy catalogue cache, in-flight session creation and cleanup, lines 73–175 and 427–552.

[R13](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/ModernMcpTransport.kt) data/agent/tool/ModernMcpTransport.kt — endpoint discovery, schema acquisition and RPC bounds, lines 35–145, 239–332 and 380–386. This reference establishes repository behavior, not a claim about universal MCP protocol support.

[R14](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/ConnectedMemoryRecall.kt#L72-L133) data/agent/tool/ConnectedMemoryRecall.kt — selected external memory lookup and 15-second deadline, lines 72–133.

[R15](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L1576-L2099) data/repository/ChatRepositoryImpl.kt — tool/memory/source preparation and delegated research before primary streaming, lines 1576–1750 and 1888–2099.

[R16](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/rag/FactVaultRepository.kt) data/rag/FactVaultRepository.kt — background indexes, scoped prepareTurn, capture and snapshot load/persist, lines 119–165, 265–324 and 686–759.

[R17](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/LocalSemanticMemory.kt#L25-L146) data/memory/LocalSemanticMemory.kt — dedicated native owner, embedder and synchronized search/index methods, lines 25–146.

[R18](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt) presentation/ui/chat/ChatViewModel.kt — chat initialization, tool availability, persistence, history snapshot and retries/combined entry points, lines 387–420, 887–945, 1007–1124 and 1451–1572.

[R19](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L2081-L2507) data/repository/ChatRepositoryImpl.kt — completeChat, primary session construction, sharedFlow admission and streaming, lines 1196–1258, 2081–2168 and 2269–2507.

[R20](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/ProgressiveToolCatalog.kt) data/agent/ProgressiveToolCatalog.kt — existing discover_available_tools facade, known-definition set and per-run selection rules.

[R21](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/context/RequestAuthorizationGuard.kt) data/context/RequestAuthorizationGuard.kt — retained-source authorization around session construction and physical-boundary context.

[R22](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ModelRequestPreparation.kt) data/network/ModelRequestPreparation.kt and data/context/PhysicalAttemptBoundary.kt — final serialized admission and attempt ownership.

[R23](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ProviderCooldown.kt) data/network/ProviderCooldown.kt — credential/endpoint cooldown and delay.

[R24](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ResilientStreamingClient.kt) data/network/ResilientStreamingClient.kt — retry/backoff and cancellation behavior.

[R25](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/provider/ProviderAdapters.kt#L195-L459) data/agent/provider/ProviderAdapters.kt — effective stream settings, OpenRouter reasoning and provider sessions, lines 195–298 and 433–459.

[R26](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/OpenAIAPIImpl.kt#L155-L358) data/network/OpenAIAPIImpl.kt — cooldown, dispatch, JSON/nonstreaming and SSE processing, lines 155–358.

[R27](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/AppFeatureSettings.kt#L1-L140) data/model/AppFeatureSettings.kt — current settings, last-used profiles and mutation mapping, lines 1–140.

[R28](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/PreparationTiming.kt) data/diagnostics/PreparationTiming.kt — current preparation phase enumeration and timing helper.

[R29](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/RunDiagnosticJournal.kt) data/diagnostics/RunDiagnosticJournal.kt — event roles, bounded opt-in diagnostics and export.

[R30](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/91312450f864da17572ec925e2af84683504b4c1/.github/workflows/debug-build.yml) .github/workflows/debug-build.yml — JDK/SDK setup, build and native-library validation commands.

## Official engineering references

[E01](https://kotlinlang.org/docs/coroutines-cancellation.html) Kotlin: Cancellation and timeouts — cooperative cancellation, parent/child completion and blocking-work caveats.

[E02](https://kotlinlang.org/docs/exception-handling.html) Kotlin: Coroutine exceptions handling — supervision does not remove scope completion or exception-handling obligations.

[E03](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html) Kotlin API: runInterruptible — interruption adapter for interruptible JVM calls; not a guarantee for arbitrary native code.

[E04](https://developer.android.com/develop/background-work/background-tasks) Android Developers: Background tasks overview — visible asynchronous work versus scheduled background work/services.

[E05](https://developer.android.com/develop/background-work/background-tasks/persistent) Android Developers: Task scheduling with WorkManager — durable scheduled work, distinct from immediate foreground chat readiness.

[E06](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) Android Developers: Write a Macrobenchmark — release-like UI/startup measurements and trace evidence.

[E07](https://ktor.io/docs/client-timeout.html) Ktor: Client timeouts — separate request, connect and socket timeout controls.

[E08](https://developer.android.com/topic/architecture/recommendations) Android Developers: Architecture recommendations — lifecycle-aware collection and asynchronous lifecycle work.

## Handoff checklist

Implement W01–W14 from a fresh branch based on current main. Compare changed integration anchors against the pinned baseline. Maintain a traceability record from INV-01–INV-10 to T01–T56 and actual test output. Do not mark a package complete with an unused helper, a prose claim, a fake timing fixture or a skipped device test.

The first proof should be a deterministic test where optional MCP, catalogue and memory preparation never finish but an admissible remote greeting still dispatches exactly once. The second proof should show an explicitly required connected task still discovers and uses its authorized capability. Together they establish that Smart Readiness makes the app faster without making it less capable or less safe.
