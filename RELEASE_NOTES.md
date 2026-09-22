# Release Notes - v0.9.6.1

Welcome to **GPT Mobile AI (Improved)** v0.9.6.1!

This full release brings suggestion button hold-to-highlight with animated smooth transitions, gateway-owned tool execution traces with live progress SSE streaming and visual status bars, interaction fixes for chips, and built-in Location & Geocoding MCP tool extensions.

---

### Key Highlights & Improvements

#### 1. 🔍 Suggestion Button Hold-to-Highlight & Chip Interactions
- **Hold-to-Highlight Feature**: Added `SuggestionHighlightManager` enabling interactive hold-to-highlight on suggestion buttons. Highlights the corresponding source sentence dynamically within the response markdown with smooth alpha/progress transitions (`highlightSentence`, `highlightProgress`).
- **Chip Interaction Fixes**: Refined click and long-press interaction handlers on `AssistChip` and `SuggestionChip` with dedicated `MutableInteractionSource` instances to prevent gesture conflicts and missed click dispatches.

#### 2. ⚡ Gateway Progress SSE & UI Trace Integration
- **Server-Sent Event Progress**: Added `GatewayProgress` DTO to `ChatCompletionChunk` and new `GatewayProgressUpdate` provider event for streaming real-time status updates from remote gateway agents.
- **Gateway Tool Identity**: Persisted gateway-owned tool traces in `ChatRepositoryImpl` and introduced distinct visual identities (`GATEWAY`) distinguishing gateway actions from on-device MCP tool executions.
- **`GatewayActivityBar`**: Rendered real-time activity indicators directly in `ChatBubble` displaying active gateway status during long-running tasks.

#### 3. 📍 Location & Geocoding MCP Tool Integration
- **Built-in Geo Tools**: Integrated `geolocate_ip`, `reverse_geocode`, `get_current_location`, `geocode_address`, and `calculate_distance` (Haversine formula) in the built-in MCP server.
- **Manifest Triple Verification**: Validated schema and checksum integrity across `mcp/tools/manifest.json`.

#### 4. ⚙️ Build & Packaging Details
- **Version Code**: `60`
- **Version Name**: `0.9.6.1`
- **Target SDK**: 36 (Android 16), **Min SDK**: 31 (Android 12)
- **Architectures**: `arm64-v8a`, `x86_64`, Universal APK
- **Optimization**: R8 minification, resource shrinking, and native packaging (`useLegacyPackaging = true`).

---

### Artifacts & Downloads
- **Universal APK**: `app-universal-release.apk`
- **Targeted ABI APKs**: `app-arm64-v8a-release.apk` and `app-x86_64-release.apk`
- **Release Bundle (AAB)**: `app-release.aab`
