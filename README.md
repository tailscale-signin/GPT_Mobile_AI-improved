GPT Mobile AI - v0.9.2 Release

🚀 **Key Features Added in v0.9.2**

### 📌 Core Enhancements
- **Archiving System**: Support for archiving conversations with swipe gestures (Left=Delete, Right=Archive)
- **Label Management**: Add, remove, and color-coded label system for platforms
- **Timestamps**: Transparent timestamps below every message
- **Model Fetcher**: New service to discover and fetch models from OpenRouter API
- **Validation System**: Platform connection validation with detailed error messages

### 🎨 UI Improvements
- **Platform List**: Sort by Enabled/Favorites/Name, long-press to toggle favorites
- **Chat Input**: Fade animation during generation, session-specific platform disable toggle
- **Chat Bubbles**: Pure black bubbles with 2x transparency, expandable tool call grouping
- **Favorites Screen**: Anchored 'View' button, improved layout
- **Archived Bar**: Expandable bottom bar for archived conversations

### 🧠 Domain Logic
- **Archive Use Case**: Compress/archive conversations with metadata retention
- **Platform Manager**: Sort, favorite, and label management for platforms
- **Backup Updates**: Favorites and message groups now included in backups

### 🛠️ Additional Improvements
- **ApiKeyValidator**: Instant API key validation utility
- **Setup Wizard Integration**: OpenRouter model picker and API key validation
- **Unit Tests**: Comprehensive test suite for domain use cases

Documentation and full changelog available in the [wiki](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/wiki).