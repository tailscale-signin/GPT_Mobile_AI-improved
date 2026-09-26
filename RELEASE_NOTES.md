# GPT Mobile AI (Improved) v0.9.14.0

## Highlights

- **Local MCP Marketplace Expansion**: Integrated 29 local AI projects into the marketplace catalog—including 23 configurable server connections (Atlassian, Chat2DB, FlyEnv, AgentSet, DBHub, Code Memory, etc.) and six setup guides (including Houtini LM)—complete with curated brand vector and raster icons.
- **Native Model Delegation**: Models can now delegate specialized subtasks to other configured AI profiles (on-device LiteRT, Ollama, Llama, or cloud endpoints) with strict loop protection, timeout limits, token budgets, and reentry prevention.
- **Integrated Local Memory Tool**: Direct Fact Vault recall and capture integration exposed in Tool Connections and the marketplace, featuring encrypted storage, per-chat scoping, and automatic redaction of recalled facts from persistent tool traces.
- **Restored Usage Statistics & Analytics**: Re-enabled comprehensive usage metrics, total queries, success rates, token usage breakdown, tool activity summaries, and run output inspection modals.
- **Chat Profile Picker Ordering**: Drag-to-reorder and customizable sorting for chat profiles on the home screen, allowing quick switching between preferred models.

## Improvements & Safeguards

- Model delegation prevents self-delegation cycles and active on-device engine reentry while forwarding only bounded prompts without tool leakage.
- Fact Vault memory capture captures from validated user turns and respects cloud transmission policies and review status.
- Preserved existing opt-outs for telemetry and vault features, keeping local learning strictly opt-in.
- Streamlined Tool Connections screen with dedicated Local Tools management panel and configurable delegation parameters.

## Installation and artifacts

- Version: **0.9.14.0** (version code **69**).
- Android 12 or newer; target Android 16 (API 36).
- Package: `dev.melo.gptmobile.improved`.
- Signed APKs: **arm64-v8a**, **x86_64**, and **universal**. Most modern Android phones use arm64-v8a.
- Signed Android App Bundle (AAB) and SHA-256 checksums are produced by the release workflow.
- Continuous signing compatibility preserved across updates.

## Memory and telemetry notes

Fact Vault learning remains opt-in and off by default. Relevant active facts are recalled only with user-approved policies and redacted from persistent trace logs. Delegated subtask token usage is isolated to subtask executions.

## Validation

All core features, model delegation safety bounds, local memory tools, and marketplace catalog integrations have passed focused test suites (56/56 tests passing), lint verification, and clean build checks. Signed release packaging is handled via the automated GitHub Actions pipeline.
