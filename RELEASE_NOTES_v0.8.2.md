# Release Notes - v0.8.2

## Highlights
- **Thinking Parser & Progress UI**: Real-time extraction of `<think>` and `<thought>` reasoning blocks with collapsible UI and unit tests.
- **MCPSearch Built-in Tools & Catalog**: Integrated 5-in-1 multi-engine search set (`search`, `investigate`, `compare`, `trending`, `get_crawl_stats`) and `mcpsearch-android-termux` preset.
- **Cyan Theme Palette**: Complete Material 3 Cyan color scheme support.
- **CI/CD Modernization**: Updated to `actions/checkout@v6`, `actions/setup-java@v5` (JDK 21 Temurin), and `android-actions/setup-android@v4` with Android SDK 36.
- **Performance & Ceilings**: Scaled agent ceilings to maximum bounds, 100 MB URL reading, and up to 100 web search results per query.
