# Community scoreboards

The app reads `scoreboards/index.json` on the public **scoreboard-data** branch.
A user explicitly publishes a result through their connected GitHub account by
creating a `[Scoreboard v1]` issue. The Community Scoreboard workflow validates
its allowlisted JSON, recalculates its score, archives the numeric outcomes at
`scoreboards/submissions/<issue-number>.json`, and updates the index. Accepted
submissions are immutable. A lower retest remains archived and never replaces a
higher score. Quick and Full, and Model and Delegation, are separate rankings.

No service credential is embedded in the app. Anonymous reads require a public
repository. Sharing requires permission to create repository issues. This
workflow starts working when merged onto the default branch; the first accepted
submission creates the data branch. Do not merge or release merely to test it.
Branch protection must allow this workflow's token to create/update the data
branch. If organization policy prevents that, an administrator must configure
an appropriate narrowly scoped installation token; never ship it to clients.

These are **self-reported, best-of-runs** scores, not independent certification.
Structural validation cannot prove which provider/model executed the tests.
The GitHub username is public on the submission. Only provider/model identities,
timestamps, test outcomes, timings, token and tool counters, and reviewer/domain
scores are published. Prompts, response text, profile names, endpoint URLs,
credentials, diagnostics, device identifiers and chat contents are excluded.

## Score policy 1

Model (suite 6): task accuracy 35%, structured JSON 20%, tool task success 20%,
Intelligence accuracy 15%, completion 10%; capped by completion. Intelligence is
an original 6-question Quick / 36-question Full text capability screen, not a
measure of complete intelligence. Missing timing is not invented. Speed and
latency are diagnostics, so network and hardware do not inflate capability.

Delegation (suite 1): objective accuracy 50%, successful tool calls 15%, research
handoff 15%, independent reviewer 20%; capped by objective accuracy. Quick has
5 fixtures; Full has 50 covering all eight pipeline workloads. Every completed
fixture requires a valid reviewer assessment. The benchmark enables research
and reviewer modes with a fixed preset without editing conversation settings.
The reviewer gets trusted synthetic ground truth in a separate final assessment;
it cannot repair or rewrite the scored candidate. Primary/reviewer model IDs are
visible because their abilities influence the delegate's result. Research uses
controlled tools and source fixtures, not nondeterministic live web searches.

Both policies require exact manifests, unique IDs, supported outcome types and
complete question coverage. Unsupported, interrupted, canceled, skipped and
legacy runs remain local history but cannot rank. Errors/timeouts are failures,
not missing points to exclude from a denominator. A completed output without
reviewer assessment is incomplete. Full/Quick results never share a rank.

The index keeps one best **single run** per (kind, plan, suite, provider, model).
Provider enum and case-sensitive trimmed model ID define identity; profile names
and UIDs never participate. Ties prefer the newer run, then lexical run ID. No
freshness expiry discards a best result. Scores are recomputed by both Android
and the repository. Data branches only contain JSON; no submitted code executes.
The bounded index supports 1,000 entries / 8 MB; exceeding limits rejects a
submission without deleting existing data. Immutable archives remain in git.

Run `python3 -m unittest discover -s scripts/tests` for validator/publication tests.
