# GitHub Workspace APK Design Implementation

Text requirements imported from the supplied design document. Original diagrams and visual references remain in the DOCX. This specification is not an implementation-completion claim.

01  Design brief

GitHub WorkspaceAPK Builds

Design and implementation specification

Prepared for Emanuel Melo  •  GPT Mobile  •  Version 1.0

Decision: make APK discovery and downloading a first-class part of the existing GitHub Workspace. Keep the direct user flow independent of an AI conversation, then strengthen the shared GitHub integration so repository reads and writes use the same explicit context and reliable execution contracts.

| Document baseline | Recorded value |
| --- | --- |
| Repository | tailscale-signin/GPT_Mobile_AI-improved |
| Code inspected | Main 7060f32; APK feature b02f734 in draft PR 713 |
| Evidence snapshot | 17 October 2026 at 18:33 EDT / 22:33 UTC |
| Document status | Implementation specification for review; proposed work is not implemented by this document |

Intended outcome

A user opens Workspace, chooses a repository, opens Builds, and downloads either a published APK or an APK extracted from a successful workflow artifact. The app shows useful build identity, survives ordinary navigation and backgrounding, and makes completed files easy to save or share. PR 713 already implements this core path. [R01] [E01]

Scope and status language

Implemented means present in the feature branch or inspected baseline. Proposed identifies subsequent engineering work. Acceptance target identifies a check that must be demonstrated before its associated change is considered complete. Passing focused CI is evidence for that scope only; this document does not assert a completed merge, a fully green repository suite, or physical-device validation.

The implementation plan covers the Builds experience and the GitHub foundations that affect it: account and repository context, capability routing, branch-aware reads, safe edits, PR checks, release workflows, and provenance. Automatic installation, automatic publication, and a new backend service are outside this design.

Reading guide

Builds design  3–6  •  Architecture  7–14  •  Delivery and tests  15–21  •  Sources  22–24

02  Baseline and verification

The APK feature is isolated in draft PR 713. At the recorded snapshot, main remains at the audited baseline and the feature has not merged. The PR changes 13 files and uses the existing Compose, Hilt, WorkManager, SecretVault, MediaStore, and FileProvider infrastructure. It introduces no additional library dependency or Android permission. [R01] [R02] [R04] [E01]

| Check on b02f734 | Recorded result | Meaning |
| --- | --- | --- |
| GitHub build downloads | Passed | 28 tests: 12 catalog and 16 APK file tests; zero failures or skips. [E02] |
| UI Review APK | Passed | Review APK produced; device and benchmark test sources compiled. [E03] |
| Kotlin Lint | Passed | Formatting gate completed. [E04] |
| CodeQL | Passed | Analysis workflow completed. [E04] |
| Android acceptance | Passed | v0.9.50 acceptance workflow completed. [E04] |
| PR Validation and diagnostic audit | In progress | No full-suite success claim. [E05] |
| Unsigned release build | Skipped | Draft PR behavior; not release evidence. [E01] |

Review build identity

The current review artifact is 12116731917, from run 39073617144. Its archive is 598,398,059 bytes and is scheduled to expire on 31 October 2026 at 22:22 UTC. The artifact name records PR merge checkout 5e1cec9, while the PR head is b02f734. A workflow head SHA and the commit actually checked out for compilation can differ; the UI must not treat them as interchangeable provenance. [E03]

Known validation boundary

The earlier full-suite investigation found unchanged AgentRunner failure-boundary failures and a streaming cancellation hang: the markup guard can retain a short partial reply while a test waits to receive that reply before cancelling. Draft PR 712 tracks separate runtime work. Keep its validation and review independent from the APK feature. Recheck both PRs before scheduling a merge because these statuses are a timestamped snapshot. [R16] [E05]

No physical Android installation, background-transfer, save/share, or accessibility test is recorded as completed here. Compiling device tests does not execute those tests. The manual matrix in section 20 is a release gate for the user-facing download path.

03  User experience and navigation

Implemented navigation: Home → Builds → Code → Changes → PRs. Builds belongs in the existing Workspace and uses its current GitHub connection and repository selection. Downloads do not require an AI profile binding or a model tool call. [R01] [R02]

Primary download journey

Choose a repository, then open Builds. Keep the selected repository visible above the content; retain the branch selector for workflow builds.

Choose Releases or Workflow builds. Releases cover the repository; workflow builds follow the selected repository and branch.

Inspect the build card: release tag or workflow title, source, branch and supplied SHA when available, date, transfer size, variant hint, and GitHub details link.

Tap Download. Show queued or running state, determinate progress when a total is known, cancellation, and a clear result. Browsing and pagination never start a transfer.

For a completed workflow ZIP, choose the extracted APK. Use Save to Downloads or Share APK. Save confirmation offers Open Downloads.

Use Remove local download after work has stopped to reclaim private storage. Files already exported to Downloads remain available there.

Information hierarchy

Promote the latest stable release among loaded results and show preview releases clearly. Highlight a filename variant matching the device ABI without hiding alternatives. A filename match is a selection hint; it does not prove the package version, signature, or compatibility with an installed application. Workflow cards show the full ZIP transfer size, which may include several APKs. [R02] [R03]

Recommended refinement

Keep the dominant action on each card aligned with its state: Download, Cancel, Retry, or completed-file actions. Show actionable connection errors within the affected source, preserving the other source and local history. Add verified package metadata and provenance in a later increment; until available, label GitHub-supplied identifiers accurately. Do not introduce signing claims from artifact names.

The download flow ends with an APK the user can access through Android. Installation stays an explicit system interaction initiated by the user. The current feature offers no direct installer action and requires no new install permission. [R01] [R05]

04  Catalog identity and state

