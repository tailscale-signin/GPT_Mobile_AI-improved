# Release Notes - v0.8.9

Welcome to the official release of **GPT Mobile AI (Improved)** (v0.8.9)!

This release includes major upgrades to autonomous agent capabilities, favorites management and navigation, multi-key API credential rotation, local on-device inference, and database schema evolution.

---

### Key Highlights & Features

#### 1. Favorites Management & Deep Navigation
- **Rich Favorite Detail View**: Redesigned Favorites tab with custom category groups ("All", user groups, "+ Add Group"), assignment dropdowns, Markdown/LaTeX/code rendering, and confirmation dialog for unfavoriting.
- **Reliable In-Chat Navigation**: Tapping "View" in the favorite detail dialog seamlessly opens the exact chat room and scrolls directly to the favorited message.
- **Platform Tab Auto-Switching**: When navigating to a favorited response in multi-platform chats, the corresponding provider tab automatically activates.
- **Taller Highlight Container**: Integrated `OpponentResponseContainer` with an animated cyan highlight surrounding the entire assistant response block (avatar, loading indicators, platform tabs, and chat bubble).
- **Haptic Feedback**: Long-pressing the favorite icon triggers a subtle haptic vibration confirming the action.

#### 2. Agent Tools & Line Slicing
- **Bounded Line Slicing (`read_file_slice`)**: Added the built-in `read_file_slice` tool and automatic MCP line slicing (`start_line`, `end_line`) for remote file-reading tools (e.g. GitHub `get_file_contents`) to dramatically cut token usage and context overhead.
- **Preinstalled Search Tooling**: Bundled `droid-mcp-web` Online Search (`web_search`, `fetch_webpage`) for out-of-the-box web search and content retrieval.
- **Execution Traces**: Restyled `ToolTraceBlock` with dark card backgrounds, brand icons, and collapsible tool outputs.

#### 3. Multi-Key API Credential Rotation
- **High-Availability Multi-Key Support**: `ApiCredentialRotator` with round-robin failover across multiple keys per provider.
- **Automatic Fallback on Rate Limits**: Seamless fallback on HTTP 429, 402, 401, and quota exhaustion without interrupting streaming sessions.
- **Dynamic Key Management UI**: Easily add and manage multiple API keys with the `+API` button across Platform Settings, the Setup Wizard, and MCP Tool Connections.

#### 4. Architecture & Persistence
- **Room Database Schema v14**: Fully migrated database schema adding tool connections, timeline items, agent run persistence, agent tool bindings, and configurable platform tool-call limits (`max_tool_calls`).
- **Android Target**: Compiled against Android 16 (API 36) with min SDK 31, Java 21 bytecode, and modern 64-bit ABIs (`arm64-v8a`, `x86_64`).
- **Local LiteRT-LM Inference**: Dynamic hardware acceleration selection (NPU, GPU, CPU) and background model downloading via WorkManager.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
