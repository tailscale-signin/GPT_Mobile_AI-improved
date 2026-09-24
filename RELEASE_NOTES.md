# Release Notes - v0.9.8.0

Welcome to **GPT Mobile AI (Improved)** v0.9.8.0.

This major release introduces the live Agent Flight Recorder, combined multi-model conversation synthesis, encrypted passwordless backups, and remote AI streaming resilience.

---

### Key Highlights & Improvements

#### 1. Agent Flight Recorder & Gateway Efficiency
- **Flight Recorder Card**: Live tracking of active Gateway and agent execution state directly within the chat interface.
- **Adaptive Work States**: Visually displays agent phases: Starting, Exploring, Focused, Using Tools, Recovering, Synthesizing, and Finalizing.
- **Efficiency Telemetry**: Real-time visibility into tool productivity, round count, useful tool calls, checkpointing, and tool atlas counts.
- **Streamlined Standard UX**: Flight Recorder details remain cleanly accessible in Debug Mode while keeping standard chat clean and focused.

#### 2. Combined Multi-Model Conversation Mode
- **Parallel Model Synthesis**: Query multiple AI platforms simultaneously in a single turn.
- **Unified Merged Response**: Runs all selected models in parallel, using the primary model to perform a clean synthesis pass.
- **Inspectable Raw Responses**: Expandable model response accordion to inspect individual model outputs side by side.
- **Persisted Schema (v24)**: Native database support for Separate vs Combined conversation modes.

#### 3. Encrypted Passwordless Complete Backup & Restore
- **GPTFULL2 Format**: Seamless AES-256-GCM authenticated archives protected by the Android Keystore/App Credential Vault without mandatory manual password typing.
- **Full Scope Coverage**: Backs up all 10 Room tables, app preferences, vault credentials, downloaded local models, tool configs, and file attachments.
- **Legacy Compatibility**: Full backward compatibility for restoring password-protected GPTFULL1 and legacy backups.

#### 4. Remote Streaming Longevity & Connection Resilience
- **Extended Timeouts**: Inactivity socket timeouts extended up to 5 minutes with unlimited request deadlines for reasoning and long-running generation.
- **Graceful Disconnect Recovery**: Gracefully handles late socket closes as payload completion for Gemini, Anthropic, and Groq providers.
- **OkHttp Bound Guarding**: Bounds finite timeouts to valid OkHttp ranges, eliminating oversized timeout exceptions.

#### 5. Build & Packaging
- **Version Code**: `64`
- **Version Name**: `0.9.8.0`
- **Target SDK**: 36 (Android 16) | **Min SDK**: 31 (Android 12)
- **Architectures**: `arm64-v8a`, `x86_64`, Universal APK
- **Release Artifacts**: Signed APK variants (arm64-v8a, x86_64, Universal) and release Android App Bundle (AAB).