GitHubBuildCatalog already normalizes release assets and workflow artifacts into GitHubApkBuild. Preserve numeric asset identity, source, repository, and connection when constructing transfer identity. A selection change must not retarget an existing job. [R03] [R05]

| Source | Eligibility and scope | Display contract |
| --- | --- | --- |
| Releases | Published non-draft release; uploaded asset ending in .apk. Exclude AAB files and sidecars. | Tag, title, published time, asset size, preview flag, and details URL. SHA only when supplied as a SHA. |
| Workflow builds | Completed successful run for the selected branch and matching head repository. Exclude same-name fork heads and missing head identity. | Run ID, title, branch, head SHA, artifact size, created time, expiration, and details URL. |
| Artifact contents | An APK-like artifact name is a discovery heuristic. Downloaded ZIP contents must pass validation. | Expose validated APK entries individually after extraction; never present the ZIP as an installable APK. |

| Catalog state | Required behavior |
| --- | --- |
| Loading | Retain selection and source controls; do not show stale results as the new repository. |
| Loaded | Show eligible builds and explicit Load more when another page exists. |
| Empty with more pages | Explain no APKs were found on this page; keep Load more available. |
| Expired artifact | Keep identity and details visible; disable Download and offer the run link. |
| Limited or denied | Show a sanitized reason and next action; preserve usable local downloads. |
| Selection changed | Invalidate old request generation; ignore late responses from the previous scope. |

Bounded discovery

The delivered catalog requests 30 releases or 10 runs per page, enumerates up to 3 pages of 100 artifacts per run, allows three concurrent run-artifact lookups, and caps each metadata body at 4 MiB. An artifact enumeration limit produces a visible error rather than a silently complete list. Page requests are constructed from validated API paths rather than following arbitrary Link URLs. [R03]

Repository changes reset both sources. A branch change resets workflow results and preserves repository-wide releases. Keep these invalidation rules when introducing common context services. [R02]

05  Download lifecycle and recovery

The implemented worker uses unique WorkManager jobs identified from the connection and immutable build identity. Private build records supplement WorkManager so completed downloads remain discoverable after finished work records are pruned. Ordinary Activity recreation preserves the current live selection; full restoration of every browsing choice after process death is a separate acceptance target. [R02] [R04] [R05]

| Lifecycle state | User controls | Durability and exit condition |
| --- | --- | --- |
| QUEUED | Cancel | Persistent work identity; no file is published. |
| RUNNING | Cancel | Stream to private .part file; expose byte progress, not an unverified completed APK. |
| SUCCEEDED | Save, Share, Remove | Expose validated files and retained source metadata. |
| FAILED | Retry, Remove | Show sanitized reason. Preserve only resumable private state; honor any cooldown. |
| CANCELLED | Retry, Remove | Stop network/extraction work; publish no partial result. |

Retry and concurrency rules

Inside the worker, immediately before transfer, resolve the current credential through SecretVault. A retry obtains a fresh API download response rather than reusing a persisted signed storage URL. Resume only when range and total-length checks are consistent; if the server cannot support a valid resume, restart safely. Serialize worker and local-removal access through the existing transfer lock. [R04] [R05]

The worker bounds retries using MAX_RETRIES = 3 and persists rate-limit retry deadlines. Manual Retry before that deadline makes zero network requests. A transient network failure, a permission failure, an expired artifact, and an invalid archive require different user messages and recovery behavior. [R04]

Android scheduling constraints

Continue using foreground WorkManager for the delivered feature and preserve progress/cancellation on re-entry. Android documents job-quota considerations for long-running foreground workers on Android 16; background execution must therefore be tested on the target OS and must not be promised as unlimited. A later scheduler change requires evidence of an observed problem and a separate migration design. [P07]

Acceptance must distinguish ordinary backgrounding, process recreation, and user force-stop. After a force-stop, report recovered state when the app is reopened; do not promise that Android will continue the interrupted work while the app remains force-stopped.

06  Storage integrity and file access

Files become available only after transfer and validation. The implemented path checks an available SHA-256 digest, all outer archive paths, selected APK-entry lengths and CRCs during extraction, APK ZIP structure, and Android package parsing. It does not exhaustively CRC-check every ignored outer entry or every member inside an APK. Digest agreement does not independently establish publisher trust or installed-package compatibility. [R04]

| Guard implemented in PR 713 | Limit or rule |
| --- | --- |
| Downloaded asset or archive | 2 GiB maximum |
| Expanded APK output total | 2 GiB maximum |
| Individual APK | 1.5 GiB maximum |
| Archive enumeration | 2,048 outer entries; at most 20 APKs |
| Free-space reserve | 64 MiB in addition to anticipated work |
| Paths and names | Reject unsafe paths, including ignored entries; sanitize and flatten APK names safely. |
| Package validation | Require manifest and successful Android package parsing before exposure. |

Credential and redirect boundary

Tokens stay out of WorkData, saved build records, filenames, notifications, and errors. Authorization is sent only to the intended GitHub API host. Follow only checked HTTPS storage redirects, without forwarding the GitHub bearer header. Persist numeric artifact IDs, not temporary signed URLs. Private workflow artifact retrieval requires appropriate Actions read access. [R04] [R05] [P06]

Export and share

Save copies a validated APK into Downloads/GPT Mobile using a pending MediaStore entry, verifies the completed length, then publishes it. Delete a partial public entry if copying fails or is cancelled. Share uses the narrow github-apks/ FileProvider path and a granted content URI. Disable local removal while a save is active. [R05] [P08]

Retention and cleanup

Preserve durable local metadata without credentials and keep user-requested exported copies independent from private history. Proposed follow-up: expose per-download private size and last access, alongside the delivered Remove local download action. Do not silently remove an APK still advertised as available. Connection removal must prevent further authenticated work; retained private files require a documented retention choice.

