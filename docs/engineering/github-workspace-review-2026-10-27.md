# GitHub workspace engineering review — October 27, 2026

Target: `fix/consolidated-engineering-review-2026-10-27`, draft PR #750.
Audited base: `1eeb4bb65d0099cec3dbbec102abf72b3714c50c`.

This review incorporates the supplied GitHub Integration & Workspace UI architecture review and the subsequent concrete Chat/Settings requirements. Recommendations are assessed against source, rather than assuming they are missing. No source change establishes physical-device or public-service acceptance by itself.

## Implemented in this change

- The default workspace is a full chat viewport, with the existing composer, retained user/assistant messages, Markdown rendering, earlier-message loading, quick actions and recent operation status/timing/error detail. The previous 2,400-character latest-answer preview is removed.
- The only top-level tabs are **Chat** and **Settings**. Files and Review are no longer tabs. Detailed files, checks, PRs and patch review remain contextual/secondary tools.
- Repository and branch selectors live in workspace Settings. The compact chat header shows the repository and working branch. **Prepare code context**, incremental batches and coverage information also live in Settings.
- Workspace Settings selects a dedicated engineering profile or **Use global default**. Main app Settings selects the default GitHub profile. Sending a command under the global default no longer silently creates a per-connection override.
- Manual branch switching and creation prompt before execution. Creation binds to the displayed base SHA and rejects a moved base. Drafts and staged patches block destination changes.
- AI mutable-branch selection uses exact one-shot approval even when read trust was previously granted; generic persistent grants cannot waive it. Immutable SHA inspection remains a read. Re-selecting an explicit mutable ref also prompts, conservatively, even if the model asks to resolve the same branch.
- Successful structured GitHub tool receipts can propose following the AI's working branch. Prose, failures, another account/repository, incomplete receipts and unconfirmed tool calls cannot change the UI destination. Following a proposal prompts before switching and updates the existing repository conversation's future-command context. Already queued/running commands retain their captured destination.

## Architecture review coverage

| Requested area | Source implementation / disposition | Remaining work or acceptance |
| --- | --- | --- |
| Typed GitHub writes and actionable errors | `GitHubOperationTool`, `GitHubTool`, `GitHubWorkspaceClient`: narrow read/stage/commit/PR/CI operations, credential/permission/rate-limit/rules classification and progressive discovery. Earlier branch fixes preserve write schemas and bound search/read recovery. | Live account/repository/permission matrix; remote availability cannot be guaranteed. |
| Repository/ref/head session | `GitHubConversationContext`, repository sessions and state store pin context/evidence by account/repository/conversation/task/commit. Creation checks the displayed base; writes use expected head/blob identities and non-force updates. | Race acceptance against a moving remote branch; GitHub REST update-ref is not a universal atomic compare-and-swap service. |
| Safe patches and publication | Engineering controller validates structural patches, scans added content for secrets, checks protected paths, stages exact patches and requires patch/head-scoped approval. Git saga checkpoints remote objects and verifies the updated ref. | Structural validation is not local compilation. Run repository workflows and required exact-head checks; do not claim phone-side builds or arbitrary shell execution. |
| Authentication | Existing branch includes browser PKCE authorization broker, Android callback bridge, secure pending transactions, account-bound sessions, refresh rotation, disconnect, device-flow metadata and backup credential exclusions. | Public GitHub App registration/deployment, installation selection and full public sign-in lifecycle remain acceptance/deployment work. No fabricated credentials or unconfigured production sign-in. |
| Chat and settings | Implemented above using the actual Android UI and durable queue. | Device rotation, IME, long-output and accessibility acceptance beyond local rendered tests. |
| Operation timeline and live activity | Durable tool events, runs, queue, approvals, errors and timings drive the workspace. Existing engineering ledger distinguishes patch/commit/ref/CI states. | A complete cross-stage activity explorer and cost/token breakdown are enhancements; no synthetic success/progress is added. |
| Background/resumable workflow | Existing durable conversation queue, engineering Room ledger, operation IDs, saga reconciliation and CI verification work preserve checkpoints and bounded retries. | Kill/restart and long-duration physical-phone acceptance; Android scheduling cannot guarantee uninterrupted execution. |
| Repository intelligence/indexing | Optional bounded incremental lexical source indexing, commit/blob cache, search continuation and partial coverage already exist; moved controls to Settings. | Compiler-backed symbol resolution, dependency graph, terminal and LSP are not implemented by lexical indexing. |
| Adaptive reasoning and cost control | Existing per-profile engineering/context/output/tool budgets remain authoritative. A user-selected model/provider is not silently swapped. | Complexity-aware model routing and automatic cost optimization require an explicit policy and provider-capacity/cost evidence; not implemented or claimed complete here. |
| Task memory | Branch/account/task-scoped conversation, encrypted drafts, retained tool evidence and engineering ledger exist. | A dedicated task-board UI and richer historical-change reasoning remain enhancements. |
| CI repair loop | Existing CI digest, exact-head verifier and focused **Fix CI** command support investigation/repair through the durable runner. | Live repeated-failure repair acceptance; no unrestricted automatic workflow publication or rerun loop. |
| Multi-agent coding/security/testing/review | Existing agent/delegation infrastructure is retained. | A dedicated independently verified four-role review orchestration policy is not implemented by this UI change. |
| Smart quick actions | Audit branch, Fix CI, Prepare PR and Check CI are available in chat; detailed tools are secondary. | Deeper task-aware suggestions remain an enhancement. |
| Risky administration | Exact mutation approvals, elevated administrative boundary and branch/rules checks remain. | No auto-commit toggle that bypasses exact approval, destructive change, force push, merge or workflow authorization is introduced. |

## Verification

Debug Kotlin/Java compilation, 160 focused Kotlin tests (zero failures/errors/skips), all 204 script tests, ktlint, resources and regex checks passed. Native-graphics Compose tests rendered the chat and exercised large-font Settings controls; the chat screenshot was inspected. Direct D8 inspection measured the largest compiled local-adapter method at 12,996 units, below the 16,383-unit ART threshold; full packaged APK checking remains a CI gate. The final UI follow-up rerun passed all 16 workspace/profile/rendered-UI tests with zero failures/errors/skips, including contextual patch-review navigation and workspace-error presentation changes. Public OAuth deployment, real GitHub mutations, GPU/NPU qualification and physical-phone lifecycle/IME/performance acceptance must remain visibly open until tested. Main is not merged or released by this change.
