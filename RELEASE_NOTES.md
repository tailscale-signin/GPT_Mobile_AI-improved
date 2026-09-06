# Release Notes - v0.8.4

Welcome to the release of **GPT_Mobile_AI-improved** (v0.8.4)!

This release delivers comprehensive background & screen-off task execution resilience, robust agent safety limits, improved Termux / DuckDuckGo tool integration, and CI stability fixes.

---

### Highlights & Key Improvements

#### 1. Background, Screen-off & Device-Locked Execution
- **Foreground Service Alignment**: Fully registered `AgentRunForegroundService` with `dataSync` foreground service type in `AndroidManifest.xml` targeting modern Android 14+ / 16 (API 36).
- **CPU Partial WakeLock**: Added `WAKE_LOCK` capability via `PowerManager.PARTIAL_WAKE_LOCK` during active agent runs, network streaming, and local model inference to prevent CPU sleep when the screen turns off or the device locks.
- **Battery Optimization Safeguards**: Added `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` support to protect long-running sessions from aggressive OEM process killers.
- **Memory Pressure Protection**: Guarded `onTrimMemory` in `GPTMobileApp` so active local LiteRT inference models and generation threads are not evicted prematurely under background memory warnings.

#### 2. Agent Safety & Tool Execution Ceiling
- **Hard Tool Call Limit**: Enforced a strict default limit of 6 tool calls per prompt in `AgentRunLimits`. Agent loops safely stop before exceeding 6 tool calls, preventing runaway loops and excessive API costs.
- **Robust Unit Testing**: Added regression coverage in `AgentRunnerTest` to guarantee tool ceilings are enforced.

#### 3. Zero-Config Toolset & Extensibility
- **Termux & DuckDuckGo Search Integration**: Built-in fallback toolset with descriptive headers and seamless parameter validation.
- **MCP Marketplace**: In-app marketplace with categorized filtering and instant installation.

#### 4. CI/CD & Linter Stability
- **Reviewdog CI Fix**: Fixed missing GitHub token and job permissions in `ktlint.yml` workflow, restoring automated pull request code style checks.

---

### Download & Installation
- **APK**: Download split (`arm64-v8a`, `x86_64`) or universal `app-release.apk` from the GitHub Release assets.
- **AAB**: `app-release.aab` is available for deployment or internal testing distributions.