07  Target architecture

Proposed architecture: keep the existing Kotlin and Android components, and introduce shared contracts around them. Direct Workspace operations and model-driven operations enter a common context and policy boundary, while maintaining their different authorization origins. Builds remains usable without a conversation or a tool-capable model.

Figure 1  Proposed service boundaries. Existing Builds services remain in place; shared admission and mutation coordinators are subsequent work. Diagram arrows describe service calls, not credential propagation.

Reuse before adding infrastructure

Retain GitHubWorkspaceClient, the response cache, repository index, credential-scoped rate limits, SecretVault, WorkManager, Room, and the existing approval/budget services. Add typed context, capability, and operation contracts where the current callers disagree. Avoid an alternative token store or parallel approval mechanism. [R03] [R05] [R08] [R10] [R11]

Failure boundaries

Read-only discovery may fail independently of local file access. Optional PR-check access may fail independently of reading PR details. Mutation coordinators own ambiguous remote outcomes and reconciliation. Transport owns cancellation, bounded responses, authentication, rate limits, and sanitized error classification. No caller converts incomplete evidence into a successful merge or publication result.

08  Repository context and admission

The repository already persists a connection-specific default in GitHubWorkspaceStore when the user selects Use in chats. Extend that behavior with conversation overrides and an immutable run snapshot. Separately, fix the contradiction in which native GitHub is exposed without profile bindings but the execution gate later requires a nonempty binding list. [R06] [R07]

| Context scope | Proposed contract |
| --- | --- |
| Connection default | Keep saved repository and symbolic ref; preserve existing data during migration. |
| Conversation override | Optional connection and repository selection for one conversation; honor temporary-chat retention. |
| Run snapshot | Connection UID, repository ID and canonical name, requested ref, resolved commit SHA, and resolution time. |
| Effective operation | Explicit target overrides conversation, then connection default. A different repository must not inherit another repository’s branch. |

Prepare once and authorize the actual target

Resolve defaults before computing an approval or duplicate-action identity. Today GitHubTool injects owner, repository, and ref after the surrounding layers have seen the raw arguments. Introduce GitHubEffectiveRequest containing the normalized target, operation, resolved read snapshot or write precondition, and payload digest. [R09] [R10]

Apply raw argument/call-ID limits and reject exhausted attempts before metadata requests. Validate actor and connection eligibility, preserving native and MCP binding rules.

Resolve the target using permitted reads charged to a bounded acquisition budget. Validate effective arguments and calculate the canonical plan digest.

Reserve the applicable execution budget and obtain approval required by current policy. Tool budgets apply to agent calls; Workspace uses shared policy and transport limits without an AI run.

Before dispatch, recheck connection state and policy version, claim the operation receipt, and apply write preconditions.

Record the confirmed or uncertain outcome. Separate permission validity from cached-result reuse; neither substitutes for the other.

An active run retains its captured snapshot if the Workspace selection changes. Refreshing to a newer branch head creates a new visible snapshot. Keep path case exact, compare repository names canonically, and invalidate affected state when a connection is deleted or its credential changes.

09  Capabilities and permission diagnostics

Proposed: route by the requested operation and verified capability, rather than suppressing an entire GitHub provider whenever a native route exists. The current action inventory lacks parts of the PR lifecycle that the planner describes, and blanket MCP suppression can remove otherwise available operations. Repository actor push permission also does not prove that the current token can invoke a particular endpoint. [R06] [R09] [R11]

| Capability dimension | State and use |
| --- | --- |
| Model tool protocol | NATIVE_STRUCTURED, VALIDATED_PROMPT_BRIDGE, NONE, UNKNOWN; bind to model, endpoint, adapter/runtime revision, and verification time. |
| GitHub operation | Native or configured MCP route supports this exact read or mutation; include schema and execution owner. |
| Permission evidence | ALLOWED, DENIED, UNKNOWN, RECONNECT_REQUIRED with reason, checked time, credential epoch, and repository scope. |
| Live policy | Local read-only/write policy and actor grant remain valid at dispatch, including delegated actors. |

Routing rules

Prefer the authorized native route when it supports the operation; use configured MCP only within its capability and grants. A denial must not trigger weaker authorization. Assign one execution owner and claim dispatch atomically by durable command UUID, independent of CLIENT/GATEWAY delivery or regenerated call IDs. Reject another owner and return the existing receipt for a duplicate. [R10] [R14]

Preserve the existing LiteRT validated tool bridge and extend the capability model consistently to other providers. Every parsed call passes through the same admission and budget path. A helper-only limitation must not disable a working primary-agent connection; keyword matching such as “Check CI” should not decide whether repository context exists. [R07] [R14]

NONE omits model-executed tools while preserving Workspace access. UNKNOWN permits a bounded capability refresh or validated protocol probe, not assumed support. Probes perform no GitHub mutations; a helper route requires that helper’s own established capability and authorization.

Connection experience

Add an operation-oriented connection check in the existing settings flow: identity and repository read, release read, Actions read, and separately reported mutation capabilities. A read-only probe proves only what it exercised. Distinguish 403 permission denial from rate limiting using response evidence. Offer reconnection when necessary, but preserve usable sources and downloaded files.

Partition caches by stable connection and credential/access scope; invalidate them on relevant changes. Reuse existing cache refresh patterns and independent rate-limit buckets for core, search, code search, and GraphQL. Do not infer a write grant from a successful catalog request.

10  Branch aware reads and search

The current reader rejects files above 256,000 bytes before applying a requested line range. That already excludes useful slices from this repository’s roughly 261 kB chat repository source and roughly 618–621 kB gateway sources. Its search path also filters only the first 30 results and does not accept a selected ref. GitHub REST code search covers the default branch, so selected-branch conclusions need another evidence path. [R08] [P05]

