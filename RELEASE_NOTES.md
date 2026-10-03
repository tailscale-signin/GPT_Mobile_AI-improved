# GPT Mobile AI 0.9.30.0

## Diagnostics, statistics, and benchmarks
- Reworks Debug, Statistics, and Benchmark into cleaner top-level areas with dedicated sub-tabs and richer presentation.
- Expands benchmark and delegation diagnostics, scoring, failure inspection, and reproducible validation coverage.
- Improves release, gateway, provider, MCP, and local-runtime telemetry so failures are easier to classify and recover from.

## Local memory, projects, and documents
- Expands on-device memory capture and recall with project-aware and branch-aware context, provenance, corrections, and synthetic evaluation.
- Adds persistent hybrid document retrieval and deferred on-device enrichment while keeping cloud memory recall separately controlled.
- Adds non-destructive conversation branching, durable drafts, temporary conversations, and Android share-in workflows.

## Workspaces, tools, and GitHub
- Adds workspace support for tasks, retained research evidence, branches, recipes, model guidance, budgets, and GitHub review.
- Redesigns plugin and remote MCP configuration with scoped expiring tool grants, catalog-change revocation, and modern MCP compatibility.
- Improves portable plugin configuration while excluding credentials and sensitive URL components.

## Privacy, recovery, and gateway security
- Adds app-lock and screenshot controls, clearer backup/deletion boundaries, and private-log suppression.
- Protects authoritative facts and memory-bearing context receipts with encryption and improves temporary-session cleanup.
- Adds revocable per-device gateway tokens, single-use pairing, job ownership checks, and loopback-by-default gateway binding.

## Release reliability
- Fixes signed-release certificate verification for Android Build Tools 37, whose `apksigner --print-certs` signer label differs from earlier versions.
- Validates that parsed SHA-256 fingerprints are non-empty and well formed before accepting APK, keystore, or AAB signer continuity.
- Keeps the existing release signing key and upgrade path intact.

## Validation
- The v0.9.30.0 implementation record reports 1,454 JVM/Robolectric tests passing, 22 gateway tests passing, and 3 performance-comparison tests passing.
- Android lint, release vital lint, dependency verification, package integrity, ABI policy, and 16 KiB host-library alignment checks were completed.
- Physical-device execution, native inference quality, NPU compatibility, thermals, and measured Baseline Profile gains remain device validation items.

## Version
- Version: 0.9.30.0
- Version code: 99

## Installation
- Install the signed Android APK from this release.
- This release is built, verified, attested, and published by the repository's Publish Signed Release workflow.
