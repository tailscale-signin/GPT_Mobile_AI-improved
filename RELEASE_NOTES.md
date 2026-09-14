# Release Notes - v0.9.4.0 (Pre-release)

Welcome to **GPT Mobile AI (Improved)** v0.9.4.0 Pre-release!

This release brings stability hardening across database migrations, core agent tooling architecture, design token standardization, and build pipeline optimization.

---

### Key Highlights & Improvements

#### 1. 🗄️ Authoritative Database Migration Architecture
- **Consolidated Migration Registry**: Centralized all database migrations into an authoritative `ChatDatabaseV2Migrations.ALL_MIGRATIONS` array, eliminating manual duplicate registrations in `DatabaseModule`.
- **Dynamic Schema Continuity Verification**: Enhanced `ChatDatabaseV2MigrationsTest` to dynamically validate incremental migration step continuity up through Schema 19.

#### 2. 🛡️ Pure-Domain Tool Budget & Safety Ceiling Engine
- **Decoupled Tool Budget Policy**: Isolated agent tool budget enforcement, step thresholds, and steering prompts into a standalone, pure-domain `ToolBudgetPolicy`.
- **Exhaustive Edge-Case Validation**: Comprehensive test suite covering budget limits, token reserve calculations, low-ceiling fixtures, and step-budget boundaries.

#### 3. 🎨 Standardized Chat UI Design Tokens
- **Theme Alpha Tokens**: Introduced `@Immutable data class ChatAlphaTokens` and `LocalChatAlpha` design token container to standardize UI surface alphas across chat bubbles, code surfaces, and tool traces.

#### 4. 📦 Build & Packaging Details
- Version code incremented to `43`, version name set to `0.9.4.0-pre`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported ABIs: `arm64-v8a`, `x86_64`.
- Automated release build with R8 minification and resource shrinking.

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (universal binary for all modern 64-bit devices)
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` (optimized footprint for modern Android phones) and `app-x86_64-release.apk` (for emulators and Chromebooks)
- **Release Bundle (AAB)**: `app-release.aab`
