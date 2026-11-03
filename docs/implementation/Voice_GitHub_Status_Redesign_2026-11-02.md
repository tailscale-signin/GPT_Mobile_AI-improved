# Voice and GitHub status redesign

Repository: tailscale-signin/GPT_Mobile_AI-improved. Base branch: main.
Base SHA: 62d895f9db9ccfabf9619dde23ff71efc2385e1e.
Working branch: feat/voice-github-status-redesign-2026-11-02.

Intent: implement Voice_Mode_Implementation.docx and the ten accompanying GitHub UI, build download, search preview, notification and title requirements. The new instructions supersede older repository-prefixed chat subjects and the larger six-tab workspace. Branch text uses 70 percent opacity, interpreting 30 percent transparency literally. Status shows measured elapsed/total time; no fabricated ETA.

Checkpoint: source inspection complete; implementation and regression validation in progress. Android hardware acceptance is separate from host validation.

## Implemented behavior

- Voice uses explicit theme foregrounds and the app type scale, 64dp controls, safe insets, selectable transcript, captions, a visible microphone action and a real cooldown countdown. Decorative waves are not retry targets. Reduced motion stops the wave clock.
- One main-thread session owner exposes StateFlow and fences capture, playback, interruption and disposal. Shared monotonic admission survives screen/recognizer recreation. Throttling uses configurable 10/30/60 second defaults with explicit retry, resets only after a successful recognized turn, and cannot be bypassed with unmute or repeated starts. Startup and finalization watchdogs retire stuck recognizers.
- Recognition, model generation and TTS failures are separate. Drafts use the existing durable composer path, and rotation restores paused/review state. Review before send, record again, replay, language, installed voice, rate, captions, optional turn continuation and route selection are available. Voice remains default off; private model/context routes require on-device speech. Online speech requires explicit opt-in.
- Append-only sentence buffering streams speech while preserving live captions, rejects revised prefixes, respects engine chunk limits and fences duplicate/late callbacks. End/background/route changes stop audio. Interruption cancels the old generation before capturing a new turn. Raw audio is not saved. Recognition and playback timing events contain IDs and monotonic timestamps rather than transcripts.
- GitHub search previews use actual tool results, count distinct observed website domains, and display overlapping circular bundled engine/site logos. Unknown counts stay unnumbered. A completed search says Found rather than falsely claiming that search is still running. No third-party favicon lookup is introduced.
- Chat, Status and Settings are the only primary workspace tabs. Settings retains Files, Changes, builds, refresh and external repository access. Targets remain at least 48dp despite the reduced visual height.
- The repository header is 30 percent smaller and primary-colored. Branch and composer hint text use primary at 70 percent opacity, interpreting 30 percent transparency literally. Header action icons are removed. One aligned activity header appears only during AI work.
- Status refreshes selected-branch workflow/job results every 15 seconds while visible and pauses polling with the lifecycle. Colored lights include textual states; unknown/cancelled/skipped states are never green. Actual job steps, elapsed time, verified completed timestamps and freshness are shown. Workflow updated_at is not invented completion time. ETA is explicitly unavailable.
- Creating a PR through the AI tool attaches a durable model-free check watch where authorized. The ongoing PR notification includes draft identity, branch/revision, a status light and system chronometer; it becomes a completed notification at the terminal watch state. A failure to attach monitoring does not turn a completed PR write into a retryable write failure.
- APK catalog/download paths consume the returned refreshed GitHub credential rather than rereading an old vault reference. Workflow archive requests use the documented API Accept header; one expired CDN authorization response can reacquire a fresh redirect. Tokens remain restricted to api.github.com; APK structure, digest, archive traversal and size checks remain in force.
- GitHub assistant identity uses the existing outline logo. Subjects are short development tasks derived from the first prompt before generation finishes, without an extra model request or repository prefix.

## Verification

Host regression suite: 57 tests passed, including voice duplicate starts, late callbacks, throttling escalation, explicit retry, draft review/restoration, streamed sentence deduplication, interruption, credential rotation, APK extraction safeguards, workflow timing parsing and concise task titles. The new search preview provenance tests are included for Android CI.

Canonical fast validation passed all 295 Python host tests, resource checks, literal Android regex checks, CI/dependency policy checks and diff whitespace checks. ktlint 1.3.1 passed for changed Kotlin files.

Full Android compile was attempted using JDK 21 and SDK 37 but stopped during dependency resolution because required artifacts are absent from the offline cache and Gradle distribution/dependency downloads are unavailable from this execution environment. The Android speech adapter was also compiled in isolation against the real Android 37 API with small host substitutes for logging and ContextCompat. This is a type check, not device execution or a full app build.

Physical acceptance remains required on the ROG Phone 9 Pro and another speech implementation: font scaling, TalkBack, privacy mic toggle, permission denial, Bluetooth changes, incoming calls, screen lock, 30-turn voice sessions and workflow APK installation. Performance percentiles are not measured. A sustained streaming speech engine for always-open hands-free use and separately published plugin packaging remain future capabilities, as specified by the document.

References: Android SpeechRecognizer and TextToSpeech API contracts; Material 3 ColorScheme; Android audio focus and accessibility guidance; https://docs.github.com/en/rest/actions/artifacts for authenticated archive redirects and Actions read permissions.

## Final integration review

The CI architecture-index freshness failure was corrected by regenerating the index. A second review moved task-subject generation ahead of the active-generation gate, connected the voice screen to the coordinator StateFlow, paired selected communication routes with TTS audio attributes, added the normal MODIFY_AUDIO_SETTINGS permission, and bounded TTS initialization/playback waits. Private speech policy changes stop an already active online route before taking effect.
