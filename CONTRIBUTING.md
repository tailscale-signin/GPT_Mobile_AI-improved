# Contributing to GPT Mobile AI

Thank you for your interest in contributing to **GPT Mobile AI**! We welcome contributions, bug reports, and feature suggestions.

---

## 🛠️ Prerequisites & Development Environment

Before contributing, make sure your development environment matches the project specifications:

- **JDK**: Java Development Kit 21 (Temurin or OpenJDK recommended)
- **Android SDK**:
  - Target/Compile SDK: `36`
  - Min SDK: `26`
  - Build Tools: `35.0.0`
- **Gradle**: `9.4.1` (use `./gradlew` wrapper)
- **Kotlin**: `2.3.21` / KSP `2.3.4`
- **IDE**: Android Studio Ladybug (or newer) recommended

---

## 🚀 Building & Testing

### Clone Repository

```bash
git clone https://github.com/tailscale-signin/GPT_Mobile_AI-improved.git
cd GPT_Mobile_AI-improved
```

### Build Debug APK

```bash
./gradlew assembleDebug
```

### Run Unit Tests

```bash
./gradlew testDebugUnitTest
```

### Run Android Lint Checks

```bash
./gradlew lintDebug
```

---

## 📐 Architecture & Code Conventions

The codebase follows Clean Architecture with MVVM:
- **Presentation Layer**: Jetpack Compose UI, Material 3, ViewModel, UI state management.
- **Data Layer**: Room SQLite Database, Ktor HTTP client, LiteRT / GGUF local model runtime.
- **Dependency Injection**: Hilt.

### Conventions
1. **Formatting**: Follow standard Kotlin coding conventions.
2. **Commit Messages**: Write concise, descriptive commit messages in conventional commit format (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `ci:`).
3. **Tests**: Add or update unit tests in `app/src/test/java/dev/chungjungsoo/gptmobile/` when adding new business logic or fixing bugs.

---

## 🔀 Submitting a Pull Request

1. Fork or branch off `main` with a descriptive branch name (`feature/xyz` or `fix/issue-description`).
2. Make your changes and verify with `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`.
3. Open a Pull Request against `main`. Fill out the Pull Request template details.
4. Ensure all CI validation checks pass.
