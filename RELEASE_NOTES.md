# Release Notes - v0.9.5.4

Welcome to **GPT Mobile AI (Improved)** v0.9.5.4!

This release introduces a consolidated backup and restore module with atomic rollback and SHA-256 integrity checks, an overhauled Platform Label and Platform Settings Dialog with decoupled domain models and secure credential entry, and updated Android Gradle build dependencies.

---

### Key Highlights & Improvements

#### 1. 🛡️ Consolidated Backup & Restore Module
- **Integrity & Security**: Complete database and preferences backup bundle featuring SHA-256 manifest verification and tamper protection.
- **Atomic Operations & Rollback**: Safe atomic write procedures with automatic staging directory cleanups and rollback upon failure.
- **Data Preview & Selectivity**: Inspect conversations, configurations, and platform settings before executing a restore.

#### 2. 🏷️ Enhanced Platform Labels & Settings Dialogs
- **Decoupled Architecture**: `AIPlatform` enum decoupled from UI drawing objects, cleanly separating presentation assets via helper extensions.
- **Secure Password Field**: API keys and tokens are securely masked with input sanitization and `KeyboardType.Password`.
- **Custom Endpoints**: Direct proxy/gateway URL configuration support for OpenAI, Anthropic, Ollama, and local/custom endpoints.

#### 3. ⚙️ Build & Packaging Improvements
- Resolved Material Components dependency configuration.
- Version Code: `56`
- Version Name: `0.9.5.4`
- Target SDK: 36 (Android 16), Min SDK: 31 (Android 12)
- Architectures: `arm64-v8a`, `x86_64`, Universal APK
