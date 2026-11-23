# GitHub agent workspace

The agent workspace extends an existing accepted Gateway engineering task.
Open its saved job and choose **Workspace** on Android, or **Open agent
workspace** in the Windows Gateway's engineering job list. The accepted
repository, branch, source commit, account, model binding and final validation
contract remain attached to the task.

The phone's native GitHub tools continue to work without Gateway. Optional
remote agents, sandboxed development environments and configured MCP tools use
Gateway when the user chooses those workflows.

## What is available

| Area | Behavior | Evidence and limits |
| --- | --- | --- |
| Workers | Planner, retrieval, editor and reviewer roles; dependency-ready parallel read-only waves; isolated candidate proposals | Immutable source/path/role scope and shared parent budgets; edits and checks preserve serialization; changing a worker's model requires a separately admitted job |
| Agents | Explicit delegation, status, events, supported steering/cancellation, plan approval and candidate import | Actual provider/API/SDK/ACP transports, exact run identities and retained dispatch receipts; unsupported controls are disabled |
| Agent comparison | Two to eight configured agents receive the same accepted objective, source and declared limits | Labeled `AGENT_SYSTEM`; provider/model behavior and tool/runtime effects are not presented as isolated model quality; no invented quality score |
| Source and review | Pinned tree/file browsing, paged candidate diffs, executed test output and private review drafts | Rechecks repository permission and source; verifies Git blob identities; drafts are encrypted and are not posted to GitHub |
| Candidate comparison | Compare retained candidate checks and overlapping paths | Ranks actual passes only when source, policy and executed check match; does not select a winner or authorize merge |
| Requirements | Dependency-ready work packages tied to every accepted requirement | Completion requires exact passing check receipts covering the mapped behavior cases on one candidate; the final validation and publication gates remain separate |
| Context | Task-personalized graph ranking, path-specific instructions and discoverable procedures | Graph context is bounded and source-bound; inferred relationships are not runtime proof; instruction contents are loaded progressively |
| Task tools | Plan, edit, review, test and retrieval discovery profiles | Profiles narrow model-facing discovery; every operation still validates existing authority, source, policy and lease |
| Summaries | Compact extractive working summaries | Full evidence remains in immutable private artifacts; summaries never replace completion evidence |
| Context export | Selected observed files as JSON, XML or Markdown, with optional installed Repomix | Blob identities, omissions and byte limits are retained; Repomix runs in the approved pinned validation container without network access |
| Next edit | A small proposal from the selected explicitly local-only route | No automatic model download/switch or file application; a separate approved check creates a retained candidate for review |
| Environment | Host mirror, exact source, validation policy and pinned image readiness | No model runs during qualification; optional language servers stay `NOT_PROBED` until a real query demonstrates support |
| Integrations | Configured Context7, Playwright and complementary GitHub MCP operations | Exact runtime tool schema and host allowlist are checked at execution; native GitHub operations remain the existing publication route |

The dedicated model benchmark workflow remains separate. Its Overview, Compare,
Tasks, Evidence and History views keep frozen paired model tasks, independent
behavior/input-preservation grading and execution qualification together. See
[model benchmark design and setup](../../gateway/benchmarks/README.md) and
[direct phone-to-GitHub Actions verification](../../gateway/benchmarks/PHONE_ACTIONS.md).
The model benchmark can use the selected Gateway or the explicitly configured
phone GitHub connection. Its four evidence lanes distinguish simulated checks,
restricted Python grading, repository-native execution and live GitHub verification.
An agent comparison does not reuse a model score as evidence of a complete
agent system's quality. Missing execution, cost or settings observations remain
unqualified or inconclusive.

## Configure an optional coding runtime

On Windows, open **Tools → Coding agents**. Choose the runtime, exact model,
allowed repositories and Gateway accounts, and a credential environment-variable
reference. Local runtimes also need an already installed image pinned by digest.
Existing command/environment/budget fields are preserved when editing a runtime.
Restart an idle Gateway after changing its settings, then use **Check readiness**
in the task workspace before delegating.

