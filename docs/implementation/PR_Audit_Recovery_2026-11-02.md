# PR audit recovery checkpoint

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
- Base: `main` at `061a3f0fa420e68148ee127c6fa58295cd943921`.
- Working branch: `fix/github-investigation-recovery-pressure-2026-11-02`, PR #786.
- Starting head: `e114e3ce6f88e6339a31e1ad0f5a32208aeef849`.
- Dependent PR #787 retains its existing MCP changes and receives this repair.
- Intent: repair CI infrastructure without weakening assertions or completion gates.

Audit run 41534281783 recorded 5,881 tests, 27 class-initialization failures and
three skips. All 27 failed classes reported unavailable Robolectric runtime
artifacts; Maven Central returned HTTP 429 for the SDK 34 and 35 instrumented
runtimes. The later APK stage remained in packaging until the job deadline.
Reruns retain multiple reports with the same name; attempt labels distinguish them.
The next audit attempt passed its unit suite and uploaded its report successfully;
no artifact collision is claimed.

The repair downloads the two affected runtime JARs and POMs before Gradle tests,
using publisher-verified SHA-512 pins, atomic replacement, bounded streaming,
three attempts only for transient HTTP/transport failures, and one total download
deadline. Validated local Maven entries are reused; corrupt entries are replaced
only after verification. Permanent HTTP and integrity failures stop immediately.
The remaining SDKs continue to use Robolectric's normal resolver.

Audit artifact names include the run attempt. Tests, APK packaging and lint have
individual deadlines, with overall headroom for diagnostics and cleanup. APK
packaging failure remains a failure; no test, assertion, lint or merge gate is
removed. This bounds a repeated packaging stall; it does not claim to repair an
unconfirmed packaging deadlock.

Validation: eight behavioral downloader regressions and all 295 canonical fast
host checks passed. Both actual runtime JARs and POMs downloaded and matched the
publisher-verified pins. Architecture-index and whitespace checks passed. Full Android qualification and merge readiness
remain subject to the new CI runs on the final heads. The existing in-app
engineering task store is unavailable to this repository session; remote writes
are reconciled through their returned commit/ref receipts.
