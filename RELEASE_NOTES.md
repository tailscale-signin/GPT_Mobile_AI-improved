# Release Notes - v0.9.2.4

Welcome to **GPT Mobile AI (Improved)** (v0.9.2.4)!

This release introduces voice conversation coordination, on-device document RAG, multi-step agent workflow visualization, resilient streaming with exponential backoff, incremental streaming diff parsing, and sandboxed artifact previewing.

---

### Key Highlights & Features

#### 1. Voice Session Coordinator (Full-Duplex Voice Conversations)
- **State Machine Architecture**: `VoiceSessionCoordinator` manages a complete full-duplex voice conversation lifecycle with states: `IDLE → LISTENING → TRANSCRIBING → THINKING → SPEAKING → ERROR`.
- **Immediate User Interruption**: When the system is speaking and the user begins talking, the session immediately interrupts and returns to `LISTENING` state.
- **Audio Energy Monitoring**: Real-time audio energy level tracking (`audioEnergyLevel` StateFlow) for visual feedback during voice input.
- **Silence Detection**: Automatic transition from `LISTENING` to `TRANSCRIBING` when silence is detected, enabling hands-free operation.

#### 2. On-Device Document RAG Engine
- **Zero-Cloud Document Retrieval**: `DocumentRagEngine` enables local Retrieval-Augmented Generation for attached text, Markdown, and source code files without any cloud dependency.
- **Overlapping Chunking**: Documents are split into overlapping windows (default 500 chars, 100 char overlap) respecting newline and sentence boundaries.
- **Dual Search Modes**:
  - **Keyword (BM25-style)**: Token-overlap lexical similarity search without requiring an embedding model.
  - **Vector (Cosine Similarity)**: Precomputed vector embedding search for semantic retrieval.
- **Thread-Safe Indexing**: Synchronized chunk store with per-document or full clearing.

#### 3. Multi-Step Agent Workflow Visualization (AgentPlanCard)
- **Plan-and-Execute Model**: `AgentPlan` and `AgentTaskStep` data models enable multi-step autonomous task tracking with structured sub-goal execution.
- **Workflow States**: Plans track `NOT_STARTED → IN_PROGRESS → COMPLETED → FAILED → WAITING_USER_INPUT` with individual step statuses (`PENDING → RUNNING → SUCCESS → FAILED → SKIPPED`).
- **Tool Integration**: Each step can reference a specific tool name and display result snippets for transparency.
- **UI Component**: `AgentPlanCard` composable renders the full workflow with progress indicators and step details.

#### 4. Resilient Streaming Client
- **Exponential Backoff with Jitter**: `ResilientStreamingClient` automatically retries transient failures (HTTP 429, 502, 503, 504, socket disconnects, timeouts) with configurable exponential backoff and jitter.
- **Configurable Retry Policy**: `RetryConfig` supports max attempts (default 4), initial delay (1s), max delay (16s), backoff factor (2x), and jitter ratio (20%).
- **Retry Callback**: Optional `onRetry` callback provides attempt count, delay duration, and error reason for logging/UI feedback.
- **Smart Retry Detection**: `isRetryable()` identifies transient network errors, rate limits, and server gateway failures.

#### 5. Streaming Diff Parser
- **Incremental Markdown Parsing**: `StreamingDiffParser` tracks stable completed markdown blocks (code fences, paragraphs, lists) versus the actively streaming leaf tail.
- **Reduced Recomposition**: Prevents full-AST re-tokenization and excessive Jetpack Compose recomposition jank during high-speed token streaming (100+ tokens/sec).
- **Block Types**: Supports `PARAGRAPH`, `CODE_BLOCK`, `HEADING`, `LIST_ITEM`, and `BLOCKQUOTE` block types.
- **Stateful Caching**: Maintains parsed block state across streaming updates with `DiffResult` containing stable blocks, active tail, and change detection.

#### 6. Sandboxed Artifact Previewing
- **Safe HTML/SVG Rendering**: `SandboxedArtifactView` provides secure interactive previewing of HTML and SVG artifacts within the chat interface.
- **Isolation**: Artifacts are rendered in a sandboxed environment to prevent XSS and other security risks.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`