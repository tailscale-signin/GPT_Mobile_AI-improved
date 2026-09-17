# Release Notes - v0.9.4.6-pre

Welcome to **GPT Mobile AI (Improved)** v0.9.4.6-pre!

This pre-release integrates core application-wide performance and responsiveness optimizations, fixes Qualcomm QNN NPU runtime loading and probe verification, and prepares official distribution builds.

---

### Key Highlights & Improvements

#### 1. ⚡ Performance & Core Architectural Optimizations
- **Application & ViewModel Optimization**:
  - Streamlined main activity startup and viewmodel state updates.
  - Optimized database query caching and state dispatcher coordination.
  - Reduced GC pressure during heavy streaming sessions.

#### 2. 🧠 Qualcomm QNN HTP & NPU Runtime Fixes
- **QNN Operator Precedence Fix**:
  - Corrected boolean operator precedence in `QnnEnvironment.probeEnvironment` to accurately verify Hexagon NPU skeleton (`skelFoundPath`) and FastRPC libraries.
- **Redundant Engine Load & Error Propagation**:
  - Eliminated redundant model re-loading in `LocalRuntimeQnnImpl` when falling back to LiteRT, preventing duplicate memory allocations and Out-Of-Memory events on device.
  - Proper propagation of `CancellationException` to avoid blocking coroutine scopes.

#### 3. 📦 Build & Packaging Details
- Version code incremented to `50`, version name set to `0.9.4.6-pre`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs (Universal, arm64-v8a, x86_64) and Android App Bundle (AAB).
