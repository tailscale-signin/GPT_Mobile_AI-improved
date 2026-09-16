# Technical Design Document: Feature Review for GPT Mobile AI (Improved)

## 1. Introduction

This document outlines the technical design for reviewing the recent feature branches and releases merged into `main`, ensuring that all architectural additions and feature highlights are correctly integrated, verified, and documented to provide a reliable foundation for future development iterations.

## 2. Scope

The scope of this review covers:
- Verification of feature integration for recent milestones and PR branches (`0.8.0` through `0.9.4.2-pre`)
- Detailed analysis of feature highlights documented in `README.md`, `CHANGELOG.md`, and `PROGRESS.md`
- Gap analysis identifying recently integrated features previously omitted from technical design documentation
- Concrete architectural patterns and contracts for ongoing and future feature development

## 3. Feature Highlights Analysis

Based on repository documentation and merged implementations, the codebase features several core modules and subsystems:

### 3.1. Core Inference & Runtime Engines
- **Dual Local Inference Backends (LiteRT-LM & Qualcomm QNN)**:
  - Phase-split scheduling (`PREFILL` vs. `GENERATING`) with cooperative thread yielding (`yield()`) to eliminate UI thread stalls.
  - Qualcomm QNN NPU hardware acceleration with dynamic library verification, FastRPC execution, and multi-tier fallback hierarchy: QNN (NPU) → OpenCL (GPU) → Multi-threaded CPU.
  - Unified runtime dispatch via `LocalRuntimeRouter` with automated fallback delegation and lifecycle management.
  - Dynamic `DeviceHardwareGovernor` regulating context token limits, stream pacing, and top-k sampling under thermal or battery pressure.
  - Idle memory auto-unloading (`unloadIfIdle`) in `LocalEngineHolder` preventing memory bloat after inactivity.
  - Turn 0 anchor preservation and context ceiling truncation via `RollingContextWindowCompactor`.
- **High-Performance Remote Providers (Ollama & OpenRouter)**:
  - Ollama high-performance default tuning: Flash Attention, KV cache quantization (`q8_0`), keep-alive (`30m`), parallel execution slots (`num_parallel = 2`), and GPU memory overhead reservations.
  - OpenRouter advanced provider routing: Sort strategy (price, throughput, latency), quantization selection, provider fallbacks, and max reasoning tokens configuration.
  - Real-time OpenRouter Account Balance & Credits widget: In-memory caching, animated Compose card (`FancyOpenRouterCreditsCard`), and graceful error handling.

### 3.2. Agent Architecture & Multimodal Tooling
- **Autonomous Agent Runtime & Model Context Protocol (MCP)**:
  - Native tool calling across OpenAI, Groq, Anthropic, Google Gemini, and Ollama.
  - MCP Streamable HTTP server integration supporting bearer token and OAuth authentication (`dev.melo.gptmobile.improved://oauth/mcp/`).
  - Recursive tool parameter sanitization (`geminiToolParameters`) stripping invalid keywords and metadata.
  - Prebundled tools: Online search (`web_search`, `fetch_webpage`), bounded line slicer (`read_file_slice`), and in-app GitHub agent tool (`GitHubTool`).
  - Visual multi-step plan tracking: `AgentPlanCard` with step-by-step execution status and progress indicators.
- **On-Device Document RAG Engine (`DocumentRagEngine`)**:
  - Local document chunking, BM25 keyword matching, and cosine similarity vector retrieval with zero cloud data leakage.
- **Full-Duplex Voice Session Lifecycle (`VoiceSessionCoordinator`)**:
  - Five-stage finite state machine (`IDLE` → `LISTENING` → `TRANSCRIBING` → `THINKING` → `SPEAKING`).
  - Low-latency conversational pipeline with instant user interruption handling and clean audio resource cleanup.

### 3.3. Presentation, UI/UX & Formatting
- **Instant Bottom Anchoring**: Chat scroll stabilization with `rememberChatListState` keyed on message count with target message protection for deep links.
- **Sandboxed Interactive Artifacts (`SandboxedArtifactView`)**:
  - Isolated WebView previewing for generated HTML and SVG code blocks with preview/code toggle tabs, preventing XSS and script leakage.
- **In-Chat Diagnostics HUD**:
  - Real-time hardware and generation telemetry display tracking latency, TTFT, tok/s, token counts, and thermal status.
- **Collapsible UI Components**:
  - Spring-animated collapsible details (`DetailsButton` / `fastEffectsSpec`).
  - Subtle `ThinkingBlock` contrast polish (background alpha set to `0.25f`).
  - Cyan-highlighted `OpponentResponseContainer` for favorited responses.
- **Favorites & Deep Navigation**:
  - Grouped favorites management ("All", user-defined groups), deep linking straight to target chat bubbles, and automatic provider tab switching.

### 3.4. Reliability & Fault Tolerance
- **Resilient Conversation Error Handling & Circuit Breaker**:
  - 3-state circuit breaker (`CLOSED`, `OPEN`, `HALF-OPEN`) preventing API hammering during service outages.
  - Categorized exception mapping (`ErrorClassification`), actionable retry templates (`ErrorTemplates`), and recovery tracking (`ErrorTracker`).
- **Resilient Streaming Client & Diff Parser**:
  - Automatic reconnection with exponential backoff (`ResilientStreamingClient`).
  - Real-time diff parsing (`StreamingDiffParser`) minimizing Jetpack Compose recomposition overhead.