[Coding agent adapter documentation](coding-agent-adapters.md) lists the actual
transports, source-binding guarantees, SDK/runtime requirements and commands.
Available adapters are Codex, GitHub Copilot cloud, Copilot SDK, Jules, Cursor,
OpenHands, Cline, Claude Agent SDK, Kiro/Devin ACP and Aider. Missing credentials,
runtime binaries, models or entitlements are reported as setup requirements.
Runtime qualification is distinct from a successful real model task.

The workspace also labels the researched products that are references rather
than executable adapters in this release. Perch/DevSwarm/editor ideas inform
source review, separate workspaces and role-specific assistance. Roo, Continue
and the historical Sweep issue-bot workflow are not started by selecting their
names. Type.com does not gain an implicit account connection. GitHub MCP uses
the existing configured MCP runtime, not an independently invented API bridge.

## Configure development integrations

The Windows repository validation editor exposes `workspace_integrations` with
the other host-approved repository settings. A headless Gateway uses the same
repository policy. Configure the actual MCP server/account in the existing
Gateway tool settings, then allow only its exact discovered tool names:

```json
{
  "workspace_integrations": {
    "context7": {
      "enabled": true,
      "allow_remote": true,
      "allow_mutations": false,
      "tools": ["REPLACE_WITH_EXACT_DISCOVERED_TOOL_NAME"]
    },
    "playwright": {
      "enabled": true,
      "allow_remote": true,
      "allow_mutations": false,
      "tools": ["REPLACE_WITH_EXACT_DISCOVERED_TOOL_NAME"]
    }
  }
}
```

The illustrative tool names must be replaced by the installed catalog names.
Use **Discover configured tools**, inspect the observed argument schema, and
submit structured arguments. For documentation, select the required library
version in the actual tool's arguments. Browser interactions additionally need
the host's explicit `allow_mutations` setting and the accepted implementation
scope. The GitHub integration admits complementary code/secret/dependency
scanning reads and suppresses overlapping native operations and MCP writes.
Local-only jobs cannot send context to these remote integrations.

Policy edits change its digest. Existing tasks do not silently inherit changed
permissions; start or re-admit work under the reviewed policy.

## Durable commands and recovery

Android and Windows retain the original encrypted command arguments before
sending. Each envelope includes the job, source commit, revision and a unique
command ID. The server checks the authenticated account/installation and
requires the explicit `workspace-command` user action marker. The existing
engineering controller consumes accepted commands under its current lease;
there is no second coding supervisor.

An interrupted running command becomes `UNKNOWN`. Checking a saved receipt does
not replay it. A retry is offered only after confirmed absence and only for the
unchanged original envelope at its original revision/source. Unknown outcomes
remain visible; acknowledging an unresolved command locally never converts it
to success or authorizes a new external write. Safe status, readiness and
cancellation commands can inspect a paused task without resuming coding or
publication. Review drafts also remain local/private while the task is paused.

Parent cancellation revokes active execution before scheduling cleanup on the
existing worker pool. Local containers are identified and removed using their
retained source/run identity. Cloud cancellation is sent at most once through a
durable dispatch fence; unsupported or unconfirmed remote stopping remains
`REMOTE_CONTINUES`, rather than being labeled stopped. Containers and tasks have
finite lifetimes. Exact-main CI, configured acceptance evidence and publication
requirements remain the final authority for the repository changes.

## Qualification scope

Protocol, fault, source identity, SQLite durability, command recovery, candidate
validation, real HTTP routing and Android/Windows source compilation are covered
by repository tests. A fixture test does not certify a user's installed SDK,
vendor account, paid provider result, local model quality or device performance.
Those capabilities are reported from the selected installation's actual
qualification and execution receipts. The workspace never manufactures these
measurements to make a task appear complete.
