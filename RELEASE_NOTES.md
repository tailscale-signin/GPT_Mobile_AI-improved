# GPT Mobile AI 0.9.33.0

## Conversation and menu fixes
- Place Archive after active conversations and open archived chats in a bottom sheet covering half the screen. Back and downward swipe close the sheet.
- Match Select Platform filters to the profile labels, including their beveled shape and colours.
- Open existing chats immediately at the bottom while preserving favourite-response entry.
- Keep the conversation viewport stable when switching AI tabs in multi-chat and combined mode.
- Raise the upper conversation fade by 5% of screen height and fix bottom scroll-range jumps.
- Show grey combined-profile bubbles and text with 60% transparency while keeping status lights fully opaque.
- Correct the inverted speech-bubble detail in the generation icon.

## Better combined responses
- Group matching labelled sections across model replies even when their order and common wording differ.
- Interleave matching timelines chronologically, including BCE dates, while keeping disjoint time periods separate.
- Preserve unique details, citations, contradictions, Markdown tables and code. Original model responses remain available.

## Reliability corrections
- Preserve protobuf-lite fields required by semantic embedding initialization in optimized APKs, and prevent repeated initialization-failure loops.
- Route web-search aliases through the existing authorized aggregate search tool.
- Avoid treating history reports as GitHub repository requests in the gateway.
- Stop repeated unreachable reviewer attempts, retain delegated evidence as unverified, and avoid estimated token charges for requests that never reach a model.

## Validation
- The merged implementation passed 1,668 Android unit tests, 90 gateway tests, formatting, Android lint and CodeQL.
- The signed release workflow revalidates this release commit, verifies package/version identity and signing-certificate continuity, and publishes checksums and provenance.
- Physical-device animation and gesture validation remains pending.

## Version and installation
- Version: 0.9.33.0
- Version code: 102
- Install the signed Android APK from this release. Existing installations can update with the same signing certificate.
- Changes: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/611
