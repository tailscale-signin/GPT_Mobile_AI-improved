# Final diagnostic reconciliation follow-up

Repository: `tailscale-signin/GPT_Mobile_AI-improved`

Base: `main` at `c313a9897afe6e0a7cff609cdcf33d83fad83a90`

Branch: `fix/diagnostic-final-reconciliation-2026-11-07`

This is a narrow follow-up to the two November 6 diagnostic reviews implemented in [PR #806](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/806). That PR merged as `223b7c9f1dbf1ecaa77b65936ca112817cfb6a9b`; its final source tree is identical to the qualified branch head `d558784998248c075583c6481cadb9e22080e9de`. The subsequent #807 changes on the base are preserved.

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

Physical-device jank, the original private MCP/gateway deployments, and historical evidence that was already deleted remain outside the claims established by deterministic source regressions. No budget, retention ceiling, privacy boundary or CI requirement is relaxed.

## Follow-up validation

`bash scripts/validate-local.sh fast` passed 343 host tests and the policy, dependency, secret, regex and resource checks. The generated architecture index is current. The release-related subset passed 42 tests, including 14 new verifier/workflow methods. The executable workflow fixtures cover successful publication, lost creation and publication acknowledgements, stale receipts, wrong targets and altered bytes; they make each authorized mutation once, and reject invalid evidence before publication. All external release effects in these regressions are mocked.

The final dispatch suite passed 20 tests under a 256 MiB heap using the actual production journal, store and request implementations with Android-edge host adapters. Coverage includes changed arguments/IDs, concurrency, storage exhaustion and recovery, incomplete receipts, legacy invocation latching, version preflight and distinct accepted run receipts. Both changed Kotlin files pass ktlint 1.3.1. Independent review found no remaining blocker in this scoped patch.

Exact-revision Android/canonical checks must also pass before merge. Their run IDs and terminal results are recorded in the pull request and durable task checkpoint; historical green runs above do not substitute for those checks.
