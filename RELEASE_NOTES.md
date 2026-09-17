# Release Notes - v0.9.4.4-pre

Welcome to **GPT Mobile AI (Improved)** v0.9.4.4-pre!

This pre-release includes the newly merged Ollama auto-continuation feature for truncated completions, updated model generation resilience, and version code bump to 47.

---

### Key Highlights & Improvements

#### 1. 🔄 Ollama Auto-Continue for Truncated Generations
- Detects when Ollama completions finish due to token limits (`finishReason == "length"`).
- Automatically chains subsequent completion requests (`autoContinue = true`) up to `maxAutoContinues` (default: 3) to seamlessly complete responses.
- Provides immediate streaming user feedback (`"Auto-continuing response (1/3)..."`).
- Added configuration toggles and inputs in `OllamaAdvancedSettingsDialog` with corresponding string resources and test suite coverage.

#### 2. 📦 Build & Packaging Details
- Version code incremented to `47`, version name set to `0.9.4.4-pre`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs and Android App Bundle (AAB).