Immutable read contract

Introduce GitHubCodeSlice: repository identity, requested ref, commit SHA, blob SHA, path, start/end lines, optional total line count, returned text, continuation cursor, and completeness/limit reason. Resolve the path from the captured commit and keep commit and blob identities separate. Reuse the existing response cache and repository index.

| Proposed initial bound | Design reason |
| --- | --- |
| 10 MiB source input per blob | Permit practical large-file slices with a finite scan budget. |
| 64 KiB returned text | Keep model and UI responses bounded independently of file size. |
| 2,000 returned lines | Make continuation predictable; enforce the byte limit as well. |
| Snapshot-bound cursor | Tie continuation to commit, path, encoding, and position; reject cross-snapshot reuse. |

Reader and search algorithm

Stream and incrementally decode UTF-8; avoid splitting the whole file into an in-memory line list. Define behavior for long individual lines, binary content, unsupported encoding, LFS pointers, symlinks, and submodules. Stop promptly on cancellation. A request exceeding a bound returns an explicit limit result with a usable continuation when possible.

Search enumerates the captured tree and verifies candidate matches against immutable blobs. Enforce aggregate limits for fetched bytes, files, HTTP requests, elapsed time, and concurrency as well as the per-blob limit. Bind search cursors to access scope, repository, commit, normalized query, filters, and traversal version. Return examined/skipped coverage, truncation and continuation. Exhaustion returns PARTIAL with the precise limit reason; a bounded negative scan is not proof of absence.

Cache decoded content privately under credential/access scope, repository identity, and blob SHA with a finite disk budget. Apply filtering and test-file discovery across the declared search coverage, not merely the first remote page. Label textual references as text matches; compiler-resolved symbol references require different analysis.

11  Atomic edits and operation receipts

The existing commit_files implementation is a strong foundation: explicit working branch, expected head SHA, one commit, preserved executable modes, default-branch protection, and symlink/submodule guards. Its final REST ref update uses force=false, which does not express an exact old-head condition. Legacy update_file follows a different path and can omit branch and SHA. Unify both operations. [R08] [R09] [P04]

Prepared command

Create a durable PreparedGitHubCommand with command UUID, actor and connection, repository ID, operation, explicit ref, expected head, staged change-set handle, canonical plan digest, approval scope, state, and remote IDs. The digest covers paths, modes, content hashes, preconditions, and intent. Keep full changes in a bounded local store so the generic 16 KiB tool-argument ceiling remains useful. [R10]

Apply exact-context patches to blobs from the expected commit. Reject stale patch context. Preserve the existing 20-file, 256,000-byte per-file and 1 MB aggregate commit bounds initially; increasing them requires a separate budget decision. Review the actual staged diff, not a model-generated description alone. [R08]

Write a temporary change set, verify its digest, flush and atomically finalize it, then publish its handle in Room. Recheck the approved digest at dispatch and recovery; missing, partial, or changed payloads cannot execute. Interrupted Git-object staging may leave unreferenced objects, but no branch ref may publish until the created commit SHA is durably recorded.

Exact publication condition

Continue constructing trees and commits through the existing Git Data path so mode handling is retained. Persist the created commit SHA before publishing. Use GraphQL updateRefs with refs/heads/<branch>, beforeOid=expectedHead, afterOid=createdCommit, and force=false. GitHub documents the prior-object condition and atomic ref updates. If unsupported, return a capability error rather than silently weakening the condition. [P02]

| Receipt outcome | Meaning and recovery |
| --- | --- |
| REJECTED_BEFORE_DISPATCH | Local validation or policy stopped the command; no remote write attempted. |
| FAILED_CONFIRMED | A definite server rejection is known; preserve its sanitized reason. |
| SUCCEEDED | Record verified remote identity and return the existing result on duplicate delivery. |
| OUTCOME_UNKNOWN | A request may have taken effect. Reconcile saved identities before considering any repeat. |

Persist PREPARED, STAGED, and DISPATCHING phases before terminal outcomes. On a lost ref-update response, a saved commit at the branch head or verified in its ancestry supports success. A different head requires reconciliation. An unchanged head alone does not prove the write never happened. Reuse the staged commit only after reconciliation; never manufacture another commit to conceal ambiguity. GraphQL clientMutationId is not an idempotency guarantee.

12  Pull request lifecycle and checks

Proposed: extend native operations to update PR metadata, convert draft state, mark ready, inspect complete review evidence, prepare a merge, execute the approved merge, and reconcile its outcome. Retain conservative NONE/UNKNOWN behavior from the current check summary. Today PR context takes one page of files and checks and retains at most 50 check runs; it does not include complete review and commit-status evidence. [R08] [R09] [R11]

Merge evidence snapshot

| Evidence | Required treatment |
| --- | --- |
| PR identity | Number, repositories, qualified head/base refs and SHAs, draft state, allowed methods, and mergeability. |
| Checks and statuses | All relevant pages for the frozen head; retain producer/context identity and applicable latest result. |
| Reviews and rules | Approvals, dismissals, requested reviewers, unresolved requirements, and applicable repository policy evidence. |
| Completeness | Expose API ceilings, unreadable evidence, exhausted budgets, and unknown rule state; do not label them passing. |

Dispatch and queue behavior

Bind merge authorization to the PR, frozen head SHA, method, and intended scope. Re-read the head and relevant policy evidence immediately before dispatch. A changed head invalidates the plan; a changed base requires refreshed evaluation. The server remains authoritative for its rules, and a client-side base read is not an atomic lock.

For the current GitHub.com API, support asynchronous merge with expected sha and bypass_rules=false. Persist the request UUID and represent pending, queued, and merged separately. Verify final PR state and the merge commit before reporting completion. A queue acceptance is not a completed merge. [P03]

Bounded review and batch operations

