# GitHub chat quality, export and connection recovery

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base: main at 543c70c99648ecefed816cb770d16d09a3fa0973
Branch: fix/github-chat-quality-export-recovery-2026-11-01

Intent: address build 133 diagnostics: bounded local connection recovery, independent MCP availability, useful grounded answers and smaller verification requests, conversation export from GitHub settings, and APK/Markdown UI performance. Preserve authorization, immutable evidence identity, unfinished-task checkpoints, and explicit merge approval.

This branch cannot repair an offline external server or prove physical-device performance. Regression and validation results are recorded below before delivery.

## Diagnostic findings

The supplied build 133 log contained 2,911 records, including 28 failed local-model attempts across four runs (seven attempts per run). Fifteen attempts failed the TCP probe and thirteen hit the local-service cooldown; no model text arrived from that endpoint. Nine GitHub tool calls succeeded through the remote profile. Optional MCP discovery also timed out twelve times. Claim extraction submitted roughly 24k and 15k input-token estimates and took about ten seconds on each observed call. APK download enqueue produced main-thread disk diagnostics.

## Implemented changes

- Bound offline local-service recovery to one recovery attempt after the initial failure. Preserve the original connection error and unfinished run, respect the remaining cooldown, and prevent nested transport retries or repeated cooldown extensions.
- Let an explicit connection test clear the cooldown and inspect the selected GitHub chat profile. Optional MCP discovery returns cached schemas during a known connection backoff; execution authorization and the connection health controller remain in place.
- Present task-specific answers with supported explanations before supporting facts. Admit bounded, credential-redacted source excerpts tied to a recorded revision, while preserving receipt identity, freshness, epoch checks, exact-value verification, and partial-result handling. Repository text stays quoted as data.
- Select verification evidence by relevance, retain omission counts, limit excerpt sizes, and cap claim-extraction output at 4,096 tokens. Deterministic status requests avoid the model verification pass. Only ask follow-up questions when a decision is needed.
- **GitHub chat → Settings → Export conversation** opens the existing export picker for that chat. Markdown and plain text include user requests, selected assistant replies, repository/ref/context commit, export time, and run-state snapshot. Export reads persisted history and overlays current visible/live text so opening it directly from settings does not produce an empty transcript. Tokens are redacted. Active and failed runs can be exported without implying completion.
- Debug mode keeps the diagnostic ZIP separate from the readable transcript. Chat-scoped protected GitHub evidence/checkpoints are made readable for diagnostic export and pass through the existing export redactor; unavailable protected data gets an explicit marker.
- Move APK download enqueue/retry storage work onto the IO dispatcher and retain synchronization. Remember the Markdown body style across recompositions.

## Validation

- `bash scripts/validate-local.sh fast`: passed all 285 host checks, Android-regex scan, resource/icon preflight, QNN provenance check, and diff whitespace check.
- Focused Kotlin/JUnit host harness: 48 tests passed across recovery, evidence policy/presentation, verified sessions, connection health, and conversation export. It compiled the changed pure Kotlin production code with cached dependencies; Android-dependent endpoint and export declarations were extracted for the host harness. This is not an Android integration test.
- ktlint 1.3.1: all changed/new Kotlin files formatted and checked successfully.
- Architecture index regenerated; `git diff --check` passed.
- Android Gradle unit/build execution could not start locally: the wrapper needs Gradle 9.8.0 and the download failed with `Network is unreachable`. The local Java installation is 17, while this repository requires JDK 21 and SDK 37. Android compilation, Compose interaction, device performance, and live PC/Tailscale connectivity remain to be validated by CI/device testing.
