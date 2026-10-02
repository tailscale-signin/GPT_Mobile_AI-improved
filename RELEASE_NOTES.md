# GPT Mobile AI 0.9.26.0

## Delegation reliability
- Count delegated tool execution and explicit gateway tool lifecycle events as real watchdog progress.
- Keep healthy tool-active delegates alive beyond short adaptive windows while preserving first-progress, idle, and hard runtime limits.
- Propagate canceled and primary-only delegation as failures/handoffs instead of successful delegate responses.
- Fail over and quarantine delegates that violate hard output-token caps.

## GitHub tool reliability
- Complete missing owner or repository fields from the selected GitHub workspace context instead of failing partial requests.
- Add bounded GitHub failure diagnostics so tool errors include the actual validation or API reason.
- Stop repeated tool-call storms after 24 calls to the same tool in one response and open a circuit after 3 consecutive tool failures.
- Preserve tool-call/result ordering by call ID when calls are suppressed or deferred.

## Context and local-model safeguards
- Enforce the configured primary replay budget as a true hard ceiling, including accumulated tool-call metadata.
- Remove the hidden 512-token replay minimum so smaller configured replay budgets are respected exactly.
- Temporarily suppress unavailable local delegate runtimes within the current delegation turn without leaking unavailable-worker state into later turns.
- Refactor large delegation and OpenAI-compatible provider hot paths to reduce Android ART compiler-instruction pressure.

## Validation
- Kotlin lint passed.
- Android debug APK build and native-library verification passed.
- Unit tests, JaCoCo, Room schema verification, and Android lint passed.
- Remote diagnostics passed for resources/XML, unit tests, debug build, and Android lint.
- CodeQL analysis passed.
- Version: 0.9.26.0; version code: 95.

## Installation
- Install the signed Android APK from this release to use these fixes.
- No gateway script change is required specifically for the 0.9.26.0 client reliability fixes; continue using the current supported Gateway v12.1.x deployment.
