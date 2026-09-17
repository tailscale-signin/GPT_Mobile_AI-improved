# Release Notes - v0.9.4.5

Welcome to **GPT Mobile AI (Improved)** v0.9.4.5!

This release brings conversation pinning, swipe-to-dismiss gestures, full Android assist and Quick Settings tile integration, and improved UI contrast.

---

### Key Highlights & Improvements

#### 1. 📌 Conversation Pinning & Swipe-to-Dismiss Gestures
- **Swipeable Conversation Rows**:
  - Swipe right to archive conversations.
  - Swipe left to delete conversations with safety confirmation dialog.
  - 1-second long-press interaction to pin/unpin conversations with animated spot elevation glow.
  - Haptic feedback when reaching trigger boundaries.
  - Real-time conversation list ordering by favorite/pinned status, draft indicators, and pinned badges.

#### 2. 📱 Android Assist & Quick Settings Tile Support
- `GptTileService`: Quick Settings tile to instantly launch chat.
- `VoiceInteractionService` & `VoiceInteractionSessionService`: Voice assistant service declared in system manifest.
- `VoiceAssistantActivity`: Handles `android.intent.action.ASSIST` to forward assistant queries into the app seamlessly.
- Vector drawable `@drawable/ic_gpt_mobile` for branding and tile iconography.

#### 3. 🎨 UI Contrast Polish
- Increased `ToolTraceBlock` opacity to `0.15f` for enhanced readability on dark theme surfaces.

#### 4. 📦 Build & Packaging Details
- Version code incremented to `49`, version name set to `0.9.4.5`.
- Target SDK 36 (Android 16), Min SDK 31 (Android 12).
- Supported 64-bit ABIs: `arm64-v8a`, `x86_64`.
- Signed release APKs (Universal, arm64-v8a, x86_64) and Android App Bundle (AAB).
