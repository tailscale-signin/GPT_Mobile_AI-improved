# Release Notes - v0.9.5.3

Welcome to **GPT Mobile AI (Improved)** v0.9.5.3!

This release introduces message queuing during AI generation, shared platform labels for multi-platform organization, Llama model selection in router mode, and standardized MCP tool definitions with triple-verification processes.

---

### Key Highlights & Improvements

#### 1. 📬 Message Queuing During AI Generation
- **`GenerationQueueManager`**: Implements FIFO message queueing allowing users to write and queue messages while active responses generate.
- **Capacity Limits & Overflow Protection**: Strict 50-message queue limit with automatic FIFO dropping of oldest pending message when saturated.
- **Reactive UI & Status Tracking**: Live queue badge counter, stop-generation confirmation dialogs, and automated sequential dequeuing upon completion.

#### 2. 🏷️ Shared Labels for AI Platforms
- **`PlatformLabel` & `PlatformLabelManager`**: Categorize, filter, and tag platforms across cloud and local providers.
- **Predefined Palette & Validation**: 12-color hex palette, 2–50 character name validation, and cross-platform usage count tracking.

#### 3. 🦙 Llama Model Selection Dropdown (Router Mode)
- **`LlamaModelInfo` & `LlamaModelMapper`**: Structured model info extracting parameter counts (in millions), quantization levels (e.g. Q4_K_M, fp16), and context window sizes.
- **Router Configuration**: Configurable server endpoint paths, retry attempts, timeout parameters, and dynamic server load metrics.

#### 4. 🧩 Model Context Protocol (MCP) Structure & Triple Verification
- Standardized directory layout under `mcp/tools/`, `mcp/resources/`, `assets/mcp-downloads/`, and server landing page `mcp/index.html`.
- Triple-verification workflow: manifest schema checking, SHA digest validation, and read-after-write consistency checks.

---

### Build & Packaging Details
- Version Code: `55`
- Version Name: `0.9.5.3`
- Target SDK: 36 (Android 16), Min SDK: 31 (Android 12)
- Architectures: `arm64-v8a`, `x86_64`
