# Release Notes - v0.9.2

Welcome to the official release of **GPT Mobile AI (Improved)** (v0.9.2)!

This milestone release brings enhanced message favorites organization with custom labeling and categorization, Room Database Schema 19, improved chat navigation and deep-linking, refined background execution resilience, and stabilization across UI and local inference components.

---

### Key Highlights & Features

#### 1. Message Favorites & Custom Labeling
- **Message-Level Favoriting**: Mark individual messages as favorites directly in chat threads with instant state synchronization.
- **Custom Labels & Organization**: Assign custom labels, tags, and category groupings to favorited messages for structured access and search.
- **Smooth Deep-Linking**: Jump directly to favorited responses from the Favorites management screen with accurate list scroll positioning and platform tab auto-selection.

#### 2. Persistence & Room Database Schema 19
- **Schema Migration (18 -> 19)**: Added favorite status (`is_favorite`), labels (`labels`), and creation timestamp fields to database entities with backward-compatible SQLite migrations.
- **Robust Backup & Restore**: Full preservation of message tags and favorite statuses across configuration export/import cycles.

#### 3. Chat Layout & Performance Refinements
- **Unified Notice & Execution Chips**: Streamlined presentation of agent tool execution, thinking traces, and provider notices within chat bubbles.
- **Optimized Compose Rendering**: Reduced recompositions and improved scrolling responsiveness across long conversation histories.
- **Hardware Acceleration Stability**: Tuned cooperative thread yielding and thermal throttling checkpoints during local LiteRT-LM model execution.

#### 4. Build, Packaging & Architecture
- **Target SDK**: Android 16 (API 36) with minimum SDK 31 and Java 21 bytecode.
- **Modern 64-bit ABIs**: Optimized signed release APKs (`arm64-v8a`, `x86_64`, and universal) built with R8 code and resource shrinking.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