- **Multi-Key API Credential Rotation**:
  - Round-robin failover (`ApiCredentialRotator`) on HTTP 429 / 401 / 402 with seamless in-flight retry.

### 3.5. Security, Packaging & Persistence
- **Android Keystore Encryption**: AES-256-GCM encrypted credential vault with encrypted JSON export/import.
- **Side-by-Side Coexistence**: Separate Application ID (`dev.melo.gptmobile.improved`) enabling simultaneous installation with upstream releases.
- **Deterministic Persistent Signing**: Deterministic release signing key for seamless in-place app updates.
- **Room Database Evolution**: Room Schema v19 supporting platform configurations, tool bindings, agent run history, and routing rules.
- **Target Platform**: Android 16 (API 36), Java 21 bytecode, modern 64-bit ABIs (`arm64-v8a`, `x86_64`).

## 4. Merged Milestones & Branch Verification

Integration verification across recent release branches and merged PRs:

1. **`0.9.4.2-pre` (PR #273, #274, #275)**:
   - UI polish on `HomeTopBar` (removed redundant header title for clean navigation).
   - Documentation synchronization (`PROGRESS.md`, `CHANGELOG.md`) and automated pre-release build triggers.
2. **`0.9.4.1` (PR #255, #256, #269)**:
   - High-performance Ollama tuning options (`flash_attention`, `kv_cache_type = "q8_0"`, `keep_alive = "30m"`, `num_ctx = 4096`).
   - OpenRouter Live Credits & Balance card (`FancyOpenRouterCreditsCard`) with cached repository.
3. **`0.9.4.0` / `Qualcomm-QNN` (PR #246, #271, #280)**:
   - Qualcomm QNN NPU native execution, library detection, and hardware probe diagnostics.
   - Restored and verified `LocalAccelerators` and `LocalModelValidator` integrity checks (#284).
4. **`0.9.3.0` (PR #243)**:
   - Debug Mode and live Diagnostics HUD in `ChatBubble` and `ChatScreen`.
   - DataStore persistence for diagnostics toggle and `ThinkingBlock` visual contrast polish.
5. **`0.9.2.4` (PR #261, #262, #266, #268)**:
   - Full-duplex `VoiceSessionCoordinator`, on-device `DocumentRagEngine`, and `SandboxedArtifactView`.
   - Resilient conversation error handling with `CircuitBreaker`, `ErrorClassification`, and `ErrorTracker`.
6. **`0.9.0`**:
   - LiteRT-LM hardware acceleration (NPU/GPU/CPU), split phase scheduling, and rolling context compaction.
   - OpenRouter advanced provider routing and Room migration v15.
7. **`0.8.9.1`**:
   - OpenRouter custom reasoning tokens, sorting strategies, and fallback providers.
8. **`0.8.9`**:
   - Rich Favorites management with deep navigation and provider tab auto-switching.
   - Line slicing tools (`read_file_slice`, MCP slicing) and multi-key API credential rotation.
9. **`0.8.2`**:
   - Application ID separation (`dev.melo.gptmobile.improved`) and deterministic release signing.
   - Uncapped agent execution loops, timeouts, and parallel tool concurrency.
10. **`0.8.0` / `0.8.1`**:
    - Configuration backup & restore with encrypted JSON export.
    - Initial MCP HTTP server integration and Room DB Schema v14.

## 5. Technical Design Guidelines for Future Development

### 5.1. Runtime Backend Contract & Fallback Policy
- Any new local acceleration backend (e.g. Vulkan, WebGPU, or dedicated DSP) must implement the `LocalRuntime` interface.
- Runtime routing must be managed exclusively through `LocalRuntimeRouter`, which provides non-blocking initialization, automated fallback to `LiteRtRuntime`, and unified cancellation.
- Model files must be validated using `LocalModelValidator` (verifying minimum file size, existence, read permissions, and header magic) prior to invoking native JNI methods to prevent uncatchable native SIGSEGV crashes.

### 5.2. UI State Management & Compose Performance
- Keep state immutable and hoisted into ViewModels.
- UI components consuming streaming token flows must leverage diffing or debounced collectors (e.g. `StreamingDiffParser`) to limit recomposition frequency to 60fps/120fps display budgets.
- All code block rendering for executable web content (`html`, `svg`) must be routed through `SandboxedArtifactView` with script execution strictly scoped within an isolated WebView context.

### 5.3. Fault Tolerance & Network Resilience
- Every outbound remote LLM call must pass through `CircuitBreaker` and `ApiCredentialRotator`.
- Streaming endpoints must employ `ResilientStreamingClient` with exponential backoff on transient errors (HTTP 429, 502, 503, 504) rather than failing abruptly.

### 5.4. Unit Test Standards
- Maintain strict interface parity in all test fakes (`FakeLocalRuntime`, `FakeSettingDataSource`, `FakeRouterSettingRepository`).
- Coroutine-based tests must use `runTest` with `StandardTestDispatcher` and `advanceUntilIdle()` to ensure clean virtual time progression without thread race conditions.

## 6. Conclusion

All major feature highlights across the recent merged branches—including dual local inference (LiteRT-LM & Qualcomm QNN), full-duplex voice coordination, local document RAG, sandboxed artifact previews, circuit-breaker resilience, and OpenRouter credit telemetry—are verified and active within the application. This design document establishes architectural integrity and clear guidance for forthcoming iterations.
