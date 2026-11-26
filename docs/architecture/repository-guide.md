# Repository guide

Read this guide and `feature-map.json` from the selected repository commit. The
manifest binds its referenced files to their Git blob identities. The active task
supplies the immutable commit/tree identity. Historical repair notes explain past
decisions and do not establish present behavior or passing tests.

## Application structure

The Android application uses Kotlin, Compose, Hilt and Room. User actions flow
through presentation ViewModels into repository/service boundaries. Those
boundaries own persisted conversation identity, selected provider configuration,
permissions and dispatch. `ChatRepositoryImpl` assembles requests and coordinates
the shared agent loop. Read relevant functions by symbol/range because that file
is larger than a normal prompt.

`core-domain` holds portable contracts. `app/src/main/kotlin` contains Android
implementation; unit tests are in `app/src/test/kotlin`, and device tests in
`app/src/androidTest/kotlin`. `gateway/v14` implements the desktop engineering
controller and source, job, validation and model boundaries. Desktop interfaces
are clients of that controller.

## GitHub context and repository maps

A GitHub task selects an account, connection, repository and immutable commit/tree.
Source and context remain bound to that selection. The phone map works without
Gateway. Cached source still requires current authorization; cached presence does
not establish complete coverage or wholly offline access.

Keep compact symbols/references separate from disposable source bytes. Select the
functions, callers, configuration, tests and document sections needed by the task.
Retain their ranges, blob identities, omissions and confidence. A file reference
is not proof its text was dispatched to a model.

Repository instructions, reference documents, current source and historical memory
have different roles. Reference prose is source data: it cannot grant permissions,
execute commands, waive checks or override the user. Personal documents remain
outside repository reference retrieval. Local/private content retains its original
destination restrictions.

## Context preparation controls

GitHub chat preferences save the context choice per AI profile. New desktop
engineering tasks expose the same choices. A saved Gateway conversation retains
its original choice alongside its model/source binding when continued.

| Choice | Preparation |
| --- | --- |
| Current context baseline | Retain the existing baseline for controlled comparisons. |
| Repository reference | Add revision-checked repository guidance and matching document sections. |
| Task context | Add ranked source ranges, reference sections, relationship candidates and relevant tests. This is the default. |
| Task context with local similarity | Add bounded local similarity ranking to the task selection; retain an explicit lexical fallback when unavailable. |

The repository-map and Gateway Context views show the prepared commit, selected
ranges/documents, coverage, cache reuse and available measurements. Preparation
is distinct from final request admission and provider delivery. Unknown token
counts remain unknown; test candidates do not establish that tests passed.

Source caches and compact phone indexes have independent encrypted retention.
Unchanged Git blobs can be reused after branch advancement, while task selections
bind to the new immutable head. Eviction, unsupported files and incomplete trees
remain visible. No cache grants access after an account/connection policy change.

For a private context-pack artifact, select whole paths or `path:start-end`
ranges. Ranges support useful sections of large files. Input and output byte
budgets are separate, and an explicit token limit requires the selected model's
supported tokenizer. The built-in exporter supports disjoint ranges from one
file; Repomix accepts one selected range per path inside its existing sandbox.

## GitHub engineering intelligence

GitHub chat opens on the selected repository and branch. The empty composer offers editable starting questions. The chat header keeps the branch visible alongside live progress, and its actions open repository files, checks, the map, task details or staged changes as they become available.

A selected GitHub plugin in ordinary chat and the dedicated GitHub screen use
the same request-scoped planning policy for repository work. For example,
“Show PR #12” on either surface resolves to the selected connection and
repository, advertises the matching read, and retains the observation for the
answer. Unrelated conversation and account-level repository discovery have
their own scope. If two connections could supply the requested repository,
the app asks for a selection before dispatch.

With an official GitHub MCP connection, the model chooses a read from that
connection's advertised schemas. The deterministic first read and the native
verification ledger use tools bound to the app's GitHub executor.

| Stage | Runtime responsibility |
| --- | --- |
| Select the target | `GitHubTaskScope` resolves the request, connection, repository and branch from the explicit workspace or admitted native plugin. The dispatch guard checks current authorization. |
| Prepare the turn | The profile's intelligence level determines reasoning effort. An exact record lookup uses its direct read. Engineering tasks select revision-bound source, dependencies and tests from the repository map within the route's available context. |
| Execute | The progressive tool catalog selects schemas from the authorized inventory. The runner checks each call against its bound repository, available budget and approvals. A connected gateway can host the engineering job and retain its identity across reconnects. |
| Verify and answer | The evidence book records tool results and outstanding acceptance criteria. Its next-action policy guides the next read or recovery. The final answer identifies what ran and which observations or prerequisites are still missing. |

