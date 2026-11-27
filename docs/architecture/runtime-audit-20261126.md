# Runtime audit repair — November 26, 2026

## Revision and authorized intent

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
- Base branch: `main`.
- Base revision: `0e625e250117475446b8f216df2501ddbef168f2` (merged PR #2599).
- Integration branch: `fix/runtime-audit-20261126`; publication PR #2646 on `fix/unified-github-gateway`.
- Concurrent revision incorporated: `4135a7ca2fcb359888bff8383b762befdbc4c576`.
- Intent: implement the sixteen findings from the retained build-151 diagnostic audit,
  qualify the actual repair revision, update the latest draft PR, merge after successful required
  verification, and publish the versioned Android/Gateway release. The user explicitly
  authorized this sequence and requested reconciliation with concurrent branch changes.

The initial audit covers issues #2614–#2638. Issue #2639 adds a sleeping selected
Gateway model; #2640 repeats strict reasoning qualification and #2641–#2645 repeat provider quota failures. Repeated reports are evidence of a failure family, not separate fixes.
Source code, simulated tests, hosted checks and physical-device measurements remain
distinct evidence. No physical performance or provider-billing resolution is inferred
from a source change.

## Implementation ledger

| Finding | Scope | Owner |
| --- | --- | --- |
| F01 | One context budget before preparation; schema staging by measured headroom | Budget repair |
| F02 | Reserve irreducible tool replay before optional context | Budget repair |
| F03 | Compact-result paging, bounded lookups and cursor progress | GitHub repair |
| F04 | Useful output and changed, bounded continuation recovery | GitHub repair |
| F05 | Selected-revision CI evidence | GitHub repair |
| F06 | CI scope, freshness and complete pagination | GitHub repair |
| F07 | Watch selectors and production preflight recovery | GitHub repair |
| F08 | Independent read execution after an unrelated failure | GitHub repair |
| F09 | First-party source priority and versioned persistent index cursors | Performance repair |
| F10 | Bounded graph qualification and honest tool coverage | Performance repair |
| F11 | Settled transcript rendering and progress updates | Performance repair |
| F12 | Frame coverage and observation gaps | Performance repair |
| F13 | Export hydration/projection cost and reuse | Performance repair |
| F14 | Provider-specific quota recovery and incident grouping | Integration |
| F15 | Independent auxiliary-connection recovery | Integration |
| F16 | Qualified local reasoning controls and explicit recovery | Integration |

## Qualification

All sixteen repair areas are implemented and reconciled with the concurrent PR.
The release targets are Android 0.9.66.18 (build 152) and Gateway 18.1.5.

Local qualification performed during assembly:

- Gateway aggregate: 2,125 Python tests run, 2,093 passed, 32 environment-dependent skips; seven browser-bridge tests passed, including the diagnostic stress suite.
- Authentication/reporting service: all 243 tests passed.
- Focused actual-source host checks covered budget/planning (37), CI/lookup/paging progress (16), reasoning and model recovery (10), and cancellation/neighborhood selection (2). Their platform boundary stubs do not qualify Android wiring.
- The canonical Android command was attempted locally but the Gradle distribution bootstrap could not connect through this environment. Hosted Android/Runner/Robolectric/lint/packaging checks remain the required execution gate.

The canonical host gate, final-head PR checks, post-merge checks, signed artifact
verification and actual Android/Gateway publication must complete before this release
is reported as published. Physical device performance, external provider allowance,
and unexecuted Unified Reliability v2 scenarios remain the release-note limitations.
