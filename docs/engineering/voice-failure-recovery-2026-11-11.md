# Voice failure recovery checkpoint

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
- Parent PR: #819, `fix/voice-chat-crash-and-diagnostics-2026-11-11`.
- Reviewed source/base SHA: `ab77b77dba3e9e7c928b153e2accf44e937d7e0c`.
- Local working branch: `repair/voice-audit-local`.
- Intent: repair confirmed local-recognition recovery paths, retain specific failure
  codes without exposing transcripts or exception messages, and add behavior tests.

The new reports #823/#824 identify `VOICE_SESSION_FAILED` on build 141 at
`e106892326a02d3d57a4ff3439f1bc1131ac3fce`. Their public event summaries do not
identify an exception or establish the originating failure. This work does not
claim a root cause for either report without the corresponding diagnostic data.

Source inspection confirmed that the local silence deadline is dispatched as a
terminal generic error, bypassing the session's bounded continuous-listening
retry. Permission revocation and missing native runtime errors similarly lose
their repair actions. Native allocation failures also escape the recognition
worker's existing exception handlers.

The existing runtime engineering task store is an Android Room database and is
not running in this checkout. This development checkpoint does not create or
claim application task receipts.

Implemented recovery keeps the existing three-retry bound and preserves partial
speech for review. Permission/runtime/memory failures require repair, and queue
overflow remains terminal. Failure code, message and cause are stored together;
actual failures supersede a racing silence timeout. Decoder cleanup failures do
not get promoted to a silence retry. Specific public incident codes keep bounded
safe exception evidence through the shared issue reporter without a duplicate
generic voice report.

Android fallback recognition also retains its existing error classifications in
public incident codes, including permission, availability, language, rate limit,
network failure and timeout. Startup exceptions retain their original cause. The
existing retry, repair and cooldown choices remain unchanged. Session-level
permission denial and exhausted empty-speech retries use specific codes too.

Validation: 78 focused Kotlin/JUnit/Robolectric tests passed, compiling all current
voice production sources and the parent task's current issue-report/recorder
sources against cached unrelated application dependencies. Tests include native
failure after the silence timer, decoder-cleanup failure, permission revocation,
managed allocation failure, partial-speech retention, and one specific report
with safe exception frames. Android fallback tests additionally confirm network
errors retain automatic retry eligibility, startup timeouts require service
repair, late callbacks are ignored, and permission/rate-limit/unavailability
failures retain their classifications. The recognizer/capture fixtures simulate
failures; they do not execute Moonshine JNI or an installed Android speech
service. Changed Kotlin passed ktlint 1.3.1 and
`git diff --check`. Final aggregate validation is owned by the parent task.

Device/JNI performance and physical ASUS Android 16 qualification remain
separate from these host tests.
