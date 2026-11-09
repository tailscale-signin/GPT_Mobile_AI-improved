# GitHub preparation, Gateway conversations and diagnostic recovery

Branch: `fix/gateway-readiness-diagnostics-2026-11-08`

This repair extends the interrupted-work and Gateway-chat changes already on this branch. Its acceptance scope is the user's full GitHub-tool visibility request, findings F01–F20 from the supplied capture, and the Gateway home additions G01/G02. The supplied third numbered addition was empty.

## Product behavior

### GitHub mode

Every authorized GitHub tool schema is present on the initial execution request and remains present through normal continuation, batch repair and execution-circuit pauses. This includes authorized official MCP tools even when a native equivalent exists; their configured endpoint supplies the GitHub identity, so a custom connection alias cannot hide them. Ordinary chats retain progressive discovery and provider preference. A model whose context cannot hold the complete GitHub catalog receives a specific preparation failure; the app does not silently remove GitHub operations or start an unqualified text-only execution. Explicit permission revocation still invalidates the affected operation. Execution circuits keep their schemas visible while returning a repairable, undispatched pause.

Initial repository-admission failures that are proven unsent retain their typed cause, HTTP observation, cooldown, `dispatched=false`, and `remote_acceptance=NO`. A later dependent-read refusal preserves its own attempt evidence without erasing an earlier dispatch or unknown outcome. Refresh access and Change repository are native UI actions and do not need a model call. Refresh is scoped and deduplicated. Cached repository failures and authentication rejection are scoped to the actual credential and host; replacing a credential cannot inherit another token's denial merely because both belong to the same account. Pending prompts and staged work remain available.

An explicitly supplied upstream repository can be read independently of the selected destination. Its snapshots, evidence, branch and staged state have a separate scope. Those reads cannot authorize an upstream write or advance the destination's head. Official MCP operations with an explicit owner/repository schema also enforce the selected destination before a write reaches approval or execution. Their receipts do not become native verification evidence. Bare filesystem paths such as `app/src/Main.kt` are not repository references.

### Repository creation and transfer

The `github_repository` facade exposes capabilities, create, fork, import, clone and status. Owner, name, visibility, operation, source and copy mode are distinct fields. Native lifecycle operations require an explicit, one-time approval showing their exact source, destination and copy settings. Workspace trust, persistent grants and MCP tool names cannot bypass that approval. Account, permission and execution eligibility are checked again after the approval wait; other administrative GitHub operations retain their separate authorization requirement. Creation does not silently select the new repository as the current workspace.

For a compound request such as “create the repository, then implement a feature in it,” creation and subsequent engineering have an explicit repository-selection boundary. After the destination is verified, Change repository must select that destination before dependent writes proceed. The retained creation receipt can be checked again without repeating creation. The acceptance verifier keeps the implementation and test criteria incomplete until their own evidence exists; a successful creation receipt cannot complete the compound task.

| Operation | Executor | Completion evidence |
| --- | --- | --- |
| Create | Native GitHub REST | Retained creation ID plus fresh matching owner/name, visibility and independent-repository metadata |
| Fork | Native GitHub REST | Retained fork ID plus fresh destination metadata and exact upstream relationship; asynchronous acceptance remains pending |
| Independent snapshot import | Native Git data APIs | Pinned source commit, complete source tree and blob identities, retained destination base, non-force publication, and fresh matching destination commit/tree |
| Independent history import | Qualified engineering gateway with Git | Retained request and destination ID, exact heads/tags, tree and copy semantics, and required LFS objects |
| Local clone | Qualified engineering gateway with Git | Opaque retained workspace ID, exact source revision, checked-out revision and remote |

Native snapshots are bounded to 2,000 entries, 4 MiB per blob and 32 MiB total. Complete blobs are qualified before destination creation. A Git LFS pointer requires the gateway transfer workflow; copying a pointer alone is not accepted as transferring its content. Snapshot imports explicitly report that commit history is not preserved.

Source revisions and destination intent are protected records. If creation succeeds but its initial destination head was not retained, recovery keeps the pinned source and requires an explicitly reviewed destination head. It cannot adopt a newer head automatically. Status cannot use a model-supplied ID for an unrelated existing repository as creation proof: the ID must belong to this conversation's protected receipt.

Gateway transfers use a separate, explicitly configured engineering connection. The app and host bind the canonical request to the gateway installation, principal, GitHub actor and exact source/destination. The host retains an operation before starting work and fences each remote mutation before dispatch. A lost response is reconciled by its deterministic operation ID; it cannot turn into a second creation on another route. The Git surface uses fixed argument vectors, isolated workspaces, disabled hooks/configuration and explicit HTTPS remotes. Imported refs are published atomically with absence leases into a newly created repository.

### Gateway home

The Gateway tab reuses the main home toolbar, navigation tabs, conversation rows and New Chat control, filtered to Gateway conversations. New Chat opens the single eligible profile or a profile/model picker when several are eligible. Exact previously qualified configurations remain selectable while offline. Pending messages are durable and global dispatch is serialized; uncertain acknowledgments are reconciled before another submission. Clicking a queued, running or retained row opens its originating conversation.

