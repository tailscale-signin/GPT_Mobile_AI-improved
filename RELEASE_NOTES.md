# Release Notes - v0.9.5.1

Welcome to **GPT Mobile AI (Improved)** v0.9.5.1!

This official release resolves build stabilization and compilation issues, cleans up resource dependencies, and produces signed release distribution artifacts.

---

### Key Highlights & Improvements

#### 1. 🛠️ Build & Compilation Fixes
- **Kotlin Type Inference (`AdvancedSettingsViewModel`)**:
  - Replaced generic `TypeToken` construction with `gson.fromJson(json, AdvancedSettings::class.java)`, fixing the Kotlin 2.x type parameter inference error during compilation.
  - Removed unused `com.google.gson.reflect.TypeToken` import.
- **Resource Style Resolution (`fragment_advanced_llama_settings.xml`)**:
  - Removed undefined `@style/Widget.Material3.Button.OutlinedButton` style reference on the Reset button, allowing fallback to default Material 3 styling and preventing packaging/lint errors.
- **Manifest Cleanup (`AndroidManifest.xml`)**:
  - Removed deprecated `android:extractNativeLibs` attribute in accordance with Android Gradle Plugin modern packaging recommendations.

#### 2. 📦 Build & Packaging Details
- Version code incremented to `53`, version name set to `0.9.5.1`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs (Universal, arm64-v8a, x86_64) and Android App Bundle (AAB).

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary for all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