Keep PR details readable when optional check access is denied, while merge eligibility becomes unknown or blocked as appropriate. Request more evidence when pagination is incomplete. For an authorized request covering several PRs, materialize the exact list and order, execute sequentially, refresh base-dependent evidence after each merge, and return a receipt for every item. Do not expand scope through a stack or dependency automatically.

PR creation uses the same durable command identity. After a lost response, look for the recorded repository, qualified head, base, and command marker before another POST. Editing a PR description and merging a PR remain distinct operations with distinct reviewable intent.

13  Release workflow and provenance

The inspected release client and workflow disagree: the client validates v<version>, while non-main workflow runs derive a prerelease suffix. Dispatch currently has no declared workflow inputs, drops the response, and status lookup guesses among recent runs. An existing-draft shortcut can publish before verifying the requested source and assets. Replace these assumptions with a versioned contract. [R08] [R12]

| Contract field | Required meaning |
| --- | --- |
| command_id and schema_version | Stable operation identity and explicit contract version. |
| workflow path and dispatch ref | Exact allowlisted workflow; branch/tag selecting its definition. |
| expected_workflow_sha | Approved workflow revision, verified by the workflow before sensitive work. |
| source_sha and release_tag | Immutable source to check out and exact requested tag; validate against repository version rules. |
| draft and prerelease | Explicit intent preserved through creation, asset upload, and final state. |
| notes or notes policy | Bounded reviewed notes or a defined generation rule; no hidden publication-side substitution. |

Dispatch and completion

The client already selects API version 2026-03-10. Dispatch returns HTTP 200 with workflow_run_id and URLs; retain that ID and poll the exact run. Add a command marker to run naming and provenance for lost-response recovery. [R08] [P01]

Resolve BUILD_SOURCE_SHA once for every source checkout, tag-collision comparison, package identity check, provenance record, artifact label, and release target. Keep GITHUB_SHA as the workflow revision when it differs. Replace current uses that assume workflow and source identities are the same. Validate an intentional mismatch between those identities in GH16 and GH19. [R12] [R13]

Serialize by repository and release tag, then check existing release/provenance identity before repeating work. Serialization alone does not guarantee exactly-once behavior. A duplicate command returns the existing receipt; conflicting provenance stops. A partly created draft requires reconciliation, not automatic publication or asset replacement.

Preserve repository release gates

Reuse existing exact-commit validation, reliability evidence, version/tag collision checks, signing continuity, runtime/bundle checks, checksums, provenance.json, and attestations. Preserve the repository’s documented release exception without broadening it. Success requires the expected tag target, intended draft state, required assets, matching checksums, and provenance. [R12] [R13]

Extend build metadata with package ID, version name/code, ABI, signer fingerprint, built commit, workflow revision, and verification source when trustworthy evidence exists. Keep unknown fields unknown. Ordinary archive integrity is available before this proposed provenance layer and remains a separate claim.

14  Data model and migration

Proposed persistence: place conversation context and mutation receipts behind repository interfaces and the existing Room database. The inspected database is version 40; choose the next available migration version at implementation time because parallel work may advance it. Keep existing Workspace preferences readable during transition. [R07] [R11]

| Record | Core data and invariant |
| --- | --- |
| RepositorySelection | Connection UID, repository ID/name, requested ref, scope and update time. Mutable user preference. |
| RepositorySnapshot | Selection plus resolved commit SHA, run/conversation IDs and resolution time. Immutable evidence for a run. |
| PreparedCommand | UUID, operation, actor, target/preconditions, digest, change-set reference, approval/receipt link and policy version. |
| CommandOutcome | Phase, confirmed/unknown status, created commit, PR/release/run IDs, request UUID, timestamps and sanitized error. |
| StagedChangeSet | Private bounded payload, file modes/content hashes, exact base, diff preview and lifecycle state. |
| CapabilityEvidence | Operation, route, credential epoch, scope, allowed/denied/unknown state, reason, checked time and version. |
| Existing build history | Retain build.json/apks.json compatibility and WorkManager reconciliation; do not move this data merely to unify storage. |

Migration invariants

Preserve saved connection defaults exactly. Add optional conversation overrides without changing existing conversations’ targets. Give command IDs a durable uniqueness constraint across restarts and regenerated model invocation IDs. Link GitHub outcomes to current approval and delivery records; those existing records do not themselves identify a remote commit or release. [R10]

Legacy selections remain valid without a repository ID. Migrations make no network requests; hydrate canonical identity during the first permitted metadata read and retain the saved selection when offline or access is unavailable.

Store no credentials or temporary storage URLs. Delete temporary-conversation snapshots according to existing retention rules. On credential replacement, invalidate access-dependent caches and unexecuted prepared actions; do not erase the evidence needed to reconcile a possibly executed mutation. Connection deletion blocks new authenticated execution and requires a defined policy for retained local files.

Upgrade and rollback

Test migration from the inspected schema and from the actual immediately preceding release schema. Add fields compatibly and gate new command creation during rollout. If the new path is disabled, keep receipt reading and reconciliation available. Never roll back by treating unknown commands as fresh requests or deleting the journal.

15  Implementation work packages

The feature branch can be reviewed on its own merits. Subsequent work should land as small dependency-ordered changes with named behavior and evidence. Package names below are proposed; file names are precise targets for the implementation handoff, not a claim that new classes exist today.

