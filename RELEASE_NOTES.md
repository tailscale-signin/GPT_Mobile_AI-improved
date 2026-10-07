# GPT Mobile AI 0.9.34.0

## Prompt crash fix
- Preserve the MediaPipe Java/JNI boundary in optimized APKs so the first semantic-memory encoder result does not abort the app when sending a prompt.
- Check critical native-facing signatures before initialization and fall back to exact/topic recall when that boundary is unavailable.
- Preserve ObjectBox scored-query wrappers and their constructors so native vector search can return results after encoding and indexing.
- Gate packaged APKs and bundles on actual MediaPipe and ObjectBox DEX definitions. Keep telemetry exclusion and native-runtime integrity checks enabled.
- Record opt-in, redacted engine and first-input checkpoints with the app version for crash diagnosis.

## Sources and conversation styling
- Show compact search-engine and website circles beneath each AI response, using the sources actually recorded for that answer.
- Expand the source list from the selected icon, with local logos, titles and compact website links. Keep distinct source pages and their original clickable URLs.
- Bundle 52 common-site logos and 15 provider identities without making favicon requests.
- Collect research, nested MCP results, reader URLs and answer links off the UI thread, including evidence used by combined responses.
- Lower the top content fade by 16 dp, reduce the speech-bubble emblem, and center prompt text with full-opacity theme colouring and timestamps outside the bubble.

## Included conversation and reliability improvements
- Keep Archive after active conversations in a half-height bottom sheet, with back and downward-swipe dismissal.
- Open existing chats at the bottom while preserving favourite-response entry and viewport position when switching AI tabs.
- Match Select Platform filters to profile labels and retain transparent combined-profile bubbles with fully opaque status lights.
- Combine matching sections and chronological timelines while preserving unique details, citations, contradictions, Markdown tables and code. Original model responses remain available.
- Retain authorized aggregate web-search routing, reviewer failover and the earlier semantic-memory initialization protections.

## Release integrity and validation
- This release uses a new version and a higher version code rather than replacing the already-published v0.9.33.0 tag or assets.
- PR #612 passed its Android 16 minified native-memory smoke test on the implementation commit, including encoding, scored search, scope isolation, deletion and repeated close/reopen cycles.
- The signed-release workflow independently revalidates the exact release commit, checks packaged JNI/runtime integrity, verifies package/version identity and signing-certificate continuity, and publishes checksums and provenance.
- Physical-device visual review and an installed arm64-phone prompt check remain pending; emulator results are not a substitute for those checks.

## Version and installation
- Version: 0.9.34.0
- Version code: 103 (previous release: 102)
- Install the signed Android APK from this release. Keep the existing app installed when updating with the same signing certificate.
- Crash fix and source UI changes: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/612
- Earlier conversation improvements: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/611
