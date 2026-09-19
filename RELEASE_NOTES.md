# Release Notes - v0.9.5.0

Welcome to **GPT Mobile AI (Improved)** v0.9.5.0!

This release brings full **Llama Platform Support**, the **Unified Model Picker & Message Queue** architecture, build stabilization, and performance enhancements across the application.

---

### Key Highlights & Improvements

#### 1. 🦙 Llama Platform Support
- **Full Provider Integration**:
  - Added native `ClientType.LLAMA` support.
  - Added `llama3.3`, `llama3.2`, `llama3.1`, and `llama3` defaults with `llama3.3` as default.
  - Streaming session execution powered by the OpenAI-compatible streaming adapter.
  - Configured custom turn windows and inline attachment budgets in `ProviderContextPolicy`.
  - Added endpoint and API key validation in `ApiKeyValidator`.
  - Added dedicated setup wizard flows and documentation guidance in `SetupPlatformWizardScreen` and `AddPlatformScreen`.
  - Localized UI strings for platform names and descriptions.

#### 2. 🗂️ Unified Model Picker & Message Queue
- **Unified Model Repository**:
  - Centralized model management across OpenAI, Anthropic, Gemini, Groq, Ollama, OpenRouter, LiteRT, and Llama.
  - In-memory caching for snappy model browsing and UI responsiveness.
- **Message Queuing & AIService**:
  - Asynchronous background message execution and resilient queuing.
  - Graceful completion state handling and streaming chunk extraction.

#### 3. 🛠️ Build & Compilation Fixes
- Added `@Composable` annotation to `AddPlatformTopBar` in `AddPlatformScreen.kt`.
- Fixed exhaustive `when` expressions for `ClientType.LLAMA` across `ApiKeyValidator.kt`, `SetupPlatformWizardScreen.kt`, and `MapStringResources.kt`.
- Resolved automated build failure issue #334.

#### 4. 📦 Build & Packaging Details
- Version code incremented to `52`, version name set to `0.9.5.0`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs (Universal, arm64-v8a, x86_64) and Android App Bundle (AAB).

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary for all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
