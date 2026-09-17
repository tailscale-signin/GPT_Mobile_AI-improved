# Release Notes - v0.9.4.4

Welcome to **GPT Mobile AI (Improved)** v0.9.4.4!

This official release introduces native Ollama auto-continuation for truncated completions, enhanced model generation resilience, updated runtime optimizations, and official packaging artifacts.

---

### Key Highlights & Improvements

#### 1. 🔄 Ollama Auto-Continue for Truncated Generations
- Detects when Ollama completions finish due to token limits (`finishReason == "length"`).
- Automatically chains subsequent completion requests (`autoContinue = true`) up to `maxAutoContinues` (default: 3) to seamlessly complete responses.
- Provides immediate streaming user feedback (`"Auto-continuing response (1/3)..."`).
- Added configuration toggles and inputs in `OllamaAdvancedSettingsDialog` with corresponding string resources and test suite coverage.

#### 2. ⚡ Local Runtime & Acceleration Polish
- Unified acceleration detection supporting Qualcomm QNN NPU, OpenCL GPU, and multi-threaded CPU acceleration.
- Validated on-device LiteRT-LM runtime execution pipelines.

#### 3. 📦 Build & Packaging Details
- Version code incremented to `48`, version name set to `0.9.4.4`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs and Android App Bundle (AAB) optimized with R8 minification and resource shrinking.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary for all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
