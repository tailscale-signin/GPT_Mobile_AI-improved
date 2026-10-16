# GPT Mobile AI 0.9.53.0

## New chat experience
- Replace the old New Chat entry with a full-screen, theme-aware flow for **Research**, **Single**, **Multi-chat** and **Combined**.
- Let Research preconfigure Deep Research with explicit permission handling, research depth and one or more local/remote profiles.
- Add profile search, favorites, source filters, labels, model/provider/score/price sorting and a pinned Combined synthesis lead.
- Keep Multi-chat conversations genuinely independent, with per-profile lanes, navigation and coordinated archive/delete.

## Combined answers and responsiveness
- Improve claim coverage and recovery so distinct facts from contributors are preserved rather than simply selecting one answer.
- Use high-contrast contributor text colors without normal-view model-name footers or an extra Combined sources bar; keep supplemental source-recovery details in Debug Mode only.
- Reduce repeated Markdown, citation and attribution work during streaming; preserve final answer completion and optimize early connection preparation.

## Models, local AI and diagnostics
- Integrate the NVIDIA remote provider with credential-isolated model discovery, model-aware reasoning, streaming, supported input validation and opt-in connection testing.
- Improve Local Assist recovery, readiness/error reporting, model admission and cancellation handling.
- Repair diagnostic/runtime failure paths, including native prefill, GenieX routing and Combined response reliability.

## Release scope and remaining qualification
- **Unified Reliability v2 is not complete.** Its tracked program remains at 0/48 tasks marked DONE, and 11/110 acceptance cases marked PASS (99 unrun) as of October 16, 2026.
- v0.9.53.0 is an **interim application release**, not certification of the entire Unified Reliability v2 feature program; no pending device or provider tests have been represented as successful.
- Release artifact publication remains conditional on actual Android unit tests, release lint/AAR compatibility, native JNI and 16 KB AAB validation, previous signing-certificate continuity, and matching package/version/source identity.

## Release and Google Play
- Application ID: `dev.melo.gptmobile.improved` (unchanged, for upgrade continuity).
- Minimum SDK: 31. Target SDK: 37, meeting Google Play's October 2026 API-level requirement of 36 or higher.
- Signed Android App Bundle (`app-release.aab`) for Play Console upload, alongside signed arm64-v8a, x86_64 and universal APKs for direct distribution.
- Native Android host libraries are checked for 16 KB ELF LOAD alignment, and the release workflow verifies signing-certificate continuity, application ID, version and source commit. An AAB-native alignment and Google Play target-SDK check are included in the release gate.
- Manual Google Play Console review is still required for app content, Data safety, Health Connect/health-data permissions and special-use foreground services. Automated build checks do not substitute for device qualification or Play approval.

## Release identity
- Package: `dev.melo.gptmobile.improved`
- Version: `0.9.53.0`
- Version code: `122` (increased from 121)
