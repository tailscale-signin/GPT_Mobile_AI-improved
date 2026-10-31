# GPT Mobile AI 0.9.65.0 (build 133)

GitHub work now retains pinned source context and scoped receipts through interruption, detects incompatible tool contracts, and keeps uncertain writes from being silently repeated.

## Changes

- Preserve GitHub source context, immutable staged patches, scoped operation receipts and uncertain write claims across recovery.
- Validate local tool contract versions and call/result identities across direct, budgeted, delegated and provider-owned execution; prevent repeating uncertain mutations.
- Expand bounded pinned search, multi-file and history context, inventory caching, action relationships and version-bound tool health observations.
- Restore Android checkpoint backup files, report recovery dispositions and an optional checkpoint-load objective, and reduce diagnostic log rendering work.
- Validate GenieX cold packaging and artifact identity, collect CI metrics, prepare ancestry-checked forward rollback proposals, and repair Android emulator home selection.

## Qualification and limitations

- Unified Reliability v2 is not complete; its remaining acceptance cases are not represented as passed.
- Physical-device performance and a production baseline profile remain unqualified until measured on a reference device.
- Android GPU qualification requires compatible-device receipts for actual offload, prefill, decode, repeated requests and cancel/recovery; host tests do not establish those results.
- Repeated physical NPU/GPU schema completion, reduced warm inspection latency, calibrated memory admission and Full-run performance remain device acceptance gates. The app does not infer runtime-effective settings from requested or SDK-applied values.
- Qwen demo backend availability and live authenticated LLM7 generation are not established by deterministic tests.
- Optional runtime extraction remains a staged packaging project. The measured payload inventory and APK code compression do not extract plugins or remove native runtimes.
- Exact recovered France/Portugal wording and report fields, and the earlier benchmark export round-trip, do not establish a complete conversation replay; the later 16-run benchmark export remains unavailable.
- Live GitHub sign-in, private-repository access and credential-refresh acceptance remain unverified in this diagnostic audit; public browser sign-in requires the registered broker deployment.
- The October 28 export still requires physical-device performance replay; fixing the identified filesDir calls does not establish that the 748 ms frame stalls are resolved.
- Provider HTTP 403s, Android-app GitHub token endpoint permissions, private local/MCP server reachability and the remote MCP discovery timeout require service, credential or device-route verification.
- The ASUS framework finalizer SecurityException is outside application control. Host and emulator checks do not qualify the affected vendor firmware.
- Live Android sign-in, authenticated App-token read/write and refresh acceptance remain unverified and are not claimed as qualified by this release.
- Browser PKCE requires its broker and verified HTTPS callback deployment.
- The October 30 GitHub chat diagnostic scenarios still require live provider and physical-device replay; automated tests do not establish private server availability or uninterrupted execution through permanent outages.
- The 34 pending operations in the original report cannot be verified from the supplied log; no device-side watch was registered from this environment.
- Checkpoint loading objectives and local recovery counters do not establish end-to-end recovery time or remote transaction reconciliation.
- Tool health reports version-bound observations from actual executions; it does not actively probe services or guarantee present availability.
- The diagnostics log rendering changes require physical-device frame measurements to quantify their effect. Forward rollback proposals require a reviewed new release; published tags are never rewritten.

## Validation

Release publication requires successful exact-commit Android build, post-merge correctness, CodeQL and emulator acceptance, followed by signed-release tests, native/APK audits, artifact identity and signing-certificate continuity. PR qualification also includes GenieX cold packaging. Pending checks are not passing evidence. Host regressions cover receipt identity, interruption and concurrency, backup restoration, bounded search, dependency metadata, health observations, rollback ancestry and artifact tampering. Host checks do not qualify external services or a physical device.