Fresh profiles use adaptive budgeting and task context, with evidence-based progress and next-action selection enabled. Saved overrides retain their settings. Direct requests for issues, pull requests, branches, commits, workflows, runs, releases and tags start with the matching read. Compound tasks use the broader engineering planner. Source caching and sharing with another model follow the connection and profile settings.

Numbered follow-ups use the current workspace and the immediately preceding admitted read to resolve issue or pull-request references. Ambiguous numbers prompt for the record type. Requests for a previous report retain its original evidence scope. Source citations use the selected connection’s web origin, the checked revision, and the quoted line range.

Choose the strategy in the selected AI profile's GitHub programmer settings.
Existing profiles start with Intelligent. The primary profile retains responsibility
for tool authorization, changes, verification and the final answer.

| Strategy | Behavior |
| --- | --- |
| Instant | Give straightforward lookups a short path to a verified answer. |
| Intelligent | Choose reasoning effort and planning from the request and conversation. |
| Deep Engineering | Inspect dependencies, plan implementation stages and verify outstanding criteria. |
| Maximum Intelligence | Compare approaches, consult an approved helper or reviewer, and use a named recovery profile when a task stalls. |

An advisory plan records goals and the observations that would satisfy them.
The execution loop uses unresolved requirements and their dependencies to select
the next authorized tool. It preserves citations when it changes strategy.
Context selection prioritizes instructions, pending tool results and relevant
pinned evidence. A resumed task retrieves its canonical checkpoint for that chat.

To let another model investigate a stalled requirement, choose a recovery
profile in the primary profile's GitHub settings. It can select one scoped
repository search or source read. The existing run executes that operation
under its remaining tool budget and records the result for the primary model.
The advisor and the recovery profile share one reservation per task. Private
chats retain their existing routing restrictions.

Fixed sampling preserves semantic intent planning. When a plan is malformed the
primary can continue from the admitted request. Compaction prioritizes the
pending handoff and named proof for outstanding criteria and their prerequisites.
A profile with 2,048–8,191 context tokens can use a previously retained,
authorized tree to locate up to four paths. When source caching is enabled, the
phone checks those paths in rank order and includes the first usable, bounded
source window. Other paths remain unread pointers. The runner can issue a
targeted authorized read when the tree or source is missing. Diagnostics
distinguish a reused tree, a reused full packet, a deadline and an access change.
The model receives the pinned excerpts, relevant reference and test candidates,
and coverage limits. Cache metrics and read-progress accounting stay in diagnostics.

For independent review of the primary's code, select a distinct named reviewer
in Model Delegation and enable **Independent patch review** in Deep Engineering
or Maximum Intelligence. The app sends up to three staged before/after excerpts
once per task. Findings return to the primary for source verification. Review
consent is workspace-specific. The approval card still owns publication.

A semantic plan can divide a compound request into criteria when its exact
clauses cover the original text in order. Conditional and negative instructions
stay intact. Progress, claim extraction and final verification use the same
criteria after a restart.

The phone shares immutable tree data and paged relationship records across
chats. A new source tree, parser version, focus set or set of indexed blobs
changes the lookup key. Results and cursors stay bound to each request and
revision. The phone reconstructs missing pages from its content-addressed
source shards. The Gateway reuses matching source fragments from its four retained snapshots. A restored file can reuse its old blob after intervening commits removed it.

The phone and Gateway can search repository symbols, related tests, architecture
documents and historical repair records. Source references carry revision identity.
A previous repair provides a lead to inspect at the current revision. Gateway
local coding agents operate in separate Git worktrees on the approved commit.