| Package | Primary files or services | Completion gate |
| --- | --- | --- |
| A  APK acceptance | Existing GitHubBuildsPanel/ViewModel, GitHubBuildCatalog, GitHubApkDownloadManager/Worker, GitHubApkFiles, resources and focused workflow. | Complete device QA and review PR 713; retain all delivered safeguards. |
| B  Context and admission | New GitHubContextRepository, GitHubExecutionAdmission, GitHubOperationPolicy; update WorkspaceStore, GitHubTool, AgentToolResolver and ChatRepositoryImpl. | Same effective target and policy decision across UI/native/delegated calls. |
| C  Capability and reads | New ProviderToolCapabilities, GitHubRoutePlanner, GitHubRepositoryReader and GitHubSnapshotSearch; reuse RouteCapabilityCache, response cache and index. | Branch-pinned, bounded, honest reads; operation-level route diagnostics. |
| D  Reliable commands | New GitHubCommand, GitHubChangeSetStore and GitHubCommandRepository; update WorkspaceClient, GitHubTool, approval linkage, Room entity/DAO/migration. | Exact ref precondition and restart-safe reconciliation; legacy write path retired. |
| E  PR lifecycle | New GitHubPullRequestCoordinator and evidence models; extend native schema, paginated client reads, PR UI and receipt views. | Complete evidence gating; changed heads and queued merges reported correctly. |
| F  Release contract | New GitHubReleaseContract/Coordinator; update release-build.yml, check_release_trigger.py and verify_release_identity.py. | Exact run receipt and verified source/tag/draft/provenance contract. |

Dependency order and ownership

A can proceed independently. B precedes C and D; D precedes mutation parts of E and F. A release provenance display can follow F without delaying ordinary downloads. Assign Android UI, GitHub data/agent, and CI/release owners for each package, with one reviewer responsible for the cross-layer contract. These are role assignments to make during planning, not named staff commitments.

Do not combine the separate AgentRunner runtime fix into the APK change merely to make unrelated CI green. Track the baseline blocker explicitly and rerun required integration gates on the actual merge candidate. [R16]

16  Builds acceptance criteria

The following are required acceptance targets, not assertions that all cases have already been exercised. The existing 28 tests cover catalog and file-processing behavior; worker, Android lifecycle, and accessible UI behavior need their own evidence. [R15] [E02]

| ID | Scenario | Pass condition |
| --- | --- | --- |
| APK01 | Release filtering | Only published uploaded APK assets appear; drafts, AABs and sidecars do not. |
| APK02 | Repository and branch identity | Exclude a fork with the same branch name and a run with missing head repository; include valid successful selected-branch runs. |
| APK03 | Pagination after filtering | An empty eligible page with more remote results still exposes Load more; limits are visible. |
| APK04 | Expiration and permissions | Expired artifacts cannot start work; denied Actions access has an actionable error and does not erase release or local state. |
| APK05 | Rapid selection changes | Across ten rapid repository/branch changes, zero late responses appear under the wrong selection. |
| APK06 | Account isolation | Two connections to the same repository retain separate transfer identities and history. |
| APK07 | Explicit and unique transfer | Refresh/pagination creates zero jobs; repeated Download for one active identity creates one job. |
| APK08 | Archive extraction | A ZIP containing three valid APKs and sidecars exposes exactly three APKs with safe distinct names. |
| APK09 | Corruption and hostile paths | Reject traversal, invalid size/CRC/digest, entry overflow, and malformed APKs; expose zero invalid files. |
| APK10 | Cancel and retry | Cancellation leaves no published partial result; a resumed successful transfer matches the expected digest and length. |
| APK11 | Rate-limit cooldown | Retry before the persisted deadline makes zero network requests, including after process recreation. |
| APK12 | Export and removal race | A successful saved APK persists after private removal; save/remove cannot race into corruption. |

Use deterministic catalog and archive fixtures for repeatability, plus real authenticated smoke checks for API behavior. Never embed a token in a fixture. An automated pass should name its tested layer so a unit result cannot be mistaken for device evidence.

17  Android and integration acceptance

| ID | Scenario | Pass condition |
| --- | --- | --- |
| APK13 | Rotation and background | An active transfer remains identifiable and controllable after navigation and Activity recreation; UI does not start a duplicate. |
| APK14 | Process and WorkManager cleanup | Completed local history survives finished-work pruning; process recreation reconciles work and private files. |
| APK15 | Save and share intents | Saved file is readable in Downloads/GPT Mobile; recipient receives a valid granted content URI; cancelled copy leaves no public partial. |
| APK16 | Accessible layout | At 360 dp width and 200% font scale, filenames and actions remain readable/reachable without overlap. |
| APK17 | Accessible controls | TalkBack announces source, build identity, progress/result and action purpose; touch targets and contrast meet section 20 targets. |
| GH01 | Native no-binding execution | An enabled authorized native GitHub connection can execute without a profile binding; disabled or revoked connections cannot. |
| GH02 | Effective argument identity | Explicit arguments and identical resolved defaults produce the same target/digest; pending policy revocation blocks dispatch. |
| GH03 | Context isolation | Upgrade preserves defaults; conversation overrides do not leak; a running snapshot survives later Workspace selection changes. |
| GH04 | Branch-pinned reading | A nondefault-branch-only symbol is found at its recorded SHA; a push between pages does not change the snapshot. |
| GH05 | Large-file slices | Read a small range from a 600 KiB UTF-8 file; handle multibyte boundaries, long lines, binary and limit results correctly. |
| GH06 | Search completeness | Find later-page matches. A large tree can hit an aggregate limit without exceeding a blob limit; return PARTIAL with a query-bound cursor. |
| GH07 | Provider and execution owner | Native/bridge calls share admission; unsupported/helper-only failure is precise; concurrent CLIENT/GATEWAY delivery claims one command once. |

Test boundary

Use coroutine and HTTP fakes for catalog/routing/reconciliation logic; run Android instrumentation for WorkManager, PackageManager, MediaStore, FileProvider, and Compose semantics. Do not replace meaningful fault injection with tests that only repeat the implementation’s constants.

18  Mutation and release acceptance

