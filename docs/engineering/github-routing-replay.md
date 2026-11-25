# GitHub routing replay

Run the offline comparison from the repository root.

```sh
python scripts/replay_conversation_debug.py scripts/tests/fixtures/github-routing-pr-2568.json --github-routing
```

The checked-in task asks whether the focused branch-dependency and release-detail schemas expose their pagination and release selectors. Both graders read the same 100-line range at PR #2568 commit `461c0ac333f2687df169421c8a012a07c50b1f85`. One policy reads the Git object for each requirement. The other reuses the same revision-bound range. Both arms ran locally and used zero provider tokens. Their elapsed times include local object access and grading. Each lookup can benefit from operating-system caching.

Recapture the task with `python scripts/qualify_github_routing_replay.py 461c0ac333f2687df169421c8a012a07c50b1f85`. It reads the pinned object and prints the observed trace. The trace contains fixed route labels, digests, ordinal call identities, elapsed time, token counts and verifier witnesses.

For two runs of the same Android task, export each original staged diagnostics ZIP with its workspace, tool receipt and run sections. Compare the exports with the frozen source commit.

```sh
python scripts/replay_conversation_debug.py first.zip --github-routing --github-compare second.zip --github-revision "$REVISION"
```

Use `--github-project` to print the redacted trace before sharing it. The projector validates the archive manifest and reassembles checked record chunks. A verified requirement needs the app’s passing ledger row and every linked witness in the same binding at the selected immutable commit. Missing witnesses and mutable observations cannot establish revision-pinned verification. The original device run’s elapsed time and measured token total include private model calls. Missing usage is reported as `null`. Tokens per completed task are available only when the verifier completed the task and all required witnesses survived projection.

The active Android policy considers the evidence missing for the selected criterion. It compares admitted routes using run-local verifier outcomes, elapsed time and differences between complete model-token measurements. Missing token measurements receive the observed alternatives’ mean for planning. Staged-patch advice still requires the existing reviewer permission and independent verification. A revision-pinned cached range must match the selected repository, reader authorization, epoch and source commit.
