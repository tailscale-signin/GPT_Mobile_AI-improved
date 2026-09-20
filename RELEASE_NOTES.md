# Release Notes - v0.9.5.6

Welcome to **GPT Mobile AI (Improved)** v0.9.5.6!

This release introduces an OpenRouter batch background processing worker with WorkManager and Room persistent batch caching, updates LiteRT-LM to v0.16.1, and resolves TLS certificate trust validation in network security configuration.

---

### Key Highlights & Improvements

#### 1. 📦 OpenRouter Batch Background Worker & Room Cache
- **WorkManager Batch Execution**: Implemented persistent background worker (`OpenRouterBatchWorker`) for scheduled and reliable asynchronous batch inferences via OpenRouter.
- **Room Batch Cache**: Structured caching layer with entity storage, DAO queries, and cache eviction policies for multi-turn conversations and batched requests.
- **Queue Accumulation**: In-memory and persistent queuing pipeline with exponential backoff retries and timeout protection.

#### 2. ⚡ LiteRT-LM v0.16.1 & On-Device Native Serving
- Upgraded Google LiteRT-LM to `v0.16.1` with native C++ JNI bindings (`liblitertlm_jni.so`).
- Retained optimized Qualcomm Snapdragon AI Direct (QNN) HTP delegates for hardware-accelerated local inferences.

#### 3. 🔒 Network Security & TLS Handshake Fix
- Removed restrictive `domain-config` override in `network_security_config.xml` that triggered `checkServerTrusted` SSL errors during secure communication with remote APIs.
- Clean fallback to Android system-trusted CA store while enforcing HTTPS traffic.

#### 4. ⚙️ Build & Packaging Details
- **Version Code**: `58`
- **Version Name**: `0.9.5.6`
- **Target SDK**: 36 (Android 16), **Min SDK**: 31 (Android 12)
- **Architectures**: `arm64-v8a`, `x86_64`, Universal APK
- **Optimization**: R8 minification, resource shrinking, and native packaging (`useLegacyPackaging = true`).

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk`
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
