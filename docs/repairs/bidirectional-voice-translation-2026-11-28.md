# Two-way Voice Mode translation

## Requested behavior

Translation uses a selected base language and a second language. The fresh default pair is English and French, with a separately saved voice for each side:

| Detected source | Translation destination | Default streaming voice |
| --- | --- | --- |
| English | French | Estelle |
| French | English | Azelma |

The pair and each voice are editable in the language control below the microphone. Changing one voice does not replace the other or the ordinary conversation's saved engine voice. Questions remain translated questions, and commands remain translated commands. The existing isolated source-text request, fixed accepted translation policy, and no-tools/no-persona/no-history boundaries remain effective.

## Recognition and direction

Automatic mode requests Android's on-device language detection and switching for the two selected languages. It requires Android 14 or later, a compatible speech service, and both installed recognition languages. The service qualification checks both locales, and its reuse key includes the pair. A configured locale is not treated as proof of the spoken language. The English-only Moonshine model is available for an explicitly selected English source; it is not used for automatic bilingual capture.

The exact finalized or edited source text is classified locally with the bundled, pinned MediaPipe language detector before the provider request. No second provider request is added for detection. The global prediction is examined before checking pair membership, so a stronger third language cannot be hidden by an allowlist. Low-confidence, ambiguous, outside-pair or unavailable results retain the draft for an explicit spoken-language selection. The selected source can also be fixed manually for a service that cannot switch languages automatically.

Text-language detection identifies the transcript. It does not prove that the recognizer correctly heard the original audio, and it cannot repair a French utterance already mistranscribed by an English-only model.

## Turn and playback ownership

Draft preparation is asynchronous and occurs before submission. During preparation, the control remains Stop. An immutable destination and matching voice factory are committed only while the same draft and capture generation still own the operation. Stop, draft edits, another capture, background transitions, permission loss and duplicate callbacks cannot dispatch a stale translation.

The destination becomes the existing fixed `TRANSLATE_*` policy before `submitVoiceTranscript` captures its send trace. That policy is already retained with accepted/queued turns and the user-message timeline. Playback selects the matching destination before opening a stream. The selected Pocket factory does not mutate the global backend preference and does not switch an active playback's voice or caption clock.

The playback ledger can inspect a pending reply before consuming it, allowing voice preparation to finish without losing the reply or playing it with the previous language. Replays use the accepted turn's output language rather than the last detected speaker. A language outside the currently configured pair requires an explicit compatible selection before translated replay.

## Voices and installation

Azelma and Estelle are supplied together by the Pocket Multilingual CPU pack. The English-only Pocket and compiled GPU/NPU packs cannot supply Estelle. Translation's language dialog shows the multilingual pack's state and explicit Install/Cancel controls; choosing a pair does not silently download a model or change the ordinary engine selection. The runtime verifies the installed pack and actual selected voice before synthesis. Translated playback requires that selected local voice: a startup failure retains the text and surfaces recovery instead of switching to an unrelated Android system voice. Ordinary playback keeps its existing fallback behavior.

Existing supported non-English translation targets are migrated into a pair with English. Existing ordinary synthesis preferences are preserved. A legacy target that the multilingual voice pack cannot supply produces a setup notice instead of silently translating into another language.

The previously implemented neutral idle microphone, listening pulse, static Stop, first-entry listening and listening after a completed spoken reply remain in use. Each successive utterance is classified independently; the direction does not alternate merely because the previous turn used the other language.

## Qualification

The adjacent task record and source-bound evidence describe the executed checks:

| Executed check | Result |
| --- | --- |
| Session, pair, playback policy/ledger and source isolation | 103 JVM cases passed |
| Settings migration and per-turn backend selection | 9 host cases passed |
| Detector lifecycle/confidence and recognition-pair policy | 13 JVM cases passed |
| Strict selected-voice recovery and ordinary fallback | 20 JVM cases passed |
| Bundled native detector on Linux | 4 required English/French fixtures passed; 9 observations retained |
| Changed Android/Compose controls and exact root translation wiring | API compilation passed; 2 instrumentation methods compiled |
| Canonical host gate | 863 tests, 1 skip; passed |
| Changed Kotlin formatting | 27 files passed ktlint 1.3.1 |
| Changed-source Gitleaks 8.30.1 scan | No findings |

The 145 focused JVM/host cases use actual production sources with the unrelated Android, storage, audio or native boundaries explicitly identified in each manifest. The original receipts remain unchanged; one subsequent required empty line in `VoiceSession.kt` is documented by an exact formatting proof. The final screen wiring was recompiled after enforcing the selected local voice.

The new translation settings instrumentation method is required by the device acceptance receipt checker, and the early Android regression gate now selects 27 complete classes. Six new Android recognition-service Robolectric cases are checked in for hosted execution. Compile-only Android/Compose checks and simulated services do not establish physical bilingual recognition, acoustic translation accuracy, native handset PCM or full Android qualification. Full Android compilation was not started because verified memory headroom did not satisfy the existing 8960 MiB preflight. Exact-revision hosted checks remain separate.

Implementation started at PR #2651 head `d652558c122a00c248cc7ddc388ee2f93ea528ab`, based on main `6435462ac6f306d63b720205a9f2aafc33de1e4d`, and is published on `feature/windows-native-benchmark-kit` after reconciling any newer head.
