# Release Notes - v0.8.0

Welcome to the initial release of **GPT_Mobile_AI-improved** (v0.8.0)!

This release consolidates significant architecture modernizations, security enhancements, performance optimizations, and developer ergonomics across the codebase.

---

### Highlights & Key Improvements

#### 1. Network & Architecture Modernization
- **Modern OkHttpClient & Coroutines**: Replaced deprecated/legacy network handling with structured OkHttp & Kotlin Coroutines.
- **Provider Refactoring**:
  - Implemented unified, robust SSE streaming parsing via `SseUtils` (zero-allocation line parsing supporting both `data: ` and `data:` SSE standards).
  - Streamlined provider API implementations across OpenAI, Anthropic, Google Gemini, and Groq.
  - Type-safe models and parameters with robust error handling for API responses.

#### 2. Android 14+ / 15 Compatibility & UI/UX
- **Edge-to-Edge & Insets**: Seamless edge-to-edge support across modern Android versions using `WindowInsetsCompat`.
- **Theme & Dark Mode**: Refined Material 3 color schemes, typography, and contrast for both light and dark themes.
- **Compose Stability**: Optimized Compose state holding and message list rendering with keys to minimize recompositions during rapid streaming.

#### 3. Security & Safety
- **Network Security Config**: Configured strict cleartext traffic controls and verified TLS settings.
- **Secret & API Key Handling**: Encrypted key storage and safeguards against credential leakage in logs or exports.

#### 4. Automated Release Pipeline
- **CI/CD Build Automation**: GitHub Actions workflow (`.github/workflows/release-build.yml`) produces:
  - Universal Release APK (`app-release.apk`)
  - Google Play App Bundle (`app-release.aab`)
- **Resilient Signing**: Dynamic fallback self-signing when custom keystore secrets are not configured, enabling zero-friction reproducible builds.
- **Automated GitHub Release Publishing**: Uploads and links release binaries directly upon tag push.

---

### Download & Installation
- **APK**: Download `app-release.apk` from the GitHub Release assets and install directly on Android 8.0 (API level 26) or later.
- **AAB**: `app-release.aab` is available for deployment or internal testing distributions.
