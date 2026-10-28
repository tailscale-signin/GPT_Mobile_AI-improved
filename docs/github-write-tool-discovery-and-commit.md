# GitHub write-tool discovery and commit capability

## Problem
A GPT Mobile AI agent may incorrectly report that only memory and tool-discovery actions are available, then assume GitHub repository writes are impossible. Availability is session-scoped: tools must be discovered and authorized in the *active* runtime, not inferred from previous sessions or an installed plugin.

## Required behavior
1. Discover available tools before declaring GitHub writes unsupported. Filter for repository file read, create/update, Git tree/blob/commit, branch/ref updates, and PR actions.
2. Check tool schemas and permissions for the current session; never invent a tool name or call an unavailable tool.
3. Distinguish (a) GitHub integration installed, (b) tools discovered, (c) write tools exposed, (d) permissions granted, and (e) successful write confirmed.
4. If authorized, fetch the target branch and current file blob SHA, apply the complete replacement or a validated patch, then commit specifically to the requested branch.
5. For multi-file changes, create blobs/tree/commit and update the branch ref with an expected-head lease to avoid overwriting concurrent work. Never force-push by default.
6. Handle 401/403 (authorization), 404 (repo/path/ref), 409/422 (SHA conflict, branch protection, validation) separately; re-fetch and reconcile on conflict.
7. Report the actual commit SHA and URL only after a successful tool response. A locally prepared patch, blob, or tree is not a published commit.
8. If write tools are absent, explain which capability is missing and offer a patch or a user-authorized connection flow; never claim a commit was made.
9. Keep write operations scoped to the user-selected repository and branch. Do not merge to main unless explicitly requested.
10. Verify the branch head and CI status after committing; report tests as unverified until run.

## Acceptance tests
- Discovery returns writable tools: agent chooses the authorized write path.
- Discovery returns read-only tools: agent produces a precise capability error, not a false success.
- Missing permission: agent preserves patch and surfaces a reconnect/permission request.
- Concurrent branch update: expected-head check rejects stale commits and prompts reconciliation.
- Protected branch: agent reports GitHub's rejection and offers a PR.
- Successful commit: response includes confirmed commit SHA, branch and changed paths.
- No tools available: agent does not hallucinate GitHub REST access.
