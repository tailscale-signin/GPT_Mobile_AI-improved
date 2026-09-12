# Release Notes - v0.9.2.3 (Pre-release)

Welcome to the pre-release of **GPT Mobile AI (Improved)** (v0.9.2.3)!

This release delivers refined chat layout aesthetics, enhanced conversational continuation detection with active UI prompts, high-priority background completion alerts, live loading spinners for in-progress chats on the Home screen, and platform branding visual cues in Tool Connections.

---

### Key Highlights & Features

#### 1. Bottom-Right Message Timestamp Alignment
- **Natural Spatial Anchor**: Replaced floating/left-aligned timestamp placement with a dedicated bottom-right anchor across all chat message bubbles.
- **Visual Distinction**: Preserved custom semantic color palettes for user and assistant messages while ensuring consistent right-aligned baseline positioning for readability.

#### 2. Continuation Request Detection & Pulsating Glow Chip
- **Expanded Conversational Trigger Keywords**: Added intelligent pattern detection for continuation phrases (including `"more"`, `"tell me more"`, `"keep going"`, `"continue"`, `"go on"`, `"elaborate"`, `"proceed"`, and `"more please"`).
- **Infinite Continuation Animation**: Replaced static continuation buttons with an interactive pulsing glowing chip that animates smoothly when a message is truncated or ready for conversational extension.
- **Turn Context Preservation**: Tap-to-continue automatically forwards the full conversational context and current platform parameters to seamlessly resume assistant thought generation.

#### 3. Background Completion High-Priority Heads-Up Notifications
- **High-Importance Notification Channel**: Configured `CHANNEL_AGENT_COMPLETION` with `IMPORTANCE_HIGH` and dedicated sound alerts.
- **Top-of-Screen Alert Banner**: Delivers immediate heads-up banners upon generation finish, allowing users to safely multitask or switch apps while waiting for complex multi-turn queries or tool executions to complete.

#### 4. Active Chat Status Indicator on HomeScreen
- **Live Circular Progress Spinner**: Added an animated circular loading spinner directly onto the chat list item card whenever that specific conversation has an active background agent run.
- **Immediate State Feedback**: Eliminates ambiguity on which chat thread is actively generating responses when browsing conversation history.

#### 5. Tool Connection Platform Badges
- **Visual Provider Icons**: Integrated visual platform badge icons directly into the Tool Connections screen headers for intuitive provider association (OpenAI, Anthropic, Gemini, DeepSeek, Ollama, Groq, OpenRouter, LiteRT-LM, etc.).

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk` (runs on all supported 64-bit architectures)
- **Architecture APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
