# GitHub local-model admission follow-up

Base: `main` at `e0a5586a896fa591d4811bd60cecf5e02a548180`.
Branch: `fix/github-local-decision-admission-2026-11-01`.

## Defect

GitHub engineering memory is hydrated after tool-catalog resolution. The later
pre-generation privacy check used the broad repository-routing identity predicate
to decide which tools transfer data externally. This also matched `github_decide`,
the local interactive decision control, whose connection is null. Its unqualified
tool ID did not match the authorized GitHub connection, producing
`PERSONAL_DATA_LOCAL_ONLY` before model dispatch even when repository memory was
correctly bound to the selected connection and chat.

## Repair

Catalog authorization, runtime provenance wrapping and the post-memory GitHub
check now share the same external destination classification. The local decision
control has no external service ID. Authenticated GitHub operations retain their
connection-qualified IDs and their live execution guards. No privacy labels,
saved messages, consent requirements or connector grants are removed.

## Evidence

- The extracted original preflight reproduced the reported exception in
  `ToolDataDestinationsTest` before the correction.
- The corrected implementation passed the three isolated Kotlin behavior tests
  with platform stubs, including Llama, Ollama and on-device client admission,
  authorized connector execution, and rejection of unrelated private context or
  another connection.
- `GitHubDecisionToolTest` also uses the actual decision implementation and agent
  runner with a test provider to require a nonempty visible reply after memory
  hydration. Full Android CI must execute this regression before merge.
- Physical-device or live Llama-server generation has not been measured in this
  workspace. CI results are tracked on the repair PR.
