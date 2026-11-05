# GPT Mobile 0.9.66.5

Android build 139. This scoped release includes the merged GitHub, voice and recovery changes since v0.9.66.4 and the November 5 diagnostic reliability implementation.

## Changes

- Preserve GitHub tasks, objective evidence and review state across branch changes and approved operations; improve workspace admission, bounded final review, task history, recovery presentation and the Status dashboard.
- Add and repair bounded repository graph navigation and cumulative audit evidence, while retaining explicit limitations for external Graphify and compiler-resolved semantics.
- Improve event-driven GitHub chat progress, prompt anchoring, workflow budgets, voice ownership/preferences and reproducible self-test/retry routing.
- Normalize provider DNS, abort, EOF, timeout, HTTP and admission failures; preserve partial output, typed failure phases and dispatch receipts without replaying ambiguous gateway effects.
- Keep response-flow completion distinct from verified task completion; preserve durable tool receipts, explicit read-only replay authorization, pending work and progressive tool discovery under schema budgets.
- Retain dispatch evidence across URL redirects and MCP remote errors, so later precheck failures cannot refund consumed operations or authorize replay of a completed write.
- Preserve search provider, aggregation and refill failure receipts and successful authorized sibling evidence; coordinate bounded provider health and connectivity recovery.
- Validate MCP catalogs before publishing tool readiness, separate connection/catalog/gateway/inference evidence and add explicit bounded generation probes.
- Stream URL retrieval into bounded buffers or complete temporary documents; continue PDFs with page/text offsets and pinned source identity, and disclose incomplete coverage and exhausted budgets.
- Choose style-compatible PDF font substitutes with separate symbol and dingbat coverage checks.
- Retain speech transcript review during cooldown, clean up composer focus and frame-observer ownership, and reduce diagnostic hot-path allocations.
- Group interleaved diagnostic continuations and preserve incident/correlation links, safe structured failure fields and redacted exports.
- Add a source-backed disposition for all 92 supplied findings, meaningful regressions, independent full-suite and API 34/API 36 emulator validation and a release with a new version/build identity.
- Stabilize CI and release validation by isolating source generation, compilation and lint in bounded processes.

## Verification and release identity

The signed release pipeline validates the exact merged commit, waits for its required main-branch checks, verifies packaged runtimes and privacy, preserves the established signing certificate, and publishes APKs, an AAB, SHA-256 checksums and provenance. PR results from an earlier source revision are not substituted for release-candidate checks.

The implementation report maps every supplied finding to its source paths and distinguishes implemented behavior, existing coverage and environment acceptance: [November 5 diagnostic implementation](docs/repairs/diagnostic-reliability-completion-2026-11-05.md).

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
- No application access to asus_leak_detect_proj was found; the originating ASUS framework/vendor finalizer path still requires an affected-device stack. Host and emulator checks do not qualify the affected firmware.
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
- The November 3 build-138 chat audit implements bounded UI and planner repairs; full mutation fixtures, retained-page capacity recovery and physical-device acceptance remain incomplete.
- The November 4 GitHub workspace repairs do not qualify physical-device process death, UI frame pacing or private Llama/server reachability; those outcomes require live device and service receipts. Saved evidence remains quota bounded, and an unavailable mutation result must be reconciled rather than replayed.
- Temporary conversations do not persist recovery evidence; interrupted work with missing receipts or counters remains blocked, and changing storage mode does not grant more tool calls.
- The November 5 diagnostic changes retain partial output and durable execution evidence; providers without a resume protocol do not gain token-for-token stream resumption.
- The affected ports 8101 and 8102, live DNS/providers, authenticated gateway inference, OEM recognizer/IME behavior, and the recorded frame stalls still require deployment or physical-device acceptance.
- PDF font selection uses available compatible system fonts and checked glyph coverage. Exact layout and all glyphs require the affected documents and device fonts; bounded retrieval can still report explicit partial coverage for oversized sources or pages.

## Recovery and rollback

Preserve the existing signed release and its immutable tag. Any rollback is a reviewed forward-revert change on a new branch, followed by a higher version/build and fresh validation; published tags and release assets are not replaced.
