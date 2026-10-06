# GPT Mobile AI 0.9.32.0

## Home menu and conversation controls
- Match the Chats and Favorites button backgrounds to the main menu, with theme-colored labels, icons, and indicator.
- Add animated pin and unpin feedback, row movement, and haptics. Drag upward to pin or reorder; drag a pinned conversation downward into the lower half to unpin.
- Keep the archive control at the bottom and open archived conversations in a bottom-anchored sheet that slides upward.
- Enlarge Select Platform label filters and remove filled button backgrounds; show a checkmark for selected labels.

## Conversation reading
- Move the upper fade below the title while masking scrolled content across the header.
- Open existing conversations at the true bottom after the message layout is ready.
- Open unread responses with their beginning centered in the reading area, preserving that position through composer transitions until the user scrolls.
- Keep following the bottom as late content grows when bottom following is enabled.

## Validation
- All 1,654 JVM and Robolectric tests passed for the merged implementation, including Compose conversation-entry geometry checks.
- Kotlin formatting, Android lint, CodeQL, debug APK builds, and packaged runtime/privacy checks passed.
- The signed release workflow validates this release commit, checks APK and bundle identity, verifies signing-certificate continuity, and publishes checksums and provenance.
- Physical-device animation and gesture validation remains pending.

## Version
- Version: 0.9.32.0
- Version code: 101

## Installation
- Install the signed Android APK from this release.
- Existing installations can update using the same release signing certificate.
