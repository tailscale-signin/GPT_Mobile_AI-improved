# GitHub provider denial and failure presentation

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Requested base: `fix/github-context-status-failures-2026-11-02`
- Pinned base SHA: `e3d14721f5cf318cf4e9a5632ab5d2dc96d3a6b0`
- Working branch: `fix/github-provider-failure-recovery-2026-11-02`
- Parent work: PR #783 (merged). This follow-up targets main.
- Intent: preserve provider failures before GitHub execution, display actionable causes without debug mode, and prevent permanent provider denials from entering connection recovery.

## Evidence

The attached build-133 conversation export retains four failed primary invocations for the same task. Each reports HTTP 402, with zero output tokens and no retained tool events. Three later attempts carry explicit retry descriptors; this export does not establish an automatic retry loop. A later greeting is answered locally, while the following request is blocked by the cached provider denial and has an empty answer body with its error stored in timeline metadata.

The verification wrapper previously rendered the no-receipt failure as a partial verification report. The ordinary chat error panel showed only its generic interrupted heading unless debug mode was enabled. The cached denial also suggested checking the connection despite the specific account allowance restriction.

## Changes

- With no trusted GitHub operation receipts, return the original provider failure unchanged. Do not manufacture a partial verification response or verdict. Preserve its error code, transport state and recovery metadata.
- Retain deterministic partial reporting when trusted GitHub operations actually have receipts, including failed operations. Keep private model drafts unpublished.
- Preserve an existing structured error code for an unavailable local connection.
- Stop billing and retired-endpoint failures before connection recovery, including explicit billing codes paired with transport wording. Explicit user rechecks after resolving the restriction remain available.
- Give cached billing and authentication denials cause-specific guidance. Keep the current profile-scoped denial persistence and explicit recheck behavior.
- Display the terminal error cause in the ordinary chat error panel with debug mode off. Error metadata stays separate from the answer body.

## Validation and checkpoint

- `bash scripts/validate-local.sh fast`: passed, including 287 Python tests, CI/dependency policy, secret scan, Android regex/resource checks, icon and QNN provenance checks.
- Changed Kotlin files: ktlint passed. Architecture index check and `git diff --check` passed.
- Isolated JVM exercise: 19 scenarios passed using the current verification/session/recovery sources, with storage and transport test doubles. Covered no-receipt 401/402/403 denials, untrusted output, retained trusted evidence, structured connection codes and permanent-denial recovery exclusions. This does not exercise Android UI, Room or the full AgentRunner integration.
- Repository regressions cover the AgentRunner failure path, preservation of partial reports after real observations, untrusted-output rejection and persisted billing-denial rechecks. Updated the previous no-receipt partial-report expectation.
- Targeted Android unit test command could not start: the Gradle 9.8.0 distribution download failed with `Network is unreachable`. Full Android build/tests and device rendering remain unverified locally; CI must validate them.
- The account allowance or credit restriction is external. These changes do not establish that provider generation is available.