| ID | Scenario | Pass condition |
| --- | --- | --- |
| GH08 | Unified write policy | Legacy one-file and multi-file edits require the same explicit branch, expected head, limits, path handling and default-branch rules. |
| GH09 | Ref movement race | A ref change before publication rejects the stale update through the exact beforeOid condition; executable modes remain intact. |
| GH10 | Stale or changed payload | Reject mismatched patch context or changed/missing staged content before dispatch; verify the approved digest again. |
| GH11 | Interrupted commit command | Recover a persisted commit SHA after lost ref-update response without duplicate publication; also test death between payload finalization and receipt insertion. |
| GH12 | Duplicate PR request | A duplicate/lost-response PR command resolves the recorded head/base/marker before another creation attempt. |
| GH13 | Complete check evidence | A failing check beyond the first page blocks eligibility; missing status/review/rule evidence stays unknown. |
| GH14 | Changed merge target | A new PR head invalidates the prepared plan; changed base refreshes evaluation; authorization scope is preserved. |
| GH15 | Merge queue result | Accepted, pending, queued and merged remain distinct; completion records the verified merge commit. |
| GH16 | Release inputs | Tag, source/workflow SHAs and draft/prerelease intent validate before signing; distinct workflow and build SHAs stay correctly separated. |
| GH17 | Dispatch receipt | Persist the API-returned workflow run ID; status checks never guess from the latest unrelated run. |
| GH18 | Duplicate release recovery | Command/tag serialization plus provenance reconciliation returns the existing result or a conflict; no unreviewed second publication. |
| GH19 | Provenance validation | Wrong source tag/attestation, incomplete assets, signer/checksum mismatch or wrong draft state blocks success, including differing workflow/source SHAs. |

Fault injection and evidence

Inject cancellation and response loss around mutation boundaries, including process death after server-side success and during staging. Assert receipts and published branch/PR/release effects; unreferenced intermediate Git objects are not successful branch changes. Prove no duplicate publication rather than merely checking that a retry method was called.

Mocked API tests establish local contract behavior. A controlled repository smoke test must verify the current GitHub.com CAS, workflow-dispatch, and merge-queue response contracts before enabling the corresponding production route. [P01] [P02] [P03]

19  Observability performance and errors

Proposed instrumentation: reuse the existing telemetry and tool-delivery foundations, attaching stable operation and source identities without recording credentials or file contents. Collect only the evidence needed to diagnose a failed action or measure the user journey. [R10]

| Area | Event or metric | Interpretation |
| --- | --- | --- |
| Catalog | Source, scoped request generation, duration, page count, item count, result/limit reason. | Separate network latency from empty eligibility and denied permissions. |
| Download | Queued/start/complete/cancel/fail, bytes, resume outcome, retry count and cooldown. | Measure completion rate excluding user cancellations; avoid logging signed URLs. |
| Command | UUID, operation, phase, plan digest, remote IDs and reconciliation outcome. | Trace one user intent across retries and restarts without duplicating mutations. |
| Routing | Capability version, execution owner and sanitized admission/route reason. | Explain unavailable actions without assuming model identity proves support. |

Proposed performance targets

Set baselines on the agreed device/network before committing to a remote-latency SLA. Require no APK bytes buffered wholly in memory, bounded metadata and archive work, responsive cancellation, and no main-thread network or large file copy. As a starting UI target, reflect local action acknowledgement within 200 ms and throttle frequent progress updates to avoid excessive recomposition. Measure and adjust these proposed targets from traces.

Error taxonomy

Use stable internal codes for authentication, permission, rate limit, expired artifact, connection unavailable, invalid archive/package, insufficient storage, stale context/head, incomplete evidence, unsupported capability, and outcome unknown. Pair each with a short localized explanation and a viable next action. Preserve cancellation as cancellation. Technical diagnostics may retain sanitized request IDs; normal UI should not expose stack traces or raw server bodies.

Correctness metrics

The target for duplicate remote mutations, credentials in persisted records, and published partial APKs is zero. Track download success by source and OS, save/share success, time from Builds opening to a usable APK, and the rate of correctly reconciled unknown outcomes. Report sample size and environment; do not invent baseline percentages or throughput improvements.

20  Device QA and release gates

Run the primary manual flow on the user’s ROG Phone 9 Pro, recording its actual Android version, app version, connection permissions, network, and available storage. Add API 31 and API 37 emulator/device coverage where available; the inspected app declares minimum SDK 31 and target SDK 37. Device information here is a test plan, not completed execution evidence. [R01]

| Session | Required exercise |
| --- | --- |
| Discovery | Published ARM64 APK; multiple variants; selected-branch workflow ZIP; private repository; expired and empty pages. |
| Transfer | Wi-Fi/mobile transition; interruption/resume; cancellation; retry; low storage; revoked credential; persisted rate-limit cooldown. |
| Lifecycle | Navigate away/back, rotate, background, recreate process, prune completed work, and separately force-stop/reopen. |
| Android file access | Save, Open Downloads, Share, remove private copy, and reopen exported copy. Inject save cancellation through instrumentation or lifecycle interruption. |
| Presentation | Light/dark themes, long repository/branch/file names, 360 dp width, 200% font scale, and TalkBack. |
| Notification behavior | Allow and deny notification permission; verify visible in-app progress and documented OS behavior without hidden failure. |

Accessibility acceptance targets

Provide at least 48 dp touch targets and meaningful localized semantics for icon and repeated file actions. Use labels such as “Save [filename] to Downloads” where context would otherwise be ambiguous. Announce completion/errors without narrating every progress tick. [P09]

Verify at least 4.5:1 contrast for normal text and 3:1 for large text as design targets. Check real rendered theme colors and focus/disabled states; these targets do not establish a completed WCAG conformance audit. [P10]

Merge and distribution gates

