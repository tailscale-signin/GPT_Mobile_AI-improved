# GPT Mobile AI 0.9.28.0

## Reliability and delegation
- Strengthens delegate retry, failover, timeout, and recovery behavior while preserving authorized tool access.
- Preserves model reasoning requirements during benchmarks and delegated requests instead of forcing reasoning off on endpoints that require it.
- Keeps delegate-produced debug result text hidden until execution details are expanded.
- Adds an independent Reviewer tab with a separate model, Reviewer Score, retry threshold, output budget, and optional correction controls.
- Shows benchmark/delegation scores directly in delegate and reviewer model pickers.

## Benchmarks
- Separates Benchmark and Delegation performance into top-level tabs.
- Adds sequential multi-model standard benchmarking with a selectable model list and one-click select-all.
- Keeps Delegation benchmarks on their own scoreboard with tool usability, latency, throughput, reliability, and Reviewer Score measurements.

## Memory
- Improves automatic local capture for durable preferences, identity, devices/tools, projects, goals, and explicit remember requests.
- Adds Selective, Balanced, and Detailed capture presets plus cleaner recommended and advanced controls.
- Keeps recall relevance-driven and bounded so stronger capture does not crowd responses with unrelated memories.
- Adds pinned-memory recall and improved local-model-assisted extraction while retaining privacy/tombstone protections.

## Settings, models, and plugins
- Splits AI Platforms into Remote, Local, and Free tabs with themed icons.
- Moves custom local-model import into the main Local Models library.
- Redesigns the local-model marketplace into Discover, Browse, and Downloads with improved filtering and device/accelerator guidance.
- Adds richer layered themed icons for built-in plugins.
- Increases Settings typography by 2sp across Settings destinations and removes repetitive navigation chevrons.

## Conversation experience
- Improves the themed back button and page transition animations.
- Moves conversation content behind the composer with top and bottom edge fades; the lower fade reaches transparency halfway through the input bar.
- Adds a persistent finished-response navigator with the themed chat icon, red completion count, multi-response expansion, and exact jump-to-response targeting.
- Automatically keeps the main chat list to 20 visible conversations by archiving alternating oldest eligible chats; pinned and active chats are protected.
- Keeps the 1-second streaming text fade consistent across punctuation, tables, code blocks, and display math.

## Backup and restore
- Shows the three most recent dated backups from the most recently used backup folder for quick restore access.

## Diagnostics and validation
- Improves provider recovery, circuit breaking, MCP renewal, tool failure classification, local-runtime readiness, and diagnostic telemetry.
- Adds regression coverage for benchmark reasoning, automatic chat archival, streaming punctuation, memory relevance, and completion navigation wiring.
- Validated with Kotlin lint, unit tests, Android lint, resource/XML preflight, debug/APK builds, Remote diagnostics, and CodeQL.

## Version
- Version: 0.9.28.0
- Version code: 97

## Installation
- Install the signed Android APK from this release.
- This release is built and signed by the repository's immutable Publish Signed Release workflow.