The menu entry stays present from the first frame. A shared process monitor retains availability and performs idle checks approximately every three minutes, with active-work, network-change and configuration invalidation handled separately. Mounting Home does not start an independent availability probe. Current server inventory, retained rows and their observation times are distinct, so an offline server does not erase saved work or imply fresh inventory.

## Finding disposition

| Finding | Implementation or preserved behavior | Verification boundary |
| --- | --- | --- |
| F01 | Direct Refresh access / Change repository; typed negative cache; credential/host isolation and queued-read recheck | Native HTTP/cache and admission tests; signed-in device interaction remains environment-specific |
| F02 | Create/fork/snapshot native workflows; durable gateway history import and clone; explicit capability and prerequisites | Native HTTP/algorithm tests and gateway fixture tests; no real user repository was created as a test |
| F03 | Read-source scopes separate from write destination and evidence head | Native target/binding and evidence tests |
| F04 | Typed repository failures preserved through provider conversion, run failure records and visible fallback; unsent failures do not demand uncertain-write reconciliation | Conversion, delivery and terminal-record tests |
| F05 | Accepted task remains stable across recovery; lifecycle plans map prerequisite, operation and proof; incomplete acceptance retains its own terminal stage | Task continuity, operation acceptance and recovery tests; verifier remains strict |
| F06 | Full GitHub catalog avoids repeated discovery; consumed local pages advance their issued cursor; larger recovery pages use an issued cursor only when the actual result budget can retain them | Catalog, actual-runner, cursor and saved-view tests, including the small-budget case; remote/cached/local/verified counts remain separate |
| F07 | Engineering route is explicit; identity and capabilities are qualified before job access | Gateway client/repository tests; no assumed replacement port |
| F08 | Typed unsupported history stops catalog polling; cached/legacy rows remain usable; deliberate capability refresh can recover | Jobs ViewModel/repository tests |
| F09 | Exact model selection and reasoning preferences retained; router metadata controls are not silently dropped | Metadata/admission tests; the incident host's actual model still needs live qualification |
| F10 | Bounded sanitized HTTP metadata failure and correlation fields; requested, client-allowed, sent, acknowledged and effective controls are distinct | Metadata/control receipt tests |
| F11 | Safe reads have bounded transient recovery; cancellation and deterministic 400/404 contract failures remain distinct | Existing and expanded transport/admission tests; DNS failure is not relabeled as a bad Jina path |
| F12 | Single recognition owner/session, service rate-limit code retained, cooldowns, and retired-owner callback rejection | Recognition policy tests; microphone/service behavior needs a device |
| F13 | Immediate in-memory denial followed by serialized, awaited I/O persistence before replacement identity activation | Grant ordering/failure tests and sign-in call-site review |
| F14 | Remove confirmed main-thread grant persistence; add scoped frame-operation traces and preserve measured versus unavailable counters | Recorder/privacy tests; improved physical frame times have not been claimed |
| F15 | Preserve state-aware TTS and deterministic resource ownership; harden actual stale-callback path | Ownership review; benign IME/hardware/embedding/finalizer warnings are not represented as proven crashes or leaks |
| F16 | Existing durable pending IDs and restart/model wakeups retained; a cancellable cooldown-expiry wakeup closes the unchanged-model gap | Wakeup/idempotency tests; an eligible local model is still required |
| F17 | Invalid Local Assist profile references cleared on loading and profile/connection deletion; setup action preserves explicit selection | Reference policy tests and settings integration review |
| F18 | Protected per-run admission snapshots include profile/connection, complete catalog digest, accepted tools, omissions and explicit unreached stage | Snapshot tests and export-wiring review; full Android export integration remains part of the Android gate; process-latest snapshots are not substituted for run evidence |
| F19 | Output origins and exact invocation lineage distinguish model text, system errors and progress notices; provider timing requires an actual invocation | Journal, lineage, timeline and draw-timing tests |
| F20 | Populated configuration-only and metadata-only stages have explicit manifest states and metadata counts | Staged capture/archive integrity tests |
| G01 | Gateway-only home, eligible profile picker, offline durable queue, serialized dispatch and chat navigation | Gateway home/profile/queue tests |
| G02 | Stable menu entry and shared periodic background availability | Menu/monitor and Gateway home tests |

## Validation and limits

Validation results and exact suite boundaries are recorded in the adjacent task checkpoint. Host Kotlin suites compile the changed production policy, HTTP, journal and recorder code with explicit Android/storage/UI boundaries. Python transfer tests use controlled HTTP fixtures and real local Git repositories. These are meaningful behavioral checks, not device measurements or successful operations on the incident repositories.

The full Android Gradle gate must be evaluated independently. In the editing environment, the pinned Gradle 9.8.0 distribution download was blocked by network access, and the configured JDK 21/Android SDK 37 toolchain was unavailable. Do not treat a focused JVM result as a passing Android build, Compose render, Room migration, WorkManager/process-death exercise, microphone/TTS test, release benchmark or emulator result.

The original capture does not establish an OOM, fatal crash, ANR, permanent memory-work stranding or successful repository creation. This repair does not turn those absences into diagnoses. Physical release traces and live qualification of the user's exact gateway/model/account remain the authority for those outcomes.
