# Release Notes - v0.8.0

Welcome to the release of **GPT_Mobile_AI-improved** (v0.8.0)!

This release consolidates significant architecture modernizations, database schema enhancements, security improvements, performance optimizations, and developer ergonomics across the codebase.

---

### Highlights & Key Improvements

#### 1. Database & Persistence (Room Migration 11 -> 12)
- **Favorites Support**: Added `is_favorite` flag to `ChatRoomV2` entity and underlying schema.
- **Robust Migration**: Automated SQLite migration `MIGRATION_11_12` with column existence verification and full test coverage.
- **DAO Queries**: Added dedicated queries for favorite chat management (`getFavoriteRooms`, `updateFavoriteStatus`, and sorting).

#### 2. Network & Architecture Modernization
- **Modern OkHttpClient & Coroutines**: Replaced deprecated/legacy network handling with structured OkHttp & Kotlin Coroutines.
- **Streaming Optimizations**: Unified zero-allocation SSE streaming parser via `SseUtils` across OpenAI, Anthropic, Google Gemini, and Groq providers.
- **Provider Refactoring**: Type-safe request/response models with robust error propagation.

#### 3. Android 14+ / 15 Compatibility & UI/UX
- **Edge-to-Edge & Insets**: Seamless edge-to-edge support across modern Android versions using `WindowInsetsCompat`.
- **Theme & Dark Mode**: Refined Material 3 color schemes, typography, and contrast for both light and dark themes.
- **Compose Stability**: Optimized Compose state holding and message list rendering with keys to minimize recompositions during rapid streaming.

#### 4. Build, ProGuard & Release Pipeline
- **R8 / ProGuard Optimization**: Comprehensive keep and dontwarn rules for MCP Kotlin SDK, Ktor, LiteRT-LM, and Kotlinx Serialization.
- **Automated CI/CD**: GitHub Actions workflow (`.github/workflows/release-build.yml`) compiles:
  - Universal Release APK (`app-release.apk`)
  - Google Play App Bundle (`app-release.aab`)
- **Resilient Signing**: Dynamic fallback signing for frictionless reproducible builds.
- **Automated GitHub Release Publishing**: Automatically generates and attaches release artifacts on git tag push.

---

### Download & Installation
- **APK**: Download `app-release.apk` from the GitHub Release assets and install directly on Android 8.0 (API level 26) or later.
- **AAB**: `app-release.aab` is available for deployment or internal testing distributions.
