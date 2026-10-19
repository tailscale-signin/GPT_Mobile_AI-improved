# GPT Mobile AI 0.9.54.0

## Research, tools and recovery
- Preserve authorized saved-search evidence across display-only setting edits and pricing quote refreshes.
- Coordinate delegated tool budgets without charging local schema discovery as an external evidence operation.
- Resume unfinished Research acquisition without replaying completed queries; limit fallback to cases with no readable evidence.
- Return a clear partial-evidence report when sources could not be read.
- Recover once from rejected raw tool markup with a tool-free answer, without executing the rejected payload.

## Combined delivery and responsiveness
- Accept JSON and JSONL fences for structured Combined records while retaining strict record validation.
- Scan streamed structured fragments incrementally to avoid repeated whole-buffer work.
- Split oversized agent and provider coroutine methods to reduce Android runtime compilation pressure.

## Included changes
- Includes all changes merged into main through diagnostic recovery PR #722 (source commit 6d7b46135670a9641dcfe221b2515e36418af11a).
- External MCP server availability and DNS/connectivity remain deployment prerequisites.

## Release scope and remaining qualification
- **Unified Reliability v2 is not complete.** The recorded inventory remains at 0/48 tasks marked DONE and 11/110 acceptance cases marked PASS (99 unrun) as of October 19, 2026.
- v0.9.54.0 is an **interim application release** requested by the owner, not certification of the entire Unified Reliability v2 program. Pending device and provider qualification has not been represented as successful.
- Publication requires actual Android unit tests, debug/release lint, AAR compatibility, native JNI and 16 KB AAB checks, signing-certificate continuity, and matching package/version/source identity.

## Release identity
- Package: `dev.melo.gptmobile.improved`
- Version: `0.9.54.0`
- Version code: `123` (increased from 122)
- Signed arm64-v8a, x86_64 and universal APKs for direct installation; signed `app-release.aab` for Play Console upload.
- Google Play publication and device qualification remain separate from this GitHub release.
