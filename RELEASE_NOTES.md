# GPT Mobile AI 0.9.62.0 (130)

This scoped maintenance release includes the current main branch and the October 28 diagnostic repairs from PR #751. The earlier chat-first GitHub workspace and reliability integration are described in PR #750; this maintenance audit does not requalify every inherited feature.

## Fixes

- Defer GitHub draft directory resolution to IO; run repository hashing and lexical indexing on Dispatchers.Default.
- Validate compact GitHub search inputs and require an explicit comparison base before dispatch.
- Retain the low-yield continuation guard when providers omit usage; preserve the earlier immediate empty-restored-continuation stop.
- Resolve chat deletion and temporary-chat cleanup directories on Dispatchers.IO.
- Regression coverage for encrypted draft persistence, search/comparison validation, missing-usage continuation bounds and cleanup dispatcher ownership.

- Separate cold compilation and full-suite execution deadlines in PR/GenieX validation; retain all tests and the coverage gate.

## Qualification and remaining limitations

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

## Validation

Publication is gated on the exact candidate's Android build, post-merge correctness, CodeQL and Android acceptance workflows, followed by signed-release validation and artifact identity checks. Check the linked workflow results for the final commit; pending checks are not passing evidence. Original device/provider scenarios must still be replayed to close the disclosed limitations.
