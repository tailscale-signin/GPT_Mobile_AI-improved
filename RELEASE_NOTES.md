# GPT Mobile AI 0.9.51.0

## Local AI and model insights
- Reorganize Local AI into Models, Assist, and Settings, with profile cards, clearer runtime status, marketplace recommendations, and model actions.
- Add reviewed model imports, persistent progress and cancellation, transactional staging, checksum verification, and runtime-aware deletion. Supported import formats depend on the installed runtime.
- Show personal benchmark results, optional community results, provider pricing and freshness, and scoped manual price overrides while choosing chat profiles.
- Add opt-in local summarization and query suggestions over already-authorized, read-only results, with consent and configuration rechecks.

## Deep Research reliability
- Coordinate concurrent discovery and reading through a shared source registry, fair scheduling, durable budget reservations, and checkpoints.
- Improve connectivity recovery, progress reporting, and partial-result handoffs.
- Reserve in-flight academic reads against the remaining source goal, preventing concurrent readers from exceeding it and releasing capacity after failures or cancellation.

## OpenRouter workspace
- Add Setup, Routing, and Account tabs, with account features unlocked after validation of the current key.
- Keep routing, pricing, cache controls, batch queues, allowance, and receipts scoped to their connection. Optional management access uses a separate session.
- Protect credential changes and settings drafts against late responses, and clear stale account state after key rotation.
- Bypass response caching for benchmarks, correct multi-profile benchmark billing attribution, and keep failed local batches from silently falling back to remote inference.

## Included from the 0.9.50.0 base
- Retain local Health Connect history imports, encrypted health backup and restore, and independent AI-sharing consent controls.
- Include the changes already merged from version-v0.9.50.0 into main.

## Release verification
- Validate the exact release commit with unit tests, Android lint, and release dependency compatibility checks before building signed artifacts.
- Check packaged native libraries, telemetry exclusions, application identity, and signing-certificate continuity before publication.
- Resolve the artifact verifier's Android build-tools version from the app configuration so verification follows SDK upgrades.
- Publish signed APKs and an Android App Bundle with checksums and source provenance through the existing GitHub release workflow.

## Release identity
- Package: dev.melo.gptmobile.improved
- Version: 0.9.51.0
- Version code: 120
