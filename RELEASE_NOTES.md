# GPT Mobile AI 0.9.37.0

## Chat navigation
- Halve the visible bottom arrow to 26 dp while retaining a 48 dp touch target. During AI generation it uses a soft 1.5-second opacity pulse.
- Pause upward navigation at the newest completed response header. A fresh upward gesture after one second releases the boundary; returning to the response re-arms it. Favourite-response entry remains available.

## Conversation folders
- Long-press and drag a conversation to the top pin target on the left or new-folder target on the right.
- Name new folders and move conversations into existing folder chips beside Chats. Hold a folder to rename it, choose its background colour, or remove the folder while keeping conversations.
- Store folders and memberships in Room, preserve them in conversation backups, and accept older backups without folder data.

## Memory v2 development starts
- Keep existing memories active while conflicting automatic captures await explicit review. Offer Replace existing, Keep both, and Keep existing, with persisted stale-review checks.
- Preserve assertion history when returning to a previously retired value; allow multiple occupations.
- Preserve encrypted records when a Keystore key is unavailable. Fact-index maintenance keeps document vectors intact.
- Default fresh vault cloud recall off; retain settings already saved by existing users.
- Add Room schema 35 foundation contracts for encrypted memory payloads, fact links, pending operations and cutover state, plus valid-time and authenticated-envelope primitives. Remove destructive database downgrade fallback.
- This is the safety/foundation slice of the memory-v2 design. Existing encrypted JSON memory remains authoritative. Automatic Room conversion, protected graph migration, new retrieval ranking, proactive suggestions and portable memory-v2 archives are not enabled in this release.

## Release identity
- Package: dev.melo.gptmobile.improved
- Version: 0.9.37.0
- Version code: 106
- Publication must pass the existing signed-release validation and certificate-continuity checks. Physical-device gesture, layout and native-memory checks remain required; local JVM results do not certify phone performance.
