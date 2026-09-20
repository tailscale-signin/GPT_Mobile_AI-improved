# Release Notes - v0.9.5.5

Welcome to **GPT Mobile AI (Improved)** v0.9.5.5!

This full release brings critical build and compiler stabilization fixes, restores OpenRouter advanced parameter controls, strengthens Llama router mode model selection, and produces clean signed release distribution binaries.

---

### Key Highlights & Improvements

#### 1. 🛠️ Build Stabilization & Compiler Fixes
- **`PlatformSettingDialogs.kt` Resolution**:
  - Realigned `OpenRouterAdvancedSettingsDialog` with the `OpenRouterOptions` and `OpenRouterProviderRouting` data models (`stream`, `maxTokens`, `temperature`, `topP`, `topK`, `frequencyPenalty`, `presencePenalty`, `repetitionPenalty`, `seed`, and `provider = OpenRouterProviderRouting(sort, allowFallbacks)`).
  - Restored the required `onDismissRequest` parameter on `AlertDialog` in `LlamaAdvancedSettingsDialog`, fixing compilation and composable scope errors.
  - Automatically resolves release build issues #389 and #390.

#### 2. 🦙 Llama Router Mode & Advanced Options
- Seamless integration of `LlamaModelDropdown` in router configuration for custom host endpoints.
- Proper handling of server endpoints, timeout parameters, and dynamic model fetching.

#### 3. 🔀 OpenRouter Routing & Reasoning
- Direct controls for provider routing order and fallback toggles.
- Fine-grained token parameters including frequency/presence penalty, top-k/top-p sampling, and seed predictability.

#### 4. ⚙️ Build & Packaging Details
- **Version Code**: `57`
- **Version Name**: `0.9.5.5`
- **Target SDK**: 36 (Android 16), **Min SDK**: 31 (Android 12)
- **Architectures**: `arm64-v8a`, `x86_64`, Universal APK
- **Optimization**: R8 minification, resource shrinking, and native Qualcomm FastRPC library packaging (`useLegacyPackaging = true`).

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary for all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
