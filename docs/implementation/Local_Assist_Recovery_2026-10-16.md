# Local Assist execution and delivery recovery

This change repairs application failure paths from the build 121 diagnostic audit. It does not certify a phone, GPU/NPU, local model artifact, or PC endpoint as healthy.

## Changes

- Concise llama.cpp worker requests retain the profile's reasoning policy. Auto no longer becomes strict Off solely because tools are disabled or the worker must be concise. Explicit unsupported On/Off remains a pre-dispatch rejection; it is not silently changed to Auto.
- Deterministic reasoning, context-admission and native-quarantine failures end identical delegation retries immediately. Existing fallback permission and budget rules still apply. Configuration failures are scoped to the current coordinator rather than permanently blacklisting a corrected profile.
- Assist checks native readiness before reading the artifact or waiting for engine preparation. Automatic preparation may replace a different idle engine, but cannot replace an open conversation, busy runtime or warm-only selection. Ownership is rechecked after obtaining the native lease.
- Delegation input planning respects the actual admitted context of a matching local artifact/backend, as well as catalog, profile and device limits. The native adapter still performs final context admission.
- Ordinary remote tool assistance uses a matching prepared helper. Foreground preloading and explicit Research/Test preparation own cold loads. The optional response wait defaults to two seconds and is independently configurable (one second in Fast Response); the primary keeps the original full evidence when it expires. Worker generation limits can be configured up to 90 seconds without extending that response wait. A deadline is recorded as `TIMED_OUT / OPTIONAL_WAIT_DEADLINE`, separately from cancellation or revoked permission. Detaching the last subscriber cancels its producer through existing isolated native cleanup.
- An opt-in per-turn `local_assist_plan` tool works without a preceding external tool result. It receives only the current authorized goal and returns validated, unverified query proposals. It executes no search, grants no permission, uses the existing tool/Assist budgets, and is absent when tools are disabled, the helper is unauthorized, the primary owns the native runtime, or Research owns assistance.
- Diagnostic exports include Assist admission/execution/delivery transitions, physical attempts, preparation/load events and per-physical-request evidence handoff outcomes. Handoff confirmation inspects the complete serialized candidate packet. Generated or validated output alone never establishes request inclusion or final-answer support. Operational events omit source text, task text, paths and model labels.

## Verification

An isolated JVM harness runs the real Assist coordinator, shared-work ownership, admission rules, packet validation, deadline handling, query-planning binder and failure classification. It substitutes Android/entity/logging contracts and is not a substitute for the full Android build. Repository tests also exercise the coordinator's deterministic-error retry limit; existing serializer tests cover exact handoff matching and incomplete packet rejection.

The complete Android test/lint/APK workflows run on PR #690. Local Kotlin lint and source/resource/schema checks are additional checks, not device acceptance evidence.

## Remaining qualification

A phone-to-PC connection refusal cannot be repaired by changing the phone's app code alone. Verify the configured server address, listener and network access; the app must report failure and use only approved fallback routes. No port, credential, backend or model is silently changed by this patch.

Run Verify load against the exact artifact/backend, then load/generate/cancel/reload on supported hardware. Exercise slow helpers, permission revocation, native interruption, small contexts, planner/search failure, a 24-document extraction job and evidence inclusion. Measure real tap-to-dispatch/first-token latency and UI stalls on release-equivalent builds. Existing formal acceptance cases remain unchanged until this evidence exists; semantic research verification and broader audit gaps remain tracked separately.
