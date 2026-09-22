# Release Notes - v0.9.7.0

Welcome to **GPT Mobile AI (Improved)** v0.9.7.0.

This full release introduces the Persistent Mobile Agent foundation: richer Gateway progress on Android, Android 16 progress-centric foreground notifications, and durable Gateway job reconciliation when network connectivity returns.

---

### Key Highlights & Improvements

#### 1. Persistent Mobile Agent Progress
- Structured Gateway progress now flows through the app's agent runtime instead of being limited to tool-history persistence.
- Active agent runs track Gateway stage, message, checkpoint, round, and tool-call progress.
- Foreground notifications can show the current Gateway message or stage while work is running.

#### 2. Android 16 Progress-Centric Notifications
- Added Android 16 progress-style foreground notifications for long-running agent tasks.
- Gateway stages are mapped into clear progress milestones.
- Unknown stages retain an indeterminate progress fallback.
- Earlier Android versions continue using the existing compatible progress notification.

#### 3. Durable Network Recovery
- Added validated-network monitoring for Wi-Fi, cellular, and VPN/Tailscale-style network transitions.
- When validated connectivity returns, the app reconciles persisted Gateway jobs instead of replaying the original prompt.
- Recovery begins only after startup persistence reconciliation to avoid overwriting recovered results.
- Network handoffs track the validated Android Network instance to avoid stale disconnect callbacks causing false recovery state.

#### 4. Architecture & Compatibility
- Keeps Gateway as the owner of remote execution and tool intelligence.
- Keeps Android responsible for presentation, lifecycle, cancellation, local durability, and recovery.
- No Room migration and no new runtime dependency are required.
- Provides a clean foundation for future MCP Tasks and input-required interactions.

#### 5. Build & Packaging
- **Version Code**: `62`
- **Version Name**: `0.9.7.0`
- **Target SDK**: 36 (Android 16)
- **Min SDK**: 31 (Android 12)
- **Architectures**: `arm64-v8a`, `x86_64`, Universal APK
- **Release artifacts**: signed APK variants and release AAB through the repository release workflow.
