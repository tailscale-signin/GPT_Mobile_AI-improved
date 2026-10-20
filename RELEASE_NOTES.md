# GPT Mobile AI 0.9.55.0

## GGUF inference
- Apply CPU optimization to the actual Android Debug native compiler commands, including ARM64 verification.
- Start CPU prefill with a small adaptive batch so progress is visible sooner; use four automatic CPU threads.
- Match GenieX preview native packaging to the app's ARM64-only policy and generate the runtime inventory from the packaged ABIs.
- Give GenieX unit testing, APK assembly and release shrinking separate bounded validation stages.

## Research and Combined mode
- Allow Research Examine to continue when an optional reviewer is unavailable; explicitly required review remains enforced.
- Preserve source revision identity through Combined multipass assembly and consolidate oversized inspection reports with the pinned primary model.
- Retain original checkpoints and verify complete source-span membership before final assembly.
- Align tool admission and execution limits at 32 calls.

## Interface
- Fade back navigation, including predictive back, without scaling pages.
- Use a flat themed New Chat button and reveal the full-screen glass panel from its origin over one second.
- Use theme-colored AI profile subtitles with 25% lower opacity.
- Blur the chat composer and down-arrow backgrounds.
- Group plugin chat modes under Plugins; show Research Chat when its plugin is enabled.

## Validation and release scope
- PR #726 passed Android emulator acceptance, unit tests and lint, GGUF CPU native smoke, minified native memory smoke, GenieX preview validation, UI review APK generation, diagnostic audit and CodeQL.
- The exact physical phone/model combination still requires device retesting. Provider availability, source access restrictions and unreachable LAN services remain external dependencies.
- **Unified Reliability v2 is not complete.** The recorded inventory remains at 0/48 tasks marked DONE and 11/110 acceptance cases marked PASS (99 unrun); no full-v2 qualification is claimed.
- This is an owner-requested interim application release. Publication retains Android unit tests, debug/release lint, AAR compatibility, native JNI and 16 KB AAB checks, signing-certificate continuity, and package/version/source identity verification.

## Release identity
- Package: `dev.melo.gptmobile.improved`
- Version: `0.9.55.0`
- Version code: `124` (increased from 123)
- Signed arm64-v8a, x86_64 and universal APKs for direct installation; signed `app-release.aab` for Play Console upload.
- Google Play publication and physical-device qualification remain separate from this GitHub release.
