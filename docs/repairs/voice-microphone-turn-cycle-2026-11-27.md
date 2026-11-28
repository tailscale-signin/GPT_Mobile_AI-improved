# Voice microphone states and automatic listening

## Scope and branch

This repair extends PR #2651 on `feature/windows-native-benchmark-kit`. It starts from the published conversation-restoration repair `afbd5c65483e68ded391f15d31ac7ec87d9f7d3d`, preserves the concurrent Pocket/caption update `fc0f9c62dfb9e86b36c2647d39cb5040c12b1748`, and includes the verified dependency-metadata update `e28b5df8bf92657284dff8d65bf8b5ed747285e6`. The later saved-chat/caption repair `74ba873cad202e7a99d5fa0681fc75a75b334614` is also preserved. The requested work includes the Voice Mode interaction change and the next observed issue batch, #2791–#2793.

## User-visible behavior

| State | Microphone control |
| --- | --- |
| Idle, paused, reviewing a draft, or displaying an error | Neutral surface and neutral microphone icon |
| Starting capture or listening | Theme highlight with a gentle 1.8-second fading cycle |
| Finalizing, queued, thinking, preparing speech, or speaking | Steady highlighted Stop |
| Motion disabled by the existing accessibility/window/performance policy | Steady listening highlight |

The main and transcript layouts share the treatment. Existing accessible labels, callbacks and control sizes are retained. The microphone icon stays opaque as its background fades.

A new empty voice conversation requests listening once, after the room, profile, recognition, foreground and microphone permission are ready. Its consumed opening intent belongs to the navigation entry. Recomposition, rotation and the placeholder-to-loaded room transition cannot create another opening. Restored conversations, retained drafts and failed sessions keep their existing state. Explicit profile, settings, route, transcript and Stop actions cancel the pending intent; genuine backgrounding does the same. A permission dialog retains only the continuation that already requested that permission.

An admitted listening action arms continuation after a successful spoken reply. Streaming text must be sealed and its audio must drain. A spoken acknowledgment or an intermediate phrase cannot reopen capture. Stop, interruption, failed output, lost readiness, mute and backgrounding prevent continuation. This mechanism is separate from the legacy recognition-retry flag, so it does not enable retries after a recognition error or resend a rejected message.

One cancellable cue follows each actual capture-ready event, including asynchronous retirement of the interruption monitor. Local recognition retains its cue-PCM exclusion. Duplicate ready callbacks and recorder recreation cannot replay the cue. Sound preparation waits at most 250 ms; failure or timeout releases capture readiness without queuing a later sound. Button sounds default on when settings are new or missing. Existing saved false values and platform silent mode remain respected.

## Verification

- **71 host behavior cases passed with assertions enabled**, compiling the complete current `VoiceSession` and `VoicePluginAudio` implementations with explicit diagnostic, persistence and native-boundary ports. This includes 14 new cases, all three existing voice-admission recovery cases, streaming drain, interruption, Stop, mute, background, failures and both recognition routes. The immutable `fc0f9c62` control passes its 57 existing cases.
- **Four presentation behavior cases passed** against the actual new composable and exact state/action declarations. They cover idle/capture/queued/Stop mapping and default light/dark contrast.
- Both new instrumentation methods compile against Android 37 and pinned Compose test APIs. Actual SoundPool feedback and bounded readiness classes also compile against Android 37; only generated resource constants are ported for that check.
- **Eight existing Gateway behavior cases passed** for the newly reviewed reports, with 26 imported source files bound to the observed revision. They verify reasoning-versus-answer accounting, one bounded repair, no third automatic request, original authority, usage and explicit reasoning preference.
- The early Android CI step includes the relevant complete test classes and preserves its subset XML separately from full-suite qualification. The device acceptance script requires both new microphone rendering/clock/semantics methods and preserves their failure status.
- Canonical host, formatting and final integrated source checks are recorded in the companion task checkpoint and evidence files.

The local production compilation preflight did not admit a full Android build: the required 8,960 MiB of verified RAM/cgroup headroom was unavailable (the observation reported 5,408,239,616 available RAM bytes and unknown complete cgroup headroom). The guard was retained. Host ports and API compilation do not qualify real microphone/speaker output, permission dialogs, Compose restoration on a device, or the full Android suite. Those require results for the exact published revision.

Independent review identified and corrected the transient room-ID restoration problem before publication. Review also checked one-shot cue ownership, local PCM suppression, cancellation and explicit-action boundaries.

The opening effect also observes the existing background-playback notification token. If another room temporarily owns speech, its completion rechecks the still-pending fresh-entry intent; the live ownership guard and consumed token remain authoritative.

## Additional report dispositions

The user requested closing an issue once action was taken. These reports receive individual evidence-bound review and recovery notes; the Voice UI change is not described as repairing the reported external or saved-conversation conditions.

| Issue | Established observation | Action |
| --- | --- | --- |
| #2791 | Two recorded backend attempts returned reasoning but no visible answer; accepted HTTP 200, terminal STOP, replay prohibited | Verify bounded-repair/no-replay behavior; retain the provider outcome and usage; document review of the exact model/template/settings before an explicit new generation |
| #2792 | A separate run of the same profile/model returned the same two-attempt reasoning-only outcome | Record the separate incident and the same bounded-repair disposition; do not infer output-budget exhaustion or a causal connection from an adjacent connection-refused event |
| #2793 | The safe-enum hash identifies `GATEWAY_CONVERSATION_READ_ONLY`; capture had granted permission/focus, nonzero audio and 38 recognized characters | Verify draft retention and request ownership; preserve the saved-chat admission guard and direct review of its exact binding/job prerequisite before explicit resubmission |

The #2793 payload does not identify which binding/job prerequisite failed, and the earlier empty-completion event has no retained shared run identity proving causality. The provider payloads do not prove a parser dropped a visible answer or authorize a third attempt. Full assessments and source-bound receipts are retained under `evidence/voice-microphone-turn-cycle/`.

## Dependency verification

The incoming Pocket revision exposed a missing checksum for `kotlinx-coroutines-guava-1.8.0.module`. Its 3,867 published bytes and SHA-256 were independently checked against Maven Central and the Google mirror. Concurrent commit `e28b5df8` added that same verified digest; its entry is preserved unchanged. No dependency version, verification policy or earlier checksum was weakened. The independent verification record is `docs/audits/voice-cycle-dependency-verification-2026-11-27.json`.
