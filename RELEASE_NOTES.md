# GPT Mobile 0.9.66.4

Android build 138. Signed release of the merged GitHub, voice, recovery and gateway integration changes since v0.9.65.0.

## Changes

- Add opt-in full-screen Voice Chat with speech lifecycle recovery, editable drafts, captions and audio routing.
- Improve GitHub task continuation, bounded evidence recovery, current-revision CI decisions, credential rotation and persistent task history.
- Add prompt-top anchoring, recurring activity summaries, expanded workflow status and APK provenance views.
- Repair MCP discovery, tool argument serialization, pagination, provider outcome accounting and diagnostic export pressure.
- Add the Gateway 15 desktop dashboard and Windows installer infrastructure, configurable listeners, durable engineering jobs and isolated publication validation.
- Fix legacy credential replacement admission, Android voice lint and streaming coroutine DEX size without weakening validation gates.

- Compress debug APK DEX and enforce unchanged per-ABI download budgets in PR validation as well as main builds.
- Use the shared bounded Android pipeline for signed release packaging, retaining the existing memory ceilings, APK budgets, signing checks and artifact verification.

- Run signed-release validation in separate one-worker compilation, test and lint processes, and exercise the same validation on release-workflow PRs before merge.

- Select MockK's JVM test artifact explicitly, pin five publisher-verified Byte Buddy and MockK test artifacts and descriptors for cold resolution, and require canonical PR Validation to pass on the exact published commit.

- Complete required release lint in a separate bounded process before APK packaging so cold lint analysis cannot retain the heap needed by Zipflinger; lint failures still block packaging.

- Preserve GitHub tasks across branch changes and approved commits, recover malformed reviewer output with a bounded saved retry, and synchronize smooth prompt and bottom following with the UI frame clock.

- Add opt-in bounded repository graph search, calls, impact and revision-aware retrieval with encrypted caches; structural navigation does not claim compiler-resolved Kotlin/Java semantics.

- Persist cumulative GitHub audit evidence, owned mutation-fixture results and usage observations, with Room schema 48 migration, bounded table rendering and speech-start ownership.

- Repair build 138 self-test dependency selection, pinned workflow discovery and bounded saved-page recovery; move checklist computation off the UI thread.

## Qualification and remaining work

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
- Compiler-resolved Kotlin/Java navigation, a dedicated conflict workspace and native GitHub Enterprise support remain unimplemented; the integration expansion is partial.
- Gateway publication requires an operator-provisioned GATEWAY_ENGINEERING_VALIDATION_POLICY with a local mirror, pinned Docker image and approved commands. Actual isolated Docker execution remains unqualified.
- Android webhook push, complete approval/activity history, unified model readiness, full mutation fixtures, attestation retrieval and remaining performance/accessibility work are partial.
- Voice speech services, GitHub prompt anchoring during physical gestures/IME/rotation, process death and reboot still require device acceptance. This release does not claim those cases passed.
- The Android signed release does not install or publish a new Windows gateway package; Windows installer checks are tracked separately.

- External Graphify server behavior and physical-device graph, speech and scrolling performance have not been qualified by these host and emulator checks.
- Automated mutation fixtures cover owned disposable branch creation, dependency inspection and cleanup; content writes, releases, merges and cancellations require separately authorized fixture plans.

- External Graphify execution, compiler-resolved navigation, and physical-device graph, speech and UI performance remain unqualified; host and emulator evidence does not establish those outcomes.

Publication requires successful validation of this exact commit, current main checks, signature continuity, APK/AAB identity and native-library verification.
