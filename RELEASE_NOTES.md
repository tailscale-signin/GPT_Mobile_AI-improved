# Release Notes - v0.9.4.5-pre

Welcome to **GPT Mobile AI (Improved)** v0.9.4.5-pre!

This pre-release delivers the comprehensive UI upgrades and component enhancements:

---

### Key Highlights & Improvements

#### 1. 📋 AgentPlanCard Enhancements
- Added color-coded `LinearProgressIndicator` tracking real-time step execution percentage.
- State-specific icons, animated rotating indicators for active steps, and distinct completion styling (`RUNNING`, `SUCCESS`, `FAILED`, `PENDING`, `SKIPPED`).
- Smooth vertical expand/collapse animation (`expandVertically` / `shrinkVertically`).
- Detailed expandable step views displaying tool badges and monospace result snippets.

#### 2. 💬 Chat UI & Conversation Management
- Refined message bubble hierarchy with dedicated styling for reasoning/thinking blocks and tool traces.
- Continuation prompt chip with subtle infinite pulse glow effect and long-press haptic feedback.
- `SwipeableChatRow` with spring-physics swipe gestures: swipe right to archive, swipe left to delete/pin.
- Threshold haptics on gesture boundaries and 1-second long-press pinning with elevation glow.
- Draft indicators and pinned conversation badges.

#### 3. ♿ Accessibility & Visual Polish
- Material 3 container colors meeting standard contrast ratios in light and dark modes.
- Explicit Compose `semantics` blocks with `contentDescription`, `stateDescription`, and semantic `Role.Button` bindings.
- Increased tool execution trace bubble opacity to 0.15 for improved contrast in dark mode.

#### 4. 📦 Build & Packaging Details
- Version code incremented to `49`, version name set to `0.9.4.5-pre`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs (Universal, arm64-v8a, x86_64) and Android App Bundle (AAB).
