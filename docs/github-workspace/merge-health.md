# PR health in GitHub Workspace

The check watcher persists token-free, timestamped snapshots. Cards show CI state, pinned
head, GitHub merge state, review decision, observed required-check count, failing jobs,
refresh and a direct GitHub link. They retain a visible timestamp because completed watches
stop polling; refresh starts a new subscription after rechecking account/profile access.

`github_pr` context accepts `include_health=true`. The fixed read-only GraphQL query requests
mergeStateStatus, mergeable, reviewDecision and isRequired for both CheckRun and StatusContext.
This is separate from observed CI success. The bounded query returns UNKNOWN for missing
or partial data or more than 100 contexts. Missing required checks are additionally blocked
by GitHub's own merge state; a zero observed count does not mean no rules exist.

Merge requires an open, non-draft PR, matching SHA, complete successful check inventory with
at least one real success, acceptable reviews, MERGEABLE and CLEAN. The mutation path then
re-reads head/base/state and supplies the expected head SHA to GitHub's merge API. GitHub
remains the final authority; the app does not override deployments, review rules or queues.
Queue repositories that report BLOCKED must merge through their configured queue.

Only the fixed health query uses the engineering transport's read-only POST path. Arbitrary
POST/PUT/PATCH mutations still require the approved GitHub account identity. An unavailable
GraphQL permission never downgrades to “merge ready.” Permission or network failures retain
observed CI evidence with an unknown merge state.

CI failure inspection preserves the earliest matches and final failure context, groups repeated
messages, redacts credentials, and bounds output. Automatic retries remain restricted to the
existing transport/admission policies; uncertain writes are reconciled rather than replayed.