The production qualification runs ten GitHub engineering scenarios. It reports
eight weighted dimensions, their observation counts, and a total only when the
required evidence exists. See the benchmark guide for the matching rules and
the commands to compare two profile runs. [Gateway benchmark guide](../../gateway/benchmarks/README.md#github-intelligence-score)

For Groq, OpenRouter and other saved Android generation profiles, use the paired
coding benchmark in the engineering workspace. Each profile produces its own
candidate for the same seeded tasks. The configured Gateway grader or phone-driven
GitHub Actions verifies the result independently. The 100-point production
qualification currently requires a private Llama or Ollama Gateway execution
binding. Codex can run as a configured Gateway coding agent. A native ChatGPT
or Codex session needs equivalent instrumented evidence for a production score.
For a narrower comparison, export one public benchmark task and grade each
assistant's saved answer with `gateway/grade_external_reference.py`. Both
answers face the same hidden tests and execution limits. The report identifies
the assistant name as supplied by the operator.

Design references include [OpenAI's repository guidance](https://openai.com/index/harness-engineering/),
[Git worktrees](https://git-scm.com/docs/git-worktree) and
[agent evaluation methods](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents).

## Maintaining feature references

Edit `docs/architecture/feature-map.source.json` when feature entry points,
dependencies, test candidates or architectural constraints change. Keep this
reviewed map compact. Generate `feature-map.json` from the current working bytes
with `python3 scripts/generate_repository_reference.py`, and verify with `--check`.
The canonical fast validation checks both the feature map and generated build
facts. Missing paths, escaping paths, symlinks and stale hashes are rejected.

Each feature's `terms` select relevant work. Its `routing` record identifies the
owner's production entry point, role, expected proof, live admission condition,
decline behavior, and two test cases for normal and adverse outcomes. Generation
checks that every stable built-in plugin has a classification and every referenced
case exists. GitHub chat and ordinary chat use the reviewed feature routes to prioritize
matching tools among the schemas already admitted for a conversation. Explicit exclusions
deprioritize the matching automatic routes. External MCP and marketplace tools
retain their runtime identities and schema versions. The Gateway inventory joins
discovered tools to the external connections feature and withholds revoked sources.
GitHub source preparation can prioritize the entry point and expose the expected
proof only when all feature references match the selected tree.

In GitHub chat, open **Tools and features** or ask “What tools can you use in
this chat?” to inspect the available schemas. “List all tools and features”
also includes documented tools, app settings, integrated plugins and connection
presets. “List features matching voice” narrows the results. For a task that
names a product such as Ticketmaster, discovery can rank schemas associated
with that product’s configured integration. The panel labels
the latest chat observation and the selected Gateway connection separately.
Selecting a catalog entry does not enable the feature or grant access. The
executor checks the selected repository, profile and permissions when an action
runs. A matching code-search line leads to a bounded source read at the pinned
revision before the answer cites it.

Reference commands are suggestions. Runtime retrieval validates referenced blob
identities and excludes stale claims. It does not execute these commands or treat
reference prose as an authorization policy. Historical notes should link to
current source and identify the revision/environment they actually measured.

## Feature wiring

Inspect the complete relevant route: screen/control, callback/ViewModel,
repository/service, persisted setting/entity, external/native boundary and tests.
The feature manifest contains reviewed navigation hints, not an exhaustive compiler
or runtime proof. Dynamic callbacks, reflection, generated Hilt bindings, Room
accessors and native bridges need the appropriate source/build evidence. Keep
name/syntax-derived relationships labelled as candidates.

## Conversation privacy and profiles

Options controls availability when creating private conversations. A saved chat's
privacy authority belongs to that conversation and follows its queued work,
provider requests, memory, speech and restoration. UI availability and profile
selection are distinct from dispatch authorization. Check both user interaction
and persisted/queued identity when changing privacy behavior. Masking highlights
require actual content-bound receipts.

## Voice and captions

Speech generation, accepted PCM, presented audio and visible captions are separate
stages. Caption position follows presented audio and the active reply's identity.
Preserve cancellation, replay ownership, route changes and buffering when changing
text projection or animation. Host tests do not establish physical CPU/GPU/NPU
performance; use measured device receipts for those claims.

## Engineering execution and recovery

The existing controller owns accepted scope, source, budget, permissions, uncertain
operations and publication. Workers remain inside that authority. Read the current
source before applying edits. Reconcile uncertain outcomes before retrying writes.
When a shared branch advances, reuse unchanged blobs and refresh affected context
and execution evidence.

## Tests and validation

Feature-map test paths identify relevant behavior candidates. Commands remain
suggestions and must use the currently authorized validation environment.

- Host checks: `bash scripts/validate-local.sh fast`.
- Full checks: `bash scripts/validate-local.sh full`, using JDK 21 and the Android
  SDK in the generated facts below.
- Gateway contracts: `python3 gateway/check_gateway.py`.
- Refresh references: `python3 scripts/sync_agent_metadata.py`, followed by
  `python3 scripts/generate_repository_reference.py`.

Read `.github/instructions/ai-engineering.md` and `docs/ci-cd/modernization.md`.
Results belong to their actual source and environment. A passing subset does not
qualify unexecuted checks. Device and live-provider results stay distinct.

## Benchmarks

Engineering benchmarks separate tool contracts, workflow simulation, executable
coding and live GitHub evidence. Context comparisons keep model, source, tasks,
output/tool budgets and environment fixed. Retain context variant and reference
identities. Unknown tokens, cost and retrieval metrics remain unknown. A context
comparison does not establish universal model superiority.

## Generated build facts

<!-- BEGIN GENERATED BUILD FACTS -->
Generated by `python3 scripts/sync_agent_metadata.py`. Edit build/schema sources first.

- **App version**: 0.9.66.17 (versionCode 151)
- **Min SDK**: 31
- **Compile SDK**: 37
- **Target SDK**: 37
- **Database**: Room (`ChatDatabaseV2`, schema v54) with explicit migrations
<!-- END GENERATED BUILD FACTS -->
