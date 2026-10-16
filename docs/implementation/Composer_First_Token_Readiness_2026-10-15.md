# Composer-first readiness — 15 October 2026

## Goal
Reduce new-chat tap-to-dispatch latency and time to first visible provider token without changing model choice, generation length, permission checks, or required tool/research behaviour.

## Wiring
- `ChatInputBox` publishes focus and `ChatScreen` observes only whether the draft is empty. Neither prewarm code nor network code receives the draft text.
- `ChatReadinessCoordinator` reacts to focus immediately rather than using the 500 ms idle delay. The first keystroke does not cancel or restart an already-warming connection. It prioritizes up to four enabled active/next-message profiles.
- `ProviderTransportWarmer` issues a bounded (1.8-second) unauthenticated HEAD to the selected **public HTTPS origin only**, with no model request, query, path, token, credentials or draft. This uses the same Ktor/OkHttp pool as provider calls and skips private/local URLs.
- Warm connection probes precede MCP catalogue requests; local memory metadata and user-approved Local Assist preload use a separate one-slot local preparation lane.
- The scheduler coalesces repeated hints and limits network/local speculation. Foreground, network, thermal, permission-generation, configuration and active-send guards remain effective. None of this is joined by accepted Send.
- Bounded warmup is enabled by default for **new** settings. Existing persisted opt-outs remain respected, and predicting draft memory and metered-network warming remain opt-in under Advanced Settings.

## Safety invariants
- No user draft, recalled fact, location, bearer token, auth credential, research execution or generated text before Send.
- No speculative operation is allowed to delay Send. All required work continues after accepted input under the existing policy.
- Provider probes may return 401/404/405 yet still prepare the connection. They are not treated as authorization or inference success.
- Prewarming cannot eliminate remote queueing or model first-token generation time. It may not work if the server closes the probed socket.

## Validation
- Unit tests: composer state lifecycle and bounded selected-profile priority, public-origin URL restrictions, and readiness default serialization.
- CI: Android Kotlin compilation, test suite, static analysis and safety gates.
- Device acceptance: 25 cold vs 25 focus-warmed sends, including Wi-Fi, metered opt-in, Combined, local-model pressure, degraded MCP and no-network cases. Capture SEND_TAPPED, provider-dispatch timestamp, DNS/connect/TLS, first remote token and first visible token, and compare p50/p95. No empirical speedup is claimed without traces.
