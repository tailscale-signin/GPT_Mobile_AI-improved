# Release Notes - v0.9.3.1

Welcome to **GPT Mobile AI (Improved)** (v0.9.3.1)!

This release resolves conversation dropouts, unexpected cancellations, and premature timeouts during long AI generations and multi-step tool executions.

---

### Key Highlights & Resiliency Improvements

#### 1. Infinite Streaming Timeout Cap with Adaptive Socket Watchdog
- Replaced the fixed 180s total request timeout cap on streaming connections with `HttpTimeoutConfig.INFINITE_TIMEOUT_MS`.
- Long generations, deep reasoning chains (e.g., DeepSeek-R1, o1/o3-mini), and multi-step tool workflows are never killed prematurely by a hard request clock as long as tokens/chunks are arriving.
- Active streaming connections are governed by a responsive socket inactivity timeout to detect and terminate severed TCP links cleanly.

#### 2. Ktor Engine Keep-Alive & Connection Pooling Hardening
- Hardened Ktor CIO engine configurations with a 60-second connection `keepAliveTime`, 30s connection timeout, 100 max connections per route, and 3 connection attempts.
- Eliminates silent TCP connection drops from intermediate cellular carriers, Cloudflare proxies, and reverse proxy gateways while an LLM is thinking (TTFT).

#### 3. Per-Tool Execution Timeout Safeguard
- Added a 45-second execution timeout watchdog (`toolTimeoutMillis = 45_000L`) to `agentRunnerForPlatform`.
- If an MCP tool or local function call stalls or hangs indefinitely, the conversation engine recovers gracefully with a descriptive error message instead of locking the entire agent loop and freezing the UI.

#### 4. Version Bump to v0.9.3.1
- Version code incremented to `41`, version name updated to `0.9.3.1`.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