For PR 713, require focused APK tests, review APK assembly, lint/security checks, the manual download matrix, and an explicit decision on the outstanding baseline suite. The independent runtime fix must receive its own evidence. On later packages, add the applicable acceptance IDs and migration/fault-injection results. Rerun gates on the actual merge candidate and keep workflow success distinct from installed-device behavior. [E02] [E03] [E04] [E05]

21  Rollout decisions and completion

Incremental rollout

Ship the reviewed Builds path first. Introduce new context and admission contracts behind existing interfaces, initially comparing old/new decisions in shadow mode with zero duplicate HTTP dispatch. Enable the new read path, then snapshot search, then mutation coordinators. Finally enable the explicit release contract and verified provenance display. Preserve saved downloads throughout.

Decision register

| Decision | Recommended disposition |
| --- | --- |
| Where users download | Existing Workspace Builds tab with Releases and Workflow builds; no AI dependency. |
| How writes publish | Existing mode-preserving commit construction plus exact GraphQL ref precondition; explicit unsupported state if unavailable. |
| How builds are trusted | Separate transfer integrity, package metadata, signer identity, and verified build provenance; expose evidence level. |
| How permissions are reported | Operation-specific evidence plus live local policy; read success does not imply write permission. |
| How unknown outcomes recover | Durable command journal and remote reconciliation; no blind mutation retry. |
| Release metadata authority | Versioned workflow contract and immutable source/workflow identities, with returned run receipt. |

Remaining implementation decisions

Choose concrete owners, rollout switches, retention duration for staged commands and private downloads, cache disk budget, and the initial capability probe policy during engineering planning. Confirm whether GitHub Enterprise hosts are a future requirement; the delivered APK transport is GitHub.com-specific. If Enterprise support is added, use an explicit validated host configuration and per-host API capability evidence rather than weakening the current redirect boundary.

Definition of done

Each work package is complete when its code is reviewed, its listed acceptance targets have recorded evidence, migrations preserve existing user state, user-visible errors explain a next step, and ambiguous remote operations remain recoverable. Builds is complete when a user can find, download, save/share, and later access the correct APK on the target Android device with clear source identity and no partial or duplicate result.

This document is a handoff for that work. It records the feature already built, the verified limits of its evidence, and the proposed next changes. It does not authorize or perform a merge or publication.

22  Repository source register

Repository links are pinned to the inspected baseline or feature commit. Bracketed references throughout the document link to this register. File names and paths identify the engineering evidence; proposals in the body are design judgments based on that evidence.

R01  Inspected repository and feature specification

Main baseline 7060f32  •  Feature implementation b02f734  •  APK feature documentation  •  Android build configuration

R02  Workspace navigation and Builds presentation

GitHubWorkspaceScreen  •  GitHubBuildsPanel  •  GitHubBuildsViewModel

R03  Catalog identity filters pagination and limits

GitHubBuildCatalog

R04  Transfer archive validation and foreground worker

GitHubApkFiles  •  GitHubApkDownloadWorker

R05  Download history export and local removal

GitHubApkDownloadManager  •  FileProvider paths

R06  Tool availability and execution admission

AgentToolResolver  •  ChatRepositoryImpl authorization

R07  Existing context persistence and delegated routing

GitHubWorkspaceStore  •  Use in chats action  •  DelegationGitHubPolicy

R08  Native reads writes PR context and release client

GitHubWorkspaceClient reads and search  •  Atomic commit implementation  •  Release publication and run lookup

23  Implementation source register

R09  Tool argument defaults legacy writes and planning

GitHubTool effective defaults  •  Legacy update_file  •  GitHubOperationPlanner

R10  Budget approval and delivery receipt foundations

ToolExecutionBudget  •  ToolApprovalManager  •  ToolDeliveryReceipt

R11  Database and operation evidence

ChatDatabaseV2 version 40  •  GitHubCheckSummary  •  Workspace capability presentation

R12  Signed release workflow and trigger rules

release-build.yml  •  check_release_trigger.py

R13  Release identity and provenance

verify_release_identity.py

R14  Provider capability and route infrastructure

LiteRtLmAdapter  •  RouteCapabilityCache  •  AgentContracts execution owner

R15  Focused APK regression coverage

GitHubBuildCatalogTest  •  GitHubApkFilesTest  •  Focused GitHub build downloads workflow

R16  Separate streaming baseline dependency

AssistantTextMarkupGuard  •  AgentRunnerTest  •  Runtime fix PR 712

Identity note: the feature head is b02f7344ca1a477a4b1a1046887e3580cb2909c8. Baseline-only code references use 7060f3245cb4012ed71043a09aa5595f9629563b. The review artifact’s merge checkout is 5e1cec99c872ba651a653a77d538d298beeb709f. Use the appropriate identity for code review, workflow status, and package provenance.

24  CI evidence and platform references

CI results below are recorded at 18:33 EDT / 22:33 UTC on 17 October 2026. Workflow pages may show later changes. Platform documentation was checked during preparation; verify the supported host/API contract again during implementation.

E01  APK implementation pull request

Draft PR 713

E02  Focused tests on feature head

Run 39073617174

E03  Review build on feature head

UI Review APK run 39073617144  •  Review artifact 12116731917

E04  Other completed checks on feature head

Kotlin Lint  •  CodeQL  •  Android acceptance

E05  Unfinished full workflows at evidence snapshot

PR Validation  •  Diagnostic audit

Platform contracts

P01  GitHub workflow dispatch API

P02  GitHub GraphQL Git reference

P03  GitHub pull request API

P04  Repository contents  •  Git reference updates

P05  GitHub search API

P06  GitHub Actions artifacts API

P07  Android persistent background work

P08  Android shared media storage

P09  Android Compose accessibility

P10  W3C contrast guidance
