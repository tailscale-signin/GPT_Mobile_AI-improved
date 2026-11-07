# Final diagnostic reconciliation follow-up

Repository: `tailscale-signin/GPT_Mobile_AI-improved`

Original base: `main` at `c313a9897afe6e0a7cff609cdcf33d83fad83a90`

Integrated base: `main` at `d4843a0552a7b77dff93d73da8d23cece1ac6e26`

Branch: `fix/diagnostic-final-reconciliation-2026-11-07`

This is a narrow follow-up to the two November 6 diagnostic reviews implemented in [PR #806](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/806). That PR merged as `223b7c9f1dbf1ecaa77b65936ca112817cfb6a9b`; its final source tree is identical to the qualified branch head `d558784998248c075583c6481cadb9e22080e9de`. The subsequent #807 changes and concurrent #808/#809 reliability and response-provenance changes are preserved.

## Confirmed remaining failures

### Lost workflow dispatch acknowledgement

The final #806 change used an untracked HTTP request for release workflow dispatch so separately authorized operations could dispatch identical payloads. Its outer journal hashed the complete argument object. Changing a display-only argument could therefore create another outer operation after the first dispatch had an uncertain outcome. Identical-argument retry coverage did not exercise that bypass.

The required contract is twofold: unresolved delivery of the same payload must prevent a retry, and a distinct authorized operation may dispatch after the preceding operation has authoritative evidence of its outcome. The protected record must survive client recreation and cannot rely on evictable display results. Legacy uncertain claims must not disappear when argument normalization changes.

The new behavior regressions first reproduced two POST requests after a lost acknowledgement when either a display-only argument or the operation ID changed. The repair uses a canonical operation receipt and a protected pointer for the actual dispatch payload. Metadata pointers do not consume a pending response reservation. Identified uncertain legacy receipt IDs are retained independently of the short display index; directly addressable old HTTP claims remain authoritative. An old invocation whose arguments and identifying history were already lost cannot be reconstructed from its hash, and still needs external reconciliation.

### Draft release lookup before publication

The automatic release run [42314516219](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42314516219) built and signed the merged source and uploaded the draft assets. Its publication job then failed when `verify_published_release.py` requested `/releases/tags/v0.9.66.6` before the draft was published. The fallback for an absent Git tag occurred later in the script and could not handle this earlier failure.

The required contract is to find an unambiguous draft through the authorized release inventory, pin its numeric identity, and verify that identity, tag, immutable source and downloaded artifact bytes before publication. A failed or lost creation response permits read-only reconciliation, not another creation. Publication and its readback must use the same verified release identity.

## Evidence boundaries

The original merge's [canonical validation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42314516168) recorded 7,113 unit cases: 7,108 passed and five intentionally deferred to dedicated heap jobs. Its [Android acceptance](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42314516298) passed 55 instrumentation tests on each of API 34 and API 36. These are historical evidence for that merge, not qualification of this follow-up.

The later base's API 36 acceptance attempt crashed before its first test started. Retained logcat reports a startup ANR and high emulator CPU pressure; the remaining 19 groups passed 54 tests. A single failed-job retry was requested without changing the test, timeout or gate. The crash remains recorded and is not reclassified as a passing test.

Release `v0.9.66.6` was subsequently published against `223b7c9f1dbf1ecaa77b65936ca112817cfb6a9b`. This follow-up does not republish it, modify its tag, or claim that its existing APK includes later source changes. Future releases still require exact-commit checks, signing evidence, downloaded-byte verification and a new release identity when appropriate.

Physical-device jank, the original private MCP/gateway deployments, and historical evidence that was already deleted remain outside the claims established by deterministic source regressions. No application budget, retention ceiling, privacy boundary or CI requirement is relaxed by this follow-up. Emulator resources and the separate bounded HOME/display preparation phases introduced on the integrated base are preserved.

## Follow-up validation

The final candidate integrated with `d4843a0552a7b77dff93d73da8d23cece1ac6e26` passed all 361 host tests using `bash scripts/validate-local.sh fast`. Policy, dependency, secret, regex and resource checks passed, and the architecture index is current. All 35 emulator preparation/caller tests also passed in an independent run, including the new `EXITING` regression that failed before the correction.

The initial follow-up passed 343 host tests with `bash scripts/validate-local.sh fast`, plus the policy, dependency, secret, regex and resource checks. The release-related subset passed 42 tests, including 14 new verifier/workflow methods. The executable workflow fixtures cover successful publication, lost creation and publication acknowledgements, stale receipts, wrong targets and altered bytes; they make each authorized mutation once, and reject invalid evidence before publication. All external release effects in these regressions are mocked.

The final dispatch suite passed 20 tests under a 256 MiB heap using the actual production journal, store and request implementations with Android-edge host adapters. Coverage includes changed arguments/IDs, concurrency, storage exhaustion and recovery, incomplete receipts, legacy invocation latching, version preflight and distinct accepted run receipts. Both changed Kotlin files pass ktlint 1.3.1. Independent review found no remaining blocker in this scoped patch.

Exact-revision Android/canonical checks must also pass before merge. Their run IDs and terminal results are recorded in the pull request and durable task checkpoint; historical green runs above do not substitute for those checks.

## Emulator readiness reconciliation

The first published follow-up, `e52a8f5e97f8f087a946506833111e5d24b04941`, passed canonical verification and all 55 API 34 tests. Its API 36 attempt failed before the first test from a startup ANR, with the other 54 tests passing. One failed-job retry passed the editor but failed two foreground-obstruction guards when a System UI error dialog covered the fixture. Both outcomes are retained as failed qualification. The recorded startup delay largely preceded application initialization under saturated emulator CPU pressure; application `onCreate` itself took 186 ms.

Source inspection identified that the old caller changed display size, density and font scale after the helper returned `PREPARED`, without observing the resulting configuration. An independently reviewed local repair was prepared, but concurrent main changes closed the same setup gap before publication. The integrated base is preserved rather than replacing its distinct, already-qualified solution.

That base adds boot broadcast and application barriers, post-configuration HOME/window/process observations, and retained boot/setup/exit diagnostics. It removes the stock-launcher force-stop that could force SystemUI's QuickStep service to rebuild, observes later viewport changes, and gives both emulators four cores and 4096 MiB of RAM. HOME and display preparation each have a separate maximum 60-second budget; this is not a single total 60-second setup guarantee. The base's [API 34/API 36 acceptance](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42345386770) passed all 55 tests per device on the first attempt, including the editor and glass foreground checks. Its checked head `92db7bfcda61e25fe50cec1118c0b344bf662e64` and merge `d4843a0552a7b77dff93d73da8d23cece1ac6e26` have the same tree.

Integration review reproduced one remaining narrow readiness defect: a focused test HOME window bearing Android's `EXITING` suffix was accepted as ready. The follow-up requires the exact live HOME window and adds a behavioral regression. It leaves the merged readiness mechanisms, resources, deadlines, cold first application launch, all 55 instrumentation tests, ANR reporting and foreground/pixel assertions intact. Fresh complete qualification of the integrated follow-up is still required; historical passing runs do not approve it.
