# Release Notes - v0.8.2

Welcome to the release of **GPT_Mobile_AI-improved** (v0.8.2)!

This release delivers major performance optimizations, robust networking, enhanced UI responsiveness, and memory safeguards alongside full feature parity.

---

### Highlights & Key Improvements

#### 1. Performance & UI Responsiveness
- **Jetpack Compose Frame Alignment**: Implemented frame-aligned 30fps inbound streaming message buffer (`StreamingMessageBuffer`) preventing dropped frames during high-throughput inference tokens.
- **Compose Stability Contracts**: Annotated chat data and state classes (`MessageItemState`, `ThinkingState`, `VoiceState`, etc.) with `@Immutable` / `@Stable` to skip unneeded recompositions.
- **LazyList Optimization**: Added stable item keys and recycled `contentType` bindings across all chat message list components.
- **Stream-based Image Downsampling**: Direct stream decoding with bounds sampling (`inJustDecodeBounds`) in `FileUtils` reduces peak memory footprint by 50–75% for large image attachments.

#### 2. Network Resilience & Streaming Reliability
- **Pooled Networking Engine**: Standardized singleton CIO Ktor engine pooling across clients.
- **Exponential Backoff**: Integrated jittered exponential retry mechanism for transient network dropouts.
- **Cross-Provider SSE Recovery**: Robust newline buffering via `SseUtils` handling split SSE packets from any provider.
- **Safe Resource Limits**: Bounded buffer streaming in `ReadUrlTool` and sliding-window compacting in `ContextBuilder` preventing OOMs.

#### 3. DeepSeek / Reasoning Thinking Accordion
- **Collapsible UI**: Integrated `ThinkingAccordion` Jetpack Compose component to display `<think>...</think>` reasoning tokens cleanly.
- **Dynamic State**: Supports in-progress thinking animation and completed collapsibility with copy-to-clipboard functionality.
- **Robust Parser**: Zero-leak regex-based `ThinkingParser` with full unit test coverage (`ThinkingParserTest.kt`).

#### 4. MCP Marketplace & Extensibility
- **In-App Discovery**: Added `McpMarketplaceDialog` to browse, search, and install MCP server configurations with a single tap.
- **Category Filter Chips**: Filter by Search, Development, Database, Productivity, System, or Browser tools.
- **MCPSearch Android-Termux Toolset**: Native 5-in-1 toolset support for local device tools and Termux environment discovery (`MCPSearchAndroidTermuxToolset`).
- **Per-Chat Tool Customization**: Contextual tool and MCP scoping via `ChatToolSelectionBottomSheet`.
- **Device Location Tool**: Safe contextual geolocation tool with Android runtime permission checks (`DeviceLocationTool`).

#### 5. Build, Packaging & Release Pipeline
- **Targeted ABI Splits**: Native APKs for `arm64-v8a` and `x86_64` plus Universal APK.
- **Optimized Minification**: Aggressive R8 rules and ProGuard log stripping for lean, secure production binaries.
- **Automated CI/CD**: Deterministic signing and artifact generation in `.github/workflows/release-build.yml`.

---

### Download & Installation
- **APK**: Download split (`arm64-v8a`, `x86_64`) or universal `app-release.apk` from the GitHub Release assets.
- **AAB**: `app-release.aab` is available for deployment or internal testing distributions.
