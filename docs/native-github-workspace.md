# Native GitHub workspace

Open **Settings → Tool connections**, add or edit a **GitHub API** connection, then expand its card and select **Open GitHub workspace**. The workspace uses GitHub's REST API directly and does not require an MCP server.

The October 21 upgrade adds persistent repository sessions, revision search, source evidence handles, exact patch staging, CI failure digests and incremental review. Implementation and validation are tracked in [the reliability repair record](repairs/2026-10-21-reliability-and-workspace.md).

## AI programmer home

The workspace opens on **AI**, with the same command composer used by conversations. Select a **main AI profile** for the GitHub connection, choose a repository and branch, and describe the desired outcome. **Fix CI**, **Review PRs**, **Plan a change**, and **Summarize work** fill the composer for review before sending.

Commands use the existing durable conversation queue. The selected profile, model, connection, repository and branch are captured with the command; the workspace shows the actual run state, pending approvals, recent tool results and latest answer. **Open full conversation** keeps that repository's connection scope. Changing repositories or profiles selects a separate conversation and encrypted command draft. Conversation copies retain their repository scope; deletion removes the binding. Backup restore requires selecting the GitHub workspace again, because reused chat or connection identifiers do not establish account identity. A storage failure keeps the command available and reports the error.

**Review changes** opens the engineering review surface scoped to the selected account and repository. View the proposed patch before requesting its approval. Approval still covers the exact patch and expected head; selecting a main AI profile grants no additional permissions.

**Prepare code context** enables optional lexical indexing. Each foreground batch makes at most 24 blob-fetch attempts, visits at most 100 eligible paths, and uses a bounded byte budget. Cached blobs are reused by account/repository/blob SHA; continuation stays pinned to the inspected commit. An incomplete repository tree or skipped file never becomes proof of full coverage. Files remain available on demand with the option off.

**Checks**, **Review**, **PRs**, and **Files** retain the detailed tools. Repository structure and manual editing are secondary to the command and its outcomes.

## Account and repository access

The existing connection editor stores the token in `SecretVault`. Use a fine-grained GitHub token restricted to the repositories you want. Browsing needs Contents read access; commits need Contents write access; draft pull requests need Pull requests write access. Editing workflow files also requires the appropriate GitHub workflow permission. Repository permissions and the connection's read-only policy disable editing when appropriate.

This implementation reuses token authentication. It does not implement GitHub App installation or browser OAuth sign-in; those need a registered GitHub application and its deployment configuration.

## Workflow

1. Choose an accessible repository and branch. Repository and branch lists support loading additional pages.
2. Browse folders and read text files from a fixed commit snapshot. Preview limits are visible and incomplete files cannot be staged from their preview.
3. Use **Changes** to create a working branch. The default branch cannot receive workspace commits.
4. Edit complete files or prepare an exact patch against a captured excerpt. Review before/after text in **Changes**. Exact patches validate unique source anchors and original blob hashes before reconstructing full files locally.
5. Commit up to 20 text files together in one Git commit. The branch head must still match the snapshot used to prepare the edits. The API preserves the base tree and executable file modes and never force-updates the branch.
6. Create a draft pull request against the repository's default branch. Browse existing PRs and page through changed files and check results at the inspected head.
7. Use **Search this revision** to search paths, text, lexical definitions, references or tests. Partial index coverage and continuation are visible. **Explain failed CI** shows failure windows and the run's actual checked head.
8. Mark a revision reviewed, then inspect **Changes since review** after refreshing. **Session details** shows capabilities, request/cache reuse and the stored review baseline.

**Use in chats** saves a default repository and branch for this connection. A chat task then retains its own account/connection/conversation/task-scoped repository snapshot. Explicit context selection or refresh resolves a new snapshot; evidence handles retain their original commit. Clear the connection default with **Clear chat context**.

## AI integration

Small model-facing operations share the native implementation: `github_context`, `github_search`, `github_read`, `github_pr`, `github_ci`, `github_stage_patch`, `github_commit` and `github_release`. Progressive selection advertises the relevant subset, while legacy actions remain compatibility aliases. Per-operation routing preserves authorized MCP-only capabilities. Read and mutation permissions remain distinct.

Prefer `github_read` with selected ranges from the task's exact commit. Results include source identity, coverage and expandable evidence handles; retained result cursors avoid repeating large payloads. `github_stage_patch` prepares edits without a remote commit. `github_commit` submits a validated stage through the expected-head pipeline. Legacy `commit_files` still requires an explicit branch and exact `expected_head_sha`. Draft PRs remain the default for `create_pull_request`.

## Limits and validation

- Full-file editor staging: 256 KB each, 1 MB per change set, at most 20 files. Exact patch reconstruction is separately bounded by the service. Binary files, symlink replacements and submodule edits are rejected.
- Source ingestion: up to 2 MiB per text blob, with a separate bounded excerpt budget. The editor displays up to 2,000 requested lines; incomplete excerpts cannot replace a full file. Exact patches and subsequent ranges remain available.
- GitHub Contents API: directories are limited to 1,000 entries; the workspace displays a limit notice.
- PR inspection: pages use 30 items consistently. Continuations carry the inspected head, and unavailable or truncated patches are labelled.
- Drafts are persisted for recovery. Edits are debounced, a close checkpoint is fenced against delayed older writes, and a moved branch preserves the original edit base for review.
- Indexing is lexical and bounded. Incomplete trees, unsupported/oversized blobs and truncated symbol lists remain explicit; compiler resolution is not claimed.
- CI logs are captured up to 4 MiB and returned as focused windows with completeness information. Completed build metadata is reusable; changing entries refresh separately.
- The workspace uses remote GitHub workflows for builds. It does not execute repository code on the phone.

Focused tests cover atomic multi-file commits, executable modes, stale-head rejection, default-branch protection, authentication, traversal rejection, selected-context line reading and permission errors. Android regex, resources, Room schema and Kotlin formatting checks run independently. Full Android compilation and unit tests require the project's Gradle/JDK/Android SDK environment.
