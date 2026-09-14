# Release Notes - v0.9.4.1

Welcome to **GPT Mobile AI (Improved)** v0.9.4.1!

This release introduces high-performance Ollama server and generation controls, fixes and closes open repository issues, and delivers build optimization and version bumps.

---

### Key Highlights & Improvements

#### 1. ⚡ High-Performance Ollama Advanced Options & Defaults
- **Flash Attention**: Enabled by default (`flash_attention = true`) for accelerated attention computations.
- **KV Cache Quantization**: Configured default `kv_cache_type` to `"q8_0"` to reduce VRAM consumption by ~50% while preserving inference accuracy.
- **Parallel Requests**: Configured default `num_parallel` to `2` for concurrent request handling.
- **Keep-Alive Duration**: Updated default `keep_alive` to `"30m"` to keep active models warm in VRAM.
- **Context Length**: Standardized default `num_ctx` to `4096` tokens for balanced memory and context.
- **GPU Overhead Reservation**: Configured default `gpu_overhead` to 1GB (`1073741824L`) to reserve memory headroom for GPU system operations.
- **Settings UI Controls**: Extended the Ollama Advanced Options dialog with interactive toggles and inputs for Flash Attention, KV Cache Type, Keep Alive, Num Parallel, and GPU Overhead.

#### 2. 🗄️ Database Migrations & Agent Tooling Architecture
- Consolidated authoritative `ChatDatabaseV2Migrations.ALL_MIGRATIONS` registry.
- Pure-domain `ToolBudgetPolicy` agent safety and ceiling validation engine.
- Material 3 `@Immutable` `ChatAlphaTokens` UI surface standardization.

#### 3. 📦 Build & Packaging Details
- Version code incremented to `44`, version name set to `0.9.4.1`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported ABIs: `arm64-v8a`, `x86_64`.
- Release build with R8 minification, resource shrinking, and deterministic key signing.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary for all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
