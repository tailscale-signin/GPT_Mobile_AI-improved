# GitHub resilience branch integration audit

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Base: `main` at `be5d55930df8be6caad2380eea71b2f9a6bb0419`
- Branch: `feat/github-operation-resilience-2026-10-31`
- Audited starting head: `fcf53f1b65530f9a6b0c0d4560420aa92039530e`
- Integrated concurrent parent: `f66b8a45436a7d9fd28b4d122ea8b038b2bf7ea4`
- Review: PR #769, replacing #768.

The audit covers the branch's GitHub operation, authorization, recovery,
provider-admission, background-work and progress features. It checks production
entry points and behavior regressions; it does not establish that every possible
failure is eliminated or that device performance is optimal.

## Gaps corrected by this audit

- `GitHubVerifiedSession` previously converted a transient provider failure into
  partial completion before `AgentRunner` could retry it. The runner now owns
  recovery, then requests a retained-evidence response only when recovery ends
  and there are no pending calls. Default provider sessions preserve the failure.
  The generation-safety wrapper delegates this hook and other session properties.
  The Llama review wrapper preserves the original failure instead of replacing it
  with a second generic error. Empty tool-enabled rounds retain the runner's
  existing empty-answer recovery.
- Successful retries reach normal verification. Persistent transport failures
  retain the existing six-retry bound before a partial receipt summary. Completed
  tool actions are not repeated. Pending calls, revoked publication permission,
  ledger failure and cancellation cannot become successful fallback publication.
- An oversized task or draft now produces an explicit receipt-only partial answer
  without dispatching an oversized formatter request. The evidence catalog keeps
  the newest fitting prefix using binary search instead of repeatedly serializing
  almost the entire catalog. Capacity is checked again before returning the prompt.
- Catalog preparation, parsing and evidence rendering run on a computation
  dispatcher. Partial summaries skip failed, untrusted, stale and wrong-epoch
  receipts before traversing their payloads and stop after 16 verified facts.
  Secret-field matching reuses a compiled pattern, and bounded fact traversal
  stops visiting object fields once its limit is reached. These changes reduce
  unnecessary work; no device frame-time or speedup number is inferred.
- Publication occurs outside formatter error handling. A downstream collector
  exception propagates unchanged instead of triggering a second emission.
- The generating cursor now follows the response's result cards, media,
  attachments and diagnostics in `OpponentChatBubble`. Its Compose lifecycle
  regression exercises that actual parent. The plain live preview adds a
  1,500 ms entrance fade and a drawing-phase themed gradient while keeping its
  text at 50% opacity. No cursor text enters saved or exported answers.

## Feature wiring checked

| Feature | Production path and qualification |
| --- | --- |
| GitHub capabilities and privacy | `AgentToolResolver` applies the same source/service policy to initial and discovered catalogs, dispatch and cached reuse. `ChatRepositoryImpl` rechecks recalled local-only context before GitHub generation. Typed and legacy proven-unsent refusals stay outside provider reliability circuits. |
| Lost writes and tool receipts | `GitHubWorkspaceClient`, commit reconciliation, execution journal and dispatch accounting retain exact operation state. Unknown mutation outcomes require reconciliation; transport recovery does not authorize replay. Host behavior regressions exercise the real integration sources. |
| Tool discovery and saved views | Progressive catalogs, compact facade schemas, operation argument validation, saved-view binding and `EVIDENCE_UNAVAILABLE` recovery remain connected. The initial ordinary catalog is 2,997 UTF-8 bytes / 999 estimated tokens. |
| Admission, reasoning and review | Primary requests call `RequestConstraints.forRole`; claim extraction disables reasoning while engineering retains its policy. Llama capacity and review schema probes feed request admission. The separate review and verification wrappers revalidate permission and persist protected review/evidence checkpoints. |
| Durable objectives and jobs | Primary and child runners pass objective, repository, branch and commit into `ToolBatchCheckpoint`. Saved gateway jobs validate the original eligible local profile before start/reconnect. Preferences and backup restore retain scoped job references and require credentials to be revalidated. |
| Local autonomy | Both primary prompt paths apply `GitHubAutonomousEngineeringPolicy`. The panel and repository use the same availability rules; local Llama/Ollama saved jobs require verified engineering service capabilities. Ordinary remote GitHub chat remains separate. |
| Provider receipts and diagnostics | Request configuration, usage and response metadata feed `InvocationLedger` and `ProviderCallReceipt`. Missing server/runtime evidence remains explicit. Tests cover receipt fields, context probing, diagnostic classification and redaction. |
| Memory and Examine | Memory source references survive deferred admission; literal fallback does not mark semantic processing complete. Startup/runtime recovery retains waiting work. Examine enqueue/dequeue/outcome diagnostics distinguish an empty queue from waiting or unprocessed work. Android WorkManager execution still requires CI/device qualification. |
| Progress and rendering | GitHub conversation/header and saved-job views use the shared progress presentation. Live previews, generating cursor and streaming-blur suppression are wired into the chat UI. Compose regressions are present; physical-device frame performance is not measured locally. |
| CI and packaging | Canonical fast checks validate workflow and repository policy. Packaging retains bounded workers, process-group deadlines and diagnostic capture. Actual Android compilation, shrinking, APK checks and native execution are CI responsibilities. |

## Validation

- 433 production runner/GitHub host tests passed on the integrated source,
  including transient recovery, retry exhaustion, pending-call non-dispatch,
  revoked fallback permission, context overflow, shrinking capacity, newest
  evidence admission, collector exceptions and the concurrent legacy-unsent fix.
- 108 context/network/provider-receipt/memory-contract host tests passed.
- Seven local-autonomy/progress host tests and two provenance-policy host tests passed.
- `bash scripts/validate-local.sh fast` passed 278 repository tests and the
  policy, dependency, secret, resource and regex checks.
- Changed Kotlin passes ktlint 1.3.1; whitespace checks pass.

The Kotlin host bundles compile production sources with inert Android, Room and
selected platform boundaries. Three unrelated Android resolver/provider cases
are excluded from the runner bundle. These results are not a full Android build.
The local runtime has JDK 17 and no SDK 37; full Android/Compose/WorkManager,
GenieX/native, security and emulator checks must qualify the final PR head.
Current-head CI state belongs in the PR/check receipts, not this static document.
External provider authorization, unavailable services/DNS, physical accelerator
execution and multi-hour deployed jobs remain external qualification items.
No merge, release or deployment is performed by this audit.
